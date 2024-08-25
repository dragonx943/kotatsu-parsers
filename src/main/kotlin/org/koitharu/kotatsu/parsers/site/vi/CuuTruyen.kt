package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.ArrayMap
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("CUUTRUYEN", "CuuTruyen", "vi")
internal class CuuTruyenParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.CUUTRUYEN, 20) {

    override val configKeyDomain = ConfigKey.Domain("cuutruyen.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.KOTATSU)
        .build()

    private val tagsCache = SuspendLazy(::fetchTags)

	fun String.toDate(): Date {
    	val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
    	return format.parse(this) ?: throw ParseException("Invalid date format", this)
	}

    override suspend fun getListPage(
        page: Int,
        query: String?,
        tags: Set<MangaTag>?,
        tagsExclude: Set<MangaTag>?,
        sortOrder: SortOrder,
    ): List<Manga> {
        val url = buildString {
            append("https://cuutruyen.net/api/v2/mangas/recently_updated?page=")
            append(page)
        }
        val json = webClient.httpGet(url).parseJson().getJSONArray("data")
            ?: throw ParseException("Invalid response", url)
        val total = json.length()
        val list = ArrayList<Manga>(total)
        for (i in 0 until total) {
            val jo = json.getJSONObject(i)
            list += Manga(
    			url = "/api/v2/mangas/${jo.getLong("id")}",
    			publicUrl = "https://cuutruyen.net/manga/${jo.getLong("id")}",
    			source = MangaParserSource.CUUTRUYEN,
    			title = jo.getString("name"),
    			coverUrl = jo.getString("cover_url"),
    			largeCoverUrl = jo.getString("cover_mobile_url"),
    			state = null,
    			rating = 0f,
    			id = generateUid(jo.getLong("id")),
    			isNsfw = true,
    			tags = emptySet(),
    			author = null,
    			description = "",
    			altTitle = ""
			)
        }
        return list
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://cuutruyen.net${manga.url}"
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", url)
        return manga.copy(
            tags = json.getJSONArray("tags").mapJSONToSet {
                MangaTag(
                    key = it.getString("slug"),
                    title = it.getString("name"),
                    source = manga.source,
                )
            },
            publicUrl = json.getString("official_url"),
            description = json.getString("description"),
            chapters = json.getJSONArray("chapters").mapJSON { jo ->
                MangaChapter(
                    id = generateUid(jo.getLong("id")),
                    source = manga.source,
                    url = "/api/v2/chapters/${jo.getLong("id")}",
                    uploadDate = jo.getString("created_at").toDate().time,
                    name = jo.getString("name"),
                    volume = 0,
                    number = jo.getString("number").toFloat(),
                    scanlator = null,
                    branch = null,
                )
            }.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://cuutruyen.net${chapter.url}"
        val json = webClient.httpGet(fullUrl).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", fullUrl)
        return json.getJSONArray("pages").mapJSON { jo ->
            MangaPage(
                id = generateUid(jo.getLong("id")),
                preview = null,
                source = chapter.source,
                url = jo.getString("url"),
            )
        }
    }

    override suspend fun getAvailableTags(): Set<MangaTag> {
        return tagsCache.get().values.toSet()
    }

    private suspend fun fetchTags(): Map<String, MangaTag> {
        val url = "https://cuutruyen.net/api/v2/tags"
        val json = webClient.httpGet(url).parseJson().getJSONArray("data")
            ?: throw ParseException("Invalid response", url)
        val result = ArrayMap<String, MangaTag>(json.length())
        for (i in 0 until json.length()) {
            val jo = json.getJSONObject(i)
            val tag = MangaTag(
                source = source,
                key = jo.getString("slug"),
                title = jo.getString("name"),
            )
            result[tag.title] = tag
        }
        return result
    }
}
