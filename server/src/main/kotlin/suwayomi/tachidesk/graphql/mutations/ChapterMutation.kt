package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterMetaType
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.MetaInput
import suwayomi.tachidesk.graphql.types.SyncConflictInfoType
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReadyById
import suwayomi.tachidesk.manga.impl.sync.KoreaderSyncService
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture

/**
 * TODO Mutations
 * - Download
 * - Delete download
 */
class ChapterMutation {
    data class UpdateChapterPatch(
        val isBookmarked: Boolean? = null,
        val isRead: Boolean? = null,
        val lastPageRead: Int? = null,
    )

    data class UpdateChapterPayload(
        val clientMutationId: String?,
        val chapter: ChapterType,
    )

    data class UpdateChapterInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateChapterPatch,
    )

    data class UpdateChaptersPayload(
        val clientMutationId: String?,
        val chapters: List<ChapterType>,
    )

    data class UpdateChaptersInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
        val patch: UpdateChapterPatch,
    )

    private fun updateChapters(
        ids: List<Int>,
        patch: UpdateChapterPatch,
        userId: Int,
    ) {
        if (ids.isEmpty()) {
            return
        }

        Chapter.modifyChapters(
            input =
                Chapter.MangaChapterBatchEditInput(
                    chapterIds = ids,
                    chapterIndexes = null,
                    change =
                        Chapter.ChapterChange(
                            isRead = patch.isRead,
                            isBookmarked = patch.isBookmarked,
                            lastPageRead = patch.lastPageRead,
                        ),
                ),
            userId = userId,
        )

        // Sync with KoreaderSync when progress is updated
        if (patch.lastPageRead != null || patch.isRead == true) {
            GlobalScope.launch {
                ids.forEach { chapterId ->
                    KoreaderSyncService.pushProgress(chapterId, userId)
                }
            }
        }
    }

    @RequireAuth
    fun updateChapter(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateChapterInput,
    ): DataFetcherResult<UpdateChapterPayload?> =
        asDataFetcherResult {
            val (clientMutationId, id, patch) = input
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

            updateChapters(listOf(id), patch, userId)

            val chapter =
                transaction {
                    ChapterType.fromRowForUser(ChapterTable.selectAll().where { ChapterTable.id eq id }.first(), userId)
                }

            UpdateChapterPayload(
                clientMutationId = clientMutationId,
                chapter = chapter,
            )
        }

    @RequireAuth
    fun updateChapters(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateChaptersInput,
    ): DataFetcherResult<UpdateChaptersPayload?> =
        asDataFetcherResult {
            val (clientMutationId, ids, patch) = input
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

            updateChapters(ids, patch, userId)

            val chapters =
                transaction {
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.id inList ids }
                        .map { ChapterType.fromRowForUser(it, userId) }
                }

            UpdateChaptersPayload(
                clientMutationId = clientMutationId,
                chapters = chapters,
            )
        }

    data class FetchChaptersInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
    )

    data class FetchChaptersPayload(
        val clientMutationId: String?,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    fun fetchChapters(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchChaptersInput,
    ): CompletableFuture<DataFetcherResult<FetchChaptersPayload?>> {
        val (clientMutationId, mangaId) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                Chapter.fetchChapterList(mangaId)

                val chapters =
                    Chapter.getChapterList(mangaId, onlineFetch = false, userId = userId)
                        .map { chapter -> ChapterType(chapter) }

                FetchChaptersPayload(
                    clientMutationId = clientMutationId,
                    chapters = chapters,
                )
            }
        }
    }

    data class SetChapterMetaInput(
        val clientMutationId: String? = null,
        val meta: ChapterMetaType,
    )

    data class SetChapterMetaPayload(
        val clientMutationId: String?,
        val meta: ChapterMetaType,
    )

    @RequireAuth
    fun setChapterMeta(input: SetChapterMetaInput): DataFetcherResult<SetChapterMetaPayload?> =
        asDataFetcherResult {
            val (clientMutationId, meta) = input

            Chapter.modifyChapterMeta(meta.chapterId, meta.key, meta.value)

            SetChapterMetaPayload(clientMutationId, meta)
        }

    data class DeleteChapterMetaInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
        val key: String,
    )

    data class DeleteChapterMetaPayload(
        val clientMutationId: String?,
        val meta: ChapterMetaType?,
        val chapter: ChapterType,
    )

    @RequireAuth
    fun deleteChapterMeta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteChapterMetaInput,
    ): DataFetcherResult<DeleteChapterMetaPayload?> =
        asDataFetcherResult {
            val (clientMutationId, chapterId, key) = input
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

            val (meta, chapter) =
                transaction {
                    val meta =
                        ChapterMetaTable
                            .selectAll()
                            .where { (ChapterMetaTable.ref eq chapterId) and (ChapterMetaTable.key eq key) }
                            .firstOrNull()

                    ChapterMetaTable.deleteWhere { (ChapterMetaTable.ref eq chapterId) and (ChapterMetaTable.key eq key) }

                    val chapter =
                        transaction {
                            ChapterType.fromRowForUser(ChapterTable.selectAll().where { ChapterTable.id eq chapterId }.first(), userId)
                        }

                    if (meta != null) {
                        ChapterMetaType(meta)
                    } else {
                        null
                    } to chapter
                }

            DeleteChapterMetaPayload(clientMutationId, meta, chapter)
        }

    data class SetChapterMetasItem(
        val chapterIds: List<Int>,
        val metas: List<MetaInput>,
    )

    data class SetChapterMetasInput(
        val clientMutationId: String? = null,
        val items: List<SetChapterMetasItem>,
    )

    data class SetChapterMetasPayload(
        val clientMutationId: String?,
        val metas: List<ChapterMetaType>,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    fun setChapterMetas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetChapterMetasInput,
    ): DataFetcherResult<SetChapterMetasPayload?> =
        asDataFetcherResult {
            val (clientMutationId, items) = input
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

            val metaByChapterId =
                items
                    .flatMap { item ->
                        val metaMap = item.metas.associate { it.key to it.value }
                        item.chapterIds.map { chapterId -> chapterId to metaMap }
                    }.groupBy({ it.first }, { it.second })
                    .mapValues { (_, maps) -> maps.reduce { acc, map -> acc + map } }

            Chapter.modifyChaptersMetas(metaByChapterId)

            val allChapterIds = metaByChapterId.keys
            val allMetaKeys = metaByChapterId.values.flatMap { it.keys }.distinct()

            val (updatedMetas, chapters) =
                transaction {
                    val updatedMetas =
                        ChapterMetaTable
                            .selectAll()
                            .where { (ChapterMetaTable.ref inList allChapterIds) and (ChapterMetaTable.key inList allMetaKeys) }
                            .map { ChapterMetaType(it) }

                    val chapters =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id inList allChapterIds }
                            .map { ChapterType.fromRowForUser(it, userId) }
                            .distinctBy { it.id }

                    updatedMetas to chapters
                }

            SetChapterMetasPayload(clientMutationId, updatedMetas, chapters)
        }

    data class DeleteChapterMetasItem(
        val chapterIds: List<Int>,
        val keys: List<String>? = null,
        val prefixes: List<String>? = null,
    )

    data class DeleteChapterMetasInput(
        val clientMutationId: String? = null,
        val items: List<DeleteChapterMetasItem>,
    )

    data class DeleteChapterMetasPayload(
        val clientMutationId: String?,
        val metas: List<ChapterMetaType>,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    fun deleteChapterMetas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteChapterMetasInput,
    ): DataFetcherResult<DeleteChapterMetasPayload?> =
        asDataFetcherResult {
            val (clientMutationId, items) = input
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

            items.forEach { item ->
                require(!item.keys.isNullOrEmpty() || !item.prefixes.isNullOrEmpty()) {
                    "Either 'keys' or 'prefixes' must be provided for each item"
                }
            }

            val (allDeletedMetas, allChapterIds) =
                transaction {
                    val deletedMetas = mutableListOf<ChapterMetaType>()
                    val chapterIds = mutableSetOf<Int>()

                    items.forEach { item ->
                        val keyCondition: Op<Boolean>? =
                            item.keys?.takeIf { it.isNotEmpty() }?.let { ChapterMetaTable.key inList it }

                        val prefixCondition: Op<Boolean>? =
                            item.prefixes
                                ?.filter { it.isNotEmpty() }
                                ?.map { (ChapterMetaTable.key like LikePattern("$it%")) as Op<Boolean> }
                                ?.reduceOrNull { acc, op -> acc or op }

                        val metaKeyCondition =
                            if (keyCondition != null && prefixCondition != null) {
                                keyCondition or prefixCondition
                            } else {
                                keyCondition ?: prefixCondition!!
                            }

                        val condition = (ChapterMetaTable.ref inList item.chapterIds) and metaKeyCondition

                        deletedMetas +=
                            ChapterMetaTable
                                .selectAll()
                                .where { condition }
                                .map { ChapterMetaType(it) }

                        ChapterMetaTable.deleteWhere { condition }
                        chapterIds += item.chapterIds
                    }

                    deletedMetas to chapterIds
                }

            val chapters =
                transaction {
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.id inList allChapterIds }
                        .map { ChapterType.fromRowForUser(it, userId) }
                        .distinctBy { it.id }
                }

            DeleteChapterMetasPayload(clientMutationId, allDeletedMetas, chapters)
        }

    data class FetchChapterPagesInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
        val format: String? = null,
    ) {
        fun toParams(): Map<String, String> =
            buildMap {
                if (!format.isNullOrBlank()) {
                    put("format", format)
                }
            }
    }

    data class FetchChapterPagesPayload(
        val clientMutationId: String?,
        val pages: List<String>,
        val chapter: ChapterType,
        val syncConflict: SyncConflictInfoType?,
    )

    @RequireAuth
    fun fetchChapterPages(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchChapterPagesInput,
    ): CompletableFuture<DataFetcherResult<FetchChapterPagesPayload?>> {
        val (clientMutationId, chapterId) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val paramsMap = input.toParams()

        return future {
            asDataFetcherResult {
                var chapter = Chapter.withUserState(getChapterDownloadReadyById(chapterId), userId)
                val syncResult = KoreaderSyncService.checkAndPullProgress(chapter.id, userId)
                var syncConflictInfo: SyncConflictInfoType? = null

                if (syncResult != null) {
                    if (syncResult.isConflict) {
                        syncConflictInfo =
                            SyncConflictInfoType(
                                deviceName = syncResult.device,
                                remotePage = syncResult.pageRead,
                            )
                    }

                    if (syncResult.shouldUpdate) {
                        Chapter.setUserProgress(
                            userId = userId,
                            chapterId = chapter.id,
                            lastPageRead = syncResult.pageRead,
                            lastReadAt = syncResult.timestamp,
                        )
                    }
                    // For PROMPT, SILENT, and RECEIVE, return the remote progress
                    chapter =
                        chapter.copy(
                            lastPageRead = if (syncResult.shouldUpdate) syncResult.pageRead else chapter.lastPageRead,
                            lastReadAt = if (syncResult.shouldUpdate) syncResult.timestamp else chapter.lastReadAt,
                        )
                }

                val params =
                    buildString {
                        if (paramsMap.isNotEmpty()) {
                            append("?")
                            paramsMap.entries.forEach { entry ->
                                if (length > 1) {
                                    append("&")
                                }
                                append(entry.key)
                                append("=")
                                append(URLEncoder.encode(entry.value, Charsets.UTF_8))
                            }
                        }
                    }

                FetchChapterPagesPayload(
                    clientMutationId = clientMutationId,
                    pages =
                        List(chapter.pageCount) { index ->
                            "/api/v1/manga/${chapter.mangaId}/chapter/${chapter.index}/page/${index}$params"
                        },
                    chapter = ChapterType(chapter),
                    syncConflict = syncConflictInfo,
                )
            }
        }
    }
}
