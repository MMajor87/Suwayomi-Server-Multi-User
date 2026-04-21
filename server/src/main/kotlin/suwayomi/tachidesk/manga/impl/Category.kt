package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.model.table.UserMangaLibraryTable
import kotlin.collections.component1
import kotlin.collections.orEmpty

object Category {
    /**
     * The new category will be placed at the end of the list
     */
    fun createCategory(
        name: String,
        userId: Int? = null,
    ): Int = createCategories(listOf(name), userId).first()

    fun createCategories(
        names: List<String>,
        userId: Int? = null,
    ): List<Int> =
        transaction {
            val categoryIdToName = getCategoryList(userId).associate { it.id to it.name.lowercase() }

            val categoriesToCreate =
                names
                    .filter {
                        !it.equals(DEFAULT_CATEGORY_NAME, true)
                    }.filter { !categoryIdToName.values.contains(it.lowercase()) }

            val newCategoryIdsByName =
                CategoryTable
                    .batchInsert(categoriesToCreate) {
                        this[CategoryTable.name] = it
                        this[CategoryTable.order] = Int.MAX_VALUE
                        if (userId != null) {
                            this[CategoryTable.userId] = userId
                        }
                    }.associate { it[CategoryTable.name] to it[CategoryTable.id].value }

            normalizeCategories(userId)

            names.map {
                // creating a category named Default is illegal
                if (it.equals(DEFAULT_CATEGORY_NAME, true)) {
                    DEFAULT_CATEGORY_ID
                } else {
                    newCategoryIdsByName[it] ?: categoryIdToName.entries.find { (_, name) -> name.equals(it, true) }!!.key
                }
            }
        }

    fun updateCategory(
        categoryId: Int,
        name: String?,
        isDefault: Boolean?,
        includeInUpdate: Int?,
        includeInDownload: Int?,
        userId: Int? = null,
    ) {
        transaction {
            val condition = categoryScopeOp(userId, categoryId)
            CategoryTable.update({ condition }) {
                if (
                    categoryId != DEFAULT_CATEGORY_ID &&
                    name != null &&
                    !name.equals(DEFAULT_CATEGORY_NAME, ignoreCase = true)
                ) {
                    it[CategoryTable.name] = name
                }
                if (categoryId != DEFAULT_CATEGORY_ID && isDefault != null) it[CategoryTable.isDefault] = isDefault
                if (includeInUpdate != null) it[CategoryTable.includeInUpdate] = includeInUpdate
                if (includeInDownload != null) it[CategoryTable.includeInDownload] = includeInDownload
            }
        }
    }

    /**
     * Move the category from order number `from` to `to`
     */
    fun reorderCategory(
        from: Int,
        to: Int,
        userId: Int? = null,
    ) {
        if (from == 0 || to == 0) return
        transaction {
            val categoriesQuery =
                CategoryTable
                    .selectAll()
                    .where {
                        CategoryTable.id neq DEFAULT_CATEGORY_ID
                    }
            if (userId != null) {
                categoriesQuery.andWhere { CategoryTable.userId eq userId }
            }
            val categories = categoriesQuery.orderBy(CategoryTable.order to SortOrder.ASC).toMutableList()
            if (from > categories.size || to > categories.size) {
                return@transaction
            }
            categories.add(to - 1, categories.removeAt(from - 1))
            categories.forEachIndexed { index, cat ->
                CategoryTable.update({ categoryScopeOp(userId, cat[CategoryTable.id].value) }) {
                    it[CategoryTable.order] = index + 1
                }
            }
            normalizeCategories(userId)
        }
    }

    fun removeCategory(
        categoryId: Int,
        userId: Int? = null,
    ) {
        if (categoryId == DEFAULT_CATEGORY_ID) return
        transaction {
            CategoryTable.deleteWhere { categoryScopeOp(userId, categoryId) }
            normalizeCategories(userId)
        }
    }

    /** make sure category order numbers starts from 1 and is consecutive */
    fun normalizeCategories(userId: Int? = null) {
        transaction {
            val categories =
                CategoryTable
                    .selectAll()
                    .let { query ->
                        if (userId != null) {
                            query.where {
                                (CategoryTable.userId eq userId) or (CategoryTable.id eq DEFAULT_CATEGORY_ID)
                            }
                        } else {
                            query
                        }
                    }.orderBy(CategoryTable.order to SortOrder.ASC)
                    .sortedWith(compareBy({ it[CategoryTable.id].value != 0 }, { it[CategoryTable.order] }))

            categories.forEachIndexed { index, cat ->
                CategoryTable.update({ categoryScopeOp(userId, cat[CategoryTable.id].value) }) {
                    it[CategoryTable.order] = index
                }
            }
        }
    }

    private fun needsDefaultCategory(userId: Int? = null) =
        transaction {
            if (userId == null) {
                UserMangaLibraryTable
                    .join(
                        CategoryMangaTable,
                        JoinType.LEFT,
                        additionalConstraint = {
                            (UserMangaLibraryTable.mangaId eq CategoryMangaTable.manga) and
                                (UserMangaLibraryTable.userId eq CategoryMangaTable.userId)
                        },
                    )
                    .selectAll()
                    .where { CategoryMangaTable.manga.isNull() }
                    .empty()
                    .not()
            } else {
                UserMangaLibraryTable
                    .join(
                        CategoryMangaTable,
                        JoinType.LEFT,
                        additionalConstraint = {
                            (UserMangaLibraryTable.mangaId eq CategoryMangaTable.manga) and
                                (CategoryMangaTable.userId eq userId)
                        },
                    ).selectAll()
                    .where {
                        (UserMangaLibraryTable.userId eq userId) and
                            CategoryMangaTable.manga.isNull()
                    }.empty()
                    .not()
            }
        }

    const val DEFAULT_CATEGORY_ID = 0
    const val DEFAULT_CATEGORY_NAME = "Default"

    fun getCategoryList(userId: Int? = null): List<CategoryDataClass> =
        transaction {
            CategoryTable
                .selectAll()
                .let { query ->
                    if (userId != null) {
                        query.where {
                            (CategoryTable.userId eq userId) or (CategoryTable.id eq DEFAULT_CATEGORY_ID)
                        }
                    } else {
                        query
                    }
                }.orderBy(CategoryTable.order to SortOrder.ASC)
                .let {
                    if (needsDefaultCategory(userId)) {
                        it
                    } else {
                        it.andWhere { CategoryTable.id neq DEFAULT_CATEGORY_ID }
                    }
                }.map {
                    CategoryTable.toDataClass(it, userId)
                }
        }

    fun getCategoryById(
        categoryId: Int,
        userId: Int? = null,
    ): CategoryDataClass? =
        transaction {
            CategoryTable
                .selectAll()
                .where { categoryScopeOp(userId, categoryId) }
                .firstOrNull()
                ?.let {
                    CategoryTable.toDataClass(it, userId)
                }
        }

    fun getCategorySize(
        categoryId: Int,
        userId: Int? = null,
    ): Int =
        transaction {
            if (userId == null) {
                if (categoryId == DEFAULT_CATEGORY_ID) {
                    UserMangaLibraryTable
                        .join(
                            CategoryMangaTable,
                            JoinType.LEFT,
                            additionalConstraint = {
                                (UserMangaLibraryTable.mangaId eq CategoryMangaTable.manga) and
                                    (UserMangaLibraryTable.userId eq CategoryMangaTable.userId)
                            },
                        )
                        .selectAll()
                        .where { CategoryMangaTable.manga.isNull() }
                } else {
                    CategoryMangaTable
                        .join(
                            UserMangaLibraryTable,
                            JoinType.INNER,
                            additionalConstraint = {
                                (CategoryMangaTable.userId eq UserMangaLibraryTable.userId) and
                                    (CategoryMangaTable.manga eq UserMangaLibraryTable.mangaId)
                            },
                        )
                        .selectAll()
                        .where { CategoryMangaTable.category eq categoryId }
                }.count().toInt()
            } else {
                if (categoryId == DEFAULT_CATEGORY_ID) {
                    UserMangaLibraryTable
                        .join(
                            CategoryMangaTable,
                            JoinType.LEFT,
                            additionalConstraint = {
                                (UserMangaLibraryTable.mangaId eq CategoryMangaTable.manga) and
                                    (CategoryMangaTable.userId eq userId)
                            },
                        ).selectAll()
                        .where {
                            (UserMangaLibraryTable.userId eq userId) and
                                CategoryMangaTable.manga.isNull()
                        }.count().toInt()
                } else {
                    CategoryMangaTable
                        .selectAll()
                        .where {
                            (CategoryMangaTable.userId eq userId) and
                                (CategoryMangaTable.category eq categoryId)
                        }.count().toInt()
                }
            }
        }

    fun getCategoryMetaMap(
        categoryId: Int,
        userId: Int? = null,
    ): Map<String, String> {
        if (userId != null && !isCategoryOwnedByUser(categoryId, userId)) {
            return emptyMap()
        }

        return transaction {
            CategoryMetaTable
                .selectAll()
                .where { CategoryMetaTable.ref eq categoryId }
                .associate { it[CategoryMetaTable.key] to it[CategoryMetaTable.value] }
        }
    }

    fun getCategoriesMetaMaps(
        ids: List<Int>,
        userId: Int? = null,
    ): Map<Int, Map<String, String>> {
        val filteredIds =
            if (userId == null) {
                ids.toSet()
            } else {
                getOwnedCategoryIds(userId, ids)
            }
        if (filteredIds.isEmpty()) {
            return emptyMap()
        }

        return transaction {
            CategoryMetaTable
                .selectAll()
                .where { CategoryMetaTable.ref inList filteredIds.toList() }
                .groupBy { it[CategoryMetaTable.ref].value }
                .mapValues { it.value.associate { it[CategoryMetaTable.key] to it[CategoryMetaTable.value] } }
                .withDefault { emptyMap() }
        }
    }

    fun modifyMeta(
        categoryId: Int,
        key: String,
        value: String,
        userId: Int? = null,
    ) {
        modifyCategoriesMetas(mapOf(categoryId to mapOf(key to value)), userId)
    }

    fun modifyCategoriesMetas(
        metaByCategoryId: Map<Int, Map<String, String>>,
        userId: Int? = null,
    ) {
        val normalizedMetaByCategoryId =
            if (userId == null) {
                metaByCategoryId
            } else {
                val allowedIds = getOwnedCategoryIds(userId, metaByCategoryId.keys)
                metaByCategoryId.filterKeys { it in allowedIds }
            }

        if (normalizedMetaByCategoryId.isEmpty()) {
            return
        }

        transaction {
            val categoryIds = normalizedMetaByCategoryId.keys
            val metaKeys = normalizedMetaByCategoryId.flatMap { it.value.keys }

            val dbMetaByCategoryId =
                CategoryMetaTable
                    .selectAll()
                    .where { (CategoryMetaTable.ref inList categoryIds) and (CategoryMetaTable.key inList metaKeys) }
                    .groupBy { it[CategoryMetaTable.ref].value }

            val existingMetaByMetaId =
                categoryIds.flatMap { categoryId ->
                    val dbMetaByKey = dbMetaByCategoryId[categoryId].orEmpty().associateBy { it[CategoryMetaTable.key] }
                    val existingMetas = normalizedMetaByCategoryId[categoryId].orEmpty().filter { (key) -> key in dbMetaByKey.keys }

                    existingMetas.map { entry ->
                        val metaId = dbMetaByKey[entry.key]!![CategoryMetaTable.id].value

                        metaId to entry
                    }
                }

            val newMetaByCategoryId =
                categoryIds.flatMap { categoryID ->
                    val dbMetaByKey = dbMetaByCategoryId[categoryID].orEmpty().associateBy { it[CategoryMetaTable.key] }

                    normalizedMetaByCategoryId[categoryID]
                        .orEmpty()
                        .filter { entry -> entry.key !in dbMetaByKey.keys }
                        .map { entry -> categoryID to entry }
                }

            if (existingMetaByMetaId.isNotEmpty()) {
                BatchUpdateStatement(CategoryMetaTable).apply {
                    existingMetaByMetaId.forEach { (metaId, entry) ->
                        addBatch(EntityID(metaId, CategoryMetaTable))
                        this[CategoryMetaTable.value] = entry.value
                    }
                    execute(this@transaction)
                }
            }

            if (newMetaByCategoryId.isNotEmpty()) {
                CategoryMetaTable.batchInsert(newMetaByCategoryId) { (categoryId, entry) ->
                    this[CategoryMetaTable.ref] = EntityID(categoryId, CategoryTable)
                    this[CategoryMetaTable.key] = entry.key
                    this[CategoryMetaTable.value] = entry.value
                }
            }
        }
    }

    private fun categoryScopeOp(
        userId: Int?,
        categoryId: Int,
    ): Op<Boolean> =
        if (userId == null) {
            CategoryTable.id eq categoryId
        } else {
            (CategoryTable.id eq categoryId) and ((CategoryTable.userId eq userId) or (CategoryTable.id eq DEFAULT_CATEGORY_ID))
        }

    private fun getOwnedCategoryIds(
        userId: Int,
        categoryIds: Collection<Int>,
    ): Set<Int> {
        val distinctIds = categoryIds.toSet()
        if (distinctIds.isEmpty()) {
            return emptySet()
        }

        return transaction {
            CategoryTable
                .select(CategoryTable.id)
                .where {
                    (CategoryTable.id inList distinctIds.toList()) and
                        ((CategoryTable.userId eq userId) or (CategoryTable.id eq DEFAULT_CATEGORY_ID))
                }.map { it[CategoryTable.id].value }
                .toSet()
        }
    }

    private fun isCategoryOwnedByUser(
        categoryId: Int,
        userId: Int,
    ): Boolean {
        if (categoryId == DEFAULT_CATEGORY_ID) {
            return true
        }

        return transaction {
            CategoryTable
                .select(CategoryTable.id)
                .where { (CategoryTable.id eq categoryId) and (CategoryTable.userId eq userId) }
                .firstOrNull() != null
        }
    }
}

