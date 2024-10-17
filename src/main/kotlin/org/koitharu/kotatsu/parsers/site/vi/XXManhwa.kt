package org.koitharu.kotatsu.parsers.site.vi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("XXMANHWA", "XXManhwa", "vi", ContentType.HENTAI)
internal class XXManhwa(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.XXMANHWA, 20) {

    override val configKeyDomain = ConfigKey.Domain("xxmanhwas.org")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableStates = setOf(MangaState.ONGOING, MangaState.FINISHED),
            availableTags = emptySet(),
            availableContentTypes = setOf(ContentType.HENTAI)
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            if (!filter.query.isNullOrEmpty()) {
                append("/search?s=")
                append(filter.query?.urlEncoded() ?: "")
                append("&post_type=story")
            } else {
                append("/tat-ca-cac-truyen")
                if (page > 1) {
                    append("?page_num=")
                    append(page)
                }
            }
        }
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div[data-id][data-type=story]").map { parseSearchManga(it) }
    }

    private fun parseSearchManga(element: Element): Manga {
        val href = element.selectFirst("a")?.attrAsRelativeUrl("href") ?: ""
        val title = element.selectFirst(".posts-list-title h3")?.text().orEmpty()
        val coverUrl = element.selectFirst(".posts-list-avt")?.attr("data-img").orEmpty()
        val views = element.selectFirst(".posts-list-viewed .ebe-number")?.text()?.toLongOrNull() ?: 0L
        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitle = null,
            author = null,
            tags = emptySet(),
            coverUrl = coverUrl,
            state = null,
            source = source,
            isNsfw = isNsfwSource,
            rating = RATING_UNKNOWN,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        return manga.copy(
            altTitle = doc.selectFirst(".story-other-names")?.text(),
            author = doc.selectFirst(".story-author")?.text(),
            description = doc.selectFirst(".story-detail-info")?.html(),
            state = when (doc.selectFirst(".story-status")?.text()?.trim()) {
                "Đang Cập Nhật" -> MangaState.ONGOING
                "Hoàn Thành" -> MangaState.FINISHED
                else -> null
            },
            largeCoverUrl = doc.selectFirst(".story-cover img")?.attr("src"),
            chapters = parseChapters(doc)
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        val chapterElements = doc.select("div.list_chapter div.works-chapter-item")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
        return chapterElements.mapIndexed { index, div ->
            val a = div.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val name = a.text()
            val dateText = div.selectFirst(".time-chap")?.text()
            MangaChapter(
                id = generateUid(href),
                name = name,
                number = index + 1f,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = dateFormat.tryParse(dateText),
                branch = null,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        val imageUrls = doc.select("p.g51182 img")
            .mapNotNull { it.attr("data-src") }
            .filter { it.isNotBlank() }

        return imageUrls.mapIndexed { _, url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
