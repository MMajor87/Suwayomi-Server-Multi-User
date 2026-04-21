package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.model.table.UserChapterTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest

class UserScopedChapterProgressTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "ch") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

    private fun makeUser(): Int = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true).id

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

    // --- getUserChapterStateMap ---

    @Test
    fun `getUserChapterStateMap returns empty map when no progress row exists`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        assertTrue(Chapter.getUserChapterStateMap(userId, setOf(chapterId)).isEmpty())
    }

    @Test
    fun `getUserChapterStateMap returns empty map for empty input`() {
        val userId = makeUser()
        assertTrue(Chapter.getUserChapterStateMap(userId, emptySet()).isEmpty())
    }

    @Test
    fun `getUserChapterStateMap returns correct state after setUserProgress`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = true, isBookmarked = true, lastPageRead = 7)

        val state = Chapter.getUserChapterStateMap(userId, setOf(chapterId))[chapterId]
        assertNotNull(state)
        assertTrue(state!!.isRead)
        assertTrue(state.isBookmarked)
        assertEquals(7, state.lastPageRead)
    }

    @Test
    fun `getUserChapterStateMap handles multiple chapters in one call`() {
        val userId = makeUser()
        val mangaId = makeManga()
        val ch1 = makeChapter(mangaId)
        val ch2 = makeChapter(mangaId)

        Chapter.setUserProgress(userId, ch1, isRead = true, lastPageRead = 3)
        Chapter.setUserProgress(userId, ch2, isRead = false, lastPageRead = 9)

        val stateMap = Chapter.getUserChapterStateMap(userId, setOf(ch1, ch2))
        assertEquals(2, stateMap.size)
        assertTrue(stateMap[ch1]!!.isRead)
        assertEquals(3, stateMap[ch1]!!.lastPageRead)
        assertFalse(stateMap[ch2]!!.isRead)
        assertEquals(9, stateMap[ch2]!!.lastPageRead)
    }

    // --- setUserProgress upsert behaviour ---

    @Test
    fun `setUserProgress creates exactly one row per user-chapter pair`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = false)
        Chapter.setUserProgress(userId, chapterId, isRead = true)

        val rowCount =
            transaction {
                UserChapterTable
                    .selectAll()
                    .where {
                        (UserChapterTable.userId eq userId) and
                            (UserChapterTable.chapterId eq chapterId)
                    }.count()
            }
        assertEquals(1L, rowCount)
    }

    @Test
    fun `setUserProgress second call updates the existing row`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = false, lastPageRead = 3)
        Chapter.setUserProgress(userId, chapterId, isRead = true, lastPageRead = 10)

        val state = Chapter.getUserChapterStateMap(userId, setOf(chapterId))[chapterId]!!
        assertTrue(state.isRead)
        assertEquals(10, state.lastPageRead)
    }

    // --- partial field updates do not clobber other fields ---

    @Test
    fun `updating isRead alone does not reset isBookmarked or lastPageRead`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = false, isBookmarked = true, lastPageRead = 5)
        Chapter.setUserProgress(userId, chapterId, isRead = true)

        val state = Chapter.getUserChapterStateMap(userId, setOf(chapterId))[chapterId]!!
        assertTrue(state.isRead)
        assertTrue(state.isBookmarked, "isBookmarked must not be reset by an isRead-only update")
        assertEquals(5, state.lastPageRead, "lastPageRead must not be reset by an isRead-only update")
    }

    @Test
    fun `updating isBookmarked alone does not reset isRead or lastPageRead`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = true, isBookmarked = false, lastPageRead = 5)
        Chapter.setUserProgress(userId, chapterId, isBookmarked = true)

        val state = Chapter.getUserChapterStateMap(userId, setOf(chapterId))[chapterId]!!
        assertTrue(state.isRead, "isRead must not be reset by an isBookmarked-only update")
        assertTrue(state.isBookmarked)
        assertEquals(5, state.lastPageRead, "lastPageRead must not be reset by an isBookmarked-only update")
    }

    @Test
    fun `updating lastPageRead alone does not reset isRead or isBookmarked`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = true, isBookmarked = true, lastPageRead = 3)
        Chapter.setUserProgress(userId, chapterId, lastPageRead = 9)

        val state = Chapter.getUserChapterStateMap(userId, setOf(chapterId))[chapterId]!!
        assertTrue(state.isRead, "isRead must not be reset by a lastPageRead-only update")
        assertTrue(state.isBookmarked, "isBookmarked must not be reset by a lastPageRead-only update")
        assertEquals(9, state.lastPageRead)
    }

    // --- two-user isolation ---

    @Test
    fun `two users have independent progress for the same chapter`() {
        val userA = makeUser()
        val userB = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userA, chapterId, isRead = true, lastPageRead = 5)
        Chapter.setUserProgress(userB, chapterId, isRead = false, lastPageRead = 2)

        val stateA = Chapter.getUserChapterStateMap(userA, setOf(chapterId))[chapterId]!!
        val stateB = Chapter.getUserChapterStateMap(userB, setOf(chapterId))[chapterId]!!

        assertTrue(stateA.isRead)
        assertEquals(5, stateA.lastPageRead)
        assertFalse(stateB.isRead)
        assertEquals(2, stateB.lastPageRead)
    }

    @Test
    fun `setting progress for user A does not create a row for user B`() {
        val userA = makeUser()
        val userB = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userA, chapterId, isRead = true)

        assertTrue(
            Chapter.getUserChapterStateMap(userB, setOf(chapterId)).isEmpty(),
            "User B should have no progress row after User A sets progress",
        )
    }

    @Test
    fun `removing user A progress does not affect user B progress`() {
        val userA = makeUser()
        val userB = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userA, chapterId, isRead = true, lastPageRead = 5)
        Chapter.setUserProgress(userB, chapterId, isRead = true, lastPageRead = 7)

        // Overwrite user A's progress with defaults (simulates clearing)
        Chapter.setUserProgress(userA, chapterId, isRead = false, lastPageRead = 0)

        val stateB = Chapter.getUserChapterStateMap(userB, setOf(chapterId))[chapterId]!!
        assertTrue(stateB.isRead, "User B read state must not be affected")
        assertEquals(7, stateB.lastPageRead, "User B lastPageRead must not be affected")
    }

    // --- withUserState ---

    @Test
    fun `withUserState returns chapter unchanged when userId is null`() {
        val chapterId = makeChapter(makeManga())

        val baseChapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId }
                    .single()
                    .let { ChapterTable.toDataClass(it, includeChapterCount = false, includeChapterMeta = false) }
            }

        val result = Chapter.withUserState(baseChapter, userId = null)
        assertEquals(baseChapter, result)
    }

    @Test
    fun `withUserState returns defaults when user has no progress row`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        val baseChapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId }
                    .single()
                    .let { ChapterTable.toDataClass(it, includeChapterCount = false, includeChapterMeta = false) }
            }

        val result = Chapter.withUserState(baseChapter, userId)
        assertFalse(result.read)
        assertFalse(result.bookmarked)
        assertEquals(0, result.lastPageRead)
        assertEquals(0L, result.lastReadAt)
    }

    @Test
    fun `withUserState overlays per-user state onto chapter data class`() {
        val userId = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userId, chapterId, isRead = true, isBookmarked = true, lastPageRead = 8)

        val baseChapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId }
                    .single()
                    .let { ChapterTable.toDataClass(it, includeChapterCount = false, includeChapterMeta = false) }
            }

        val result = Chapter.withUserState(baseChapter, userId)
        assertTrue(result.read)
        assertTrue(result.bookmarked)
        assertEquals(8, result.lastPageRead)
    }

    @Test
    fun `withUserState for user B returns defaults when only user A has progress`() {
        val userA = makeUser()
        val userB = makeUser()
        val chapterId = makeChapter(makeManga())

        Chapter.setUserProgress(userA, chapterId, isRead = true, lastPageRead = 6)

        val baseChapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId }
                    .single()
                    .let { ChapterTable.toDataClass(it, includeChapterCount = false, includeChapterMeta = false) }
            }

        val result = Chapter.withUserState(baseChapter, userB)
        assertFalse(result.read, "User B must not see User A's read state")
        assertEquals(0, result.lastPageRead, "User B must not see User A's lastPageRead")
    }
}
