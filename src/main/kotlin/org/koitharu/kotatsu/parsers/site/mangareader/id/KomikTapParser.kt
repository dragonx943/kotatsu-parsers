package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKTAP", "KomikTap", "id", ContentType.HENTAI)
internal class KomikTapParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKTAP, "komiktap.info", pageSize = 25, searchPageSize = 10) {

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val docs = webClient.httpGet(chapterUrl).parseHtml()

		val scriptContent = docs.select("script")
			.firstOrNull { it.data().contains("ts_reader.run") }
			?.data()
			?: return emptyList()

		val jsonPart = scriptContent.substringAfter("ts_reader.run(")
			.substringBeforeLast(")")

		val imagesJsonArray = JSONObject(jsonPart)
			.getJSONArray("sources")
			.getJSONObject(0)
			.getJSONArray("images")

		return List(imagesJsonArray.length()) { i ->
			val imageUrl = imagesJsonArray.getString(i).toAbsoluteUrl(domain).removePrefix("https://$domain/")
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}
}
