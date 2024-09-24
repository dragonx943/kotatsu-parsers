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
import java.util.Base64
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

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

    private fun decryptDRM(drmData: String, key: ByteArray): ByteArray? {
        val decodedData = try {
            Base64.getDecoder().decode(drmData)
        } catch (e: IllegalArgumentException) {
            return null
        }

        return decodedData.mapIndexed { index, byte ->
            (byte.toInt() xor key[index % key.size].toInt()).toByte()
        }.toByteArray()
    }

    private fun reconstructImage(imageData: ByteArray, decrypted: ByteArray, oriWidth: Int, oriHeight: Int): BufferedImage? {
        val delimiter = "#v4|".toByteArray()
        val delimiterIndex = decrypted.indexOfSlice(delimiter)
        if (delimiterIndex == -1) {
            return null
        }

        val segmentsInfoStart = delimiterIndex + delimiter.size
        val segments = mutableListOf<String>()
        var currentPos = segmentsInfoStart
        while (true) {
            val nextPipe = decrypted.indexOf('|'.toByte(), currentPos)
            if (nextPipe == -1) {
                val segment = String(decrypted, currentPos, decrypted.size - currentPos)
                if ('-' in segment) {
                    segments.add(segment)
                }
                break
            }
            val segment = String(decrypted, currentPos, nextPipe - currentPos)
            if ('-' in segment) {
                segments.add(segment)
                currentPos = nextPipe + 1
            } else {
                break
            }
        }

        if (segments.isEmpty()) {
            return null
        }

        val segmentInfo = segments.mapNotNull { seg ->
            val parts = seg.split('-')
            if (parts.size == 2) {
                val dy = parts[0].removePrefix("dy").trim().toIntOrNull()
                val height = parts[1].trim().toIntOrNull()
                if (dy != null && height != null) {
                    dy to height
                } else {
                    null
                }
            } else {
                null
            }
        }

        val image = ImageIO.read(ByteArrayInputStream(imageData))
        val width = oriWidth
        val heightImage = oriHeight

        val totalHeight = segmentInfo.sumOf { it.second }
        if (totalHeight != heightImage) {
            val remainingHeight = heightImage - totalHeight
            if (remainingHeight > 0) {
                segmentInfo.add(0 to remainingHeight)
            }
        }

        val newImage = BufferedImage(width, heightImage, BufferedImage.TYPE_INT_RGB)
        val graphics = newImage.createGraphics()

        var sy = 0
        for ((dy, segHeight) in segmentInfo) {
            if (sy + segHeight > heightImage) {
                break
            }
            val segment = image.getSubimage(0, sy, width, segHeight)
            if (dy < 0 || dy + segHeight > heightImage) {
                sy += segHeight
                continue
            }
            graphics.drawImage(segment, 0, dy, null)
            sy += segHeight
        }

        graphics.dispose()
        return newImage
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        val json = webClient.httpGet(url).parseJson().getJSONObject("data")

        return json.getJSONArray("pages").mapJSON { jo ->
            val imageUrl = jo.getString("image_url")
            val drmData = jo.getString("drm_data")
            val oriWidth = jo.getInt("width")
            val oriHeight = jo.getInt("height")

            val response = webClient.httpGet(imageUrl)
            val imageData = response.body?.bytes() ?: return@mapJSON null

            val decrypted = decryptDRM(drmData, decryptionKey.toByteArray()) ?: return@mapJSON null

            val reconstructedImage = reconstructImage(imageData, decrypted, oriWidth, oriHeight) ?: return@mapJSON null
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(reconstructedImage, "jpg", outputStream)
            val reconstructedImageData = outputStream.toByteArray()

            MangaPage(
                id = generateUid(jo.getLong("id")),
                url = imageUrl,
                preview = reconstructedImageData,
                source = source,
            )
        }.filterNotNull()
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

        val decrypted = try {
            decompress(decrypt(bytes))
        } catch (e: Exception) {
            bytes
        }
        val newBody = decrypted.toResponseBody(contentType)
        return response.newBuilder().body(newBody).build()
    }

    private fun decrypt(input: ByteArray): ByteArray {
        val key = decryptionKey.toByteArray()
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
        return outputStream.toByteArray()
    }
}
