package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("PIXHENTAI", "PixHentai", "id", ContentType.HENTAI)
internal class PixHentai(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.PIXHENTAI, 8) {

	override val configKeyDomain = ConfigKey.Domain("pixhentai.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/page/")
					append(page)
					append("/?s=")
					append(filter.query.urlEncoded())
				}

				else -> {

					filter.tags.oneOrThrowIfMany()?.let {
						append("/genre/")
						append(it.key)
						append('/')
					}

					append("/page/")
					append(page)
					append('/')
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div#content article").mapNotNull { div ->
			val href = div.selectFirst("a")?.attr("href") ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src()?.replace("-200x285", ""),
				title = div.selectFirst("h2")?.text().orEmpty(),
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.select("ul.megamenu li").mapToSet { li ->
			val key = li.selectFirstOrThrow("a").attr("href").removeSuffix('/').substringAfterLast('/')
			val name = li.selectFirstOrThrow("a").text()
			MangaTag(
				key = key,
				title = name,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val author = doc.selectFirst("div.entry-content ul li:contains(Artists :) em")?.textOrNull()
		return manga.copy(
			description = doc.selectFirst("div.entry-content p")?.html(),
			altTitles = setOfNotNull(
				doc.selectFirst("div.entry-content ul li:contains(Alternative Name(s) :) em")?.textOrNull(),
			),
			authors = setOfNotNull(author),
			state = null,
			chapters = listOf(
				MangaChapter(
					id = manga.id,
					title = manga.title,
					number = 1f,
					volume = 0,
					url = fullUrl,
					scanlator = null,
					uploadDate = 0,
					branch = null,
					source = source,
				),
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(".thumbnail img, figure.gallery-item img").map { img ->
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
