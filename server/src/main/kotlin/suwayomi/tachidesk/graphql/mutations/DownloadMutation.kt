package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.DownloadStatus
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType.DEQUEUED
import suwayomi.tachidesk.manga.impl.download.model.Status
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class DownloadMutation {
    private fun requireAllowedChapterIds(
        userId: Int,
        chapterIds: Collection<Int>,
    ): Set<Int> {
        val requestedIds = chapterIds.toSet()
        if (requestedIds.isEmpty()) {
            return emptySet()
        }
        val allowedIds = Library.getAccessibleChapterIds(userId, requestedIds)
        if (allowedIds.size != requestedIds.size) {
            throw ForbiddenException()
        }
        return allowedIds
    }

    private fun scopedDownloadStatus(userId: Int): DownloadStatus =
        DownloadStatus(Library.filterDownloadStatusForUser(userId, DownloadManager.getStatus()))

    data class DeleteDownloadedChaptersInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class DeleteDownloadedChaptersPayload(
        val clientMutationId: String?,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    fun deleteDownloadedChapters(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteDownloadedChaptersInput,
    ): DataFetcherResult<DeleteDownloadedChaptersPayload?> {
        val (clientMutationId, chapters) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val allowedChapters = requireAllowedChapterIds(userId, chapters)

        return asDataFetcherResult {
            Chapter.deleteChapters(allowedChapters.toList())

            DeleteDownloadedChaptersPayload(
                clientMutationId = clientMutationId,
                chapters =
                    transaction {
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id inList allowedChapters }
                            .map { ChapterType(it) }
                    },
            )
        }
    }

    data class DeleteDownloadedChapterInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class DeleteDownloadedChapterPayload(
        val clientMutationId: String?,
        val chapters: ChapterType,
    )

    @RequireAuth
    fun deleteDownloadedChapter(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteDownloadedChapterInput,
    ): DataFetcherResult<DeleteDownloadedChapterPayload?> {
        val (clientMutationId, chapter) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        requireAllowedChapterIds(userId, setOf(chapter))

        return asDataFetcherResult {
            Chapter.deleteChapters(listOf(chapter))

            DeleteDownloadedChapterPayload(
                clientMutationId = clientMutationId,
                chapters =
                    transaction {
                        ChapterType(ChapterTable.selectAll().where { ChapterTable.id eq chapter }.first())
                    },
            )
        }
    }

    data class EnqueueChapterDownloadsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class EnqueueChapterDownloadsPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun enqueueChapterDownloads(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: EnqueueChapterDownloadsInput,
    ): CompletableFuture<DataFetcherResult<EnqueueChapterDownloadsPayload?>> {
        val (clientMutationId, chapters) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val allowedChapters = requireAllowedChapterIds(userId, chapters)

        return future {
            asDataFetcherResult {
                DownloadManager.enqueue(DownloadManager.EnqueueInput(allowedChapters.toList()))

                EnqueueChapterDownloadsPayload(
                    clientMutationId = clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first {
                                DownloadManager.getStatus().queue.any { it.chapterId in allowedChapters }
                            }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class EnqueueChapterDownloadInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class EnqueueChapterDownloadPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun enqueueChapterDownload(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: EnqueueChapterDownloadInput,
    ): CompletableFuture<DataFetcherResult<EnqueueChapterDownloadPayload?>> {
        val (clientMutationId, chapter) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        requireAllowedChapterIds(userId, setOf(chapter))

        return future {
            asDataFetcherResult {
                DownloadManager.enqueue(DownloadManager.EnqueueInput(listOf(chapter)))

                EnqueueChapterDownloadPayload(
                    clientMutationId = clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first { it.updates.any { it.downloadQueueItem.chapterId == chapter } }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class DequeueChapterDownloadsInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
    )

    data class DequeueChapterDownloadsPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun dequeueChapterDownloads(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DequeueChapterDownloadsInput,
    ): CompletableFuture<DataFetcherResult<DequeueChapterDownloadsPayload?>> {
        val (clientMutationId, chapters) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val allowedChapters = requireAllowedChapterIds(userId, chapters)

        return future {
            asDataFetcherResult {
                DownloadManager.dequeue(DownloadManager.EnqueueInput(allowedChapters.toList()))

                DequeueChapterDownloadsPayload(
                    clientMutationId = clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first {
                                it.updates.any {
                                    it.downloadQueueItem.chapterId in allowedChapters && it.type == DEQUEUED
                                }
                                    }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class DequeueChapterDownloadInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class DequeueChapterDownloadPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun dequeueChapterDownload(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DequeueChapterDownloadInput,
    ): CompletableFuture<DataFetcherResult<DequeueChapterDownloadPayload?>> {
        val (clientMutationId, chapter) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        requireAllowedChapterIds(userId, setOf(chapter))

        return future {
            asDataFetcherResult {
                DownloadManager.dequeue(DownloadManager.EnqueueInput(listOf(chapter)))

                DequeueChapterDownloadPayload(
                    clientMutationId = clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first {
                                it.updates.any {
                                    it.downloadQueueItem.chapterId == chapter && it.type == DEQUEUED
                                }
                                    }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class StartDownloaderInput(
        val clientMutationId: String? = null,
    )

    data class StartDownloaderPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun startDownloader(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: StartDownloaderInput,
    ): CompletableFuture<DataFetcherResult<StartDownloaderPayload?>> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        if (!Library.isAdmin(userId)) {
            throw ForbiddenException()
        }

        return future {
            asDataFetcherResult {
                DownloadManager.start()

                StartDownloaderPayload(
                    input.clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first { it.status == Status.Started }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class StopDownloaderInput(
        val clientMutationId: String? = null,
    )

    data class StopDownloaderPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun stopDownloader(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: StopDownloaderInput,
    ): CompletableFuture<DataFetcherResult<StopDownloaderPayload?>> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        if (!Library.isAdmin(userId)) {
            throw ForbiddenException()
        }

        return future {
            asDataFetcherResult {
                DownloadManager.stop()

                StopDownloaderPayload(
                    input.clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first { it.status == Status.Stopped }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class ClearDownloaderInput(
        val clientMutationId: String? = null,
    )

    data class ClearDownloaderPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun clearDownloader(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ClearDownloaderInput,
    ): CompletableFuture<DataFetcherResult<ClearDownloaderPayload?>> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            asDataFetcherResult {
                if (Library.isAdmin(userId)) {
                    DownloadManager.clear()
                } else {
                    val ownChapters =
                        Library
                            .filterDownloadQueueByUser(userId, DownloadManager.getStatus().queue)
                            .map { it.chapterId }
                    DownloadManager.dequeue(DownloadManager.EnqueueInput(ownChapters))
                }

                ClearDownloaderPayload(
                    input.clientMutationId,
                    downloadStatus =
                        if (Library.isAdmin(userId)) {
                            withTimeout(30.seconds) {
                                DownloadManager.updates.first { it.status == Status.Stopped }
                                scopedDownloadStatus(userId)
                            }
                        } else {
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }

    data class ReorderChapterDownloadInput(
        val clientMutationId: String? = null,
        val chapterId: Int,
        val to: Int,
    )

    data class ReorderChapterDownloadPayload(
        val clientMutationId: String?,
        val downloadStatus: DownloadStatus,
    )

    @RequireAuth
    fun reorderChapterDownload(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ReorderChapterDownloadInput,
    ): CompletableFuture<DataFetcherResult<ReorderChapterDownloadPayload?>> {
        val (clientMutationId, chapter, to) = input
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        if (!Library.isAdmin(userId)) {
            throw ForbiddenException()
        }

        return future {
            asDataFetcherResult {
                DownloadManager.reorder(chapter, to)

                ReorderChapterDownloadPayload(
                    clientMutationId,
                    downloadStatus =
                        withTimeout(30.seconds) {
                            DownloadManager.updates.first { it.updates.indexOfFirst { it.downloadQueueItem.chapterId == chapter } <= to }
                            scopedDownloadStatus(userId)
                        },
                )
            }
        }
    }
}
