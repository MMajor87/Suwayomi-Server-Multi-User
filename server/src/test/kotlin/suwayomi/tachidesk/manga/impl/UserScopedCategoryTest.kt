package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest

class UserScopedCategoryTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "cat") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

    private fun makeUser(): Int = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true).id

    private fun makeCategory(
        userId: Int,
        name: String = uniqueName(),
    ): Int = Category.createCategory(name, userId)

    private fun makeManga(): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[MangaTable.title] = uniqueName("manga")
                    it[MangaTable.url] = uniqueName("url")
                    it[MangaTable.sourceReference] = 1L
                }.value
        }

    private fun ensureDefaultCategoryExists() {
        transaction {
            val exists =
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.id eq Category.DEFAULT_CATEGORY_ID }
                    .firstOrNull() != null
            if (exists) {
                return@transaction
            }

            CategoryTable.insert {
                it[id] = EntityID(Category.DEFAULT_CATEGORY_ID, CategoryTable)
                it[name] = Category.DEFAULT_CATEGORY_NAME
                it[userId] = null
                it[order] = 0
                it[isDefault] = false
                it[includeInUpdate] = IncludeOrExclude.UNSET.value
                it[includeInDownload] = IncludeOrExclude.UNSET.value
            }
        }
    }

    @Test
    fun `user A categories are not visible to user B`() {
        val userA = makeUser()
        val userB = makeUser()
        val catId = makeCategory(userA)

        assertTrue(Category.getCategoryList(userA).any { it.id == catId })
        assertFalse(Category.getCategoryList(userB).any { it.id == catId })
    }

    @Test
    fun `getCategoryById is user-scoped`() {
        val userA = makeUser()
        val userB = makeUser()
        val catId = makeCategory(userA)

        assertNotNull(Category.getCategoryById(catId, userA))
        assertNull(Category.getCategoryById(catId, userB))
    }

    @Test
    fun `updateCategory with wrong userId does not rename user A category`() {
        val userA = makeUser()
        val userB = makeUser()
        val originalName = uniqueName("original")
        val catId = makeCategory(userA, originalName)

        Category.updateCategory(catId, name = "changed-by-b", isDefault = null, includeInUpdate = null, includeInDownload = null, userId = userB)

        assertEquals(originalName, Category.getCategoryById(catId, userA)!!.name)
    }

    @Test
    fun `removeCategory with wrong userId does not delete user A category`() {
        val userA = makeUser()
        val userB = makeUser()
        val catId = makeCategory(userA)

        Category.removeCategory(catId, userId = userB)

        assertNotNull(Category.getCategoryById(catId, userA))
    }

    @Test
    fun `DEFAULT category is visible to all users`() {
        val userA = makeUser()
        val userB = makeUser()
        ensureDefaultCategoryExists()

        assertNotNull(Category.getCategoryById(Category.DEFAULT_CATEGORY_ID, userA))
        assertNotNull(Category.getCategoryById(Category.DEFAULT_CATEGORY_ID, userB))
    }

    @Test
    fun `CategoryManga ownership is isolated by userId`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()
        val catA = makeCategory(userA, uniqueName("a"))
        val catB = makeCategory(userB, uniqueName("b"))

        CategoryManga.addMangaToCategory(mangaId, catA, userA)
        CategoryManga.addMangaToCategory(mangaId, catB, userB)

        val categoriesForUserA = CategoryManga.getMangaCategories(mangaId, userA).map { it.id }.toSet()
        val categoriesForUserB = CategoryManga.getMangaCategories(mangaId, userB).map { it.id }.toSet()

        assertEquals(setOf(catA), categoriesForUserA)
        assertEquals(setOf(catB), categoriesForUserB)
        assertEquals(1, Category.getCategoryById(catA, userA)!!.size)
        assertEquals(1, Category.getCategoryById(catB, userB)!!.size)

        val rawMappings =
            transaction {
                CategoryMangaTable
                    .selectAll()
                    .where { CategoryMangaTable.manga eq mangaId }
                    .map { it[CategoryMangaTable.userId]?.value to it[CategoryMangaTable.category].value }
                    .toSet()
            }
        assertTrue(rawMappings.contains(userA to catA))
        assertTrue(rawMappings.contains(userB to catB))
    }
}
