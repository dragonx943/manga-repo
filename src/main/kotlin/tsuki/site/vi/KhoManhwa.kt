package tsuki.site.vi

import org.json.JSONObject
import tsuki.Broken
import tsuki.ErrorMessages
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.generateUid
import tsuki.util.json.asTypedList
import tsuki.util.json.mapJSON
import tsuki.util.mapChapters
import tsuki.util.mapToSet
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.parseSafe
import tsuki.util.splitByWhitespace
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.urlBuilder
import java.text.SimpleDateFormat
import java.util.EnumSet

// Thằng nào làm web này xứng đáng bị búng dái!
@Broken("403 forbidden in getPages, need to handle login")
@MangaSourceParser("KHOMANHWA", "KhoManhwa", "vi", type = ContentType.HENTAI)
internal class KhoManhwa(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.KHOMANHWA, 30) {

    override val configKeyDomain = ConfigKey.Domain("khomanhwa.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL, // az
        SortOrder.ALPHABETICAL_DESC, // za
        SortOrder.UPDATED, // updated
        SortOrder.NEWEST, // newest
        SortOrder.POPULARITY, // popular
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isAuthorSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(), // later
        availableStates = EnumSet.of(
            MangaState.ONGOING, // Ongoing
            MangaState.FINISHED, // Completed
            MangaState.PAUSED, // Hiatus
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder().addPathSegment("search")

        // keyword
        url.addEncodedQueryParameter("q", filter.query.splitByWhitespace().joinToString("+") { it })

        // genre
        filter.tags.firstOrNull()?.key?.let { genre ->
            url.addEncodedQueryParameter("genre", genre.splitByWhitespace().joinToString("+") { it })
        }

        // status
        if (filter.states.size >= 2) {
            // oneOrThrowIfMany
            throw IllegalArgumentException(ErrorMessages.FILTER_MULTIPLE_STATES_NOT_SUPPORTED)
        } else {
            when (filter.states.firstOrNull()) {
                MangaState.ONGOING -> url.addQueryParameter("status", "Ongoing")
                MangaState.FINISHED -> url.addQueryParameter("status", "Completed")
                MangaState.PAUSED -> url.addQueryParameter("status", "Hiatus")
                else -> url.addQueryParameter("status", "")
            }
        }

        // sort
        when (order) {
            SortOrder.ALPHABETICAL -> url.addQueryParameter("sort", "az")
            SortOrder.ALPHABETICAL_DESC -> url.addQueryParameter("sort", "za")
            SortOrder.NEWEST -> url.addQueryParameter("sort", "newest")
            SortOrder.POPULARITY -> url.addQueryParameter("sort", "popular")
            else -> url.addQueryParameter("sort", "updated") // updated, default
        }

        // author
        if (!filter.author.isNullOrEmpty()) {
            url.addEncodedQueryParameter("author", filter.author.splitByWhitespace().joinToString("+") { it })
        }

        if (page > 1) {
            url.addQueryParameter("page", page.toString())
        }

        val request = webClient.httpGet(url.build()).parseHtml()
        return request.select(".grid-cards a.series-card").map {
            val href = it.attr("href")
            val img = it.selectFirst("img")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img?.src(),
                title = it.selectFirst("strong")?.text().orEmpty(),
                altTitles = emptySet(),
                description = it.selectFirst("p")?.text(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = when (it.selectFirst("span.meta-status-ongoing")?.text()) {
                    "Ongoing" -> MangaState.ONGOING
                    "Completed" -> MangaState.FINISHED
                    "Hiatus" -> MangaState.PAUSED
                    else -> null
                },
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val dateFormat = SimpleDateFormat("MMM d, yyyy", sourceLocale)
        val response = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val script = response.selectFirst("script[type='application/ld+json']")?.data()
        val json = if (!script.isNullOrBlank()) JSONObject(script) else null

        val main = response.selectFirst("section.series-main")

        val altTitles: Set<String> = main?.selectFirst("p.alt-names")?.text()
            ?.removePrefix("Alternative:")?.split("/")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        val state = when (response.selectFirst("dl.vortex-facts a")?.text()) {
            "Ongoing" -> MangaState.ONGOING
            "Completed" -> MangaState.FINISHED
            "Hiatus" -> MangaState.PAUSED
            else -> null
        }

        return manga.copy(
            authors = setOfNotNull(json?.getJSONObject("author")?.getString("name")),
            altTitles = altTitles,
            description = main?.selectFirst("section.summary-inline p")?.ownText(),
            tags = json?.getJSONArray("genre")?.asTypedList<String>()?.mapToSet {
                MangaTag(it, it, source)
            } ?: emptySet(),
            state = state,
            chapters = response.select(".chapter-list .chapter-row").mapChapters(true) { _, row ->
                val name = row.attr("data-title")
                MangaChapter(
                    id = generateUid(name),
                    title = name,
                    number = row.attr("data-number").toFloat(),
                    volume = 0,
                    url = row.selectFirst("a.chapter-main")?.attr("href")?.toRelativeUrl(domain).orEmpty(),
                    scanlator = null,
                    uploadDate = dateFormat.parseSafe(row.selectFirst("span.chapter-age")?.text()),
                    branch = null,
                    source = source,
                )
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val vip = doc.selectFirst("title")?.text() ?: ""

        if (vip.contains("vip")) {
            throw ParseException("This chapter is locked, please login to read it", fullUrl)
        }

        val token = doc.selectFirst("main.reader .chapter_boxImages")?.attr("data-token")
        val imageUrl = urlBuilder().addPathSegment("reader_images.php")
            .addQueryParameter("manga", chapter.url.substringBeforeLast("/"))
            .addQueryParameter("chapter", chapter.url.substringAfterLast("/"))
            .addQueryParameter("token", token)
            .build()

        val res = webClient.httpGet(imageUrl).parseJson()
        return res.getJSONArray("images").mapJSON {
            val url = it.getString("url")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun getAvailableTags(): Set<MangaTag> {
        val url = urlBuilder().addPathSegment("search").build()
        val request = webClient.httpGet(url).parseHtml()
        return request.select("select[name='genre'] option").mapToSet {
            MangaTag(
                title = it.text(),
                key = it.attr("value"),
                source = source,
            )
        }
    }
}