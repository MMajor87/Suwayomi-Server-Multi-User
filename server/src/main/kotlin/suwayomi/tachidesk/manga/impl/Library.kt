package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.local.LocalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.download.model.DownloadStatus
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdates
import suwayomi.tachidesk.manga.impl.update.JobStatus
import suwayomi.tachidesk.manga.impl.update.UpdateStatus
import suwayomi.tachidesk.manga.impl.update.UpdateUpdates
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.model.table.UserAccountTable
import suwayomi.tachidesk.server.model.table.UserMangaLibraryTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.ForbiddenException
import java.time.Instant

object Library {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun addMangaToLibrary(
        userId: Int,
        mangaId: Int,
    ) {
        var shouldDownloadThumbnail = false

        transaction {
            MangaTable.select(MangaTable.id).where { MangaTable.id eq mangaId }.first()

            val alreadyInLibrary =
                UserMangaLibraryTable
                    .select(UserMangaLibraryTable.id)
                    .where {
                        (UserMangaLibraryTable.userId eq userId) and
                            (UserMangaLibraryTable.mangaId eq mangaId)
                    }.firstOrNull() != null
            if (alreadyInLibrary) {
                return@transaction
            }

            val hadAnyLibraryMembership = isMangaInAnyLibraryInternal(mangaId)
            val now = Instant.now().epochSecond

            UserMangaLibraryTable.insert {
                it[UserMangaLibraryTable.userId] = userId
                it[UserMangaLibraryTable.mangaId] = mangaId
                it[inLibraryAt] = now
            }

            assignDefaultCategoriesIfNoneInternal(userId, mangaId)
            syncLegacyLibraryColumnsInternal(mangaId)
            shouldDownloadThumbnail = !hadAnyLibraryMembership
        }

        if (shouldDownloadThumbnail) {
            handleMangaThumbnail(mangaId, true)
        }
    }

    suspend fun addMangasToLibrary(
        userId: Int,
        mangaIds: Collection<Int>,
    ) {
        mangaIds.toSet().forEach { addMangaToLibrary(userId, it) }
    }

    suspend fun removeMangaFromLibrary(
        userId: Int,
        mangaId: Int,
    ) {
        var shouldDeleteThumbnail = false

        transaction {
            val deleted =
                UserMangaLibraryTable.deleteWhere {
                    (UserMangaLibraryTable.userId eq userId) and
                        (UserMangaLibraryTable.mangaId eq mangaId)
                } > 0
            if (!deleted) {
                return@transaction
            }

            syncLegacyLibraryColumnsInternal(mangaId)
            shouldDeleteThumbnail = !isMangaInAnyLibraryInternal(mangaId)
        }

        if (shouldDeleteThumbnail) {
            handleMangaThumbnail(mangaId, false)
        }
    }

    suspend fun removeMangasFromLibrary(
        userId: Int,
        mangaIds: Collection<Int>,
    ) {
        mangaIds.toSet().forEach { removeMangaFromLibrary(userId, it) }
    }

    fun getUserLibraryEntryMap(
        userId: Int,
        mangaIds: Collection<Int>,
    ): Map<Int, Long> {
        val distinctIds = mangaIds.toSet()
        if (distinctIds.isEmpty()) {
            return emptyMap()
        }

        return transaction {
            UserMangaLibraryTable
                .selectAll()
                .where {
                    (UserMangaLibraryTable.userId eq userId) and
                        (UserMangaLibraryTable.mangaId inList distinctIds)
                }.associate {
                    it[UserMangaLibraryTable.mangaId].value to it[UserMangaLibraryTable.inLibraryAt]
                }
        }
    }

    fun getMangaLibraryState(
        userId: Int,
        mangaId: Int,
    ): Pair<Boolean, Long> {
        val inLibraryAt = getUserLibraryEntryMap(userId, setOf(mangaId))[mangaId]
        return (inLibraryAt != null) to (inLibraryAt ?: 0L)
    }

    fun isMangaInUserLibrary(
        userId: Int,
        mangaId: Int,
    ): Boolean = getUserLibraryEntryMap(userId, setOf(mangaId)).containsKey(mangaId)

    fun isMangaInAnyLibrary(mangaId: Int): Boolean =
        transaction {
            isMangaInAnyLibraryInternal(mangaId)
        }

    fun getUserLibraryMangaIds(userId: Int): Set<Int> =
        transaction {
            UserMangaLibraryTable
                .select(UserMangaLibraryTable.mangaId)
                .where { UserMangaLibraryTable.userId eq userId }
                .map { it[UserMangaLibraryTable.mangaId].value }
                .toSet()
        }

    fun getAllLibraryMangaIds(): Set<Int> =
        transaction {
            UserMangaLibraryTable
                .select(UserMangaLibraryTable.mangaId)
                .withDistinct()
                .map { it[UserMangaLibraryTable.mangaId].value }
                .toSet()
        }

    fun isAdmin(userId: Int): Boolean =
        transaction {
            isAdminInternal(userId)
        }

    fun getAccessibleMangaIds(
        userId: Int,
        mangaIds: Collection<Int>,
    ): Set<Int> {
        val distinctIds = mangaIds.toSet()
        if (distinctIds.isEmpty()) {
            return emptySet()
        }

        return transaction {
            if (isAdminInternal(userId)) {
                distinctIds
            } else {
                UserMangaLibraryTable
                    .select(UserMangaLibraryTable.mangaId)
                    .where {
                        (UserMangaLibraryTable.userId eq userId) and
                            (UserMangaLibraryTable.mangaId inList distinctIds)
                    }.map { it[UserMangaLibraryTable.mangaId].value }
                    .toSet()
            }
        }
    }

    fun getAccessibleChapterIds(
        userId: Int,
        chapterIds: Collection<Int>,
    ): Set<Int> {
        val distinctIds = chapterIds.toSet()
        if (distinctIds.isEmpty()) {
            return emptySet()
        }

        return transaction {
            if (isAdminInternal(userId)) {
                distinctIds
            } else {
                ChapterTable
                    .join(
                        UserMangaLibraryTable,
                        JoinType.INNER,
                        additionalConstraint = { UserMangaLibraryTable.mangaId eq ChapterTable.manga },
                    ).select(ChapterTable.id)
                    .where {
                        (ChapterTable.id inList distinctIds) and
                            (UserMangaLibraryTable.userId eq userId)
                    }.map { it[ChapterTable.id].value }
                    .toSet()
            }
        }
    }

    fun requireMangaAccess(
        userId: Int,
        mangaId: Int,
    ) {
        if (!getAccessibleMangaIds(userId, setOf(mangaId)).contains(mangaId)) {
            throw ForbiddenException()
        }
    }

    fun requireChapterAccess(
        userId: Int,
        chapterId: Int,
    ) {
        if (!getAccessibleChapterIds(userId, setOf(chapterId)).contains(chapterId)) {
            throw ForbiddenException()
        }
    }

    fun getLibraryMangaIdsForUserCategories(
        userId: Int,
        categoryIds: List<Int>?,
    ): Set<Int> {
        val baseMangaIds =
            if (isAdmin(userId)) {
                getAllLibraryMangaIds()
            } else {
                getUserLibraryMangaIds(userId)
            }
        if (baseMangaIds.isEmpty()) {
            return emptySet()
        }
        if (categoryIds.isNullOrEmpty()) {
            return baseMangaIds
        }

        val includeDefault = categoryIds.contains(Category.DEFAULT_CATEGORY_ID)
        val explicitCategoryIds = categoryIds.filter { it != Category.DEFAULT_CATEGORY_ID }.toSet()

        return transaction {
            val result = mutableSetOf<Int>()

            if (explicitCategoryIds.isNotEmpty()) {
                result +=
                    CategoryMangaTable
                        .select(CategoryMangaTable.manga)
                        .where {
                            (CategoryMangaTable.userId eq userId) and
                                (CategoryMangaTable.category inList explicitCategoryIds) and
                                (CategoryMangaTable.manga inList baseMangaIds)
                        }.map { it[CategoryMangaTable.manga].value }
            }

            if (includeDefault) {
                val mangasWithCategories =
                    CategoryMangaTable
                        .select(CategoryMangaTable.manga)
                        .where {
                            (CategoryMangaTable.userId eq userId) and
                                (CategoryMangaTable.manga inList baseMangaIds)
                        }
                        .map { it[CategoryMangaTable.manga].value }
                        .toSet()
                result += baseMangaIds - mangasWithCategories
            }

            result
        }
    }

    fun filterDownloadQueueByUser(
        userId: Int,
        queue: List<DownloadQueueItem>,
    ): List<DownloadQueueItem> {
        if (queue.isEmpty()) {
            return emptyList()
        }
        val allowedMangaIds = getAccessibleMangaIds(userId, queue.map { it.mangaId })
        return queue.filter { it.mangaId in allowedMangaIds }
    }

    fun filterDownloadStatusForUser(
        userId: Int,
        status: DownloadStatus,
    ): DownloadStatus =
        status.copy(
            queue = filterDownloadQueueByUser(userId, status.queue),
        )

    fun filterDownloadUpdatesForUser(
        userId: Int,
        updates: DownloadUpdates,
    ): DownloadUpdates {
        val allMangaIds =
            (updates.updates.map { it.downloadQueueItem.mangaId } + updates.initial.orEmpty().map { it.mangaId }).toSet()
        val allowedMangaIds = getAccessibleMangaIds(userId, allMangaIds)

        return updates.copy(
            updates = updates.updates.filter { it.downloadQueueItem.mangaId in allowedMangaIds },
            initial = updates.initial?.filter { it.mangaId in allowedMangaIds },
        )
    }

    fun filterUpdateStatusForUser(
        userId: Int,
        status: UpdateStatus,
    ): UpdateStatus {
        if (isAdmin(userId)) {
            return status
        }

        val allowedMangaIds = getUserLibraryMangaIds(userId)
        val filteredMangaStatusMap =
            status.mangaStatusMap.mapValues { (_, mangas) ->
                mangas.filter { it.id in allowedMangaIds }
            }

        return status.copy(
            categoryStatusMap = emptyMap(),
            mangaStatusMap = filteredMangaStatusMap,
            numberOfJobs = filteredMangaStatusMap.values.sumOf { it.size },
        )
    }

    fun filterUpdateUpdatesForUser(
        userId: Int,
        updates: UpdateUpdates,
    ): UpdateUpdates {
        if (isAdmin(userId)) {
            return updates
        }

        val allowedMangaIds = getUserLibraryMangaIds(userId)
        val filteredMangaUpdates = updates.mangaUpdates.filter { it.manga.id in allowedMangaIds }
        val filteredInitial = updates.initial?.let { filterUpdateUpdatesForUser(userId, it) }

        return updates.copy(
            categoryUpdates = emptyList(),
            mangaUpdates = filteredMangaUpdates,
            totalJobs = filteredMangaUpdates.size,
            finishedJobs = filteredMangaUpdates.count { it.status == JobStatus.COMPLETE || it.status == JobStatus.FAILED },
            skippedCategoriesCount = 0,
            skippedMangasCount = filteredMangaUpdates.count { it.status == JobStatus.SKIPPED },
            initial = filteredInitial,
        )
    }

    fun handleMangaThumbnail(
        mangaId: Int,
        inLibrary: Boolean,
    ) {
        scope.launch {
            val sourceId =
                transaction {
                    MangaTable
                        .select(MangaTable.sourceReference)
                        .where { MangaTable.id eq mangaId }
                        .first()
                        .get(MangaTable.sourceReference)
                }

            if (sourceId == LocalSource.ID) {
                return@launch
            }

            try {
                if (inLibrary) {
                    ThumbnailDownloadHelper.download(mangaId)
                } else {
                    ThumbnailDownloadHelper.delete(mangaId)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun assignDefaultCategoriesIfNoneInternal(
        userId: Int,
        mangaId: Int,
    ) {
        val hasCategories =
            CategoryMangaTable
                .select(CategoryMangaTable.id)
                .where {
                    (CategoryMangaTable.userId eq userId) and
                        (CategoryMangaTable.manga eq mangaId)
                }
                .firstOrNull() != null
        if (hasCategories) {
            return
        }

        val defaultCategories =
            CategoryTable
                .selectAll()
                .where {
                    (CategoryTable.isDefault eq true) and
                        (CategoryTable.id neq Category.DEFAULT_CATEGORY_ID) and
                        (CategoryTable.userId eq userId)
                }.orderBy(CategoryTable.order to SortOrder.ASC)

        defaultCategories.forEach { category ->
            CategoryMangaTable.insert {
                it[CategoryMangaTable.category] = category[CategoryTable.id].value
                it[CategoryMangaTable.manga] = mangaId
                it[CategoryMangaTable.userId] = userId
            }
        }
    }

    private fun syncLegacyLibraryColumnsInternal(mangaId: Int) {
        val allRows =
            UserMangaLibraryTable
                .selectAll()
                .where { UserMangaLibraryTable.mangaId eq mangaId }
                .toList()

        val inAnyLibrary = allRows.isNotEmpty()
        val earliestAddedAt = allRows.minOfOrNull { it[UserMangaLibraryTable.inLibraryAt] } ?: 0L

        MangaTable.update({ MangaTable.id eq mangaId }) {
            it[MangaTable.inLibrary] = inAnyLibrary
            it[MangaTable.inLibraryAt] = if (inAnyLibrary) earliestAddedAt else 0L
        }
    }

    private fun isAdminInternal(userId: Int): Boolean =
        UserAccountTable
            .select(UserAccountTable.role)
            .where { UserAccountTable.id eq userId }
            .firstOrNull()
            ?.get(UserAccountTable.role)
            ?.equals(UserRole.ADMIN.name, ignoreCase = true) == true

    private fun isMangaInAnyLibraryInternal(mangaId: Int): Boolean =
        UserMangaLibraryTable
            .select(UserMangaLibraryTable.id)
            .where { UserMangaLibraryTable.mangaId eq mangaId }
            .firstOrNull() != null
}
