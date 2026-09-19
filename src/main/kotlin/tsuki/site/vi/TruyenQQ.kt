package tsuki.site.vi

import okhttp3.OkHttpClient
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.network.OkHttpWebClient
import tsuki.network.WebClient
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TRUYENQQ", "TruyenQQ", "vi")
internal class TruyenQQ(context: MangaLoaderContext):
	PagedMangaParser(context, MangaParserSource.TRUYENQQ, 42) {

	private val client: OkHttpClient
		get() = context.httpClient.newBuilder()
			.apply {
				interceptors().clear()
				networkInterceptors().clear()
			}
			.proxy(java.net.Proxy.NO_PROXY)
			.build()

	override val webClient: WebClient
		get() = OkHttpWebClient(client, source)

	override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
		val request = chain.request()
		val host = request.url.host
		if (host.contains("truyenqq", true) ||
			host.contains("docqq", true)) {
			return client.newCall(request).execute()
		}
		return super.intercept(chain)
	}

	override val configKeyDomain = ConfigKey.Domain("truyenqqko.com")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.UPDATED_ASC,
			SortOrder.POPULARITY,
			SortOrder.POPULARITY_ASC,
			SortOrder.NEWEST,
			SortOrder.NEWEST_ASC,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
			ContentType.OTHER,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().host(domain)

		// keyword
		if (!filter.query.isNullOrEmpty()) {
			url.addEncodedPathSegments("tim-kiem/trang-$page")
			url.addEncodedQueryParameter("q", filter.query.splitByWhitespace().joinToString("%20") { it })
		} else {
			url.addEncodedPathSegments("tim-kiem-nang-cao/trang-$page")

			// country
			val country = when (filter.types.oneOrThrowIfMany()) {
				ContentType.MANHUA -> 1
				ContentType.OTHER -> 2 // Việt Nam
				ContentType.MANHWA -> 3
				ContentType.MANGA -> 4
				ContentType.COMICS -> 5
				else -> 0 // Tất cả
			}
			url.addEncodedQueryParameter("country", country.toString())

			// Order
			val order = when (order) {
				SortOrder.UPDATED_ASC -> 3
				SortOrder.NEWEST -> 0
				SortOrder.NEWEST_ASC -> 1
				SortOrder.POPULARITY -> 4
				SortOrder.POPULARITY_ASC -> 5
				else -> 2 // UPDATED
			}
			url.addEncodedQueryParameter("sort", order.toString())

			// Status
			val status = when (filter.states.oneOrThrowIfMany()) {
				MangaState.ONGOING -> 0
				MangaState.FINISHED -> 1
				else -> -1
			}
			url.addEncodedQueryParameter("status", status.toString())

			// Genres
			url.addEncodedQueryParameter("category", filter.tags.joinToString(separator = ",") { it.key })

			// Exclude genres
			url.addEncodedQueryParameter("notcategory", filter.tagsExclude.joinToString(separator = ",") { it.key })

			// Other
			url.addEncodedQueryParameter("minchapter", 0.toString())
		}
		val doc = webClient.httpGet(url.build()).parseHtml()
		return doc.requireElementById("main_homepage").select("li").map { li ->
			val href = li.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = li.selectFirst(".book_name")?.text().orEmpty(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = li.selectFirst("img")?.src().orEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
		val author = doc.selectFirst("li.author a")?.text()
		return manga.copy(
			altTitles = setOfNotNull(doc.selectFirst("h2.other-name")?.textOrNull()),
			tags = doc.select("ul.list01 li").mapToSet {
				val key = it.attr("href").substringAfterLast("-").substringBeforeLast(".")
				MangaTag(
					key = key,
					title = it.text(),
					source = source,
				)
			},
			state = when (doc.selectFirst(".status p.col-xs-9")?.text()) {
				"Đang Cập Nhật" -> MangaState.ONGOING
				"Hoàn Thành" -> MangaState.FINISHED
				else -> null
			},
			authors = setOfNotNull(author),
			description = doc.selectFirst(".story-detail-info")?.html(),
			chapters = doc.select("div.list_chapter div.works-chapter-item").mapChapters(true) { i, div ->
				val a = div.selectFirstOrThrow("a")
				val href = a.attrAsRelativeUrl("href")
				val name = a.text()
				val dateText = div.selectFirst(".time-chap")?.text()
				MangaChapter(
					id = generateUid(href),
					title = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val root = doc.body().selectFirstOrThrow(".chapter_content")
		return root.select("div.page-chapter").map { div ->
			val img = div.selectFirstOrThrow("img")
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = urlBuilder().host(domain).addPathSegment("tim-kiem-nang-cao")
		val doc = webClient.httpGet(url.build()).parseHtml()
		return doc.select(".advsearch-form div.genre-item").mapToSet {
			MangaTag(
				key = it.selectFirstOrThrow("span").attr("data-id"),
				title = it.text(),
				source = source,
			)
		}
	}
}

