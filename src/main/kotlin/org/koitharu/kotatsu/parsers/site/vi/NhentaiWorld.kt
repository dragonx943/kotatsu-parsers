package org.koitharu.kotatsu.parsers.site.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NHENTAIWORLD", "Nhentai World", "vi", ContentType.HENTAI)
internal class NhentaiWorld(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.NHENTAIWORLD, 24) {

	override val configKeyDomain = ConfigKey.Domain("nhentaiworld-h1.top")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/genre/all").parseHtml()
		return doc.select("div.genre-item").mapToSet {
			MangaTag(
				key = it.attr("value"),
				title = it.selectFirst("label")?.text() ?: it.text(),
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			
			if (!filter.query.isNullOrEmpty() && filter.tags.isEmpty()) {
				append("/genre/")
				append(filter.query.urlEncoded())
			} else {
				append("/genre/")
				append(filter.tags.oneOrThrowIfMany()?.key ?: "all")
			}
			
			append("?")
			
			if (!filter.query.isNullOrEmpty() && filter.tags.isNotEmpty()) {
				append("search=")
				append(filter.query.urlEncoded())
				append("&")
			}
			
			append("sort=")
			when (order) {
				SortOrder.UPDATED -> append("recent-update")
				SortOrder.POPULARITY -> append("view")
				else -> append("recent-update")
			}
			
			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				when (it) {
					MangaState.ONGOING -> append("progress")
					MangaState.FINISHED -> append("completed")
					else -> append("progress")
				}
			}
			
			append("&page=")
			append(page)
		}
		
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.manga-item").map { div ->
			val a = div.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = a.text(),
				altTitle = null,
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				isNsfw = true,
				coverUrl = div.selectFirst("img")?.src().orEmpty(),
				tags = emptySet(),
				state = null,
				author = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
		val trans = doc.select("div.translation-lang").mapNotNull {
			val lang = it.text().trim().lowercase()
			if (lang == "en" || lang == "vi") lang else null
		}.toSet()
		
		return manga.copy(
			altTitle = doc.selectFirst("h2.other-name")?.text(),
			tags = doc.select("ul.tags li").mapToSet {
				MangaTag(
					key = it.attr("data-id"),
					title = it.text(),
					source = source,
				)
			},
			state = when (doc.selectFirst("div.status")?.text()) {
				"Đang tiến hành" -> MangaState.ONGOING
				"Hoàn thành" -> MangaState.FINISHED
				else -> null
			},
			author = doc.selectFirst("div.author a")?.text(),
			description = doc.selectFirst("div.description")?.html(),
			chapters = doc.select("div.chapter-list div.chapter-item").mapChapters(reversed = true) { i, div ->
				val a = div.selectFirstOrThrow("a")
				val href = a.attrAsRelativeUrl("href")
				val name = a.text()
				val dateText = div.selectFirst("span.date")?.text()
				val branch = when {
					trans.contains("vi") -> "vi"
					trans.contains("en") -> "en"
					else -> null
				}
				
				MangaChapter(
					id = generateUid(href),
					name = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = dateFormat.tryParse(dateText),
					branch = branch,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = buildString {
			append(chapter.url.toAbsoluteUrl(domain))
			when (chapter.branch?.lowercase()) {
				"en" -> append("?lang=EN")
				"vi" -> append("?lang=VI")
				else -> {}
			}
		}
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select("div.page img").map { img ->
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
