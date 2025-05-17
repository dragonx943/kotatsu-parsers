package org.koitharu.kotatsu.parsers.site.kemono.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.kemono.KemonoParser
import org.koitharu.kotatsu.parsers.model.ContentType

@MangaSourceParser("KEMONO", "Kemono", type = ContentType.OTHER)
internal class Kemono(context: MangaLoaderContext) :
    KemonoParser(
        context = context,
        source = MangaParserSource.KEMONO,
        domain = "kemono.su",
        supportedServices = listOf(
            "Patreon",
            "Pixiv Fanbox",
            "Fantia",
            "Afdian",
            "Boosty",
            "Gumroad",
            "SubscribeStar"
        )
    )