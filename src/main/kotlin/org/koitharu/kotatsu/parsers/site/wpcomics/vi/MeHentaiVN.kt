package org.koitharu.kotatsu.parsers.site.wpcomics.vi

import androidx.collection.ArraySet
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.wpcomics.WpComicsParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MEHENTAIVN", "MeHentaiVN", "vi", ContentType.HENTAI)
internal class MeHentaiVN(context: MangaLoaderContext) :
	WpComicsParser(context, MangaParserSource.MEHENTAIVN, "www.mehentaivn.xyz", 44) {
	
	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("www.mehentaivn.xyz", "www.hentaivnx.autos")

    override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)
    
    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet(domain).parseHtml()
        val tagItems = doc.select("ul.nav li a")
        val tagSet = ArraySet<MangaTag>(tagItems.size)
        for (item in tagItems) {
            val title = item.attr("data-title")
            val key = item.attr("href").substringAfterLast('/')
            if (key.isNotEmpty() && title.isNotEmpty()) {
                tagSet.add(MangaTag(title = title, key = key, source = source))
            }
        }
        return tagSet
    }
}
