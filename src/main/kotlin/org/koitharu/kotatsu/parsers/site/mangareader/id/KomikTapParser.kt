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

		val test = docs.select(selectTestScript)
		if (test.isNullOrEmpty() and !encodedSrc) {
			return docs.select(selectPage).map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		} else {
			val images = if (encodedSrc) {
				val script = docs.select(selectScript)
				var decode = ""
				for (i in script) {
					if (i.attr("src").startsWith("data:text/javascript;base64,")) {
						decode = Base64.getDecoder().decode(i.attr("src").replace("data:text/javascript;base64,", ""))
							.decodeToString()
						if (decode.startsWith("ts_reader.run")) {
							break
						}
					}
				}
				JSONObject(decode.substringAfter('(').substringBeforeLast(')'))
					.getJSONArray("sources")
					.getJSONObject(0)
					.getJSONArray("images")

			} else {
				val script = docs.selectFirstOrThrow(selectTestScript)
				JSONObject(script.data().substringAfter('(').substringBeforeLast(')'))
					.getJSONArray("sources")
					.getJSONObject(0)
					.getJSONArray("images")
			}

			val pages = ArrayList<MangaPage>(images.length())
			for (i in 0 until images.length()) {
				val url = images.getString(i)
				pages.add(
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					),
				)
			}
			return pages
		}
	}
}
