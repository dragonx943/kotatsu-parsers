package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NHENTAIWORLD", "Nhentai World", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) : LegacyPagedMangaParser(context, MangaParserSource.NHENTAIWORLD, 24) {

	override val configKeyDomain = ConfigKey.Domain("nhentaiworld-h1.info")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
        .add("Origin", "https://$domain/")
		.build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
        SortOrder.POPULARITY,
	)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {

            append("/genre/")
            if (!filter.tags.isNullOrEmpty()) {
                append(filter.tags.first().key)
            } else {
                append("all")
            }

            append("?sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "recent-update"
                    SortOrder.POPULARITY -> "view"
                    else -> "recent-update"
                }
            )

            if (!filter.query.isNullOrEmpty()) {
                append("&search=")
                append(filter.query.urlEncoded())
            }

            if (!filter.states.isNullOrEmpty()) {
                append("&status=")
                append(
                    when (filter.states.first()) {
                        MangaState.ONGOING -> "progress"
                        MangaState.FINISHED -> "completed"
                        else -> ""
                    }
                )
            }

            append("&page=")
            append(page)
        }

        val doc = webClient.httpGet(url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("div.relative.mb-1.h-full.max-h-\\[375px\\]").map { div ->
            val img = div.selectFirst("img.hover\\:scale-105.transition-all.w-full.h-full")
            val a = div.selectFirst("a")
            
            val title = img?.attr("alt").orEmpty()
            val coverUrl = img?.attr("src").orEmpty()
            val url = a?.attr("href").orEmpty()
            
            Manga(
                id = generateUid(url),
                title = title,
                altTitles = emptySet(),
                url = url,
                publicUrl = url.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.selectFirst("div.flex-1.bg-neutral-900") ?: return manga
        val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("GMT+7")
        }

        val tags = root.select("div.flex.flex-wrap.gap-2 button").mapNotNull { button ->
            val tagName = button.text()
            val tagUrl = button.parent()?.attr("href")?.substringAfterLast("/")
            if (tagUrl != null) {
                MangaTag(
                    title = tagName,
                    key = tagUrl,
                    source = source
                )
            } else null
        }.toSet()

        val state = when {
            root.selectFirst("a[href*=status=completed]") != null -> MangaState.FINISHED
            root.selectFirst("a[href*=status=progress]") != null -> MangaState.ONGOING
            else -> null
        }

        val description = root.selectFirst("div#introduction-wrap p.font-light")?.text()
        val altTitles = buildSet {
            description?.let {
                val engTitle = it.substringAfter("Tên tiếng anh: ").substringBefore("\n").trim()
                val originalTitle = it.substringAfter("Tên gốc: ").substringBefore("\n").trim()
                add(engTitle)
                add(originalTitle)
            }
        }

        val chapterData = doc.toString().run {
            val dataPattern = "\"data\":\\[(.*?)\\]".toRegex()
            val enPattern = "\"chapterListEn\":\\[(.*?)\\]".toRegex()
            
            val viMatch = dataPattern.find(this)?.groupValues?.get(1)
            val enMatch = enPattern.find(this)?.groupValues?.get(1)
            
            buildList {
                if (!viMatch.isNullOrEmpty()) {
                    val chapterName = viMatch.substringAfter("\"name\":\"").substringBefore("\"")
                    val uploadDate = chapterDateFormat.parse(viMatch.substringAfter("\"createdAt\":\"").substringBefore("\""))?.time ?: 0L
                    
                    add(
                        MangaChapter(
                            id = generateUid(manga.url + "/1?lang=VI"),
                            title = chapterName,
                            number = 1f,
                            url = "/read/${manga.id}/1?lang=VI",
                            scanlator = null,
                            uploadDate = uploadDate,
                            branch = "Tiếng Việt",
                            source = source,
                            volume = 0,
                        )
                    )
                }
                
                if (!enMatch.isNullOrEmpty()) {
                    val chapterName = enMatch.substringAfter("\"name\":\"").substringBefore("\"")
                    val uploadDate = chapterDateFormat.parse(enMatch.substringAfter("\"createdAt\":\"").substringBefore("\""))?.time ?: 0L
                    
                    add(
                        MangaChapter(
                            id = generateUid(manga.url + "/1?lang=EN"),
                            title = chapterName,
                            number = 1f,
                            url = "/read/${manga.id}/1?lang=EN",
                            scanlator = null,
                            uploadDate = uploadDate,
                            branch = "English",
                            source = source,
                            volume = 0,
                        )
                    )
                }
            }
        }

        return manga.copy(
            tags = tags,
            state = state,
            description = description,
            altTitles = altTitles,
            chapters = chapterData,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> { // Temporary, not fixed
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("div#viewer img").mapNotNull { img ->
            val url = img.requireSrc()
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

}