package org.koitharu.kotatsu.parsers.site.kemono

import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.UserAgents
import java.util.*

internal abstract class KemonoParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    protected val supportedServices: List<String>
) : LegacyPagedMangaParser(context, source, 50) {

    override val configKeyDomain = ConfigKey.Domain(domain)
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)
    
    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/v1/creators")
            append("?o=")
            append((page - 1) * pageSize)
            append("&limit=")
            append(pageSize)
        }

        val json = webClient.httpGet(url).parseJsonArray()
        return json.mapJSON { jo ->
            val id = jo.getString("id") 
            val service = jo.getString("service")
            if (service !in supportedServices) {
                return@mapJSON null
            }
            
            Manga(
                id = generateUid("$service:$id"),
                url = "/$service/user/$id",
                publicUrl = "https://$domain/$service/user/$id",
                title = jo.getString("name"),
                altTitles = emptySet(),
                coverUrl = jo.getString("avatar_url"),
                largeCoverUrl = null,
                rating = jo.getFloatOrDefault("favorited", 0f) / 100f,
                tags = emptySet(),
                description = "",
                state = null,
                authors = setOf(service),
                contentRating = ContentRating.ADULT,
                source = source
            )
        }.filterNotNull()
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val (service, userId) = manga.url.split("/user/")
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/v1")
            append(manga.url)
        }

        val posts = async {
            webClient.httpGet("$url/posts").parseJsonArray()
        }

        manga.copy(
            chapters = posts.await().mapChapters { index, jo ->
                val postId = jo.getString("id")
                MangaChapter(
                    id = generateUid("$service:$userId:$postId"),
                    title = jo.getString("title"),
                    number = index + 1f,
                    url = "$url/post/$postId",
                    uploadDate = jo.getLong("published"),
                    source = source,
                    scanlator = null,
                    branch = null,
                    volume = 0
                )
            }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val json = webClient.httpGet(chapter.url).parseJson()
        return json.getJSONArray("images").mapIndexed { i, imageObj ->
            val path = (imageObj as JSONObject).getString("path")
            MangaPage(
                id = generateUid(path),
                url = "https://$domain$path",
                preview = null,
                source = source
            )
        }
    }
}
