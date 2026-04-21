package suwayomi.tachidesk.manga.impl.track.tracker

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import suwayomi.tachidesk.test.ApplicationTest
import java.util.concurrent.atomic.AtomicInteger

class TrackerSecretIsolationTest : ApplicationTest() {
    companion object {
        // Avoids collisions with production tracker IDs (1-9) or other test classes.
        private val trackerIdSeq = AtomicInteger(98_000)
    }

    private fun nextTrackerId() = trackerIdSeq.incrementAndGet()

    private fun makeTestTracker(
        trackerId: Int,
        userId: Int?,
    ): Tracker =
        object : Tracker(trackerId, "TestTracker-$trackerId", userId) {
            override fun getLogo() = ""

            override fun getStatusList() = emptyList<Int>()

            override fun getStatus(status: Int): String? = null

            override fun getReadingStatus() = 0

            override fun getRereadingStatus() = 0

            override fun getCompletionStatus() = 0

            override fun getScoreList() = emptyList<String>()

            override fun displayScore(track: Track) = ""

            override suspend fun update(
                track: Track,
                didReadChapter: Boolean,
            ) = track

            override suspend fun bind(
                track: Track,
                hasReadChapters: Boolean,
            ) = track

            override suspend fun search(query: String) = emptyList<TrackSearch>()

            override suspend fun refresh(track: Track) = track

            override suspend fun login(
                username: String,
                password: String,
            ) = Unit
        }

    @Test
    fun `setTrackCredentials writes to isolated keys per userId`() {
        val tid = nextTrackerId()
        val trackerA = makeTestTracker(tid, userId = 1001)
        val trackerB = makeTestTracker(tid, userId = 1002)

        TrackerPreferences.setTrackCredentials(trackerA, "alice", "passA1!")
        TrackerPreferences.setTrackCredentials(trackerB, "bob", "passB2!")

        assertEquals("alice", trackerA.getUsername())
        assertEquals("bob", trackerB.getUsername())
        assertNotEquals(trackerA.getUsername(), trackerB.getUsername())
    }

    @Test
    fun `getTrackPassword is isolated per userId`() {
        val tid = nextTrackerId()
        val trackerA = makeTestTracker(tid, userId = 2001)
        val trackerB = makeTestTracker(tid, userId = 2002)

        TrackerPreferences.setTrackCredentials(trackerA, "userA", "secretA9!")
        TrackerPreferences.setTrackCredentials(trackerB, "userB", "secretB8!")

        assertEquals("secretA9!", trackerA.getPassword())
        assertEquals("secretB8!", trackerB.getPassword())
        assertNotEquals(trackerA.getPassword(), trackerB.getPassword())
    }

    @Test
    fun `setTrackToken is isolated per userId`() {
        val tid = nextTrackerId()
        val trackerA = makeTestTracker(tid, userId = 3001)
        val trackerB = makeTestTracker(tid, userId = 3002)

        TrackerPreferences.setTrackToken(trackerA, "token-for-A")
        TrackerPreferences.setTrackToken(trackerB, "token-for-B")

        assertEquals("token-for-A", TrackerPreferences.getTrackToken(trackerA))
        assertEquals("token-for-B", TrackerPreferences.getTrackToken(trackerB))
        assertNotEquals(
            TrackerPreferences.getTrackToken(trackerA),
            TrackerPreferences.getTrackToken(trackerB),
        )
    }

    @Test
    fun `null userId writes to legacy key separate from user-scoped key`() {
        val tid = nextTrackerId()
        val legacyTracker = makeTestTracker(tid, userId = null)
        val userTracker = makeTestTracker(tid, userId = 4001)

        TrackerPreferences.setTrackCredentials(legacyTracker, "legacyUser", "legacyPass1!")
        TrackerPreferences.setTrackCredentials(userTracker, "scopedUser", "scopedPass2!")

        // Each reads its own distinct value
        assertEquals("legacyUser", legacyTracker.getUsername())
        assertEquals("scopedUser", userTracker.getUsername())
    }

    @Test
    fun `readString falls back to legacy key when no user-scoped key exists`() {
        val tid = nextTrackerId()
        val legacyTracker = makeTestTracker(tid, userId = null)
        // userId=5001 has no explicit scoped key set — should fall back to legacy
        val newUserTracker = makeTestTracker(tid, userId = 5001)

        TrackerPreferences.setTrackCredentials(legacyTracker, "legacyFallbackUser", "legacyFallbackPass1!")

        // No setTrackCredentials called for userId=5001 — expect fallback to legacy value
        assertEquals("legacyFallbackUser", newUserTracker.getUsername())
    }

    @Test
    fun `migrateLegacySecretsToUser copies legacy credentials to user-scoped keys`() {
        val tid = nextTrackerId()
        val legacyTracker = makeTestTracker(tid, userId = null)
        val userId = 6001

        TrackerPreferences.setTrackCredentials(legacyTracker, "migratedUser", "migratedPass9!")
        TrackerPreferences.setTrackToken(legacyTracker, "migratedToken")

        val count = TrackerPreferences.migrateLegacySecretsToUser(userId)

        assertTrue(count >= 2, "Expected at least username and token keys to be migrated, got $count")

        val migratedTracker = makeTestTracker(tid, userId = userId)
        assertEquals("migratedUser", migratedTracker.getUsername())
        assertEquals("migratedToken", TrackerPreferences.getTrackToken(migratedTracker))
    }

    @Test
    fun `migrateLegacySecretsToUser does not overwrite an existing user-scoped key`() {
        val tid = nextTrackerId()
        val legacyTracker = makeTestTracker(tid, userId = null)
        val userId = 7001
        val existingTracker = makeTestTracker(tid, userId = userId)

        // Pre-set both legacy and user-scoped credentials
        TrackerPreferences.setTrackCredentials(legacyTracker, "legacyValue", "legacyPwd1!")
        TrackerPreferences.setTrackCredentials(existingTracker, "existingValue", "existingPwd2!")

        TrackerPreferences.migrateLegacySecretsToUser(userId)

        // User-scoped key must NOT be overwritten by the migration
        assertEquals("existingValue", existingTracker.getUsername())
    }
}
