package org.koitharu.kotatsu.parsers.site.vi

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("HENTAIVNBUZZ", "HentaiVn.buzz", "vi")
internal class HentaiVnBuzz(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.HENTAIVNBUZZ, 42) {

	override val configKeyDomain = ConfigKey.Domain("hentaivn.buzz")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrEmpty() -> {
				buildString {
					append(domain)
					append("/tim-kiem?key_word=")
					append(filter.query.urlEncoded())
					if (page > 1) {
						append("&page=")
						append(page)
					}
				}
			}

			else -> {
				buildString {
					append(domain)
					append("/danh-sach/truyen-moi?")
					when (order) {
						SortOrder.NEWEST -> append("sort=0")
						SortOrder.UPDATED -> append("sort=1")
						SortOrder.POPULARITY -> append("sort=2")
						else -> append("sort=0")
					}
					if (filter.states.isNotEmpty()) {
						filter.states.forEach {
							when (it) {
								MangaState.ONGOING -> append("&is_full=0")
								MangaState.FINISHED -> append("&is_full=1")
								else -> append("")
							}
						}
					}
					if (page > 1) {
						append("&page=")
						append(page)
					}
				}
			}
		}

		val fullUrl = url.toAbsoluteUrl(domain)
		if (!filter.query.isNullOrEmpty()) {
			val doc = webClient.httpGet(fullUrl).parseHtml()
			return parseSearchManga(doc)
		} else {
			val doc = webClient.httpGet(fullUrl).parseHtml()
			return parseListManga(doc)
		}
	}

	private fun parseSearchManga(doc: Document): List<Manga> {
		return doc.select(".story-item-list").map { div ->
			val href = div.selectFirstOrThrow("a.story-item-list__image").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = div.selectFirstOrThrow("h3.story-item-list__name").text(),
				altTitle = null,
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				isNsfw = isNsfwSource,
				coverUrl = div.selectFirst("img")?.attr("data-src").orEmpty(),
				tags = emptySet(),
				state = null,
				author = null,
				source = source,
			)
		}
	}

	private fun parseListManga(doc: Document): List<Manga> {
		return doc.select(".list-story-in-search .story-item-list").map { div ->
			val href = div.selectFirstOrThrow("a.story-item-list__image").attrAsRelativeUrl("href")
			val state = when (div.selectFirst("span.state")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Hoàn thành" -> MangaState.FINISHED
				else -> null
			}
			Manga(
				id = generateUid(href),
				title = div.selectFirstOrThrow("h3.story-item-list__name").text(),
				altTitle = null,
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				isNsfw = isNsfwSource,
				coverUrl = div.selectFirst("img")?.attr("data-src").orEmpty(),
				tags = emptySet(),
				state = state,
				author = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		return manga.copy(
			altTitle = doc.selectFirst("h2.other-name")?.textOrNull(),
			author = doc.select("p:contains(Tác giả) + p").joinToString { it.text() }.nullIfEmpty(),
			tags = doc.select("a.clblue").mapToSet {
				MangaTag(
					key = it.attr("href").substringAfterLast('-').substringBeforeLast('.'),
					title = it.text(),
					source = source,
				)
			},
			description = doc.select("div.story-detail-info").text(),
			state = when (doc.select("p:contains(Trạng Thái) + p").text()) {
				"Đang Cập Nhật" -> MangaState.ONGOING
				"Hoàn Thành" -> MangaState.FINISHED
				else -> null
			},
			chapters = doc.select("ul.list_chap > li.item_chap").mapChapters(reversed = true) { i, div ->
				val a = div.selectFirstOrThrow("a")
				val href = a.attrAsRelativeUrl("href")
				val name = a.text()
				MangaChapter(
					id = generateUid(href),
					name = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0,
					branch = null,
					source = source,
				)
			}
		)
	}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val imageUrls = doc.select("meta[property='og:image']").map { it.attr("content") }
		val filteredImageUrls = imageUrls.drop(1)
		return filteredImageUrls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
