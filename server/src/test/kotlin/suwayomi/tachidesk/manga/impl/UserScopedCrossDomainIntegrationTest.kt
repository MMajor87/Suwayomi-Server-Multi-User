package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.model.table.UserMangaLibraryTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest
import java.time.Instant

class UserScopedCrossDomainIntegrationTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "cross") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

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
            if (alreadyPresent) {
                return@transaction
            }

            UserMangaLibraryTable.insert {
                it[UserMangaLibraryTable.userId] = userId
                it[UserMangaLibraryTable.mangaId] = mangaId
                it[inLibraryAt] = Instant.now().epochSecond
            }
        }
    }

    private fun chapterData(chapterId: Int) =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.id eq chapterId }
                .single()
                .let { ChapterTable.toDataClass(it, includeChapterCount = false, includeChapterMeta = false) }
        }

    @Test
    fun `two users same manga keep independent library progress and category state in one scenario`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()
        val chapterId = makeChapter(mangaId)
        val categoryA = Category.createCategory(uniqueName("cat-a"), userA)
        val categoryB = Category.createCategory(uniqueName("cat-b"), userB)

        addToLibrary(userA, mangaId)
        Chapter.setUserProgress(userA, chapterId, isRead = true, lastPageRead = 11)
        CategoryManga.addMangaToCategory(mangaId, categoryA, userA)

        assertTrue(Library.isMangaInUserLibrary(userA, mangaId))
        assertFalse(Library.isMangaInUserLibrary(userB, mangaId))
        assertTrue(CategoryManga.getMangaCategories(mangaId, userB).isEmpty())
        val userBBeforeLibrary = Chapter.withUserState(chapterData(chapterId), userB)
        assertFalse(userBBeforeLibrary.read)
        assertEquals(0, userBBeforeLibrary.lastPageRead)

        addToLibrary(userB, mangaId)
        Chapter.setUserProgress(userB, chapterId, isRead = false, isBookmarked = true, lastPageRead = 3)
        CategoryManga.addMangaToCategory(mangaId, categoryB, userB)

        val stateA = Chapter.getUserChapterStateMap(userA, setOf(chapterId)).getValue(chapterId)
        val stateB = Chapter.getUserChapterStateMap(userB, setOf(chapterId)).getValue(chapterId)
        assertTrue(stateA.isRead)
        assertEquals(11, stateA.lastPageRead)
        assertFalse(stateB.isRead)
        assertTrue(stateB.isBookmarked)
        assertEquals(3, stateB.lastPageRead)

        val categoriesForUserA = CategoryManga.getMangaCategories(mangaId, userA).map { it.id }.toSet()
        val categoriesForUserB = CategoryManga.getMangaCategories(mangaId, userB).map { it.id }.toSet()
        assertEquals(setOf(categoryA), categoriesForUserA)
        assertEquals(setOf(categoryB), categoriesForUserB)
    }

    @Test
    fun `cross-user chapter access denial returns defaults for unauthorized user`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()
        val chapterId = makeChapter(mangaId)

        addToLibrary(userA, mangaId)
        Chapter.setUserProgress(userA, chapterId, isRead = true, isBookmarked = true, lastPageRead = 9)

        assertThrows(ForbiddenException::class.java) {
            Library.requireChapterAccess(userB, chapterId)
        }

        val chapter = chapterData(chapterId)
        val userAView = Chapter.withUserState(chapter, userA)
        val userBView = Chapter.withUserState(chapter, userB)

        assertTrue(userAView.read)
        assertTrue(userAView.bookmarked)
        assertEquals(9, userAView.lastPageRead)
        assertFalse(userBView.read)
        assertFalse(userBView.bookmarked)
        assertEquals(0, userBView.lastPageRead)
    }

    @Test
    fun `cross-user category mutations by user B do not affect user A categories`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()
        val originalName = uniqueName("cat-a")
        val categoryA = Category.createCategory(originalName, userA)

        addToLibrary(userA, mangaId)
        CategoryManga.addMangaToCategory(mangaId, categoryA, userA)

        Category.updateCategory(categoryA, name = "b-attempted-rename", isDefault = null, includeInUpdate = null, includeInDownload = null, userId = userB)
        CategoryManga.addMangaToCategory(mangaId, categoryA, userB)
        CategoryManga.removeMangaFromCategory(mangaId, categoryA, userB)
        Category.removeCategory(categoryA, userB)

        val categoryForA = Category.getCategoryById(categoryA, userA)
        assertNotNull(categoryForA)
        assertEquals(originalName, categoryForA!!.name)

        val categoriesForUserA = CategoryManga.getMangaCategories(mangaId, userA).map { it.id }.toSet()
        val categoriesForUserB = CategoryManga.getMangaCategories(mangaId, userB).map { it.id }.toSet()
        assertEquals(setOf(categoryA), categoriesForUserA)
        assertTrue(categoriesForUserB.isEmpty())
    }
}
