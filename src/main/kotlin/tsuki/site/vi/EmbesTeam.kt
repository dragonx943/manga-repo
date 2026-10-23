package tsuki.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl
import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.network.CommonHeaders
import tsuki.network.UserAgents
import tsuki.util.generateUid
import tsuki.util.mapChapters
import tsuki.util.parseHtml
import tsuki.util.urlBuilder
import java.util.EnumSet
import kotlin.collections.emptySet

@Broken("Đọc tạm, cần cải thiện thêm! Gõ chay mệt vc!")
@MangaSourceParser("EMBESTEAM", "Embes Team", "vi")
internal class EmbesTeam(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.EMBESTEAM, 20) {

    override val configKeyDomain = ConfigKey.Domain("embesteam0505.wordpress.com")

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add(CommonHeaders.ORIGIN, "https://$domain")
        .add(CommonHeaders.REFERER, "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val manga = urlBuilder().addPathSegment("manga").build() // TODO: Oneshot
        return fetchMangaList(manga)
    }

    private suspend fun fetchMangaList(url: HttpUrl): List<Manga> {
        val request = webClient.httpGet(url).parseHtml()
        return request.select("div.wp-block-media-text").mapNotNull { e ->
            val figure = e.selectFirst("figure.wp-block-media-text__media a")
            val href = figure?.attr("href") ?: return@mapNotNull null
            val title = e.selectFirst("div.wp-block-media-text__content p a")?.text()
                ?: "Không có tên manga"
            val img = figure.selectFirst("img")?.attr("data-orig-file")
                ?: e.selectFirst("img")?.attr("src")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href,
                title = title,
                altTitles = emptySet(),
                coverUrl = img?.let { "$it?w=720" },
                largeCoverUrl = img,
                state = null,
                source = source,
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                authors = emptySet(),
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val request = webClient.httpGet(manga.url).parseHtml()
        val author = request.selectFirst("p.has-text-align-center:has(strong > em:contains(Tác giả))")
            ?.text()?.substringAfter("Tác giả")?.removePrefix(":")?.trim()

        val description = request.selectFirst("p.has-text-align-center:has(strong > em:contains(Giới thiệu))")
            ?.text()?.substringAfter("Giới thiệu")?.removePrefix(":")?.trim()

        val stateText = request.selectFirst("p.has-text-align-center:has(strong > em:contains(Tình trạng))")
            ?.text()?.substringAfter("Tình trạng")?.removePrefix(":")?.trim()

        val state = when (stateText) {
            "Đang ra" -> MangaState.ONGOING
            "Hoàn thành" -> MangaState.FINISHED
            else -> null
        }

        val trans = request.selectFirst("p.has-text-align-center:has(strong > em:contains(Trans))")?.text()
        val edit = request.selectFirst("p.has-text-align-center:has(strong > em:contains(Edit))")?.text()
        val scanlators = listOfNotNull(trans, edit).joinToString(" - ")
        val chapters = request.select("figure.wp-block-table strong").mapChapters { i, e ->
            val a = e.selectFirst("a")
            val href = a?.attr("href") ?: return@mapChapters null
            MangaChapter( // TODO: Fix chapters not found
                id = generateUid(href),
                title = a.text(),
                number = (i + 1).toFloat(),
                volume = 0,
                url = href,
                uploadDate = 0L,
                source = source,
                scanlator = scanlators,
                branch = null,
            )
        }
        return manga.copy(
            authors = setOfNotNull(author),
            chapters = chapters,
            description = description,
            state = state,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val elements = "figure.wp-block-gallery img, figure.wp-block-image img"
        val request = webClient.httpGet(chapter.url).parseHtml()
        val imgs = request.select(elements).mapNotNull { img ->
            val raw = img.attr("data-orig-file").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() }
            if (raw.isNullOrBlank()) null else raw.substringBefore("?")
        }
        if (imgs.isEmpty()) return emptyList()
        return imgs.dropLast(1).map { url ->
            MangaPage(
                id = generateUid(url),
                url = url, // TODO: Use wordpress CDN
                preview = null,
                source = source,
            )
        }
    }
}