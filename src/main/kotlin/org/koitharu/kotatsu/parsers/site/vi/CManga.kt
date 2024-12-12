package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.arraySetOf
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("CMANGA", "CManga", "vi")
internal class CManga(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.CMANGA, 50) {
    
    override val configKeyDomain = ConfigKey.Domain("cmangal.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY
    )

    override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = availableTags(),
		)
	}

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/home_album_list?limit=50&page=")
            append(page)
            
            when (order) {
                SortOrder.NEWEST -> append("&sort=1") 
                SortOrder.POPULARITY -> append("&sort=0")
                else -> append("&sort=0")
            }

            if (!filter.tags.isNullOrEmpty()) {
                append("&tag=")
                append(filter.tags.first().key)
            }

            if (!filter.query.isNullOrEmpty()) {
                return searchManga(filter.query)
            }
        }

        return parseMangaList(webClient.httpGet(url).parseJson())
    }

    private suspend fun searchManga(query: String): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/search?child_protect=0&string=")
            append(query.urlEncoded())
        }
        return parseMangaList(webClient.httpGet(url).parseJson())
    }

    private suspend fun parseMangaList(json: JSONObject): List<Manga> {
        return json.getJSONArray("data").mapJSON { item ->
            val info = JSONObject(item.getString("info"))
            Manga(
                id = info.getLong("id"),
                url = info.getString("url"),
                publicUrl = "https://$domain/book/${info.getLong("id")}",
                title = info.getString("name"),
                altTitle = null,
                author = null,
                tags = info.optJSONArray("tags")?.mapJSON { tag ->
                    MangaTag(
                        title = tag.toString(),
                        key = tag.toString().lowercase(),
                        source = source
                    )
                }?.toSet() ?: emptySet(),
                state = when(info.optString("status")) {
                    "doing" -> MangaState.ONGOING
                    "done" -> MangaState.FINISHED  
                    else -> null
                },
                coverUrl = "https://$domain/images/manga/${info.getString("avatar")}",
                rating = RATING_UNKNOWN,
                isNsfw = false,
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/get_data_by_id?table=album&data=info,file&id=")
            append(manga.id)
        }
        val json = webClient.httpGet(url).parseJson()
        val info = JSONObject(json.getString("info"))
        
        return manga.copy(
            description = info.optString("detail"),
            tags = info.optJSONArray("tags")?.mapJSON { tag ->
                MangaTag(
                    title = tag.toString(),
                    key = tag.toString().lowercase(),
                    source = source
                )
            }?.toSet() ?: manga.tags,
            state = when(info.optString("status")) {
                "doing" -> MangaState.ONGOING
                "done" -> MangaState.FINISHED
                else -> manga.state
            }
        )
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/chapter_list?page=1&limit=10000&album=")
            append(manga.id)
        }
        val json = webClient.httpGet(url).parseJsonArray()
        return json.mapJSON { item ->
            val info = JSONObject(item.getString("info"))
            MangaChapter(
                id = generateUid(info.getString("id")),
                name = info.optString("name").ifEmpty { "Chapter ${info.getString("num")}" },
                number = info.getString("num").toFloatOrNull() ?: 0f,
                url = info.getString("id"),
                scanlator = null,
                uploadDate = parseChapterDate(info.optString("last_update")),
                branch = null,
                volume = 0,
                source = source
            )
        }.sortedByDescending { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/chapter_image?chapter=")
            append(chapter.url)
        }
        val json = webClient.httpGet(url).parseJson()
        return json.getJSONArray("image").mapJSON { url ->
            MangaPage(
                id = generateUid(url.toString()),
                url = url.toString(),
                preview = null,
                source = source
            )
        }
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun availableTags() = arraySetOf(
        MangaTag("Romance", "romance", source),
    )
}