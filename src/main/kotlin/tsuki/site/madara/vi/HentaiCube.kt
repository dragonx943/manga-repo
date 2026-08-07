package tsuki.site.madara.vi

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.exception.ParseException
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.SortOrder
import tsuki.network.CommonHeaders
import tsuki.network.UserAgents
import tsuki.site.madara.MadaraParser
import tsuki.util.generateUid
import tsuki.util.json.asTypedList
import tsuki.util.mapToSet
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.suspendlazy.getOrNull
import tsuki.util.suspendlazy.suspendLazy
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder
import tsuki.util.urlEncoded
import java.util.UUID

// Thằng nào làm web xứng đáng bị búng dái :p
@MangaSourceParser("HENTAICUBE", "HentaiCube", "vi", ContentType.HENTAI)
internal class HentaiCube(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HENTAICUBE, "hentaicube.xyz") {

	override val datePattern = "dd/MM/yyyy"
	override val authorSearchSupported = true
	override val postDataReq = "action=manga_views&manga="

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.build()

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pages = page + 1

		val url = buildString {
			if (!filter.author.isNullOrEmpty()) {
				clear()
				append("https://")
				append(domain)
				append("/tacgia/")
				append(filter.author?.lowercase().orEmpty().replace(" ", "-"))

				if (pages > 1) {
					append("/page/")
					append(pages.toString())
				}

				append("/?m_orderby=")
				when (order) {
					SortOrder.POPULARITY -> append("views")
					SortOrder.UPDATED -> append("latest")
					SortOrder.NEWEST -> append("new-manga")
					SortOrder.ALPHABETICAL -> {}
					SortOrder.RATING -> append("trending")
					SortOrder.RELEVANCE -> {}
					else -> append("latest") // default
				}
				return@buildString
			}

			append("https://")
			append(domain)

			if (pages > 1) {
				append("/page/")
				append(pages.toString())
			}

			append("/?s=")
            append(filter.query.urlEncoded())

			append("&post_type=wp-manga")

			if (filter.tags.isNotEmpty()) {
				filter.tags.forEach {
					append("&genre[]=")
					append(it.key)
				}
			}

			filter.states.forEach {
				append("&status[]=")
				when (it) {
					MangaState.ONGOING -> append("on-going")
					MangaState.FINISHED -> append("end")
					MangaState.ABANDONED -> append("canceled")
					MangaState.PAUSED -> append("on-hold")
					MangaState.UPCOMING -> append("upcoming")
					else -> throw IllegalArgumentException("$it not supported")
				}
			}

			filter.contentRating.oneOrThrowIfMany()?.let {
				append("&adult=")
				append(
					when (it) {
						ContentRating.SAFE -> "0"
						ContentRating.ADULT -> "1"
						else -> ""
					},
				)
			}

			if (filter.year != 0) {
				append("&release=")
				append(filter.year.toString())
			}

			append("&m_orderby=")
			when (order) {
				SortOrder.POPULARITY -> append("views")
				SortOrder.UPDATED -> append("latest")
				SortOrder.NEWEST -> append("new-manga")
				SortOrder.ALPHABETICAL -> append("alphabet")
				SortOrder.RATING -> append("rating")
				SortOrder.RELEVANCE -> {}
				else -> {}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun createMangaTag(a: Element): MangaTag? {
		val allTags = availableTags.getOrNull().orEmpty()
		val title = a.text().replace(Regex("\\(\\d+\\)"), "").trim() // force trim to remove space
		// compare to avoid duplicate tags with the same title
		return allTags.find {
			it.title.trim().equals(title, ignoreCase = true) // try to search with trim
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val result = super.getDetails(manga)
		val doc = webClient.httpGet(result.publicUrl).parseHtml()
		return result.copy(
			tags = manga.tags,
			title = doc.selectFirst("h1")?.ownText() ?: result.title,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val token = html.selectFirst(".manga-secure-reader")?.attr("data-masr2-token")
			?: throw ParseException("Web đã thay đổi thuật toán mã hóa ảnh, hết cứu!", chapter.url)

		val headers = Headers.Builder()
			.removeAll(CommonHeaders.REFERER)
			.add(CommonHeaders.REFERER, chapter.url.toAbsoluteUrl(domain))
			.add(CommonHeaders.USER_AGENT, UserAgents.CHROME_MOBILE)
			.add(CommonHeaders.COOKIE, context.cookieJar.toString())
			.build()

		val url = urlBuilder().addPathSegments("wp-json/manga-reader/v2/pages")
			.addQueryParameter("token", token)
			.addQueryParameter("cid", randomHash())
			.build()

		val json = webClient.httpGet(url, headers).parseJson()
		return json.getJSONArray("items").asTypedList<String>().map {
			MangaPage(generateUid(it), it, null, source)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val headers = request.headers.newBuilder()
			.removeAll(CommonHeaders.REFERER)
			.add(CommonHeaders.REFERER, "https://$domain/")
			.add(CommonHeaders.USER_AGENT, UserAgents.CHROME_MOBILE)
			.build()

		val newRequest = request.newBuilder()
			.headers(headers)
			.build()

		return chain.proceed(newRequest)
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/the-loai-genres").parseHtml()
		val elements = doc.select("ul.list-unstyled li a")
		return elements.mapToSet { element ->
			val href = element.attr("href")
			val key = href.substringAfter("/theloai/").removeSuffix("/")
			val title = element.text().replace(Regex("\\(\\d+\\)"), "").trim() // force trim
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}.toSet()
	}

	private fun randomHash(): String = UUID.randomUUID().toString().replace("-", "")
}
