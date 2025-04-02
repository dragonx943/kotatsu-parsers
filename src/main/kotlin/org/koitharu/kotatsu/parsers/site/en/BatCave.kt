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
	LegacyPagedMangaParser(context, MangaParserSource.BATCAVE, 10) {

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
        return when {
            !filter.query.isNullOrEmpty() -> parseSearchManga(doc)
            else -> parseListManga(doc)
        }
    }

}