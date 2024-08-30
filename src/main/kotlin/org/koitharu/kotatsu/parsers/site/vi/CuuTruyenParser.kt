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
import org.koitharu.kotatsu.parsers.util.domain

@MangaSourceParser("CUUTRUYEN", "CuuTruyen", "vi")
internal class CuuTruyenParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.CUUTRUYEN, 20) {

    override val configKeyDomain = ConfigKey.Domain("cuutruyen.net", "nettrom.com", "hetcuutruyen.net", "cuutruyent9sv7.xyz")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.KOTATSU)
        .add("Referer", domain)
        .build()

    private fun String.toDate(): Date {
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
            append("$domain/api/v2/mangas")
            when (sortOrder) {
                SortOrder.UPDATED -> append("/recently_updated")
            }
            append("?page=")
            append(page)
            if (!query.isNullOrEmpty()) {
                append("&q=")
                append(query.urlEncoded())
            }
        }
        val json = webClient.httpGet(url).parseJson().getJSONArray("data")
            ?: throw ParseException("Invalid response", url)
        return json.mapJSON { jo ->
            Manga(
                id = generateUid(jo.getLong("id")),
                url = "/api/v2/mangas/${jo.getLong("id")}",
                publicUrl = "$domain/manga/${jo.getLong("id")}",
                title = jo.getString("name"),
                altTitle = jo.optString("other_names", ""),
                coverUrl = jo.getString("cover_url"),
                largeCoverUrl = jo.getString("cover_mobile_url"),
                author = jo.optString("author", ""),
                artist = jo.optString("artist", ""),
                tags = emptySet(),
                state = when (jo.optString("status")) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
                description = jo.optString("description", ""),
                isNsfw = jo.optBoolean("is_nsfw", false),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = domain + manga.url
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", url)
        
        return manga.copy(
            description = json.getString("description"),
            chapters = json.getJSONArray("chapters").mapJSON { jo ->
                MangaChapter(
                    id = generateUid(jo.getLong("id")),
                    name = jo.getString("name"),
                    number = jo.getString("number").toFloatOrNull() ?: 0f,
                    url = "/api/v2/chapters/${jo.getLong("id")}",
                    scanlator = jo.optString("group_name"),
                    uploadDate = jo.getString("created_at").toDate().time,
                    branch = null,
                    source = source,
                )
            }.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = domain + chapter.url
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", url)
        
        return json.getJSONArray("pages").mapJSON { jo ->
            MangaPage(
                id = generateUid(jo.getLong("id")),
                url = jo.getString("url"),
                preview = null,
                source = source,
            )
        }
    }
}
