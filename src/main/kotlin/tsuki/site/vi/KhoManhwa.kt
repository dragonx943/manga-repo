package tsuki.site.vi

import tsuki.ErrorMessages
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
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
import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.mapChapters
import tsuki.util.mapToSet
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.splitByWhitespace
import tsuki.util.src
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder
import java.util.EnumSet

// Thằng nào làm web này xứng đáng bị búng dái!
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
        // https://khomanhwa.com/search?q=&genre=&status=&sort=updated
        // https://khomanhwa.com/search?q=&status=&sort=updated&page=30
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
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        return manga.copy(
            rating = doc.selectFirst("div > span.leading-none")?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
            authors = setOfNotNull(doc.selectFirst("aside p:contains(Tác giả:) a[href^='/tac-gia/']")?.textOrNull()),
            chapters = doc.select("ul[itemtype='https://schema.org/ItemList'] li")
                .mapChapters(reversed = true) { i, li ->
                    val a = li.selectFirstOrThrow("a")
                    val href = a.attrAsRelativeUrl("href")
                    val name = li.selectFirst("div.w-\\[50\\%\\].truncate.flex")?.text() ?: ""
                    MangaChapter(
                        id = generateUid(href),
                        title = name,
                        number = i + 1f,
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = null,
                        source = source,
                    )
                },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("img.lozad.mx-auto.transition-all.max-w-full.relative").map { img ->
            val url = img.attr("data-src")
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