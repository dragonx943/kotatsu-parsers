package org.koitharu.kotatsu.parsers.site.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*
import org.koitharu.kotatsu.parsers.Broken

@Broken("TODO: Improve getListPage, getDetails; Add tags, filters,...")
@MangaSourceParser("MULTPORN", "Multporn")
internal class Multporn(context: MangaLoaderContext) : LegacyPagedMangaParser(context, MangaParserSource.MULTPORN, 42) {

    override val configKeyDomain = ConfigKey.Domain("multporn.net")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
		.build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search?views_fulltext=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append((page - 1).toString())
                }
                order == SortOrder.UPDATED -> {
                    append("/new?page=")
                    append((page - 1).toString())
                }
                else -> {
                    append("/best?page=")
                    append((page - 1).toString())
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".masonry-item").map { div ->
            val href = div.selectFirstOrThrow(".views-field-title a").attrAsRelativeUrl("href")
            val coverUrl = div.selectFirst(".views-field.views-field-field-preview img")?.attrAsAbsoluteUrlOrNull("src")
            Manga(
                id = generateUid(href),
                title = div.select(".views-field-title").text(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val authors = (doc.select(".field:has(.field-label:contains(Author:)) .links a").map { it.text().trim() } +
                parseUnlabelledAuthorNames(doc)).distinct()

        val tags = listOf("Tags", "Section", "Characters")
            .flatMap { type -> 
                doc.select(".field:has(.field-label:contains($type:)) .links a").map { it.text().trim() }
            }
            .distinct()
            .map { tag ->
                MangaTag(
                    key = tag.lowercase().replace(" ", "-"),
                    title = tag,
                    source = source,
                )
            }
            .toSet()

        val isOngoing = doc.select(".field .links a").any { it.text() == "Ongoings" }

        return manga.copy(
            authors = authors.toSet(),
            tags = tags,
            description = buildString {
                append("Pages: ")
                append(doc.select(".jb-image img").size)
                append("\n\n")
                doc.select(".field:has(.field-label:contains(Section:)) .links a").joinTo(this, prefix = "Section: ") { it.text() }
                doc.select(".field:has(.field-label:contains(Characters:)) .links a").joinTo(this, prefix = "\n\nCharacters: ") { it.text() }
            },
            state = if (isOngoing) MangaState.ONGOING else MangaState.FINISHED,
            chapters = listOf(
                MangaChapter(
                    id = generateUid(manga.url),
                    title = "Oneshot",
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            ),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(".jb-image img").mapIndexed { i, img ->
            val url = img.attrAsAbsoluteUrl("src")
                .replace("/styles/juicebox_2k/public", "")
                .substringBefore("?")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseUnlabelledAuthorNames(document: org.jsoup.nodes.Document): List<String> {
        val authorClasses = listOf(
            "field-name-field-author",
            "field-name-field-authors-gr",
            "field-name-field-img-group",
            "field-name-field-hentai-img-group",
            "field-name-field-rule-63-section"
        )
        return authorClasses.flatMap { className ->
            document.select(".$className a").map { it.text().trim() }
        }
    }
}