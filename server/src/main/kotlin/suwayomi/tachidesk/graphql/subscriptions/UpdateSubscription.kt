/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.subscriptions

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.UpdateStatus
import suwayomi.tachidesk.graphql.types.UpdaterUpdates
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.impl.update.UpdateUpdates
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser
import uy.kohesive.injekt.injectLazy

class UpdateSubscription {
    private val updater: IUpdater by injectLazy()

    @GraphQLDeprecated("Replaced with updates", ReplaceWith("updates(input)"))
    @RequireAuth
    fun updateStatusChanged(dataFetchingEnvironment: DataFetchingEnvironment): Flow<UpdateStatus> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()

        return updater.status.map { updateStatus ->
            UpdateStatus(Library.filterUpdateStatusForUser(userId, updateStatus))
        }
    }

    data class LibraryUpdateStatusChangedInput(
        @GraphQLDescription(
            "Sets a max number of updates that can be contained in a updater update message." +
                "Everything above this limit will be omitted and the \"updateStatus\" should be re-fetched via the " +
                "corresponding query. Due to the graphql subscription execution strategy not supporting batching for data loaders, " +
                "the data loaders run into the n+1 problem, which can cause the server to get unresponsive until the status " +
                "update has been handled. This is an issue e.g. when starting an update.",
        )
        val maxUpdates: Int?,
    )

    @RequireAuth
    fun libraryUpdateStatusChanged(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LibraryUpdateStatusChangedInput,
    ): Flow<UpdaterUpdates> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val omitUpdates = input.maxUpdates != null
        val maxUpdates = input.maxUpdates ?: 50

        return updater.updates.map { updates ->
            val scopedUpdates = Library.filterUpdateUpdatesForUser(userId, updates)
            val categoryUpdatesCount = scopedUpdates.categoryUpdates.size
            val mangaUpdatesCount = scopedUpdates.mangaUpdates.size
            val totalUpdatesCount = categoryUpdatesCount + mangaUpdatesCount

            val needToOmitUpdates = omitUpdates && totalUpdatesCount > maxUpdates
            if (!needToOmitUpdates) {
                return@map UpdaterUpdates(scopedUpdates, omittedUpdates = false)
            }

            val maxUpdatesAfterCategoryUpdates = (maxUpdates - categoryUpdatesCount).coerceAtLeast(0)

            // the graphql subscription execution strategy does not support data loader batching which causes the n+1 problem,
            // thus, too many updates (e.g. on mass enqueue or dequeue) causes unresponsiveness of the server until the
            // update has been handled
            UpdaterUpdates(
                UpdateUpdates(
                    scopedUpdates.isRunning,
                    scopedUpdates.categoryUpdates.take(maxUpdates),
                    scopedUpdates.mangaUpdates.take(maxUpdatesAfterCategoryUpdates),
                    scopedUpdates.totalJobs,
                    scopedUpdates.finishedJobs,
                    scopedUpdates.skippedCategoriesCount,
                    scopedUpdates.skippedMangasCount,
                    scopedUpdates.initial,
                ),
                omittedUpdates = true,
            )
        }
    }
}
