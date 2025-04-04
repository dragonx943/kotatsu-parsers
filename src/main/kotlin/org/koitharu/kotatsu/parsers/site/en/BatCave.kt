package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
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
		
		val dateFormat = SimpleDateFormat("d.MM.yyyy", Locale.US)
		val chapters = doc.select("div.comix__fullstory-chapters .cl__item").mapChapters(reversed = true) { i, item ->
			val titleElement = item.selectFirst(".cl__item-title a") ?: return@mapChapters null
			val dateStr = item.selectFirst(".cl__item-date")?.text()
			
			MangaChapter(
				id = generateUid(titleElement.attr("href")),
				title = titleElement.text(),
				number = i + 1f,
				url = titleElement.attrAsRelativeUrl("href"),
				uploadDate = dateStr?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L,
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}

		val author = doc.selectFirst("li:contains(Publisher:)")?.text()?.substringAfter("Publisher:")?.trim()
		val state = when (doc.selectFirst("li:contains(Release type:)")?.text()?.substringAfter("Release type:")?.trim()) {
			"Ongoing" -> MangaState.ONGOING
			else -> MangaState.FINISHED
		}

		return manga.copy(
			authors = setOfNotNull(author),
			state = state,
			chapters = chapters,
			description = doc.select("div.page__text.full-text.clearfix").text()
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val data = doc.selectFirst("script:containsData(__DATA__)")?.data()
			?.substringAfter("=")
			?.trim()
			?.removeSuffix(";")
			?.substringAfter("\"images\":[")
			?.substringBefore("]")
			?.split(",")
			?.map { it.trim().removeSurrounding("\"").replace("\\", "") }
			?: throw ParseException("Image data not found", chapter.url)

		return data.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source
			)
		}
	}
}