package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("HEYTOON", "HeyToon", "en")
internal class HeyToonParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.HEYTOON, pageSize = 54) {

	override val configKeyDomain = ConfigKey.Domain("heytoon.net")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}
