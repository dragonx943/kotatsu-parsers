package org.koitharu.kotatsu.parsers.site.vi

import java.util.*
import okhttp3.*
import org.jsoup.nodes.Document
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.util.json.*

@MangaSourceParser("XXMANHWA", "XXManhwa", "vi", ContentType.HENTAI)
internal class XXManhwa(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.XXMANHWA, 25), MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("xxmanhwas.net")

    override val authUrl: String
        get() = "https://${domain}/login"

    private val authCookies = arrayOf("wordpress_logged_in")

    override val isAuthorized: Boolean
        get() = authCookies.all { cookie ->
            context.cookieJar.getCookies(domain).any { it.name == cookie }
        }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false
        )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = emptySet()
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/tat-ca-cac-truyen")
            append("?page_num=")
            append(page)
        }
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div[data-type=story]").map { element ->
            val a = element.selectFirstOrThrow("a")
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                publicUrl = a.absUrl("href"),
                title = a.attr("title"),
                coverUrl = element.selectFirst("div.posts-list-avt")?.attr("data-img").orEmpty(),
                altTitle = null,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                description = null,
                state = null,
                author = null,
                isNsfw = true,
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        return manga.copy(
            title = doc.selectFirst("h1")?.text() ?: manga.title,
            altTitle = null,
            description = doc.selectFirst(".summary__content")?.text(),
            tags = doc.selectFirst("div.each-to-taxonomy")?.attr("data-id")
                ?.split(",")
                ?.mapNotNullToSet { id ->
                    MangaTag(
                        key = id,
                        title = "Unknown",
                        source = source
                    )
                }.orEmpty(),
            state = null,
            author = null,
            chapters = parseChapters(doc)
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        val chaptersData = doc.html()
            .substringAfter("var scope_data=")
            .substringBefore(";</script")
        return chaptersData.parseJson().getJSONArray("chapters").mapIndexed { index, item ->
            val chapter = item as JSONObject
            MangaChapter(
                id = generateUid(chapter.getString("chapterLink")),
                name = chapter.getString("postTitle"),
                number = index + 1f,
                url = "/${chapter.getString("chapterLink")}",
                scanlator = null,
                uploadDate = parseChapterDate(chapter.getString("postModified")),
                branch = null,
                source = source,
                volume = 0
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterDoc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val mangaId = chapterDoc.location().split("/").dropLast(1).last()
        val chapterId = chapterDoc.location().split("/").last().split("-")[0]
        val expiry = chapterDoc.html().substringAfter("expire:").substringBefore(",").trim()
        val token = chapterDoc.html().substringAfter("token:\"").substringBefore("\"").trim()
        val src = chapterDoc.selectFirst("div.cur p[data-src]")?.attr("data-src")
            ?: throw Exception("Could not get filename of first image")

        val body = FormBody.Builder().apply {
            add("iid", "_0_${generateRandomString(12)}")
            add("ipoi", "1")
            add("sid", chapterId)
            add("cid", mangaId)
            add("expiry", expiry)
            add("token", token)
            add("src", src)
            add("doing_ajax", "1")
        }.build()

        val headers = Headers.Builder()
            .add("Referer", chapter.url.toAbsoluteUrl(domain))
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

            val response: Response = webClient.httpPost(
                "https://$domain/chaps/img".toHttpUrl(),
                body.let { fb ->
                    (0 until fb.size).associate { i -> fb.name(i) to fb.value(i) }
                },
                headers
            )
        val responseJson = JSONObject(response.body?.string() ?: throw Exception("Empty response body"))
        val basePageUrl = "https://${responseJson.getString("media")}/${responseJson.getString("src").substringBeforeLast("/")}/"

        return chapterDoc.select("div.cur p[data-src]").mapIndexed { i, it ->
            MangaPage(
                id = generateUid(it.attr("data-src")),
                url = basePageUrl + it.attr("data-src"),
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getUsername(): String {
        val doc = webClient.httpGet("https://$domain/").parseHtml()
        return doc.selectFirst(".user-name")?.text()
            ?: throw AuthRequiredException(source)
    }

    private fun parseChapterDate(date: String): Long {
        return runCatching {
            date.toLongOrNull()?.times(1000) ?: 0L
        }.getOrDefault(0L)
    }

    private fun generateRandomString(length: Int): String {
        val allowedChars = ('2'..'7') + ('a'..'z')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun String.parseJson(): JSONObject {
        return JSONObject(this)
    }
}
