package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.LibraryUpdateStatus
import suwayomi.tachidesk.graphql.types.UpdateStatus
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.requireUser
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class UpdateMutation {
    private val updater: IUpdater by injectLazy()

    data class UpdateLibraryInput(
        val clientMutationId: String? = null,
        val categories: List<Int>?,
    )

    data class UpdateLibraryPayload(
        val clientMutationId: String? = null,
        val updateStatus: LibraryUpdateStatus,
    )

    @RequireAuth
    fun updateLibrary(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateLibraryInput,
    ): CompletableFuture<DataFetcherResult<UpdateLibraryPayload?>> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        if (Library.isAdmin(userId)) {
            updater.addCategoriesToUpdateQueue(
                Category.getCategoryList(userId).filter { input.categories?.contains(it.id) ?: true },
                clear = true,
                forceAll = !input.categories.isNullOrEmpty(),
            )
        } else {
            val scopedMangaIds = Library.getLibraryMangaIdsForUserCategories(userId, input.categories)
            val scopedMangas =
                transaction {
                    MangaTable
                        .selectAll()
                        .where { MangaTable.id inList scopedMangaIds }
                        .map { MangaTable.toDataClass(it) }
                }
            updater.addMangasToQueue(scopedMangas)
        }

        return future {
            asDataFetcherResult {
                UpdateLibraryPayload(
                    input.clientMutationId,
                    updateStatus =
                        withTimeout(30.seconds) {
                            LibraryUpdateStatus(
                                Library.filterUpdateUpdatesForUser(userId, updater.updates.first()),
                            )
                        },
                )
            }
        }
    }

    data class UpdateLibraryMangaInput(
        val clientMutationId: String? = null,
    )

    data class UpdateLibraryMangaPayload(
        val clientMutationId: String?,
        val updateStatus: UpdateStatus,
    )

    @RequireAuth
    fun updateLibraryManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateLibraryMangaInput,
    ): CompletableFuture<DataFetcherResult<UpdateLibraryMangaPayload?>> {
        updateLibrary(
            dataFetchingEnvironment,
            UpdateLibraryInput(
                clientMutationId = input.clientMutationId,
                categories = null,
            ),
        )
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                UpdateLibraryMangaPayload(
                    input.clientMutationId,
                    updateStatus =
                        withTimeout(30.seconds) {
                            UpdateStatus(Library.filterUpdateStatusForUser(userId, updater.status.first()))
                        },
                )
            }
        }
    }

    data class UpdateCategoryMangaInput(
        val clientMutationId: String? = null,
        val categories: List<Int>,
    )

    data class UpdateCategoryMangaPayload(
        val clientMutationId: String?,
        val updateStatus: UpdateStatus,
    )

    @RequireAuth
    fun updateCategoryManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateCategoryMangaInput,
    ): CompletableFuture<DataFetcherResult<UpdateCategoryMangaPayload?>> {
        updateLibrary(
            dataFetchingEnvironment,
            UpdateLibraryInput(
                clientMutationId = input.clientMutationId,
                categories = input.categories,
            ),
        )
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                UpdateCategoryMangaPayload(
                    input.clientMutationId,
                    updateStatus =
                        withTimeout(30.seconds) {
                            UpdateStatus(Library.filterUpdateStatusForUser(userId, updater.status.first()))
                        },
                )
            }
        }
    }

    data class UpdateStopInput(
        val clientMutationId: String? = null,
    )

    data class UpdateStopPayload(
        val clientMutationId: String?,
    )

    @RequireAuth
    fun updateStop(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateStopInput,
    ): UpdateStopPayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        if (!Library.isAdmin(userId)) {
            throw ForbiddenException()
        }
        updater.reset()
        return UpdateStopPayload(input.clientMutationId)
    }
}
