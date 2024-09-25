package org.koitharu.kotatsu.parsers.site.vi

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.Inflater
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

@MangaSourceParser("CUUTRUYEN", "CuuTruyen", "vi")
internal class CuuTruyenParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.CUUTRUYEN, 20), Interceptor {

    override val configKeyDomain =
        ConfigKey.Domain("cuutruyen.net", "nettrom.com", "hetcuutruyen.net", "cuutruyent9sv7.xyz")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.KOTATSU)
        .build()

    private val decryptionKey = "3141592653589793"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/api/v2/mangas/search?q=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page.toString())
                }

                else -> {
                    val tag = filter.tags.oneOrThrowIfMany()
                    if (tag != null) {
                        append("/api/v2/tags/")
                        append(tag.key)
                    } else {
                        append("/api/v2/mangas")
                        when (order) {
                            SortOrder.UPDATED -> append("/recently_updated")
                            SortOrder.POPULARITY -> append("/top")
                            SortOrder.NEWEST -> append("/recently_updated")
                            else -> append("/recently_updated")
                        }
                    }
                    append("?page=")
                    append(page.toString())
                }
            }

            append("&per_page=")
            append(pageSize)
        }

        val json = webClient.httpGet(url).parseJson()
        val data = json.getJSONArray("data")

        return data.mapJSON { jo ->
            Manga(
                id = generateUid(jo.getLong("id")),
                url = "/api/v2/mangas/${jo.getLong("id")}",
                publicUrl = "https://$domain/manga/${jo.getLong("id")}",
                title = jo.getString("name"),
                altTitle = null,
                coverUrl = jo.getString("cover_url"),
                largeCoverUrl = jo.getString("cover_mobile_url"),
                author = jo.getStringOrNull("author_name"),
                tags = emptySet(),
                state = null,
                description = null,
                isNsfw = isNsfwSource,
                source = source,
                rating = RATING_UNKNOWN,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val url = "https://" + domain + manga.url
        val chapters = async {
            webClient.httpGet("$url/chapters").parseJson().getJSONArray("data")
        }
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")
        val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        manga.copy(
            title = json.getStringOrNull("name") ?: manga.title,
            isNsfw = json.getBooleanOrDefault("is_nsfw", manga.isNsfw),
            author = json.optJSONObject("author")?.getStringOrNull("name")?.substringBefore(','),
            description = json.getString("full_description"),
            tags = json.optJSONArray("tags")?.mapJSONToSet { jo ->
                MangaTag(
                    title = jo.getString("name").toTitleCase(sourceLocale),
                    key = jo.getString("slug"),
                    source = source,
                )
            }.orEmpty(),
            chapters = chapters.await().mapJSON { jo ->
                val chapterId = jo.getLong("id")
                val number = jo.getFloatOrDefault("number", 0f)
                MangaChapter(
                    id = generateUid(chapterId),
                    name = jo.getStringOrNull("name") ?: number.formatSimple(),
                    number = number,
                    volume = 0,
                    url = "/api/v2/chapters/$chapterId",
                    scanlator = jo.optString("group_name"),
                    uploadDate = chapterDateFormat.tryParse(jo.getStringOrNull("created_at")),
                    branch = null,
                    source = source,
                )
            }.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")

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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!request.url.host.contains(domain, ignoreCase = true)) {
            return response
        }

        val body = response.body ?: return response
        val contentType = body.contentType()
        val bytes = body.bytes()

        val decrypted = decryptDRM(bytes, decryptionKey.toByteArray())
        val reconstructed = decrypted?.let {
            reconstructImage(it, originalWidth = 800, originalHeight = 1200) // Bạn cần lấy kích thước gốc phù hợp
        } ?: bytes

        val newBody = reconstructed.toResponseBody(contentType)
        return response.newBuilder().body(newBody).build()
    }

    private fun decryptDRM(drmData: ByteArray, key: ByteArray): ByteArray? {
        return try {
            drmData.mapIndexed { index, byte ->
                (byte.toInt() xor key[index % key.size].toInt()).toByte()
            }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun reconstructImage(decrypted: ByteArray, originalWidth: Int, originalHeight: Int): ByteArray? {
        return try {
            val delimiter = "#v".toByteArray()
            val delimiterIndex = decrypted.indexOfFirst { 
                decrypted.sliceArray(it until it + delimiter.size) contentEquals delimiter 
            }
            if (delimiterIndex == -1) {
                return null
            }

            val segmentsInfoStart = delimiterIndex + delimiter.size
            val segmentsData = decrypted.sliceArray(segmentsInfoStart until decrypted.size)
            val segments = String(segmentsData).split("|").filter { it.contains("-") }

            if (segments.isEmpty()) {
                return null
            }

            val segmentInfo = segments.mapNotNull { seg ->
                try {
                    val (dyStr, heightStr) = seg.split("-")
                    val dy = if (dyStr.startsWith("dy")) dyStr.substring(2).trim().toInt() else dyStr.trim().toInt()
                    val height = heightStr.trim().toInt()
                    Pair(dy, height)
                } catch (e: Exception) {
                    null
                }
            }

            if (segmentInfo.isEmpty()) {
                return null
            }

            val totalHeight = segmentInfo.sumBy { it.second }
            val finalSegmentInfo = if (totalHeight != originalHeight) {
                val remainingHeight = originalHeight - totalHeight
                if (remainingHeight > 0) {
                    segmentInfo.toMutableList().apply { add(Pair(0, remainingHeight)) }
                } else {
                    segmentInfo
                }
            } else {
                segmentInfo
            }

            val originalBitmap = BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)?.copy(Bitmap.Config.RGB_565, true)
            if (originalBitmap == null) return null
            val newBitmap = Bitmap.createBitmap(originalWidth, originalHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(newBitmap)
            val paint = Paint()

            var sy = 0
            for ((dy, segHeight) in finalSegmentInfo) {
                if (sy + segHeight > originalHeight) {
                    break
                }
                val srcRect = Rect(0, sy, originalWidth, sy + segHeight)
                val destRect = Rect(0, dy, originalWidth, dy + segHeight)
                canvas.drawBitmap(originalBitmap, srcRect, destRect, paint)
                sy += segHeight
            }

            val outputStream = ByteArrayOutputStream()
            newBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun ByteArray.indexOfFirst(predicate: (Int) -> Boolean): Int {
        for (i in indices) {
            if (predicate(i)) return i
        }
        return -1
    }
}
