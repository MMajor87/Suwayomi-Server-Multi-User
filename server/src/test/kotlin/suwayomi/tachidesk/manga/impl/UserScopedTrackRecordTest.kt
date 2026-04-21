package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.dataclass.TrackRecordDataClass
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest

class UserScopedTrackRecordTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "track") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

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

    private fun insertTrackRecord(
        userId: Int,
        mangaId: Int,
        trackerId: Int,
        remoteId: Long,
    ): Int =
        transaction {
            TrackRecordTable
                .insertAndGetId {
                    it[TrackRecordTable.userId] = userId
                    it[TrackRecordTable.mangaId] = mangaId
                    it[TrackRecordTable.trackerId] = trackerId
                    it[TrackRecordTable.remoteId] = remoteId
                    it[TrackRecordTable.libraryId] = null
                    it[TrackRecordTable.title] = uniqueName("title")
                    it[TrackRecordTable.lastChapterRead] = 0.0
                    it[TrackRecordTable.totalChapters] = 0
                    it[TrackRecordTable.status] = 0
                    it[TrackRecordTable.score] = 0.0
                    it[TrackRecordTable.remoteUrl] = "https://example.test/$remoteId"
                    it[TrackRecordTable.startDate] = 0L
                    it[TrackRecordTable.finishDate] = 0L
                    it[TrackRecordTable.private] = false
                }.value
        }

    private fun nonNullRecordsForUser(
        mangaId: Int,
        userId: Int,
    ): List<TrackRecordDataClass> = Track.getTrackRecordsByMangaId(mangaId, userId).mapNotNull { it.record }

    @Test
    fun `getTrackRecordsByMangaId does not return records belonging to another user`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()
        val trackerId = TrackerManager.MYANIMELIST
        val remoteA = 101L
        val remoteB = 202L

        insertTrackRecord(userA, mangaId, trackerId, remoteA)
        insertTrackRecord(userB, mangaId, trackerId, remoteB)

        val trackerForUserA = Track.getTrackRecordsByMangaId(mangaId, userA).single { it.id == trackerId }
        val trackerForUserB = Track.getTrackRecordsByMangaId(mangaId, userB).single { it.id == trackerId }

        assertNotNull(trackerForUserA.record)
        assertNotNull(trackerForUserB.record)
        val recordA = requireNotNull(trackerForUserA.record)
        val recordB = requireNotNull(trackerForUserB.record)
        assertEquals(remoteA, recordA.remoteId)
        assertEquals(remoteB, recordB.remoteId)
        assertNotEquals(recordA.remoteId, recordB.remoteId)
    }

    @Test
    fun `direct TrackRecordTable inserts are isolated per user on read`() {
        val userA = makeUser()
        val userB = makeUser()
        val mangaId = makeManga()
        val trackerA = TrackerManager.MYANIMELIST
        val trackerB = TrackerManager.ANILIST
        val remoteA = 303L
        val remoteB = 404L

        insertTrackRecord(userA, mangaId, trackerA, remoteA)
        insertTrackRecord(userB, mangaId, trackerB, remoteB)

        val userARecordsByTracker = nonNullRecordsForUser(mangaId, userA).associateBy { it.trackerId }
        val userBRecordsByTracker = nonNullRecordsForUser(mangaId, userB).associateBy { it.trackerId }

        assertEquals(setOf(trackerA), userARecordsByTracker.keys)
        assertEquals(setOf(trackerB), userBRecordsByTracker.keys)
        assertEquals(remoteA, userARecordsByTracker[trackerA]!!.remoteId)
        assertEquals(remoteB, userBRecordsByTracker[trackerB]!!.remoteId)
        assertNull(userARecordsByTracker[trackerB])
        assertNull(userBRecordsByTracker[trackerA])
    }
}
