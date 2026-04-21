package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
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
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.MangaMetaType
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.graphql.types.MetaInput
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CompletableFuture

/**
 * TODO Mutations
 * - Download x(all = -1) chapters
 * - Delete read/all downloaded chapters
 */
class MangaMutation {
    private val updater: IUpdater by injectLazy()

    data class UpdateMangaPatch(
        val inLibrary: Boolean? = null,
    )

    data class UpdateMangaPayload(
        val clientMutationId: String?,
        val manga: MangaType,
    )

    data class UpdateMangaInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateMangaPatch,
    )

    data class UpdateMangasPayload(
        val clientMutationId: String?,
        val mangas: List<MangaType>,
    )

    data class UpdateMangasInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
        val patch: UpdateMangaPatch,
    )

    private suspend fun updateMangas(
        userId: Int,
        ids: List<Int>,
        patch: UpdateMangaPatch,
    ) {
        if (patch.inLibrary == null) {
            return
        }

        if (patch.inLibrary) {
            Library.addMangasToLibrary(userId, ids)
        } else {
            Library.removeMangasFromLibrary(userId, ids)
        }

        if (patch.inLibrary) {
            // try to initialize uninitialized in library manga to ensure that the expected data is available (chapter list, metadata, ...)
            val mangas =
                transaction {
                    MangaTable
                        .selectAll()
                        .where { (MangaTable.id inList ids) and (MangaTable.initialized eq false) }
                        .map { MangaTable.toDataClass(it) }
                }

            updater.addMangasToQueue(mangas)
        }
    }

    @RequireAuth
    fun updateManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateMangaInput,
    ): CompletableFuture<DataFetcherResult<UpdateMangaPayload?>> {
        val (clientMutationId, id, patch) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                updateMangas(userId, listOf(id), patch)
                val libraryEntries = Library.getUserLibraryEntryMap(userId, setOf(id))

                val manga =
                    transaction {
                        MangaTable.selectAll().where { MangaTable.id eq id }.first().let { row ->
                            val inLibraryAt = libraryEntries[id]
                            MangaType(row, inLibrary = inLibraryAt != null, inLibraryAt = inLibraryAt ?: 0L)
                        }
                    }

                UpdateMangaPayload(
                    clientMutationId = clientMutationId,
                    manga = manga,
                )
            }
        }
    }

    @RequireAuth
    fun updateMangas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateMangasInput,
    ): CompletableFuture<DataFetcherResult<UpdateMangasPayload?>> {
        val (clientMutationId, ids, patch) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                updateMangas(userId, ids, patch)

                val mangas =
                    transaction {
                        val rows = MangaTable.selectAll().where { MangaTable.id inList ids }.toList()
                        val libraryEntries = Library.getUserLibraryEntryMap(userId, ids)
                        rows.map { row ->
                            val mangaId = row[MangaTable.id].value
                            val inLibraryAt = libraryEntries[mangaId]
                            MangaType(row, inLibrary = inLibraryAt != null, inLibraryAt = inLibraryAt ?: 0L)
                        }
                    }

                UpdateMangasPayload(
                    clientMutationId = clientMutationId,
                    mangas = mangas,
                )
            }
        }
    }

    data class FetchMangaInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class FetchMangaPayload(
        val clientMutationId: String?,
        val manga: MangaType,
    )

    @RequireAuth
    fun fetchManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchMangaInput,
    ): CompletableFuture<DataFetcherResult<FetchMangaPayload?>> {
        val (clientMutationId, id) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                Manga.fetchManga(id)
                val libraryEntries = Library.getUserLibraryEntryMap(userId, setOf(id))

                val manga =
                    transaction {
                        MangaTable.selectAll().where { MangaTable.id eq id }.first().let { row ->
                            val inLibraryAt = libraryEntries[id]
                            MangaType(row, inLibrary = inLibraryAt != null, inLibraryAt = inLibraryAt ?: 0L)
                        }
                    }
                FetchMangaPayload(
                    clientMutationId = clientMutationId,
                    manga = manga,
                )
            }
        }
    }

    data class SetMangaMetaInput(
        val clientMutationId: String? = null,
        val meta: MangaMetaType,
    )

    data class SetMangaMetaPayload(
        val clientMutationId: String?,
        val meta: MangaMetaType,
    )

    @RequireAuth
    fun setMangaMeta(input: SetMangaMetaInput): DataFetcherResult<SetMangaMetaPayload?> {
        val (clientMutationId, meta) = input

        return asDataFetcherResult {
            Manga.modifyMangaMeta(meta.mangaId, meta.key, meta.value)

            SetMangaMetaPayload(clientMutationId, meta)
        }
    }

    data class DeleteMangaMetaInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        val key: String,
    )

    data class DeleteMangaMetaPayload(
        val clientMutationId: String?,
        val meta: MangaMetaType?,
        val manga: MangaType,
    )

    @RequireAuth
    fun deleteMangaMeta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteMangaMetaInput,
    ): DataFetcherResult<DeleteMangaMetaPayload?> {
        val (clientMutationId, mangaId, key) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return asDataFetcherResult {
            val libraryEntries = Library.getUserLibraryEntryMap(userId, setOf(mangaId))
            val (meta, manga) =
                transaction {
                    val meta =
                        MangaMetaTable
                            .selectAll()
                            .where { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) }
                            .firstOrNull()

                    MangaMetaTable.deleteWhere { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) }

                    val manga =
                        MangaTable.selectAll().where { MangaTable.id eq mangaId }.first().let { row ->
                            val inLibraryAt = libraryEntries[mangaId]
                            MangaType(row, inLibrary = inLibraryAt != null, inLibraryAt = inLibraryAt ?: 0L)
                        }

                    if (meta != null) {
                        MangaMetaType(meta)
                    } else {
                        null
                    } to manga
                }

            DeleteMangaMetaPayload(clientMutationId, meta, manga)
        }
    }

    data class SetMangaMetasItem(
        val mangaIds: List<Int>,
        val metas: List<MetaInput>,
    )

    data class SetMangaMetasInput(
        val clientMutationId: String? = null,
        val items: List<SetMangaMetasItem>,
    )

    data class SetMangaMetasPayload(
        val clientMutationId: String?,
        val metas: List<MangaMetaType>,
        val mangas: List<MangaType>,
    )

    @RequireAuth
    fun setMangaMetas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetMangaMetasInput,
    ): DataFetcherResult<SetMangaMetasPayload?> {
        val (clientMutationId, items) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return asDataFetcherResult {
            val metaByMangaId =
                items
                    .flatMap { item ->
                        val metaMap = item.metas.associate { it.key to it.value }
                        item.mangaIds.map { mangaId -> mangaId to metaMap }
                    }.groupBy({ it.first }, { it.second })
                    .mapValues { (_, maps) -> maps.reduce { acc, map -> acc + map } }

            Manga.modifyMangasMetas(metaByMangaId)

            val allMangaIds = metaByMangaId.keys
            val allMetaKeys = metaByMangaId.values.flatMap { it.keys }.distinct()
            val libraryEntries = Library.getUserLibraryEntryMap(userId, allMangaIds)

            val (updatedMetas, mangas) =
                transaction {
                    val updatedMetas =
                        MangaMetaTable
                            .selectAll()
                            .where { (MangaMetaTable.ref inList allMangaIds) and (MangaMetaTable.key inList allMetaKeys) }
                            .map { MangaMetaType(it) }

                    val mangas =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.id inList allMangaIds }
                            .map { row ->
                                val mangaId = row[MangaTable.id].value
                                val inLibraryAt = libraryEntries[mangaId]
                                MangaType(row, inLibrary = inLibraryAt != null, inLibraryAt = inLibraryAt ?: 0L)
                            }
                            .distinctBy { it.id }

                    updatedMetas to mangas
                }

            SetMangaMetasPayload(clientMutationId, updatedMetas, mangas)
        }
    }

    data class DeleteMangaMetasItem(
        val mangaIds: List<Int>,
        val keys: List<String>? = null,
        val prefixes: List<String>? = null,
    )

    data class DeleteMangaMetasInput(
        val clientMutationId: String? = null,
        val items: List<DeleteMangaMetasItem>,
    )

    data class DeleteMangaMetasPayload(
        val clientMutationId: String?,
        val metas: List<MangaMetaType>,
        val mangas: List<MangaType>,
    )

    @RequireAuth
    fun deleteMangaMetas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteMangaMetasInput,
    ): DataFetcherResult<DeleteMangaMetasPayload?> {
        val (clientMutationId, items) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return asDataFetcherResult {
            items.forEach { item ->
                require(!item.keys.isNullOrEmpty() || !item.prefixes.isNullOrEmpty()) {
                    "Either 'keys' or 'prefixes' must be provided for each item"
                }
            }

            val (allDeletedMetas, allMangaIds) =
                transaction {
                    val deletedMetas = mutableListOf<MangaMetaType>()
                    val mangaIds = mutableSetOf<Int>()

                    items.forEach { item ->
                        val keyCondition: Op<Boolean>? =
                            item.keys?.takeIf { it.isNotEmpty() }?.let { MangaMetaTable.key inList it }

                        val prefixCondition: Op<Boolean>? =
                            item.prefixes
                                ?.filter { it.isNotEmpty() }
                                ?.map { (MangaMetaTable.key like LikePattern("$it%")) as Op<Boolean> }
                                ?.reduceOrNull { acc, op -> acc or op }

                        val metaKeyCondition =
                            if (keyCondition != null && prefixCondition != null) {
                                keyCondition or prefixCondition
                            } else {
                                keyCondition ?: prefixCondition!!
                            }

                        val condition = (MangaMetaTable.ref inList item.mangaIds) and metaKeyCondition

                        deletedMetas +=
                            MangaMetaTable
                                .selectAll()
                                .where { condition }
                                .map { MangaMetaType(it) }

                        MangaMetaTable.deleteWhere { condition }
                        mangaIds += item.mangaIds
                    }

                    deletedMetas to mangaIds
                }

            val mangas =
                transaction {
                    val libraryEntries = Library.getUserLibraryEntryMap(userId, allMangaIds)
                    MangaTable
                        .selectAll()
                        .where { MangaTable.id inList allMangaIds }
                        .map { row ->
                            val mangaId = row[MangaTable.id].value
                            val inLibraryAt = libraryEntries[mangaId]
                            MangaType(row, inLibrary = inLibraryAt != null, inLibraryAt = inLibraryAt ?: 0L)
                        }
                        .distinctBy { it.id }
                }

            DeleteMangaMetasPayload(clientMutationId, allDeletedMetas, mangas)
        }
    }
}
