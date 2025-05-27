package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import java.util.*

@MangaSourceParser("SNOWMTL", "SnowMtl", "en", ContentType.OTHER)
internal class SnowMTL(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.SNOWMTL, 24) {

    override val configKeyDomain = ConfigKey.Domain("snowmtl.ru")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override val searchQueryCapabilities = MangaSearchQueryCapabilities(
        SearchCapability(
            field = SearchableField.TITLE_NAME,
            criteriaTypes = setOf(Match::class),
            isMultiple = false
        )
    )

    private fun throwParseException(url: String, cause: Exception? = null): Nothing {
        throw ParseException("Failed to parse manga page", url, cause)
    }

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/search")
            append("?")
            when (query.order) {
                SortOrder.POPULARITY -> append("sort_by=views")
                SortOrder.UPDATED -> append("sort_by=recent")
                else -> append("sort_by=recent")
            }
            if (page > 1) {
                append("&page=")
                append(page)
            }
            query.criteria.find { it.field == SearchableField.TITLE_NAME }?.let { criteria ->
                when (criteria) {
                    is Match -> {
                        append("&q=")
                        append(criteria.value.toString())
                    }
                    is Include,
                    is Exclude,
                    is Range -> Unit // Not supported for this field
                }
            }
        }

        return webClient.httpGet(url)
            .parseHtml()
            .select("div.grid.grid-cols-1.sm\\:grid-cols-2.lg\\:grid-cols-3.xl\\:grid-cols-4.gap-8.p-6 > div")
            .map { div ->
                val href = div.selectFirst("a")?.attrAsRelativeUrl("href")
                    ?: throw ParseException("Link not found", div.baseUri())

                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(div.baseUri()),
                    coverUrl = div.selectFirst("a > div > img")?.src().orEmpty(),
                    title = div.selectFirst("div > a > h3")?.text().orEmpty(),
                    altTitle = null,
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    author = null,
                    state = null,
                    source = source,
                    isNsfw = false
                )
            }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val chaptersRoot = doc.selectFirst("section.bg-gray-800.rounded-lg.shadow-md.mt-8.p-6") 
            ?: throw ParseException("Chapters not found", manga.url)
            
        val chapters = chaptersRoot.select("ul > li > a").mapIndexed { index, link ->
            val href = link.attrAsRelativeUrl("href")
            val title = link.text()
            val number = title.substringAfter("Chapter ").substringBefore(" ").toFloatOrNull() ?: (index + 1).toFloat()

            MangaChapter(
                id = generateUid(href),
                title = title,
                number = number,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }

        val title = doc.selectFirst("main > section:nth-child(1) > div > div.md\\:ml-8 > h1")?.text()?.trim()
        val coverUrl = doc.selectFirst("main > section:nth-child(1) > div > div.flex-shrink-0.md\\:w-1\\/3.mb-4.md\\:mb-0 > img")?.src()

        return manga.copy(
            title = title ?: manga.title,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        return buildList {
            doc.select("#comic-images-container > div").forEach { div ->
                div.selectFirst("img")?.let { img ->
                    val url = img.absUrl("src").takeIf { it.isNotEmpty() } ?: img.absUrl("data-src")
                    if (url.isNotEmpty()) {
                        add(MangaPage(
                            id = generateUid(url),
                            url = url,
                            preview = null,
                            source = source
                        ))
                    }
                }
                
                div.selectFirst("div:nth-child(2)")?.let { textDiv ->
                    val text = textDiv.text()
                    if (text.isNotEmpty()) {
                        // Convert text to image using a data URL
                        val dataUrl = "data:text/plain;base64," + java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                        add(MangaPage(
                            id = generateUid(dataUrl),
                            url = dataUrl,
                            preview = null,
                            source = source
                        ))
                    }
                }
            }
        }
    }
}