package org.koitharu.kotatsu.parsers.site.vi.yurigarden

import androidx.collection.arraySetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import java.util.*

internal abstract class YuriGardenParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	protected val isR18Enable: Boolean = false
) : PagedMangaParser(context, source, 18) {

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override val configKeyDomain = ConfigKey.Domain(domain)
	private val apiSuffix = "api.$domain"

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("x-app-origin", "https://$domain")
		.add("User-Agent", UserAgents.KOTATSU)
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = availableTags.get(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.ABANDONED,
				MangaState.PAUSED,
				MangaState.UPCOMING,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(apiSuffix)
			append("/comics")
			append("?page=")
			append(page)
			append("&limit=")
			append(pageSize)
			append("&r18=")
			append(isR18Enable)

			append("&sort=")
			append(when (order) {
				SortOrder.NEWEST -> "newest"
				SortOrder.NEWEST_ASC -> "oldest"
				else -> "newest" // default
			})

			if (!filter.query.isNullOrEmpty()) {
				append("&search=")
				append(filter.query.urlEncoded())
			}

			filter.states.oneOrThrowIfMany()?.let { state ->
				append("&status=")
				append(when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					MangaState.ABANDONED -> "cancelled"
					MangaState.UPCOMING -> "oncoming"
					else -> "all"
				})
			}

			append("&full=true")
                  
			if (filter.tags.isNotEmpty()) {
				append("&genre=")
				append(filter.tags.joinToString(separator = ",") { it.key })
			}

			if (!filter.author.isNullOrEmpty()) {
				clear()

				append("https://")
				append(apiSuffix)
				append("/creators/authors/")
				append(
					filter.author.substringAfter("(").substringBefore(")")
				)

				return@buildString // end of buildString
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val data = json.getJSONArray("comics")

		return data.mapJSON { jo ->
			val id = jo.getLong("id")
			val altTitles = setOf(jo.optString("anotherName", null))
				.filterNotNull()
				.toSet()
			val tags = fetchTags().let { allTags ->
				jo.optJSONArray("genres")?.asTypedList<String>()?.mapNotNullToSet { g ->
					allTags.find { x -> x.key == g }
				}
			}.orEmpty()
			
			Manga(
				id = generateUid(id),
				url = "/comics/$id",
				publicUrl = "https://$domain/comic/$id",
				title = jo.getString("title"),
				altTitles = altTitles,
				coverUrl = jo.getString("thumbnail"),
				largeCoverUrl = jo.getString("thumbnail"),
				authors = emptySet(),
				tags = tags,
				state = when(jo.optString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"cancelled" -> MangaState.ABANDONED
					"oncoming" -> MangaState.UPCOMING
					else -> null
				},
				description = jo.optString("description").orEmpty(),
				contentRating = if (jo.getBooleanOrDefault("r18", false)) ContentRating.ADULT else ContentRating.SUGGESTIVE,
				source = source,
				rating = RATING_UNKNOWN,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val id = manga.url.substringAfter("/comics/")
		val json = webClient.httpGet("https://$apiSuffix/comics/${id}").parseJson()

		val authors = json.optJSONArray("authors")?.mapJSONToSet { jo ->
			jo.getString("name") + " (${jo.getLong("id")})"
		}.orEmpty()

		val altTitles = setOf(json.getString("anotherName"))
		val description = json.getString("description")
		val team = json.optJSONArray("teams")?.getJSONObject(0)?.getString("name")

		val chaptersDeferred = async {
			webClient.httpGet("https://$apiSuffix/chapters/comic/${id}").parseJsonArray()
		}

		manga.copy(
			altTitles = altTitles,
			authors = authors,
			chapters = chaptersDeferred.await().mapChapters { _, jo ->
				val chapId = jo.getLong("id")
				MangaChapter(
					id = generateUid(chapId),
					title = jo.getString("name"),
					number = jo.getFloatOrDefault("order", 0f),
					volume = 0,
					url = "$chapId",
					scanlator = team,
					uploadDate = jo.getLong("lastUpdated"),
					branch = null,
					source = source,
				)
			},
			description = description,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val json = webClient.httpGet("https://$apiSuffix/chapters/${chapter.url}").parseJson()
		val pages = json.getJSONArray("pages").asTypedList<JSONObject>()

		return pages.mapIndexed { index, page ->
			val rawUrl = page.getString("url")

			if (rawUrl.startsWith("comics")) {
				val key = page.optString("key", null)

				val url = "https://$domain/$rawUrl".toHttpUrl().newBuilder().apply {
					if (!key.isNullOrEmpty()) {
						fragment("KEY=$key")
					}
				}

				MangaPage(
					id = generateUid(index.toLong()),
					url = url.build().toString(),
					preview = null,
					source = source,
				)
			} else {
				val url = rawUrl.toHttpUrlOrNull()?.toString() ?: rawUrl
				MangaPage(
					id = generateUid(index.toLong()),
					url = url,
					preview = null,
					source = source,
				)
			}
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		val fragment = response.request.url.fragment ?: return response
		if (!fragment.contains("KEY=")) return response

		return context.redrawImageResponse(response) { bitmap ->
			val key = fragment.substringAfter("KEY=")
			kotlinx.coroutines.runBlocking {
				unscrambleYuriGarden(bitmap, key)
			}
		}
	}

	private suspend fun unscrambleYuriGarden(bitmap: Bitmap, key: String): Bitmap {
		val js = """
    (function(K,H,PC){
      const A="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz",F=[1,1,2,6,24,120,720,5040,40320,362880,3628800];
      function I(e,p){const n=Array.from({length:p},(_,i)=>i),r=[];for(let a=p-1;a>=0;a--){const i=F[a],s=Math.floor(e/i);e%=i;r.push(n.splice(s,1)[0])}return r}
      function S(e){let t=0;for(const n of e){const r=A.indexOf(n);if(r<0)throw new Error("Invalid Base58 char");t=t*58+r}return t}
      function U(e,p){if(!/^H[1-9A-HJ-NP-Za-km-z]+$/.test(e))throw new Error("Invalid Base58 char");const t=e.slice(1,-1),n=e.slice(-1),r=S(t);if(A[r%58]!==n)throw new Error("Base58 checksum mismatch");return I(r,p)}
      function P(h,p){const n=Math.floor(h/p),r=h%p,a=[];for(let i=0;i<p;i++)a.push(n+(i<r?1:0));return a}
      function D(e){const t=Array(e.length).fill(0);for(let n=0;n<e.length;n++)t[e[n]]=n;return t}
      function X(k,h,p){const e=U(k.slice(4),p),s=D(e),u=P(h-4*(p-1),p),m=e.map(i=>u[i]);let pts=[0];for(let i=0;i<m.length;i++)pts[i+1]=pts[i]+m[i];let f=[];for(let i=0;i<m.length;i++)f.push({y:i==0?0:pts[i]+4*i,h:m[i]});return s.map(i=>f[i])}
      return JSON.stringify(X(K,H,PC));
    })("$key", ${bitmap.height}, 10);
    """.trimIndent()

		val result = context.evaluateJs(js) ?: throw IOException("Debugging: JS evaluation failed")
		val arr = JSONArray(result)

		val out = context.createBitmap(bitmap.width, bitmap.height)
		var dy = 0
		for (i in 0 until arr.length()) {
			val o = arr.getJSONObject(i)
			val sy = o.getInt("y")
			val h = o.getInt("h")
			val src = Rect(0, sy, bitmap.width, sy + h)
			val dst = Rect(0, dy, bitmap.width, dy + h)
			out.drawBitmap(bitmap, src, dst)
			dy += h
		}
		return out
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$apiSuffix/resources/systems_vi.json").parseJson()
		val genres = json.getJSONObject("genres")
		return genres.keys().asSequence().mapTo(arraySetOf()) { key ->
			val genre = genres.getJSONObject(key)
			MangaTag(
				title = genre.getString("name").toTitleCase(sourceLocale),
				key = genre.getString("slug"),
				source = source,
			)
		}
	}
}
