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
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterNodeList
import suwayomi.tachidesk.graphql.types.ChapterNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.model.table.UserChapterTable
import suwayomi.tachidesk.server.user.requireUser

private fun getUserChapterStates(
    userId: Int,
    chapterIds: Collection<Int>,
): Map<Int, Chapter.UserChapterState> {
    val distinctIds = chapterIds.toSet()
    if (distinctIds.isEmpty()) {
        return emptyMap()
    }

    return UserChapterTable
        .selectAll()
        .where {
            (UserChapterTable.userId eq userId) and
                (UserChapterTable.chapterId inList distinctIds)
        }.associate {
            it[UserChapterTable.chapterId].value to
                Chapter.UserChapterState(
                    isRead = it[UserChapterTable.isRead],
                    isBookmarked = it[UserChapterTable.isBookmarked],
                    lastPageRead = it[UserChapterTable.lastPageRead],
                    lastReadAt = it[UserChapterTable.lastReadAt],
                )
        }
}

private fun ResultRow.toChapterTypeForUser(
    userId: Int?,
    chapterStates: Map<Int, Chapter.UserChapterState> = emptyMap(),
): ChapterType {
    if (userId == null) {
        return ChapterType(this)
    }

    val chapterId = this[ChapterTable.id].value
    val state = chapterStates[chapterId]

    return ChapterType(
        id = chapterId,
        url = this[ChapterTable.url],
        name = this[ChapterTable.name],
        uploadDate = this[ChapterTable.date_upload],
        chapterNumber = this[ChapterTable.chapter_number],
        scanlator = this[ChapterTable.scanlator],
        mangaId = this[ChapterTable.manga].value,
        isRead = state?.isRead ?: false,
        isBookmarked = state?.isBookmarked ?: false,
        lastPageRead = state?.lastPageRead ?: 0,
        lastReadAt = state?.lastReadAt ?: 0,
        sourceOrder = this[ChapterTable.sourceOrder],
        realUrl = this[ChapterTable.realUrl],
        fetchedAt = this[ChapterTable.fetchedAt],
        isDownloaded = this[ChapterTable.isDownloaded],
        pageCount = this[ChapterTable.pageCount],
    )
}

class ChapterDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "ChapterDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id inList ids }
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val chapters =
                        chapterRows
                            .map { row ->
                                row.toChapterTypeForUser(userId, chapterStates)
                            }
                            .associateBy { it.id }
                    ids.map { chapters[it] }
                }
            }
        }
    }
}

class ChaptersForMangaDataLoader : KotlinDataLoader<Int, ChapterNodeList> {
    override val dataLoaderName = "ChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterNodeList> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterNodeList> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val chaptersByMangaId =
                        chapterRows
                            .map { row -> row.toChapterTypeForUser(userId, chapterStates) }
                            .groupBy { it.mangaId }
                    ids.map { (chaptersByMangaId[it] ?: emptyList()).toNodeList() }
                }
            }
        }
    }
}

class DownloadedChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "DownloadedChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader<Int, Int> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val downloadedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.isDownloaded.count())
                            .where {
                                (ChapterTable.manga inList ids) and
                                    (ChapterTable.isDownloaded eq true)
                            }.groupBy(ChapterTable.manga)
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.isDownloaded.count()] }
                    ids.map { downloadedChapterCountByMangaId[it]?.toInt() ?: 0 }
                }
            }
        }
}

class UnreadChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "UnreadChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, Int> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .select(ChapterTable.id, ChapterTable.manga)
                            .where {
                                ChapterTable.manga inList ids
                            }.toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val unreadChapterCountByMangaId =
                        chapterRows.groupBy { it[ChapterTable.manga].value }.mapValues { (_, rows) ->
                            rows.count { row ->
                                val chapterId = row[ChapterTable.id].value
                                !(chapterStates[chapterId]?.isRead ?: false)
                            }
                        }
                    ids.map { unreadChapterCountByMangaId[it] ?: 0 }
                }
            }
        }
    }
}

class BookmarkedChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "BookmarkedChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, Int> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .select(ChapterTable.id, ChapterTable.manga)
                            .where {
                                ChapterTable.manga inList ids
                            }.toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val bookmarkedChapterCountByMangaId =
                        chapterRows.groupBy { it[ChapterTable.manga].value }.mapValues { (_, rows) ->
                            rows.count { row ->
                                val chapterId = row[ChapterTable.id].value
                                chapterStates[chapterId]?.isBookmarked ?: false
                            }
                        }
                    ids.map { bookmarkedChapterCountByMangaId[it] ?: 0 }
                }
            }
        }
    }
}

class HasDuplicateChaptersForMangaDataLoader : KotlinDataLoader<Int, Boolean> {
    override val dataLoaderName = "HasDuplicateChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Boolean> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val duplicatedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.chapter_number, ChapterTable.chapter_number.count())
                            .where {
                                (
                                    ChapterTable.manga inList
                                        ids
                                ) and
                                    (ChapterTable.chapter_number greaterEq 0f)
                            }.groupBy(ChapterTable.manga, ChapterTable.chapter_number)
                            .having { ChapterTable.chapter_number.count() greater 1 }
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.chapter_number.count()] }

                    ids.map { duplicatedChapterCountByMangaId.contains(it) }
                }
            }
        }
}

class LastReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LastReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val chapterRowsByMangaId = chapterRows.groupBy { it[ChapterTable.manga].value }
                    val lastReadChaptersByMangaId =
                        chapterRowsByMangaId.mapValues { (_, rows) ->
                            rows.maxByOrNull { row ->
                                val chapterId = row[ChapterTable.id].value
                                chapterStates[chapterId]?.lastReadAt ?: 0
                            }
                        }
                    ids.map { id ->
                        lastReadChaptersByMangaId[id]
                            ?.takeIf { row ->
                                val chapterId = row[ChapterTable.id].value
                                (chapterStates[chapterId]?.lastReadAt ?: 0) > 0
                            }?.toChapterTypeForUser(userId, chapterStates)
                    }
                }
            }
        }
    }
}

class LatestReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LatestReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val latestReadChaptersByMangaId =
                        chapterRows
                            .groupBy { it[ChapterTable.manga].value }
                            .mapValues { (_, rows) ->
                                rows.firstOrNull { row ->
                                    val chapterId = row[ChapterTable.id].value
                                    chapterStates[chapterId]?.isRead ?: false
                                }
                            }
                    ids.map { id -> latestReadChaptersByMangaId[id]?.toChapterTypeForUser(userId, chapterStates) }
                }
            }
        }
    }
}

class LatestFetchedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LatestFetchedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.fetchedAt to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val latestFetchedChaptersByMangaId =
                        chapterRows.groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestFetchedChaptersByMangaId[id]?.firstOrNull()?.toChapterTypeForUser(userId, chapterStates) }
                }
            }
        }
    }
}

class LatestUploadedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LatestUploadedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.date_upload to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val latestUploadedChaptersByMangaId =
                        chapterRows.groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestUploadedChaptersByMangaId[id]?.firstOrNull()?.toChapterTypeForUser(userId, chapterStates) }
                }
            }
        }
    }
}

class FirstUnreadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "FirstUnreadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val firstUnreadChaptersByMangaId =
                        chapterRows
                            .groupBy { it[ChapterTable.manga].value }
                            .mapValues { (_, rows) ->
                                rows.firstOrNull { row ->
                                    val chapterId = row[ChapterTable.id].value
                                    !(chapterStates[chapterId]?.isRead ?: false)
                                }
                            }
                    ids.map { id -> firstUnreadChaptersByMangaId[id]?.toChapterTypeForUser(userId, chapterStates) }
                }
            }
        }
    }
}

class HighestNumberedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "HighestNumberedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> {
        val userId by lazy { graphQLContext.getAttribute(Attribute.TachideskUser).requireUser() }

        return DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterTable.chapter_number greater 0f) }
                            .orderBy(ChapterTable.chapter_number to SortOrder.DESC_NULLS_LAST)
                            .toList()
                    val chapterStates = getUserChapterStates(userId, chapterRows.map { it[ChapterTable.id].value })
                    val highestNumberedChaptersByMangaId =
                        chapterRows.groupBy { it[ChapterTable.manga].value }
                    ids.map { id ->
                        highestNumberedChaptersByMangaId[id]
                            ?.firstOrNull()
                            ?.toChapterTypeForUser(userId, chapterStates)
                    }
                }
            }
        }
    }
}


