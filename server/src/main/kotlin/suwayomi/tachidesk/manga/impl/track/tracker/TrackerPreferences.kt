package suwayomi.tachidesk.manga.impl.track.tracker

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.manga.impl.track.tracker.anilist.Anilist
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TrackerPreferences {
    private val preferenceStore =
        Injekt.get<Application>().getSharedPreferences("tracker", Context.MODE_PRIVATE)
    private val logger = KotlinLogging.logger {}

    fun getTrackUsername(sync: Tracker) =
        readString(
            legacyKey = trackUsername(sync.id),
            userId = sync.userId,
        )

    fun getTrackPassword(sync: Tracker) =
        readString(
            legacyKey = trackPassword(sync.id),
            userId = sync.userId,
        )

    fun trackAuthExpired(tracker: Tracker) =
        readBoolean(
            legacyKey = trackTokenExpired(tracker.id),
            userId = tracker.userId,
        )

    fun setTrackCredentials(
        sync: Tracker,
        username: String,
        password: String,
    ) {
        val usernameKey = scopedKey(trackUsername(sync.id), sync.userId)
        val passwordKey = scopedKey(trackPassword(sync.id), sync.userId)
        val tokenExpiredKey = scopedKey(trackTokenExpired(sync.id), sync.userId)
        preferenceStore
            .edit()
            .putString(usernameKey, username)
            .putString(passwordKey, password)
            .putBoolean(tokenExpiredKey, false)
            .apply()
    }

    fun getTrackToken(sync: Tracker) =
        readString(
            legacyKey = trackToken(sync.id),
            userId = sync.userId,
        )

    fun setTrackToken(
        sync: Tracker,
        token: String?,
    ) {
        val tokenKey = scopedKey(trackToken(sync.id), sync.userId)
        val tokenExpiredKey = scopedKey(trackTokenExpired(sync.id), sync.userId)
        if (token == null) {
            preferenceStore
                .edit()
                .remove(tokenKey)
                .putBoolean(tokenExpiredKey, false)
                .apply()
        } else {
            preferenceStore
                .edit()
                .putString(tokenKey, token)
                .putBoolean(tokenExpiredKey, false)
                .apply()
        }
    }

    fun setTrackTokenExpired(sync: Tracker) {
        val tokenExpiredKey = scopedKey(trackTokenExpired(sync.id), sync.userId)
        preferenceStore
            .edit()
            .putBoolean(tokenExpiredKey, true)
            .apply()
    }

    fun getScoreType(sync: Tracker) =
        readString(
            legacyKey = scoreType(sync.id),
            userId = sync.userId,
            defaultValue = Anilist.POINT_10,
        )

    fun setScoreType(
        sync: Tracker,
        scoreType: String,
    ) = preferenceStore
        .edit()
        .putString(scopedKey(this.scoreType(sync.id), sync.userId), scoreType)
        .apply()

    fun migrateLegacySecretsToUser(
        userId: Int,
    ): Int {
        val edits = preferenceStore.edit()
        var migratedKeys = 0
        preferenceStore.all.forEach { (key, value) ->
            if (!isLegacyTrackerSecretKey(key)) {
                return@forEach
            }
            val scopedKey = scopedKey(key, userId)
            if (preferenceStore.contains(scopedKey)) {
                return@forEach
            }

            when (value) {
                is String -> edits.putString(scopedKey, value)
                is Boolean -> edits.putBoolean(scopedKey, value)
                else -> {
                    logger.debug { "Skipping unsupported tracker secret value type for key '$key'" }
                    return@forEach
                }
            }
            migratedKeys++
        }

        if (migratedKeys > 0) {
            edits.apply()
        }
        return migratedKeys
    }

    fun autoUpdateTrack() = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    fun trackUsername(trackerId: Int) = "pref_mangasync_username_$trackerId"

    private fun trackPassword(trackerId: Int) = "pref_mangasync_password_$trackerId"

    private fun trackToken(trackerId: Int) = "track_token_$trackerId"

    private fun trackTokenExpired(trackerId: Int) = "track_token_expired_$trackerId"

    private fun scoreType(trackerId: Int) = "score_type_$trackerId"

    private fun readString(
        legacyKey: String,
        userId: Int?,
        defaultValue: String = "",
    ): String? {
        val scopedKey = scopedKey(legacyKey, userId)
        return if (preferenceStore.contains(scopedKey)) {
            preferenceStore.getString(scopedKey, defaultValue)
        } else {
            preferenceStore.getString(legacyKey, defaultValue)
        }
    }

    private fun readBoolean(
        legacyKey: String,
        userId: Int?,
        defaultValue: Boolean = false,
    ): Boolean {
        val scopedKey = scopedKey(legacyKey, userId)
        return if (preferenceStore.contains(scopedKey)) {
            preferenceStore.getBoolean(scopedKey, defaultValue)
        } else {
            preferenceStore.getBoolean(legacyKey, defaultValue)
        }
    }

    private fun scopedKey(
        legacyKey: String,
        userId: Int?,
    ): String =
        if (userId == null) {
            legacyKey
        } else {
            "${legacyKey}__u$userId"
        }

    private fun isLegacyTrackerSecretKey(key: String): Boolean =
        !key.contains("__u") &&
            (
                key.startsWith("pref_mangasync_username_") ||
                    key.startsWith("pref_mangasync_password_") ||
                    key.startsWith("track_token_") ||
                    key.startsWith("track_token_expired_") ||
                    key.startsWith("score_type_")
            )
}
