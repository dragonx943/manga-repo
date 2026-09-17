package tsuki.site.vi

import okhttp3.Headers
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.*
import tsuki.network.CommonHeaders
import tsuki.network.OkHttpWebClient
import tsuki.util.*
import java.util.EnumSet
import kotlin.time.Duration.Companion.seconds

@MangaSourceParser("DILIB", "Dilib", "vi")
internal class Dilib(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DILIB, 24) {

	override val configKeyDomain = ConfigKey.Domain("dilib.vn")

	/**
	 * Tsuki throws TooManyRequestExceptions once the window is exhausted instead of
	 * waiting like keiyoushi does, so the window is kept generous.
	 */
	override val webClient = OkHttpWebClient(
		context.httpClient.newBuilder()
			.rateLimit(30, 1.seconds)
			.build(),
		source,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add(CommonHeaders.REFERER, "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
			// The site takes one value each for chinh/phu, not a list
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = MAIN_CATEGORIES.mapTo(mutableSetOf()) { (title, key) ->
			MangaTag(key = key, title = title, source = source)
		},
	)

	// ============================== List ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append(SEARCH_PATH)

			append("?page=")
			append(page)

			append("&media=")
			append(BOOK_TYPE)

			append("&sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "1"
					SortOrder.POPULARITY_ASC -> "2"
					SortOrder.NEWEST -> "3"
					SortOrder.NEWEST_ASC -> "4"
					else -> TRENDING_ORDER
				},
			)

			val tags = filter.tags
			if (tags.isNotEmpty()) {
				append("&chinh=")
				append(tags.first().key)
			}

			val author = filter.author
			if (!author.isNullOrBlank()) {
				append("&author=")
				append(author.urlEncoded())
			}

			val query = filter.query
			if (!query.isNullOrBlank()) {
				append("&find=")
				append(query.urlEncoded())
			}
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.products.row > div.type-product").mapNotNull { element ->
			val link = element.selectFirst(".block_product_thumbnail a, .block_product_content a")
				?: return@mapNotNull null
			val title = element.selectFirst(".block_product_content a")?.text()
				?: return@mapNotNull null

			val href = link.attrAsRelativeUrl("href")

			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = element.selectFirst(".block_product_thumbnail img")?.let { img ->
					img.attr("data-src").ifEmpty { img.attr("src") }.normalizeImageUrl()
				},
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	// ============================== Details ==============================

	override suspend fun getDetails(manga: Manga): Manga {
		val url = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val subtitle = doc.selectFirst("div#content h2")?.textOrNull()
		val intro = doc.selectFirst("div#content h2 + p")?.textOrNull()
		val updateTime = doc.selectFirst("p:contains(Cập nhật lúc)")?.ownText()?.trim()?.nullIfEmpty()
			?.let { "Cập nhật lúc: $it" }

		val description = listOfNotNull(updateTime, subtitle, intro)
			.joinToString("\n\n")
			.nullIfEmpty()

		val stateText = doc.selectFirst("p:contains(Tình trạng)")?.ownText()?.lowercase()
		val state = when {
			stateText == null -> null
			"đang cập nhật" in stateText -> MangaState.ONGOING
			"hoàn thành" in stateText -> MangaState.FINISHED
			else -> null
		}

		val tags = doc.select("fieldset#pdf a.button2")
			.mapNotNullToSet { a ->
				val title = a.textOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
				MangaTag(key = a.attr("href").substringAfterLast('/', ""), title = title, source = source)
			}

		return manga.copy(
			title = doc.selectFirst("div#primary h1")?.text() ?: manga.title,
			altTitles = emptySet(),
			authors = setOfNotNull(
				doc.selectFirst("div#primary h1 + p")?.text()
					?.substringAfter(':')
					?.trim()
					?.nullIfEmpty(),
			),
			description = description,
			tags = tags,
			state = state,
			coverUrl = doc.selectFirst("div#primary .size-shop_catalog img")
				?.attr("src")?.normalizeImageUrl() ?: manga.coverUrl,
			chapters = parseChapterList(doc),
		)
	}

	// ============================== Chapters ==============================

	/**
	 * The chapter list is not on the detail page: a read button leads to the first
	 * chapter, whose page holds a <select> with every chapter of the series.
	 */
	private suspend fun parseChapterList(doc: Document): List<MangaChapter> {
		val readButton = doc.selectFirst("a.button1[href*=-chap-]")
			?: doc.selectFirst("a:contains(Đọc Truyện)")

		val readUrl = readButton?.attrAsAbsoluteUrlOrNull("href")
			?: return emptyList()

		val baseChapterPath = readUrl.substringBefore("-chap-")
		val chapterDoc = webClient.httpGet(readUrl).parseHtml()

		return chapterDoc.select("select option")
			.mapNotNull { option ->
				val value = option.attr("value")
				if (!value.contains("-chap-", ignoreCase = true)) return@mapNotNull null

				val name = option.textOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val chapterUrl = "$baseChapterPath$value.html"

				MangaChapter(
					id = generateUid(chapterUrl),
					title = name,
					number = regexChapterNumber.find(name)?.value?.toFloatOrNull() ?: 0f,
					volume = 0,
					url = chapterUrl.toRelativeUrl(domain),
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
			}
			.distinctBy { it.url }
			.sortedBy { it.number }
	}

	// ============================== Pages ==============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(url).parseHtml()

		val images = doc.select("div#primary > img.border")
			.mapNotNull { element ->
				val raw = element.attr("data-src").ifEmpty { element.attr("src") }
					.replace("\r", "")
				raw.nullIfEmpty()?.let { it.toAbsoluteUrl(domain) }
			}
			.distinct()

		if (images.isEmpty()) throw ParseException("Could not find image data", url)

		return images.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	// ============================== Utils ==============================

	/**
	 * Image urls come in three shapes: protocol relative ("//host/x.jpg", seen on
	 * the listing thumbnails), root relative ("/img/news/...", used by the detail
	 * cover and chapter pages) and absolute. Only the first was handled before,
	 * which made the reader look for "/img/..." on the device itself.
	 */
	private fun String.normalizeImageUrl(): String = when {
		startsWith("//") -> "https:$this"
		startsWith("/") -> toAbsoluteUrl(domain)
		else -> this
	}

	private companion object {
		const val SEARCH_PATH = "/search.php"
		const val BOOK_TYPE = "5"
		const val TRENDING_ORDER = "5"

		val regexChapterNumber = Regex("""\d+(?:\.\d+)?""")

		val MAIN_CATEGORIES = arrayOf(
			"Manga" to "manga",
			"Manhua" to "manhua",
			"Manhwa" to "manhwa",
			"Action" to "action",
			"Adventure" to "adventure",
			"Comedy" to "comedy",
			"Fantasy" to "fantasy",
			"Shounen" to "shounen",
			"Shoujo" to "shoujo",
			"Supernatural" to "supernatural",
			"Sci-Fi" to "sci-fi",
			"Martial Arts" to "martial-arts",
			"Seinen" to "seinen",
			"Drama" to "drama",
			"Mystery" to "mystery",
			"Cooking" to "cooking",
			"Harem" to "harem",
			"Romance" to "romance",
			"School Life" to "school-life",
			"Historical" to "historical",
			"Psychological" to "psychological",
			"Tragedy" to "tragedy",
			"Truyện Màu" to "truyen-mau",
			"Horror" to "horror",
			"Slice Of Life" to "slice-of-life",
			"Adult (18+)" to "adult-18",
			"Sports" to "sports",
			"Ecchi" to "ecchi",
			"Webtoon" to "webtoon",
			"Mature" to "mature",
			"Tu Tiên" to "tu-tien",
			"Vampire" to "vampire",
			"Josei" to "josei",
			"Xuyên Không" to "xuyen-khong",
			"Magic" to "magic",
			"Monsters" to "monsters",
			"Hệ Thống" to "he-thong",
			"Thriller" to "thriller",
		)
	}
}
