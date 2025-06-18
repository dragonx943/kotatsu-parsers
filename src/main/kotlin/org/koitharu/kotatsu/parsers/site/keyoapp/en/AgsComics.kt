package org.koitharu.kotatsu.parsers.site.keyoapp.en

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.keyoapp.KeyoappParser
import org.koitharu.kotatsu.parsers.util.styleValueOrNull
import org.koitharu.kotatsu.parsers.util.cssUrl

@MangaSourceParser("AGSCOMICS", "AgsComics", "en")
internal class AgsComics(context: MangaLoaderContext) :
	KeyoappParser(context, MangaParserSource.AGSCOMICS, "agrcomics.com") {

	override val cover: (Element) -> String? = { div ->
		val eL = div.selectFirst("div.absolute.top-0.left-0.h-full.w-full.bg-center.bg-cover")
			?: throw Exception("No element found")

		val coverUrl = eL?.styleValueOrNull("background-image")?.cssUrl()
			?: throw Exception("No url found")
			
		coverUrl
	}

}