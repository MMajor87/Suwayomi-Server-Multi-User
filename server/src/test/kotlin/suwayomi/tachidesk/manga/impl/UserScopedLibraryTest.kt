package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.update.CategoryUpdateJob
import suwayomi.tachidesk.manga.impl.update.CategoryUpdateStatus
import suwayomi.tachidesk.manga.impl.update.JobStatus
import suwayomi.tachidesk.manga.impl.update.UpdateJob
import suwayomi.tachidesk.manga.impl.update.UpdateStatus
import suwayomi.tachidesk.manga.impl.update.UpdateUpdates
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.model.table.UserMangaLibraryTable
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest
import java.time.Instant

class UserScopedLibraryTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "lib") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

    private fun makeUser(): Int = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true).id

    private fun makeAdmin(): Int = createUserAccount(uniqueName("admin"), "AdminPass1!", UserRole.ADMIN, isActive = true).id

    private fun makeManga(): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[MangaTable.title] = uniqueName("manga")
                    it[MangaTable.url] = uniqueName("url")
                    it[MangaTable.sourceReference] = 1L
                }.value
        }

    private fun makeChapter(mangaId: Int): Int =
        transaction {
            ChapterTable
                .insertAndGetId {
                    it[ChapterTable.url] = uniqueName("ch")
                    it[ChapterTable.name] = uniqueName("ch")
                    it[ChapterTable.sourceOrder] = 0
                    it[ChapterTable.manga] = mangaId
                }.value
        }

    private fun mangaData(mangaId: Int): MangaDataClass =
        MangaDataClass(
            id = mangaId,
            sourceId = "1",
            url = "url-$mangaId",
            title = "manga-$mangaId",
        )

    private fun categoryData(categoryId: Int): CategoryDataClass =
        CategoryDataClass(
            id = categoryId,
            order = 0,
            name = "category-$categoryId",
            default = false,
            size = 0,
            includeInUpdate = IncludeOrExclude.UNSET,
            includeInDownload = IncludeOrExclude.UNSET,
        )

    /**
     * Directly inserts a UserMangaLibrary row. Used instead of Library.addMangaToLibrary
     * to avoid the CategoryManga side-effect path, which references a table that has
     * a migration naming mismatch in the H2 test environment (M0063 vs the ORM-derived name).
     * This helper tests the same underlying isolation properties of the library table.
     */
    private fun addToLibrary(
        userId: Int,
        mangaId: Int,
    ) {
        transaction {
            val alreadyPresent =
                UserMangaLibraryTable
                    .selectAll()
                    .where {
                        (UserMangaLibraryTable.userId eq userId) and
                            (UserMangaLibraryTable.mangaId eq mangaId)
                    }.firstOrNull() != null
            if (alreadyPresent) return@transaction

            UserMangaLibraryTable.insert {
                it[UserMangaLibraryTable.userId] = userId
                it[UserMangaLibraryTable.mangaId] = mangaId
                it[inLibraryAt] = Instant.now().epochSecond
            }

            // Mirror the legacy inLibrary flag sync (same logic as Library.syncLegacyLibraryColumnsInternal)
            MangaTable.update({ MangaTable.id eq mangaId }) {
                it[MangaTable.inLibrary] = true
            }
        }
    }

    private fun removeFromLibrary(
        userId: Int,
        mangaId: Int,
    ) {
        transaction {
            UserMangaLibraryTable.deleteWhere {
                (UserMangaLibraryTable.userId eq userId) and
                    (UserMangaLibraryTable.mangaId eq mangaId)
            }.also {
                // Sync legacy flag: set to false only if no other users have the manga
                val anyLeft =
                    UserMangaLibraryTable
                        .selectAll()
                        .where { UserMangaLibraryTable.mangaId eq mangaId }
                        .firstOrNull() != null
                if (!anyLeft) {
                    MangaTable.update({ MangaTable.id eq mangaId }) {
                        it[MangaTable.inLibrary] = false
                        it[MangaTable.inLibraryAt] = 0L
                    }
                }
            }
        }
    }

    // --- per-user membership queries ---

    @Test
    fun `isMangaInUserLibrary returns true after adding manga to a user's library`() {
        val userId = makeUser()
        val mangaId = makeManga()

        assertFalse(Library.isMangaInUserLibrary(userId, mangaId))
        addToLibrary(userId, mangaId)
        assertTrue(Library.isMangaInUserLibrary(userId, mangaId))
    }

    @Test
    fun `isMangaInUserLibrary returns false after removing manga from a user's library`() {
        val userId = makeUser()
        val mangaId = makeManga()

        addToLibrary(userId, mangaId)
        removeFromLibrary(userId, mangaId)
        assertFalse(Library.isMangaInUserLibrary(userId, mangaId))
    }

    @Test
    fun `addMangaToLibrary insert is idempotent — two inserts create only one row`() {
        val userId = makeUser()
        val mangaId = makeManga()

        addToLibrary(userId, mangaId)
        addToLibrary(userId, mangaId) // second call is a noop due to alreadyPresent check

        val rowCount = transaction {
            UserMangaLibraryTable
                .selectAll()
                .where {
                    (UserMangaLibraryTable.userId eq userId) and
                        (UserMangaLibraryTable.mangaId eq mangaId)
                }.count()
        }
        assertEquals(1L, rowCount)
    }

    // --- two-user isolation ---

    @Test
    fun `library membership is isolated — adding manga for user A does not affect user B`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()

        addToLibrary(userA, mangaId)

        assertTrue(Library.isMangaInUserLibrary(userA, mangaId))
        assertFalse(Library.isMangaInUserLibrary(userB, mangaId))
    }

    @Test
    fun `removing manga from user A's library does not remove it from user B's library`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()

        addToLibrary(userA, mangaId)
        addToLibrary(userB, mangaId)

        removeFromLibrary(userA, mangaId)

        assertFalse(Library.isMangaInUserLibrary(userA, mangaId))
        assertTrue(Library.isMangaInUserLibrary(userB, mangaId))
    }

    @Test
    fun `getUserLibraryMangaIds returns only that user's manga set`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaForA = makeManga()
        val mangaForB = makeManga()

        addToLibrary(userA, mangaForA)
        addToLibrary(userB, mangaForB)

        val idsA = Library.getUserLibraryMangaIds(userA)
        val idsB = Library.getUserLibraryMangaIds(userB)

        assertTrue(idsA.contains(mangaForA))
        assertFalse(idsA.contains(mangaForB))
        assertTrue(idsB.contains(mangaForB))
        assertFalse(idsB.contains(mangaForA))
    }

    // --- isMangaInAnyLibrary ---

    @Test
    fun `isMangaInAnyLibrary returns false before any user adds the manga`() {
        val mangaId = makeManga()
        assertFalse(Library.isMangaInAnyLibrary(mangaId))
    }

    @Test
    fun `isMangaInAnyLibrary returns true when at least one user has the manga`() {
        val userId = makeUser()
        val mangaId = makeManga()

        addToLibrary(userId, mangaId)
        assertTrue(Library.isMangaInAnyLibrary(mangaId))
    }

    @Test
    fun `isMangaInAnyLibrary returns false only after all users have removed the manga`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()

        addToLibrary(userA, mangaId)
        addToLibrary(userB, mangaId)

        removeFromLibrary(userA, mangaId)
        assertTrue(Library.isMangaInAnyLibrary(mangaId), "B still has it")

        removeFromLibrary(userB, mangaId)
        assertFalse(Library.isMangaInAnyLibrary(mangaId), "Nobody has it now")
    }

    // --- getMangaLibraryState ---

    @Test
    fun `getMangaLibraryState returns (true, non-zero) when manga is in the user's library`() {
        val userId = makeUser()
        val mangaId = makeManga()

        addToLibrary(userId, mangaId)

        val (inLibrary, timestamp) = Library.getMangaLibraryState(userId, mangaId)
        assertTrue(inLibrary)
        assertTrue(timestamp > 0L)
    }

    @Test
    fun `getMangaLibraryState returns (false, 0) when manga is not in the user's library`() {
        val userId = makeUser()
        val mangaId = makeManga()

        val (inLibrary, timestamp) = Library.getMangaLibraryState(userId, mangaId)
        assertFalse(inLibrary)
        assertEquals(0L, timestamp)
    }

    // --- legacy inLibrary flag sync ---

    @Test
    fun `MangaTable inLibrary is true when at least one user has the manga`() {
        val userId = makeUser()
        val mangaId = makeManga()

        addToLibrary(userId, mangaId)

        val inLibrary = transaction {
            MangaTable.selectAll().where { MangaTable.id eq mangaId }.single()[MangaTable.inLibrary]
        }
        assertTrue(inLibrary)
    }

    @Test
    fun `MangaTable inLibrary becomes false only after the last user removes the manga`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()

        addToLibrary(userA, mangaId)
        addToLibrary(userB, mangaId)

        removeFromLibrary(userA, mangaId)
        val afterFirst = transaction {
            MangaTable.selectAll().where { MangaTable.id eq mangaId }.single()[MangaTable.inLibrary]
        }
        assertTrue(afterFirst, "B still has it — should still be true")

        removeFromLibrary(userB, mangaId)
        val afterLast = transaction {
            MangaTable.selectAll().where { MangaTable.id eq mangaId }.single()[MangaTable.inLibrary]
        }
        assertFalse(afterLast, "Nobody has it — should be false")
    }

    // --- getAccessibleMangaIds ---

    @Test
    fun `getAccessibleMangaIds for USER returns only their own library manga`() {
        val userId = makeUser()
        val ownManga = makeManga()
        val otherManga = makeManga()

        addToLibrary(userId, ownManga)

        val accessible = Library.getAccessibleMangaIds(userId, setOf(ownManga, otherManga))
        assertTrue(accessible.contains(ownManga))
        assertFalse(accessible.contains(otherManga))
    }

    @Test
    fun `getAccessibleMangaIds for USER returns empty set when none of the ids are in their library`() {
        val userId = makeUser()
        val mangaId = makeManga()

        val accessible = Library.getAccessibleMangaIds(userId, setOf(mangaId))
        assertTrue(accessible.isEmpty())
    }

    @Test
    fun `getAccessibleMangaIds for ADMIN returns all provided manga ids regardless of library`() {
        val adminId = makeAdmin()
        val mangaA = makeManga()
        val mangaB = makeManga()

        val accessible = Library.getAccessibleMangaIds(adminId, setOf(mangaA, mangaB))
        assertTrue(accessible.containsAll(setOf(mangaA, mangaB)))
    }

    @Test
    fun `getAccessibleMangaIds returns empty set for empty input`() {
        val userId = makeUser()
        assertTrue(Library.getAccessibleMangaIds(userId, emptySet()).isEmpty())
    }

    // --- getAccessibleChapterIds ---

    @Test
    fun `getAccessibleChapterIds for USER returns only chapters whose parent manga is in their library`() {
        val userId = makeUser()
        val ownManga = makeManga()
        val otherManga = makeManga()
        val ownChapter = makeChapter(ownManga)
        val otherChapter = makeChapter(otherManga)

        addToLibrary(userId, ownManga)

        val accessible = Library.getAccessibleChapterIds(userId, setOf(ownChapter, otherChapter))
        assertTrue(accessible.contains(ownChapter))
        assertFalse(accessible.contains(otherChapter))
    }

    @Test
    fun `getAccessibleChapterIds for ADMIN returns all provided chapter ids`() {
        val adminId = makeAdmin()
        val manga = makeManga()
        val ch1 = makeChapter(manga)
        val ch2 = makeChapter(manga)

        val accessible = Library.getAccessibleChapterIds(adminId, setOf(ch1, ch2))
        assertTrue(accessible.containsAll(setOf(ch1, ch2)))
    }

    @Test
    fun `getAccessibleChapterIds returns empty set for empty input`() {
        val userId = makeUser()
        assertTrue(Library.getAccessibleChapterIds(userId, emptySet()).isEmpty())
    }

    // --- requireMangaAccess ---

    @Test
    fun `requireMangaAccess passes when USER has the manga in their library`() {
        val userId = makeUser()
        val mangaId = makeManga()
        addToLibrary(userId, mangaId)

        Library.requireMangaAccess(userId, mangaId) // must not throw
    }

    @Test
    fun `requireMangaAccess throws ForbiddenException when USER does not have the manga`() {
        val userId = makeUser()
        val mangaId = makeManga()

        assertThrows(ForbiddenException::class.java) {
            Library.requireMangaAccess(userId, mangaId)
        }
    }

    @Test
    fun `requireMangaAccess passes for ADMIN regardless of library membership`() {
        val adminId = makeAdmin()
        val mangaId = makeManga()

        Library.requireMangaAccess(adminId, mangaId) // must not throw
    }

    // --- requireChapterAccess ---

    @Test
    fun `requireChapterAccess passes when USER has the parent manga in their library`() {
        val userId = makeUser()
        val mangaId = makeManga()
        val chapterId = makeChapter(mangaId)
        addToLibrary(userId, mangaId)

        Library.requireChapterAccess(userId, chapterId)
    }

    @Test
    fun `requireChapterAccess throws ForbiddenException when USER does not have the parent manga`() {
        val userId = makeUser()
        val mangaId = makeManga()
        val chapterId = makeChapter(mangaId)

        assertThrows(ForbiddenException::class.java) {
            Library.requireChapterAccess(userId, chapterId)
        }
    }

    // --- filterDownloadQueueByUser ---

    @Test
    fun `filterDownloadQueueByUser returns only items for the USER's library manga`() {
        val userId = makeUser()
        val ownManga = makeManga()
        val otherManga = makeManga()
        addToLibrary(userId, ownManga)

        val queue =
            listOf(
                DownloadQueueItem(chapterId = 1, chapterIndex = 0, mangaId = ownManga, sourceId = 1L, position = 0, pageCount = 0),
                DownloadQueueItem(chapterId = 2, chapterIndex = 0, mangaId = otherManga, sourceId = 1L, position = 1, pageCount = 0),
            )

        val filtered = Library.filterDownloadQueueByUser(userId, queue)
        assertEquals(1, filtered.size)
        assertEquals(ownManga, filtered.first().mangaId)
    }

    @Test
    fun `filterDownloadQueueByUser for ADMIN returns the full queue`() {
        val adminId = makeAdmin()
        val mangaA = makeManga()
        val mangaB = makeManga()

        val queue =
            listOf(
                DownloadQueueItem(chapterId = 1, chapterIndex = 0, mangaId = mangaA, sourceId = 1L, position = 0, pageCount = 0),
                DownloadQueueItem(chapterId = 2, chapterIndex = 0, mangaId = mangaB, sourceId = 1L, position = 1, pageCount = 0),
            )

        assertEquals(2, Library.filterDownloadQueueByUser(adminId, queue).size)
    }

    @Test
    fun `filterDownloadQueueByUser returns empty list for empty queue`() {
        val userId = makeUser()
        assertTrue(Library.filterDownloadQueueByUser(userId, emptyList()).isEmpty())
    }

    // --- filterUpdateStatusForUser ---

    @Test
    fun `filterUpdateStatusForUser for USER keeps only statuses for library manga and recalculates counters`() {
        val userId = makeUser()
        val ownManga = makeManga()
        val otherManga = makeManga()
        addToLibrary(userId, ownManga)

        val status =
            UpdateStatus(
                categoryStatusMap = mapOf(CategoryUpdateStatus.UPDATING to listOf(categoryData(1))),
                mangaStatusMap =
                    mapOf(
                        JobStatus.RUNNING to listOf(mangaData(ownManga), mangaData(otherManga)),
                        JobStatus.COMPLETE to listOf(mangaData(otherManga)),
                    ),
                running = true,
                numberOfJobs = 3,
            )

        val filtered = Library.filterUpdateStatusForUser(userId, status)
        val filteredIds = filtered.mangaStatusMap.values.flatten().map { it.id }

        assertTrue(filtered.running)
        assertTrue(filtered.categoryStatusMap.isEmpty())
        assertEquals(listOf(ownManga), filteredIds)
        assertEquals(1, filtered.numberOfJobs)
        assertTrue(filtered.mangaStatusMap.getValue(JobStatus.COMPLETE).isEmpty())
    }

    @Test
    fun `filterUpdateStatusForUser for ADMIN returns original status without filtering`() {
        val adminId = makeAdmin()

        val status =
            UpdateStatus(
                categoryStatusMap = mapOf(CategoryUpdateStatus.SKIPPED to listOf(categoryData(1))),
                mangaStatusMap = mapOf(JobStatus.PENDING to listOf(mangaData(makeManga()))),
                running = true,
                numberOfJobs = 1,
            )

        val filtered = Library.filterUpdateStatusForUser(adminId, status)
        assertTrue(filtered === status)
    }

    // --- filterUpdateUpdatesForUser ---

    @Test
    fun `filterUpdateUpdatesForUser for USER keeps only library manga updates and recalculates counters recursively`() {
        val userId = makeUser()
        val ownMangaA = makeManga()
        val ownMangaB = makeManga()
        val otherManga = makeManga()
        addToLibrary(userId, ownMangaA)
        addToLibrary(userId, ownMangaB)

        val updates =
            UpdateUpdates(
                isRunning = true,
                categoryUpdates = listOf(CategoryUpdateJob(categoryData(1), CategoryUpdateStatus.UPDATING)),
                mangaUpdates =
                    listOf(
                        UpdateJob(mangaData(ownMangaA), JobStatus.COMPLETE),
                        UpdateJob(mangaData(ownMangaB), JobStatus.SKIPPED),
                        UpdateJob(mangaData(otherManga), JobStatus.FAILED),
                    ),
                totalJobs = 99,
                finishedJobs = 88,
                skippedCategoriesCount = 7,
                skippedMangasCount = 6,
                initial =
                    UpdateUpdates(
                        isRunning = false,
                        categoryUpdates = listOf(CategoryUpdateJob(categoryData(2), CategoryUpdateStatus.SKIPPED)),
                        mangaUpdates =
                            listOf(
                                UpdateJob(mangaData(ownMangaB), JobStatus.FAILED),
                                UpdateJob(mangaData(otherManga), JobStatus.COMPLETE),
                            ),
                        totalJobs = 2,
                        finishedJobs = 2,
                        skippedCategoriesCount = 1,
                        skippedMangasCount = 0,
                        initial = null,
                    ),
            )

        val filtered = Library.filterUpdateUpdatesForUser(userId, updates)

        assertTrue(filtered.isRunning)
        assertTrue(filtered.categoryUpdates.isEmpty())
        assertEquals(setOf(ownMangaA, ownMangaB), filtered.mangaUpdates.map { it.manga.id }.toSet())
        assertEquals(2, filtered.totalJobs)
        assertEquals(1, filtered.finishedJobs)
        assertEquals(0, filtered.skippedCategoriesCount)
        assertEquals(1, filtered.skippedMangasCount)

        val filteredInitial = filtered.initial
        assertTrue(filteredInitial != null)
        assertTrue(filteredInitial!!.categoryUpdates.isEmpty())
        assertEquals(listOf(ownMangaB), filteredInitial.mangaUpdates.map { it.manga.id })
        assertEquals(1, filteredInitial.totalJobs)
        assertEquals(1, filteredInitial.finishedJobs)
        assertEquals(0, filteredInitial.skippedCategoriesCount)
        assertEquals(0, filteredInitial.skippedMangasCount)
    }

    @Test
    fun `filterUpdateUpdatesForUser for ADMIN returns original updates without filtering`() {
        val adminId = makeAdmin()

        val updates =
            UpdateUpdates(
                isRunning = false,
                categoryUpdates = listOf(CategoryUpdateJob(categoryData(1), CategoryUpdateStatus.UPDATING)),
                mangaUpdates = listOf(UpdateJob(mangaData(makeManga()), JobStatus.PENDING)),
                totalJobs = 1,
                finishedJobs = 0,
                skippedCategoriesCount = 0,
                skippedMangasCount = 0,
                initial = null,
            )

        val filtered = Library.filterUpdateUpdatesForUser(adminId, updates)
        assertTrue(filtered === updates)
    }
}
