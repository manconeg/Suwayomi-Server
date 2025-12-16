package suwayomi.tachidesk.opds.impl

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import suwayomi.tachidesk.i18n.MR
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.MangaList.proxyThumbnailUrl
import suwayomi.tachidesk.manga.impl.sync.KoreaderSyncService
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.opds.constants.OpdsConstants
import suwayomi.tachidesk.opds.dto.OpdsChapterListAcqEntry
import suwayomi.tachidesk.opds.dto.OpdsChapterMetadataAcqEntry
import suwayomi.tachidesk.opds.dto.OpdsMangaAcqEntry
import suwayomi.tachidesk.opds.dto.OpdsMangaDetails
import suwayomi.tachidesk.opds.dto.OpdsMangaFilter
import suwayomi.tachidesk.opds.dto.PrimaryFilterType
import suwayomi.tachidesk.opds.model.OpdsAuthorXml
import suwayomi.tachidesk.opds.model.OpdsCategoryXml
import suwayomi.tachidesk.opds.model.OpdsContentXml
import suwayomi.tachidesk.opds.model.OpdsEntryXml
import suwayomi.tachidesk.opds.model.OpdsLinkXml
import suwayomi.tachidesk.opds.model.OpdsSummaryXml
import suwayomi.tachidesk.opds.repository.MangaRepository
import suwayomi.tachidesk.opds.repository.NavigationRepository
import suwayomi.tachidesk.opds.util.OpdsDateUtil
import suwayomi.tachidesk.opds.util.OpdsStringUtil.formatFileSizeForOpds
import suwayomi.tachidesk.server.serverConfig
import java.util.Locale

/**
 * A builder class responsible for creating OPDS Entry XML objects from data transfer objects.
 */
object OpdsEntryBuilder {
    private fun currentFormattedTime() = OpdsDateUtil.formatCurrentInstantForOpds()

    /**
     * Builds a concise summary for a manga entry, including status, source, and language.
     * @param entry The manga data object.
     * @param locale The locale for localization.
     * @return A formatted summary string.
     */
    private fun buildMangaSummary(
        entry: OpdsMangaAcqEntry,
        locale: Locale,
    ): String {
        val summaryParts = mutableListOf<String>()
        val statusKey =
            when (MangaStatus.valueOf(entry.status)) {
                MangaStatus.ONGOING -> MR.strings.manga_status_ongoing
                MangaStatus.COMPLETED -> MR.strings.manga_status_completed
                MangaStatus.LICENSED -> MR.strings.manga_status_licensed
                MangaStatus.PUBLISHING_FINISHED -> MR.strings.manga_status_publishing_finished
                MangaStatus.CANCELLED -> MR.strings.manga_status_cancelled
                MangaStatus.ON_HIATUS -> MR.strings.manga_status_on_hiatus
                else -> MR.strings.manga_status_unknown
            }
        summaryParts.add(MR.strings.opds_manga_summary_status.localized(locale, statusKey.localized(locale)))
        summaryParts.add(MR.strings.opds_manga_summary_source.localized(locale, entry.sourceName))
        summaryParts.add(MR.strings.opds_manga_summary_language.localized(locale, entry.sourceLang))
        return summaryParts.joinToString(" | ")
    }

    /**
     * Adds a facet link to the feed builder.
     * @param feedBuilder The feed builder to add the link to.
     * @param href The URL for the facet link.
     * @param title The title of the facet.
     * @param group The group this facet belongs to.
     * @param isActive Whether this facet is currently active.
     * @param count The number of items in this facet.
     */
    private fun addFacet(
        feedBuilder: FeedBuilderInternal,
        href: String,
        title: String,
        group: String,
        isActive: Boolean,
        count: Long?,
    ) {
        feedBuilder.links.add(
            OpdsLinkXml(
                OpdsConstants.LINK_REL_FACET,
                href,
                OpdsConstants.TYPE_ATOM_XML_FEED_ACQUISITION,
                title,
                facetGroup = group,
                activeFacet = isActive,
                thrCount = count?.toInt(),
            ),
        )
    }

    /**
     * Converts a manga data object into a full OPDS acquisition entry.
     * @param entry The manga data object.
     * @param baseUrl The base URL for constructing links.
     * @param locale The locale for localization.
     * @return An [OpdsEntryXml] object representing the manga.
     */
    fun mangaAcqEntryToEntry(
        entry: OpdsMangaAcqEntry,
        baseUrl: String,
        locale: Locale,
    ): OpdsEntryXml {
        val displayThumbnailUrl = entry.thumbnailUrl?.let { proxyThumbnailUrl(entry.id) }
        val categoryScheme = if (entry.inLibrary) "$baseUrl/library/genres" else "$baseUrl/genres"

        val links = mutableListOf<OpdsLinkXml>()
        links.add(
            OpdsLinkXml(
                OpdsConstants.LINK_REL_SUBSECTION,
                "$baseUrl/series/${entry.id}/chapters?lang=${locale.toLanguageTag()}",
                OpdsConstants.TYPE_ATOM_XML_FEED_ACQUISITION,
                entry.title,
            ),
        )
        entry.url?.let {
            links.add(
                OpdsLinkXml(
                    OpdsConstants.LINK_REL_ALTERNATE,
                    it,
                    "text/html",
                    MR.strings.opds_linktitle_view_on_web.localized(locale),
                ),
            )
        }
        displayThumbnailUrl?.let {
            links.add(OpdsLinkXml(OpdsConstants.LINK_REL_IMAGE, it, OpdsConstants.TYPE_IMAGE_JPEG))
            links.add(OpdsLinkXml(OpdsConstants.LINK_REL_IMAGE_THUMBNAIL, it, OpdsConstants.TYPE_IMAGE_JPEG))
        }

        val summaryText = buildMangaSummary(entry, locale)
        return OpdsEntryXml(
            id = "urn:suwayomi:manga:${entry.id}",
            title = entry.title,
            updated = OpdsDateUtil.formatEpochMillisForOpds(entry.lastFetchedAt * 1000),
            authors = entry.author?.let { listOf(OpdsAuthorXml(name = it)) },
            categories =
                entry.genres.filter { it.isNotBlank() }.map { genre ->
                    OpdsCategoryXml(
                        term = genre.lowercase().replace(" ", "_"),
                        label = genre,
                        scheme = categoryScheme,
                    )
                },
            summary = OpdsSummaryXml(value = summaryText),
            content = entry.description?.let { OpdsContentXml(type = "text", value = it) },
            link = links,
            publisher = entry.sourceName,
            language = entry.sourceLang,
        )
    }

    /**
     * Creates an OPDS entry for a chapter, including acquisition and streaming links.
     * @param chapter The chapter data object.
     * @param manga The parent manga's details.
     * @param baseUrl The base URL for constructing links.
     * @param addMangaTitle Whether to prepend the manga title to the entry title.
     * @param locale The locale for localization.
     * @return An [OpdsEntryXml] object for the chapter.
     */
    suspend fun createChapterListEntry(
        chapter: OpdsChapterListAcqEntry,
        manga: OpdsMangaDetails,
        baseUrl: String,
        addMangaTitle: Boolean,
        locale: Locale,
    ): OpdsEntryXml {
        val statusKey =
            when {
                chapter.read -> MR.strings.opds_chapter_status_read
                chapter.lastPageRead > 0 -> MR.strings.opds_chapter_status_in_progress
                else -> MR.strings.opds_chapter_status_unread
            }
        val titlePrefix = statusKey.localized(locale)
        val entryTitle = titlePrefix + (if (addMangaTitle) " ${manga.title}:" else "") + " ${chapter.name}"
        val details =
            buildString {
                append(MR.strings.opds_chapter_details_base.localized(locale, manga.title, chapter.name))
                chapter.scanlator?.takeIf { it.isNotBlank() }?.let {
                    append(MR.strings.opds_chapter_details_scanlator.localized(locale, it))
                }
                if (chapter.pageCount > 0) {
                    append(MR.strings.opds_chapter_details_progress.localized(locale, chapter.lastPageRead, chapter.pageCount))
                }
            }
        return OpdsEntryXml(
            id = "urn:suwayomi:chapter:${chapter.id}",
            title = entryTitle,
            updated = OpdsDateUtil.formatEpochMillisForOpds(chapter.uploadDate),
            authors =
                listOfNotNull(
                    manga.author?.let { OpdsAuthorXml(name = it) },
                    chapter.scanlator?.takeIf { it.isNotBlank() }?.let { OpdsAuthorXml(name = it) },
                ),
            summary = OpdsSummaryXml(value = details),
            link =
                listOf(
                    OpdsLinkXml(
                        rel = OpdsConstants.LINK_REL_SUBSECTION,
                        href = "$baseUrl/series/${manga.id}/chapter/${chapter.sourceOrder}/metadata?lang=${locale.toLanguageTag()}",
                        type = OpdsConstants.TYPE_ATOM_XML_ENTRY_PROFILE_OPDS,
                        title = MR.strings.opds_linktitle_view_chapter_details.localized(locale),
                    ),
                ),
        )
    }

    /**
     * Creates one or two OPDS entries for a chapter, handling synchronization conflicts internally.
     *
     * @param chapter The chapter metadata object.
     * @param manga The parent manga's details.
     * @param baseUrl The base URL for constructing links.
     * @param locale The locale for localization.
     * @return A `Pair` where the first element is the primary entry (always present) and the
     * second is an optional entry representing the remote progress in case of a conflict.
     */
    suspend fun createChapterMetadataEntries(
        chapter: OpdsChapterMetadataAcqEntry,
        manga: OpdsMangaDetails,
        baseUrl: String,
        locale: Locale,
    ): Pair<OpdsEntryXml, OpdsEntryXml?> {
        // Check remote progress before building the entry
        val syncResult = KoreaderSyncService.checkAndPullProgress(chapter.id)

        // Exists a conflict if the sync service reports a conflict and the page numbers differ.
        val hasConflict = syncResult?.isConflict == true && syncResult.pageRead != chapter.lastPageRead

        if (hasConflict) {
            // Generate two entries: one for local progress and another for remote.
            val localEntry =
                buildSingleChapterMetadataEntry(
                    chapter,
                    manga,
                    baseUrl,
                    locale,
                    progressSource = ProgressSource.Local(chapter.lastPageRead, chapter.lastReadAt),
                    isConflict = true,
                )

            val remoteEntry =
                buildSingleChapterMetadataEntry(
                    chapter,
                    manga,
                    baseUrl,
                    locale,
                    progressSource = ProgressSource.Remote(syncResult!!.pageRead, syncResult.timestamp, syncResult.device),
                    isConflict = true,
                )
            return Pair(localEntry, remoteEntry)
        } else {
            // No conflict, generate a single entry. Use remote progress if a silent update occurred.
            val progressSource =
                if (syncResult?.shouldUpdate == true) {
                    ProgressSource.Remote(syncResult.pageRead, syncResult.timestamp, syncResult.device)
                } else {
                    ProgressSource.Local(chapter.lastPageRead, chapter.lastReadAt)
                }

            val mainEntry =
                buildSingleChapterMetadataEntry(
                    chapter,
                    manga,
                    baseUrl,
                    locale,
                    progressSource = progressSource,
                    isConflict = false,
                )
            return Pair(mainEntry, null)
        }
    }

    /**
     * Represents the source of progress information for a chapter.
     */
    private sealed class ProgressSource {
        abstract val lastPageRead: Int
        abstract val lastReadAt: Long

        data class Local(
            override val lastPageRead: Int,
            override val lastReadAt: Long,
        ) : ProgressSource()

        data class Remote(
            override val lastPageRead: Int,
            override val lastReadAt: Long,
            val device: String,
        ) : ProgressSource()
    }

    /**
     * Helper function to build a single OpdsEntryXml for a chapter.
     */
    private suspend fun buildSingleChapterMetadataEntry(
        chapter: OpdsChapterMetadataAcqEntry,
        manga: OpdsMangaDetails,
        baseUrl: String,
        locale: Locale,
        progressSource: ProgressSource,
        isConflict: Boolean,
    ): OpdsEntryXml {
        val idSuffix: String
        val titlePrefix: String

        when (progressSource) {
            is ProgressSource.Local -> {
                idSuffix = "" // No suffix for the primary/local entry
                val statusKey =
                    when {
                        chapter.downloaded -> MR.strings.opds_chapter_status_downloaded
                        chapter.read -> MR.strings.opds_chapter_status_read
                        progressSource.lastPageRead > 0 -> MR.strings.opds_chapter_status_in_progress
                        else -> MR.strings.opds_chapter_status_unread
                    }
                titlePrefix = statusKey.localized(locale)
            }

            is ProgressSource.Remote -> {
                idSuffix = ":remote"
                titlePrefix = MR.strings.opds_chapter_status_synced.localized(locale, progressSource.device)
            }
        }

        val details =
            buildString {
                append(MR.strings.opds_chapter_details_base.localized(locale, manga.title, chapter.name))
                chapter.scanlator?.takeIf { it.isNotBlank() }?.let {
                    append(MR.strings.opds_chapter_details_scanlator.localized(locale, it))
                }
                val pageCountDisplay = chapter.pageCount.takeIf { it > 0 } ?: "?"
                append(MR.strings.opds_chapter_details_progress.localized(locale, progressSource.lastPageRead, pageCountDisplay))
            }

        val entryTitle = "$titlePrefix ${chapter.name}"
        val cbzFileSize =
            if (chapter.downloaded) {
                withContext(Dispatchers.IO) {
                    runCatching { ChapterDownloadHelper.getArchiveStreamWithSize(manga.id, chapter.id).second }.getOrNull()
                }
            } else {
                null
            }

        val links = mutableListOf<OpdsLinkXml>()
        chapter.url?.let {
            links.add(
                OpdsLinkXml(OpdsConstants.LINK_REL_ALTERNATE, it, "text/html", MR.strings.opds_linktitle_view_on_web.localized(locale)),
            )
        }
        if (chapter.downloaded) {
            links.add(
                OpdsLinkXml(
                    OpdsConstants.LINK_REL_ACQUISITION_OPEN_ACCESS,
                    "/api/v1/chapter/${chapter.id}/download?markAsRead=${serverConfig.opdsMarkAsReadOnDownload.value}",
                    serverConfig.opdsCbzMimetype.value.mediaType,
                    MR.strings.opds_linktitle_download_cbz.localized(locale),
                    length = cbzFileSize,
                ),
            )
        }
        if (chapter.pageCount > 0) {
            val basePageHref =
                "/api/v1/manga/${manga.id}/chapter/${chapter.sourceOrder}/page/{pageNumber}" +
                    "?updateProgress=${serverConfig.opdsEnablePageReadProgress.value}&opds=true"

            val title: String =
                when {
                    !isConflict -> {
                        val titleRes =
                            if (progressSource.lastPageRead > 0) {
                                MR.strings.opds_linktitle_stream_pages_continue
                            } else {
                                MR.strings.opds_linktitle_stream_pages_start
                            }
                        titleRes.localized(locale)
                    }

                    progressSource is ProgressSource.Local -> {
                        val titleRes =
                            if (progressSource.lastPageRead > 0) {
                                MR.strings.opds_linktitle_stream_pages_continue_local
                            } else {
                                MR.strings.opds_linktitle_stream_pages_start_local
                            }
                        titleRes.localized(locale)
                    }

                    progressSource is ProgressSource.Remote -> {
                        val titleRes =
                            if (progressSource.lastPageRead > 0) {
                                MR.strings.opds_linktitle_stream_pages_continue_remote
                            } else {
                                MR.strings.opds_linktitle_stream_pages_start_remote
                            }
                        titleRes.localized(locale, progressSource.device)
                    }

                    else -> {
                        // Should not happen
                        ""
                    }
                }

            links.add(
                OpdsLinkXml(
                    rel = OpdsConstants.LINK_REL_PSE_STREAM,
                    href = basePageHref,
                    type = OpdsConstants.TYPE_IMAGE_JPEG,
                    title = title,
                    pseCount = chapter.pageCount,
                    pseLastRead = progressSource.lastPageRead.takeIf { it > 0 },
                    pseLastReadDate = progressSource.lastReadAt.takeIf { it > 0 }?.let { OpdsDateUtil.formatEpochMillisForOpds(it * 1000) },
                ),
            )
            links.add(
                OpdsLinkXml(
                    rel = OpdsConstants.LINK_REL_IMAGE,
                    href = "/api/v1/manga/${manga.id}/chapter/${chapter.sourceOrder}/page/0",
                    type = OpdsConstants.TYPE_IMAGE_JPEG,
                    title = MR.strings.opds_linktitle_chapter_cover.localized(locale),
                ),
            )
        }

        return OpdsEntryXml(
            id = "urn:suwayomi:chapter:${chapter.id}:metadata$idSuffix",
            title = entryTitle,
            updated = OpdsDateUtil.formatEpochMillisForOpds(chapter.uploadDate),
            authors =
                listOfNotNull(
                    manga.author?.let { OpdsAuthorXml(name = it) },
                    chapter.scanlator?.takeIf { it.isNotBlank() }?.let { OpdsAuthorXml(name = it) },
                ),
            summary = OpdsSummaryXml(value = details),
            link = links,
            extent = cbzFileSize?.let { formatFileSizeForOpds(it) },
            format = if (cbzFileSize != null) "CBZ" else null,
        )
    }

    /**
     * Adds sorting facet links for an 'Explore Source' feed.
     */
    fun addSourceSortFacets(
        feedBuilder: FeedBuilderInternal,
        baseUrl: String,
        currentSort: String,
        locale: Locale,
    ) {
        val sortGroup = MR.strings.opds_facetgroup_sort_order.localized(locale)
        val addFacet = { href: String, titleKey: StringResource, isActive: Boolean ->
            feedBuilder.links.add(
                OpdsLinkXml(
                    OpdsConstants.LINK_REL_FACET,
                    href,
                    OpdsConstants.TYPE_ATOM_XML_FEED_ACQUISITION,
                    titleKey.localized(locale),
                    facetGroup = sortGroup,
                    activeFacet = isActive,
                ),
            )
        }
        addFacet("$baseUrl?sort=popular&lang=${locale.toLanguageTag()}", MR.strings.opds_facet_sort_popular, currentSort == "popular")
        addFacet("$baseUrl?sort=latest&lang=${locale.toLanguageTag()}", MR.strings.opds_facet_sort_latest, currentSort == "latest")
    }

    /**
     * Adds sorting and filtering facet links for a chapter feed.
     */
    fun addChapterSortAndFilterFacets(
        feedBuilder: FeedBuilderInternal,
        baseUrl: String,
        currentSort: String,
        currentFilter: String,
        locale: Locale,
        filterCounts: Map<String, Long>? = null,
    ) {
        val sortGroup = MR.strings.opds_facetgroup_sort_order.localized(locale)
        val filterGroup = MR.strings.opds_facetgroup_filter_read_status.localized(locale)

        val addSortFacet = { href: String, titleKey: StringResource, group: String, isActive: Boolean ->
            feedBuilder.links.add(
                OpdsLinkXml(
                    OpdsConstants.LINK_REL_FACET,
                    href,
                    OpdsConstants.TYPE_ATOM_XML_FEED_ACQUISITION,
                    titleKey.localized(locale),
                    facetGroup = group,
                    activeFacet = isActive,
                ),
            )
        }

        addSortFacet(
            "$baseUrl?sort=number_asc&filter=$currentFilter&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_sort_oldest_first,
            sortGroup,
            currentSort == "number_asc",
        )
        addSortFacet(
            "$baseUrl?sort=number_desc&filter=$currentFilter&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_sort_newest_first,
            sortGroup,
            currentSort == "number_desc",
        )
        addSortFacet(
            "$baseUrl?sort=date_asc&filter=$currentFilter&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_sort_date_asc,
            sortGroup,
            currentSort == "date_asc",
        )
        addSortFacet(
            "$baseUrl?sort=date_desc&filter=$currentFilter&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_sort_date_desc,
            sortGroup,
            currentSort == "date_desc",
        )

        addFacet(
            feedBuilder,
            "$baseUrl?filter=all&sort=$currentSort&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_filter_all_chapters.localized(locale),
            filterGroup,
            currentFilter == "all",
            filterCounts?.get("all"),
        )
        addFacet(
            feedBuilder,
            "$baseUrl?filter=unread&sort=$currentSort&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_filter_unread_only.localized(locale),
            filterGroup,
            currentFilter == "unread",
            filterCounts?.get("unread"),
        )
        addFacet(
            feedBuilder,
            "$baseUrl?filter=read&sort=$currentSort&lang=${locale.toLanguageTag()}",
            MR.strings.opds_facet_filter_read_only.localized(locale),
            filterGroup,
            currentFilter == "read",
            filterCounts?.get("read"),
        )
    }

    /**
     * Adds a comprehensive set of facet links for library feeds, covering sorting, content filtering,
     * and cross-filtering by source, category, status, language, and genre.
     */
    /**
     * Configuration for a simple facet option (sort or filter).
     */
    private data class FacetOption(
        val value: String,
        val titleResource: StringResource,
    )

    /**
     * Configuration for a cross-filter facet group.
     */
    private data class CrossFilterConfig<T>(
        val filterKey: String,
        val filterType: PrimaryFilterType,
        val groupTitleResource: StringResource,
        val allItemsTitleResource: StringResource,
        val dataProvider: () -> List<T>,
        val getId: (T) -> String,
        val getTitle: (T) -> String,
        val getMangaCount: (T) -> Long?,
        val isActive: (T) -> Boolean,
    )

    /**
     * Adds all library facets (sort, filter, and cross-filter) to the feed.
     */
    fun addLibraryFacets(
        feedBuilder: FeedBuilderInternal,
        baseUrl: String,
        activeFilters: OpdsMangaFilter,
        locale: Locale,
    ) {
        val currentSort = activeFilters.sort ?: "alpha_asc"
        val currentFilter = activeFilters.filter ?: "all"

        val buildUrl = { newFilters: OpdsMangaFilter, newSort: String, newFilter: String ->
            val crossFilterParams = newFilters.toCrossFilterQueryParameters()
            val sortParam = "sort=$newSort"
            val filterParam = "filter=$newFilter"
            val langParam = "lang=${locale.toLanguageTag()}"
            val allParams = listOfNotNull(crossFilterParams, sortParam, filterParam, langParam).filter { it.isNotEmpty() }
            "$baseUrl/library/series?${allParams.joinToString("&")}"
        }

        addSortFacets(feedBuilder, activeFilters, currentSort, currentFilter, locale, buildUrl)
        addFilterFacets(feedBuilder, activeFilters, currentSort, currentFilter, locale, buildUrl)
        addCrossFilterFacets(feedBuilder, activeFilters, currentSort, currentFilter, locale, buildUrl)
    }

    /**
     * Adds sort facets to the feed.
     */
    private fun addSortFacets(
        feedBuilder: FeedBuilderInternal,
        activeFilters: OpdsMangaFilter,
        currentSort: String,
        currentFilter: String,
        locale: Locale,
        buildUrl: (OpdsMangaFilter, String, String) -> String,
    ) {
        val sortGroup = MR.strings.opds_facetgroup_sort_order.localized(locale)
        val sortOptions = listOf(
            FacetOption("alpha_asc", MR.strings.opds_facet_sort_alpha_asc),
            FacetOption("alpha_desc", MR.strings.opds_facet_sort_alpha_desc),
            FacetOption("last_read_desc", MR.strings.opds_facet_sort_last_read_desc),
            FacetOption("latest_chapter_desc", MR.strings.opds_facet_sort_latest_chapter_desc),
            FacetOption("date_added_desc", MR.strings.opds_facet_sort_date_added_desc),
            FacetOption("unread_desc", MR.strings.opds_facet_sort_unread_desc),
        )

        sortOptions.forEach { option ->
            addFacet(
                feedBuilder,
                buildUrl(activeFilters, option.value, currentFilter),
                option.titleResource.localized(locale),
                sortGroup,
                currentSort == option.value,
                null,
            )
        }
    }

    /**
     * Adds filter facets to the feed.
     */
    private fun addFilterFacets(
        feedBuilder: FeedBuilderInternal,
        activeFilters: OpdsMangaFilter,
        currentSort: String,
        currentFilter: String,
        locale: Locale,
        buildUrl: (OpdsMangaFilter, String, String) -> String,
    ) {
        val filterGroup = MR.strings.opds_facetgroup_filter_content.localized(locale)
        val filterCounts = MangaRepository.getLibraryFilterCounts()
        val filterOptions = listOf(
            FacetOption("all", MR.strings.opds_facet_filter_all) to null,
            FacetOption("unread", MR.strings.opds_facet_filter_unread_only) to filterCounts["unread"],
            FacetOption("downloaded", MR.strings.opds_facet_filter_downloaded) to filterCounts["downloaded"],
            FacetOption("ongoing", MR.strings.opds_facet_filter_ongoing) to filterCounts["ongoing"],
            FacetOption("completed", MR.strings.opds_facet_filter_completed) to filterCounts["completed"],
        )

        filterOptions.forEach { (option, count) ->
            addFacet(
                feedBuilder,
                buildUrl(activeFilters, currentSort, option.value),
                option.titleResource.localized(locale),
                filterGroup,
                currentFilter == option.value,
                count,
            )
        }
    }

    /**
     * Adds cross-filter facets (source, category, status, language, genre) to the feed.
     */
    private fun addCrossFilterFacets(
        feedBuilder: FeedBuilderInternal,
        activeFilters: OpdsMangaFilter,
        currentSort: String,
        currentFilter: String,
        locale: Locale,
        buildUrl: (OpdsMangaFilter, String, String) -> String,
    ) {
        val crossFilters = buildList {
            add(
                CrossFilterConfig(
                    filterKey = "source_id",
                    filterType = PrimaryFilterType.SOURCE,
                    groupTitleResource = MR.strings.opds_facetgroup_filter_source,
                    allItemsTitleResource = MR.strings.opds_facet_all_sources,
                    dataProvider = { NavigationRepository.getLibrarySources(1).first },
                    getId = { it.id.toString() },
                    getTitle = { it.name },
                    getMangaCount = { it.mangaCount },
                    isActive = { it.id == activeFilters.sourceId },
                ),
            )
            add(
                CrossFilterConfig(
                    filterKey = "category_id",
                    filterType = PrimaryFilterType.CATEGORY,
                    groupTitleResource = MR.strings.opds_facetgroup_filter_category,
                    allItemsTitleResource = MR.strings.opds_facet_all_categories,
                    dataProvider = { NavigationRepository.getCategories(1).first },
                    getId = { it.id.toString() },
                    getTitle = { it.name },
                    getMangaCount = { it.mangaCount },
                    isActive = { it.id == activeFilters.categoryId },
                ),
            )
            add(
                CrossFilterConfig(
                    filterKey = "status_id",
                    filterType = PrimaryFilterType.STATUS,
                    groupTitleResource = MR.strings.opds_facetgroup_filter_status,
                    allItemsTitleResource = MR.strings.opds_facet_all_statuses,
                    dataProvider = { NavigationRepository.getStatuses(locale) },
                    getId = { it.id.toString() },
                    getTitle = { it.title },
                    getMangaCount = { it.mangaCount },
                    isActive = { it.id == activeFilters.statusId },
                ),
            )
            add(
                CrossFilterConfig(
                    filterKey = "lang_code",
                    filterType = PrimaryFilterType.LANGUAGE,
                    groupTitleResource = MR.strings.opds_facetgroup_filter_language,
                    allItemsTitleResource = MR.strings.opds_facet_all_languages,
                    dataProvider = { NavigationRepository.getContentLanguages(locale) },
                    getId = { it.id },
                    getTitle = { it.title },
                    getMangaCount = { it.mangaCount },
                    isActive = { it.id == activeFilters.langCode },
                ),
            )
            add(
                CrossFilterConfig(
                    filterKey = "genre",
                    filterType = PrimaryFilterType.GENRE,
                    groupTitleResource = MR.strings.opds_facetgroup_filter_genre,
                    allItemsTitleResource = MR.strings.opds_facet_all_genres,
                    dataProvider = { NavigationRepository.getGenres(1, locale).first },
                    getId = { it.id },
                    getTitle = { it.title },
                    getMangaCount = { it.mangaCount },
                    isActive = { it.id == activeFilters.genre },
                ),
            )
        }

        crossFilters.forEach { config ->
            addCrossFilterFacetGroup(feedBuilder, activeFilters, currentSort, currentFilter, locale, buildUrl, config)
        }
    }

    /**
     * Adds a cross-filter facet group to the feed.
     */
    private fun <T> addCrossFilterFacetGroup(
        feedBuilder: FeedBuilderInternal,
        activeFilters: OpdsMangaFilter,
        currentSort: String,
        currentFilter: String,
        locale: Locale,
        buildUrl: (OpdsMangaFilter, String, String) -> String,
        config: CrossFilterConfig<T>,
    ) {
        if (activeFilters.primaryFilter != config.filterType) {
            val items = config.dataProvider()
            val groupTitle = config.groupTitleResource.localized(locale)

            // Add "All" facet
            addFacet(
                feedBuilder,
                buildUrl(activeFilters.without(config.filterKey), currentSort, currentFilter),
                config.allItemsTitleResource.localized(locale),
                groupTitle,
                activeFilters.without(config.filterKey) == activeFilters,
                null,
            )

            // Add individual item facets
            items.forEach { item ->
                addFacet(
                    feedBuilder,
                    buildUrl(activeFilters.with(config.filterKey, config.getId(item)), currentSort, currentFilter),
                    config.getTitle(item),
                    groupTitle,
                    config.isActive(item),
                    config.getMangaCount(item),
                )
            }
        }
    }
}
