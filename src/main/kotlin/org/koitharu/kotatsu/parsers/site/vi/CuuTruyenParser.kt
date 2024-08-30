package org.koitharu.kotatsu.parsers.site.vi

import androidx.collection.ArrayMap
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import java.text.SimpleDateFormat
import org.koitharu.kotatsu.parsers.util.domain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

@MangaSourceParser("CUUTRUYEN", "CuuTruyen", "vi")
internal class CuuTruyenParser(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.CUUTRUYEN, 20) {

    override val configKeyDomain = ConfigKey.Domain("cuutruyen.net", "nettrom.com", "hetcuutruyen.net", "cuutruyent9sv7.xyz")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.KOTATSU)
        .add("Referer", domain)
        .build()

    override val onlineConfig = CuuTruyenConfig()
    override fun getInterceptor(): Interceptor = CuuTruyenImageInterceptor()
    private const val DECRYPTION_KEY = "3141592653589793"

    private fun String.toDate(): Date {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        return format.parse(this) ?: throw ParseException("Invalid date format", this)
    }

    private inner class CuuTruyenImageInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)

            if (!request.url.toString().contains(".cuutruyen.net")) {
                return response
            }

            val body = response.body ?: return response
            val contentType = body.contentType()
            val bytes = body.bytes()

            val decrypted = try {
                decrypt(bytes)
            } catch (e: Exception) {
                bytes
            }

            val decompressed = try {
                decompress(decrypted)
            } catch (e: Exception) {
                decrypted
            }

            val newBody = decompressed.toResponseBody(contentType)
            return response.newBuilder().body(newBody).build()
        }

        private fun decrypt(input: ByteArray): ByteArray {
            val key = onlineConfig.decryptionKey.toByteArray()
            return input.mapIndexed { index, byte ->
                (byte.toInt() xor key[index % key.size].toInt()).toByte()
            }.toByteArray()
        }

        private fun decompress(input: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(input, 0, input.size)
            val outputStream = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            outputStream.close()
            return outputStream.toByteArray()
        }
    }

    class CuuTruyenConfig : MangaSourceConfig {
        var decryptionKey: String by config("DECRYPTION_KEY")
    }

    override suspend fun getListPage(
        page: Int,
        query: String?,
        tags: Set<MangaTag>?,
        tagsExclude: Set<MangaTag>?,
        sortOrder: SortOrder,
    ): List<Manga> {
        val url = buildString {
            if (!query.isNullOrEmpty()) {
                append("$domain/api/v2/mangas/search")
                append("?q=")
                append(query.urlEncoded())
                append("&page=")
                append(page)
                append("&per_page=100")
            } else {
                append("$domain/api/v2/mangas")
                when (sortOrder) {
                    SortOrder.UPDATED -> append("/recently_updated")
                    SortOrder.POPULARITY -> append("/most_viewed")
                    SortOrder.NEWEST -> append("/latest")
                    SortOrder.ALPHABETICAL -> append("/az")
                    else -> append("/recently_updated")
                }
                append("?page=")
                append(page)
            }
        }

        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", url)
        
        return json.getJSONArray("data").mapJSON { jo ->
            Manga(
                id = generateUid(jo.getLong("id")),
                url = "/api/v2/mangas/${jo.getLong("id")}",
                publicUrl = "$domain/manga/${jo.getLong("id")}",
                title = jo.getString("name"),
                altTitle = null,
                coverUrl = jo.getString("cover_url"),
                largeCoverUrl = jo.getString("cover_mobile_url"),
                author = jo.optString("author_name", ""),
                artist = "",
                tags = emptySet(),
                state = null,
                description = "",
                isNsfw = false,
                source = source,
                rating = RATING_UNKNOWN,
                isNsfw = false,
                searchQuery = query,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = domain + manga.url
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", url)
        
        return manga.copy(
            description = json.getString("description"),
            chapters = json.getJSONArray("chapters").mapJSON { jo ->
                MangaChapter(
                    id = generateUid(jo.getLong("id")),
                    name = jo.getString("name"),
                    number = jo.getString("number").toFloatOrNull() ?: 0f,
                    url = "/api/v2/chapters/${jo.getLong("id")}",
                    scanlator = jo.optString("group_name"),
                    uploadDate = jo.getString("created_at").toDate().time,
                    branch = null,
                    source = source,
                )
            }.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "$domain${chapter.url}"
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
            ?: throw ParseException("Invalid response", url)
    
        return json.getJSONArray("pages").mapJSON { jo ->
            val imageUrl = jo.getString("image_url")
            MangaPage(
                id = generateUid(jo.getLong("id")),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }
}
