package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.model.table.UserChapterTable
import suwayomi.tachidesk.server.model.table.UserMangaLibraryTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.system.measureTimeMillis

/**
 * Performance sanity tests for user-scoped queries under realistic H2 row counts.
 *
 * These are not micro-benchmarks — the time budgets are deliberately generous so that
 * a pathological regression (e.g. N+1 queries, accidental table scan over all users)
 * is caught without false positives on CI. They also serve as correctness smoke tests
 * for the index-backed hot paths.
 */
class UserScopedQueryPerformanceTest : ApplicationTest() {
    companion object {
        private const val LIBRARY_MANGA_COUNT = 500
        private const val CHAPTER_COUNT = 500
        private const val NOISE_USER_COUNT = 50
        private const val TIME_BUDGET_MS = 5_000L
    }

    private fun uniqueName(prefix: String) = "$prefix-perf-${System.nanoTime()}-${(1000..9999).random()}"

    private fun insertManga(): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[MangaTable.title] = uniqueName("manga")
                    it[MangaTable.url] = uniqueName("url")
                    it[MangaTable.sourceReference] = 1L
                }.value
        }

    private fun insertChapter(mangaId: Int): Int =
        transaction {
            ChapterTable
                .insertAndGetId {
                    it[ChapterTable.url] = uniqueName("ch")
                    it[ChapterTable.name] = uniqueName("ch")
                    it[ChapterTable.sourceOrder] = 0
                    it[ChapterTable.manga] = mangaId
                }.value
        }

    @Test
    fun `getAccessibleMangaIds completes within time budget for large candidate set`() {
        val userId = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true).id

        // Insert NOISE_USER_COUNT other users each with their own library to simulate a populated DB
        val noiseUserIds =
            (1..NOISE_USER_COUNT).map {
                createUserAccount(uniqueName("noise"), "NoisePass1!", UserRole.USER, isActive = true).id
            }

        // Bulk-insert library manga for the test user and noise users in one transaction
        val mangaIds = mutableListOf<Int>()
        transaction {
            repeat(LIBRARY_MANGA_COUNT) {
                val mangaId =
                    MangaTable
                        .insertAndGetId {
                            it[MangaTable.title] = uniqueName("m")
                            it[MangaTable.url] = uniqueName("u")
                            it[MangaTable.sourceReference] = 1L
                        }.value
                mangaIds += mangaId
                UserMangaLibraryTable.insert {
                    it[UserMangaLibraryTable.userId] = userId
                    it[UserMangaLibraryTable.mangaId] = mangaId
                    it[UserMangaLibraryTable.inLibraryAt] = System.currentTimeMillis() / 1000
                }
                // Each noise user also gets this manga to create cross-user density
                noiseUserIds.forEach { noiseId ->
                    UserMangaLibraryTable.insert {
                        it[UserMangaLibraryTable.userId] = noiseId
                        it[UserMangaLibraryTable.mangaId] = mangaId
                        it[UserMangaLibraryTable.inLibraryAt] = System.currentTimeMillis() / 1000
                    }
                }
            }
        }

        var result: Set<Int> = emptySet()
        val elapsed =
            measureTimeMillis {
                result = Library.getAccessibleMangaIds(userId, mangaIds)
            }

        assertEquals(LIBRARY_MANGA_COUNT, result.size, "Expected all $LIBRARY_MANGA_COUNT manga to be accessible")
        assertTrue(
            elapsed < TIME_BUDGET_MS,
            "getAccessibleMangaIds took ${elapsed}ms — exceeded ${TIME_BUDGET_MS}ms budget",
        )
    }

    @Test
    fun `getUserChapterStateMap completes within time budget for large chapter set`() {
        val userId = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true).id
        val mangaId = insertManga()

        val chapterIds = mutableListOf<Int>()
        val now = System.currentTimeMillis()
        transaction {
            repeat(CHAPTER_COUNT) { i ->
                val chapterId =
                    ChapterTable
                        .insertAndGetId {
                            it[ChapterTable.url] = uniqueName("ch")
                            it[ChapterTable.name] = "Chapter $i"
                            it[ChapterTable.sourceOrder] = i
                            it[ChapterTable.manga] = mangaId
                        }.value
                chapterIds += chapterId
                UserChapterTable.insert {
                    it[UserChapterTable.userId] = userId
                    it[UserChapterTable.chapterId] = chapterId
                    it[UserChapterTable.isRead] = i % 2 == 0
                    it[UserChapterTable.isBookmarked] = i % 5 == 0
                    it[UserChapterTable.lastPageRead] = i % 10
                    it[UserChapterTable.lastReadAt] = now / 1000
                    it[UserChapterTable.updatedAt] = now / 1000
                }
            }
        }

        var result: Map<Int, Chapter.UserChapterState> = emptyMap()
        val elapsed =
            measureTimeMillis {
                result = Chapter.getUserChapterStateMap(userId, chapterIds)
            }

        assertEquals(CHAPTER_COUNT, result.size, "Expected state for all $CHAPTER_COUNT chapters")
        assertTrue(
            elapsed < TIME_BUDGET_MS,
            "getUserChapterStateMap took ${elapsed}ms — exceeded ${TIME_BUDGET_MS}ms budget",
        )
    }

    @Test
    fun `getAccessibleChapterIds completes within time budget for large candidate set`() {
        val userId = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true).id
        val mangaId = insertManga()

        // Add the manga to the user's library
        transaction {
            UserMangaLibraryTable.insert {
                it[UserMangaLibraryTable.userId] = userId
                it[UserMangaLibraryTable.mangaId] = mangaId
                it[UserMangaLibraryTable.inLibraryAt] = System.currentTimeMillis() / 1000
            }
        }

        val chapterIds = mutableListOf<Int>()
        transaction {
            repeat(CHAPTER_COUNT) { i ->
                val chapterId =
                    ChapterTable
                        .insertAndGetId {
                            it[ChapterTable.url] = uniqueName("ch")
                            it[ChapterTable.name] = "Perf-Ch-$i"
                            it[ChapterTable.sourceOrder] = i
                            it[ChapterTable.manga] = mangaId
                        }.value
                chapterIds += chapterId
            }
        }

        var result: Set<Int> = emptySet()
        val elapsed =
            measureTimeMillis {
                result = Library.getAccessibleChapterIds(userId, chapterIds)
            }

        assertEquals(CHAPTER_COUNT, result.size, "Expected all $CHAPTER_COUNT chapters to be accessible")
        assertTrue(
            elapsed < TIME_BUDGET_MS,
            "getAccessibleChapterIds took ${elapsed}ms — exceeded ${TIME_BUDGET_MS}ms budget",
        )
    }
}
