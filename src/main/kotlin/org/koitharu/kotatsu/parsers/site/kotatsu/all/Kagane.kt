package org.koitharu.kotatsu.parsers.site.kotatsu.all

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.network.UserAgents
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGANE,  100) {

    private val apiUrl = "yuzuki.kagane.to"
    private val apiSuffix = "api/v2"

    override val configKeyDomain = ConfigKey.Domain("kagane.to")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.remove(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add(CommonHeaders.REFERER, "https://$domain/")
        .add(CommonHeaders.ORIGIN, "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE, // relevance
        SortOrder.UPDATED, // recently updated
        SortOrder.ADDED, // newest added
        SortOrder.ADDED_ASC, // oldest added
        SortOrder.RATING, // most view
        SortOrder.POPULARITY, // most popular
        SortOrder.POPULARITY_TODAY, // trending today
        SortOrder.POPULARITY_WEEK, // trending week
        SortOrder.POPULARITY_MONTH, // trending month
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isAuthorSearchSupported = true,
            isYearSupported = true,
            isYearRangeSupported = true,
            isOriginalLocaleSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
                ContentType.OTHER,
            ),
            availableDemographics = EnumSet.of(
                Demographic.JOSEI,
                Demographic.SEINEN,
                Demographic.SHOUJO,
                Demographic.SHOUNEN,
            )
            /*availableLocales = EnumSet.of(
                Locale.JAPANESE,

            ),*/
        )
    }
}