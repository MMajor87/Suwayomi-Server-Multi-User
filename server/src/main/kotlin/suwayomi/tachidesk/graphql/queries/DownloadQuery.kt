package suwayomi.tachidesk.graphql.queries

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.DownloadStatus
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class DownloadQuery {
    @RequireAuth
    fun downloadStatus(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<DownloadStatus> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return future {
            DownloadStatus(Library.filterDownloadStatusForUser(userId, DownloadManager.getStatus()))
        }
    }
}
