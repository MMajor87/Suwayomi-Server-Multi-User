package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.KoSyncConnectPayload
import suwayomi.tachidesk.graphql.types.KoSyncStatusPayload
import suwayomi.tachidesk.graphql.types.LogoutKoSyncAccountPayload
import suwayomi.tachidesk.graphql.types.SyncConflictInfoType
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.sync.KoreaderSyncService
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class KoreaderSyncMutation {
    data class ConnectKoSyncAccountInput(
        val clientMutationId: String? = null,
        val serverAddress: String,
        val username: String,
        val password: String,
    )

    @RequireAuth
    fun connectKoSyncAccount(input: ConnectKoSyncAccountInput): CompletableFuture<KoSyncConnectPayload> =
        future {
            val (message, status) = KoreaderSyncService.connect(input.serverAddress, input.username, input.password)

            KoSyncConnectPayload(
                clientMutationId = input.clientMutationId,
                message = message,
                status = status,
            )
        }

    data class LogoutKoSyncAccountInput(
        val clientMutationId: String? = null,
    )

    @RequireAuth
    fun logoutKoSyncAccount(input: LogoutKoSyncAccountInput): CompletableFuture<LogoutKoSyncAccountPayload> =
        future {
            KoreaderSyncService.logout()
            LogoutKoSyncAccountPayload(
                clientMutationId = input.clientMutationId,
                status = KoSyncStatusPayload(isLoggedIn = false, serverAddress = null, username = null),
            )
        }

    data class PushKoSyncProgressInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
    )

    data class PushKoSyncProgressPayload(
        val clientMutationId: String?,
        val success: Boolean,
        val chapter: ChapterType?,
    )

    @RequireAuth
    fun pushKoSyncProgress(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: PushKoSyncProgressInput,
    ): CompletableFuture<DataFetcherResult<PushKoSyncProgressPayload?>> =
        future {
            asDataFetcherResult {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                KoreaderSyncService.pushProgress(input.chapterId, userId)

                val chapter =
                    transaction {
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id eq input.chapterId }
                            .firstOrNull()
                            ?.let { ChapterType.fromRowForUser(it, userId) }
                    }

                PushKoSyncProgressPayload(
                    clientMutationId = input.clientMutationId,
                    success = true,
                    chapter = chapter,
                )
            }
        }

    data class PullKoSyncProgressInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
    )

    data class PullKoSyncProgressPayload(
        val clientMutationId: String?,
        val chapter: ChapterType?,
        val syncConflict: SyncConflictInfoType?,
    )

    @RequireAuth
    fun pullKoSyncProgress(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: PullKoSyncProgressInput,
    ): CompletableFuture<DataFetcherResult<PullKoSyncProgressPayload?>> =
        future {
            asDataFetcherResult {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                val syncResult = KoreaderSyncService.checkAndPullProgress(input.chapterId, userId)
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
                            chapterId = input.chapterId,
                            lastPageRead = syncResult.pageRead,
                            lastReadAt = syncResult.timestamp,
                        )
                    }
                }

                val chapter =
                    transaction {
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id eq input.chapterId }
                            .firstOrNull()
                            ?.let { ChapterType.fromRowForUser(it, userId) }
                    }

                PullKoSyncProgressPayload(
                    clientMutationId = input.clientMutationId,
                    chapter = chapter,
                    syncConflict = syncConflictInfo,
                )
            }
        }
}
