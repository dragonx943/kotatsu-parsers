package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("RESETSCANS", "ReadManhua", "en")
internal class ResetScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.RESETSCANS, "reset-scans.co", 20)

