/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.cache.CustomCacheMap
import suwayomi.tachidesk.graphql.types.MangaNodeList
import suwayomi.tachidesk.graphql.types.MangaNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.JavalinSetup.future

class MangaDataLoader : KotlinDataLoader<Int, MangaType?> {
    override val dataLoaderName = "MangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val rows =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.id inList ids }
                            .toList()
                    val libraryEntries = Library.getUserLibraryEntryMap(userId, ids)
                    val manga =
                        rows
                            .map {
                                val inLibraryAt = libraryEntries[it[MangaTable.id].value]
                                MangaType(
                                    row = it,
                                    inLibrary = inLibraryAt != null,
                                    inLibraryAt = inLibraryAt ?: 0L,
                                )
                            }.associateBy { it.id }
                    ids.map { manga[it] }
                }
            }
        }
    }
}

class MangaForCategoryDataLoader : KotlinDataLoader<Int, MangaNodeList> {
    override val dataLoaderName = "MangaForCategoryDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaNodeList> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, MangaNodeList> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val userLibraryMangaIds = Library.getUserLibraryMangaIds(userId)
                    if (userLibraryMangaIds.isEmpty()) {
                        return@transaction ids.map { emptyList<MangaType>().toNodeList() }
                    }

                    val userLibraryEntries = Library.getUserLibraryEntryMap(userId, userLibraryMangaIds)
                    val itemsByRef =
                        if (ids.contains(0)) {
                            val categorizedMangaIds =
                                CategoryMangaTable
                                    .select(CategoryMangaTable.manga)
                                    .where {
                                        (CategoryMangaTable.userId eq userId) and
                                            (CategoryMangaTable.manga inList userLibraryMangaIds)
                                    }.map { it[CategoryMangaTable.manga].value }
                                    .toSet()

                            val defaultCategoryMangas =
                                MangaTable
                                    .selectAll()
                                    .where { MangaTable.id inList (userLibraryMangaIds - categorizedMangaIds).toList() }
                                    .toList()
                                    .mapNotNull { row ->
                                        val mangaId = row[MangaTable.id].value
                                        val inLibraryAt = userLibraryEntries[mangaId] ?: return@mapNotNull null
                                        MangaType(row, inLibrary = true, inLibraryAt = inLibraryAt)
                                    }

                            mapOf(0 to defaultCategoryMangas)
                        } else {
                            emptyMap()
                        } +
                            CategoryMangaTable
                                .innerJoin(MangaTable)
                                .selectAll()
                                .where {
                                    (CategoryMangaTable.category inList ids) and
                                        (CategoryMangaTable.userId eq userId) and
                                        (CategoryMangaTable.manga inList userLibraryMangaIds)
                                }
                                .toList()
                                .let { rows ->
                                    rows
                                        .mapNotNull { row ->
                                            val mangaId = row[MangaTable.id].value
                                            val inLibraryAt = userLibraryEntries[mangaId] ?: return@mapNotNull null
                                            Pair(
                                                row[CategoryMangaTable.category].value,
                                                MangaType(row, inLibrary = true, inLibraryAt = inLibraryAt),
                                            )
                                        }
                                }
                                .groupBy { it.first }
                                .mapValues { it.value.map { pair -> pair.second } }

                    ids.map { (itemsByRef[it] ?: emptyList()).toNodeList() }
                }
            }
        }
    }
}

class MangaForSourceDataLoader : KotlinDataLoader<Long, MangaNodeList> {
    override val dataLoaderName = "MangaForSourceDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, MangaNodeList> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Long, MangaNodeList> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val rows =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.sourceReference inList ids }
                            .toList()
                    val libraryEntries = Library.getUserLibraryEntryMap(userId, rows.map { row -> row[MangaTable.id].value })
                    val mangaBySourceId =
                        rows
                            .mapNotNull { row ->
                                val mangaId = row[MangaTable.id].value
                                val inLibraryAt = libraryEntries[mangaId] ?: return@mapNotNull null
                                MangaType(row, inLibrary = true, inLibraryAt = inLibraryAt)
                            }
                            .groupBy { it.sourceId }
                    ids.map { (mangaBySourceId[it] ?: emptyList()).toNodeList() }
                }
            }
        }
    }
}

class MangaForIdsDataLoader : KotlinDataLoader<List<Int>, MangaNodeList> {
    override val dataLoaderName = "MangaForIdsDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<List<Int>, MangaNodeList> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader(
            { mangaIds ->
                future {
                    transaction {
                        addLogger(Slf4jSqlDebugLogger)
                        val ids = mangaIds.flatten().distinct()
                        val rows =
                            MangaTable
                                .selectAll()
                                .where { MangaTable.id inList ids }
                                .toList()
                        val libraryEntries = Library.getUserLibraryEntryMap(userId, ids)
                        val manga =
                            rows.mapNotNull { row ->
                                val mangaId = row[MangaTable.id].value
                                val inLibraryAt = libraryEntries[mangaId] ?: return@mapNotNull null
                                MangaType(row, inLibrary = true, inLibraryAt = inLibraryAt)
                            }
                        mangaIds.map { mangaIds ->
                            manga.filter { it.id in mangaIds }.toNodeList()
                        }
                    }
                }
            },
            DataLoaderOptions.newOptions().setCacheMap(CustomCacheMap<List<Int>, MangaNodeList>()),
        )
    }
}

