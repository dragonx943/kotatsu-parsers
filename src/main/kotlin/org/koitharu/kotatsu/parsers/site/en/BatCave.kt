package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BATCAVE", "BatCave", "en")
internal class BatCave(context: MangaLoaderContext) :
	LegacyPagedMangaParser(context, MangaParserSource.BATCAVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("batcave.biz")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = false,
            isYearSupported = true,
            isYearRangeSupported = true
		)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet()
	)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val urlBuilder = StringBuilder()
        when {
            !filter.query.isNullOrEmpty() -> {
                urlBuilder.append("/search/")
                urlBuilder.append(filter.query.urlEncoded())
                if (page > 1) urlBuilder.append("/page/$page/")
            }
            else -> {
                urlBuilder.append("/ComicList")
                if (filter.yearFrom != YEAR_UNKNOWN) {
                    urlBuilder.append("/y[from]=${filter.yearFrom}")
                }
                if (filter.yearTo != YEAR_UNKNOWN) {
                    urlBuilder.append("/y[to]=${filter.yearTo}")
                }
                if (filter.tags.isNotEmpty()) {
                    urlBuilder.append("/g=")
                    urlBuilder.append(filter.tags.joinToString(",") { it.key })
                }
                urlBuilder.append("/sort")
                if (page > 1) { urlBuilder.append("/page/$page/") }
            }
        }

        val fullUrl = urlBuilder.toString().toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("div.readed.d-flex.short").map { item ->
			val a = item.selectFirst("a.readed__img.img-fit-cover.anim")
            val img = a?.selectFirst("img.lazy-loaded")
            val url = a?.attr("href") ?: ""
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url,
				title = a?.attr("title").orEmpty(),
				altTitles = emptySet(),
				authors = emptySet(),
				description = null,
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src"),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val tags = doc.select("div.hentai-info .line-content a.item-tag")
			.mapToSet { a ->
				MangaTag(
					title = a.text().toTitleCase(sourceLocale),
					key = a.attr("href").substringAfterLast('/'),
					source = source,
				)
			}

		val chapters = doc.select("ul#chapter-list li.citem").mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a") ?: return@mapChapters null
			MangaChapter(
				id = generateUid(a.attr("href")),
				title = a.text(),
				number = i + 1f,
				url = a.attrAsRelativeUrl("href"),
				uploadDate = parseChapterDate(li.selectFirst(".time")?.text()),
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}

		val altTitle = doc.selectFirst("h2.alternative")?.textOrNull()
		val author = doc.selectFirst("div.hentai-info .line:contains(Tác giả) .line-content")?.textOrNull()
		val state = when (doc.selectFirst("div.hentai-info .line:contains(Tình trạng) .line-content")?.text()) {
			"Đang cập nhật" -> MangaState.ONGOING
			"Hoàn thành" -> MangaState.FINISHED
			else -> null
		}

		return manga.copy(
			tags = tags,
			authors = setOfNotNull(author),
			altTitles = setOfNotNull(altTitle),
			state = state,
			chapters = chapters,
			description = doc.select("div.about").text(),
		)
	}

}