package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.Category.DEFAULT_CATEGORY_ID
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.database.dbTransaction

object CategoryManga {
    fun addMangaToCategory(
        mangaId: Int,
        categoryId: Int,
        userId: Int? = null,
    ) {
        addMangaToCategories(mangaId, listOf(categoryId), userId)
    }

    fun addMangaToCategories(
        mangaId: Int,
        categoryIds: List<Int>,
        userId: Int? = null,
    ) {
        addMangasToCategories(listOf(mangaId), categoryIds, userId)
    }

    fun addMangasToCategories(
        mangaIds: List<Int>,
        categoryIds: List<Int>,
        userId: Int? = null,
    ) {
        val filteredCategoryIds = categoryIds.filter { it != DEFAULT_CATEGORY_ID }
        if (filteredCategoryIds.isEmpty() || mangaIds.isEmpty()) {
            return
        }

        val allowedCategoryIds =
            if (userId == null) {
                filteredCategoryIds.toSet()
            } else {
                transaction {
                    CategoryTable
                        .select(CategoryTable.id)
                        .where {
                            (CategoryTable.id inList filteredCategoryIds) and
                                (CategoryTable.userId eq userId)
                        }.map { it[CategoryTable.id].value }
                        .toSet()
                }
            }
        if (allowedCategoryIds.isEmpty()) {
            return
        }

        val mangaIdsToCategoryIds = getMangasCategories(mangaIds, userId).mapValues { it.value.map { category -> category.id } }
        val mangaIdsToNewCategoryIds =
            mangaIds.associateWith { mangaId ->
                allowedCategoryIds.filter { categoryId ->
                    !(mangaIdsToCategoryIds[mangaId]?.contains(categoryId) ?: false)
                }
            }

        val newMangaCategoryMappings =
            mangaIdsToNewCategoryIds.flatMap { (mangaId, newCategoryIds) ->
                newCategoryIds.map { mangaId to it }
            }
        if (newMangaCategoryMappings.isEmpty()) {
            return
        }

        dbTransaction {
            CategoryMangaTable.batchInsert(newMangaCategoryMappings) { (mangaId, categoryId) ->
                this[CategoryMangaTable.manga] = mangaId
                this[CategoryMangaTable.category] = categoryId
                if (userId != null) {
                    this[CategoryMangaTable.userId] = userId
                }
            }
        }
    }

    fun removeMangaFromCategory(
        mangaId: Int,
        categoryId: Int,
        userId: Int? = null,
    ) {
        if (categoryId == DEFAULT_CATEGORY_ID) return
        transaction {
            CategoryMangaTable.deleteWhere {
                if (userId == null) {
                    (CategoryMangaTable.category eq categoryId) and (CategoryMangaTable.manga eq mangaId)
                } else {
                    (CategoryMangaTable.userId eq userId) and
                        (CategoryMangaTable.category eq categoryId) and
                        (CategoryMangaTable.manga eq mangaId)
                }
            }
        }
    }

    /**
     * list of mangas that belong to a category
     */
    fun getCategoryMangaList(
        categoryId: Int,
        userId: Int? = null,
    ): List<MangaDataClass> {
        val mangaIds =
            if (userId == null) {
                getGlobalCategoryMangaIds(categoryId)
            } else {
                getUserCategoryMangaIds(userId, categoryId)
            }
        if (mangaIds.isEmpty()) {
            return emptyList()
        }

        val mangaRows =
            transaction {
                MangaTable
                    .selectAll()
                    .where { MangaTable.id inList mangaIds.toList() }
                    .toList()
            }.associateBy { it[MangaTable.id].value }
        val chapterRows =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga inList mangaIds.toList() }
                    .toList()
            }
        val chapterRowsByManga = chapterRows.groupBy { it[ChapterTable.manga].value }
        val chapterStates =
            if (userId == null) {
                emptyMap()
            } else {
                Chapter.getUserChapterStateMap(userId, chapterRows.map { it[ChapterTable.id].value })
            }

        val libraryEntries =
            if (userId == null) {
                emptyMap()
            } else {
                Library.getUserLibraryEntryMap(userId, mangaIds)
            }

        return mangaIds.mapNotNull { mangaId ->
            val row = mangaRows[mangaId] ?: return@mapNotNull null
            val chapters = chapterRowsByManga[mangaId].orEmpty()

            val mangaData =
                if (userId == null) {
                    MangaTable.toDataClass(row)
                } else {
                    MangaTable
                        .toDataClass(row)
                        .copy(
                            inLibrary = true,
                            inLibraryAt = libraryEntries[mangaId] ?: 0L,
                        )
                }

            mangaData.unreadCount =
                chapters.count { chapter ->
                    if (userId == null) {
                        !chapter[ChapterTable.isRead]
                    } else {
                        val chapterId = chapter[ChapterTable.id].value
                        !(chapterStates[chapterId]?.isRead ?: false)
                    }
                }.toLong()
            mangaData.downloadCount = chapters.count { chapter -> chapter[ChapterTable.isDownloaded] }.toLong()
            mangaData.chapterCount = chapters.size.toLong()
            mangaData.lastReadAt =
                chapters.maxOfOrNull { chapter ->
                    if (userId == null) {
                        chapter[ChapterTable.lastReadAt]
                    } else {
                        val chapterId = chapter[ChapterTable.id].value
                        chapterStates[chapterId]?.lastReadAt ?: 0
                    }
                }

            mangaData
        }
    }

    /**
     * list of categories that a manga belongs to
     */
    fun getMangaCategories(
        mangaId: Int,
        userId: Int? = null,
    ): List<CategoryDataClass> =
        transaction {
            CategoryMangaTable
                .innerJoin(CategoryTable)
                .selectAll()
                .where {
                    if (userId == null) {
                        CategoryMangaTable.manga eq mangaId
                    } else {
                        (CategoryMangaTable.userId eq userId) and (CategoryMangaTable.manga eq mangaId)
                    }
                }.orderBy(CategoryTable.order to SortOrder.ASC)
                .map {
                    CategoryTable.toDataClass(it, userId)
                }
        }

    fun getMangasCategories(
        mangaIDs: List<Int>,
        userId: Int? = null,
    ): Map<Int, List<CategoryDataClass>> =
        buildMap {
            transaction {
                CategoryMangaTable
                    .innerJoin(CategoryTable)
                    .selectAll()
                    .where {
                        if (userId == null) {
                            CategoryMangaTable.manga inList mangaIDs
                        } else {
                            (CategoryMangaTable.userId eq userId) and (CategoryMangaTable.manga inList mangaIDs)
                        }
                    }.groupBy { it[CategoryMangaTable.manga] }
                    .forEach {
                        val mangaId = it.key.value
                        val categories = it.value

                        set(mangaId, categories.map { category -> CategoryTable.toDataClass(category, userId) })
                    }
            }
        }

    private fun getGlobalCategoryMangaIds(categoryId: Int): List<Int> =
        transaction {
            if (categoryId == DEFAULT_CATEGORY_ID) {
                val libraryMangaIds =
                    MangaTable
                        .select(MangaTable.id)
                        .where { MangaTable.inLibrary eq true }
                        .map { it[MangaTable.id].value }
                        .toSet()
                if (libraryMangaIds.isEmpty()) {
                    return@transaction emptyList()
                }

                val mangasWithCategory =
                    CategoryMangaTable
                        .select(CategoryMangaTable.manga)
                        .where { CategoryMangaTable.manga inList libraryMangaIds.toList() }
                        .map { it[CategoryMangaTable.manga].value }
                        .toSet()

                (libraryMangaIds - mangasWithCategory).toList()
            } else {
                CategoryMangaTable
                    .select(CategoryMangaTable.manga)
                    .where { CategoryMangaTable.category eq categoryId }
                    .map { it[CategoryMangaTable.manga].value }
            }
        }

    private fun getUserCategoryMangaIds(
        userId: Int,
        categoryId: Int,
    ): List<Int> {
        val userLibraryMangaIds = Library.getUserLibraryMangaIds(userId)
        if (userLibraryMangaIds.isEmpty()) {
            return emptyList()
        }

        return transaction {
            if (categoryId == DEFAULT_CATEGORY_ID) {
                val mangasWithUserCategory =
                    CategoryMangaTable
                        .select(CategoryMangaTable.manga)
                        .where {
                            (CategoryMangaTable.userId eq userId) and
                                (CategoryMangaTable.manga inList userLibraryMangaIds.toList())
                        }.map { it[CategoryMangaTable.manga].value }
                        .toSet()
                (userLibraryMangaIds - mangasWithUserCategory).toList()
            } else {
                CategoryMangaTable
                    .select(CategoryMangaTable.manga)
                    .where {
                        (CategoryMangaTable.userId eq userId) and
                            (CategoryMangaTable.category eq categoryId) and
                            (CategoryMangaTable.manga inList userLibraryMangaIds.toList())
                    }.map { it[CategoryMangaTable.manga].value }
            }
        }
    }
}
