package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationBootstrapTest : ApplicationTest() {
    @Test
    fun `auth mode none migrates to UI_LOGIN and writes bootstrap version`() {
        val bootstrapPrefs = bootstrapPreferences()
        val baseline = captureBaseline(bootstrapPrefs)

        try {
            bootstrapPrefs.edit().remove("version").commit()

            serverConfig.multiUserBootstrapEnabled.value = true
            serverConfig.authModeNoneMigrationTarget.value = AuthMode.UI_LOGIN
            serverConfig.authMode.value = AuthMode.NONE

            runMigrations(Injekt.get<ApplicationDirs>())

            assertEquals(AuthMode.UI_LOGIN, serverConfig.authMode.value)
            assertTrue(bootstrapPrefs.contains("version"))
            assertEquals(1, bootstrapPrefs.getInt("version", 0))
        } finally {
            restoreBaseline(bootstrapPrefs, baseline)
        }
    }

    @Test
    fun `auth mode none migrates to SIMPLE_LOGIN when configured`() {
        val bootstrapPrefs = bootstrapPreferences()
        val baseline = captureBaseline(bootstrapPrefs)

        try {
            bootstrapPrefs.edit().remove("version").commit()

            serverConfig.multiUserBootstrapEnabled.value = true
            serverConfig.authModeNoneMigrationTarget.value = AuthMode.SIMPLE_LOGIN
            serverConfig.authMode.value = AuthMode.NONE

            runMigrations(Injekt.get<ApplicationDirs>())

            assertEquals(AuthMode.SIMPLE_LOGIN, serverConfig.authMode.value)
            assertEquals(1, bootstrapPrefs.getInt("version", 0))
        } finally {
            restoreBaseline(bootstrapPrefs, baseline)
        }
    }

    @Test
    fun `invalid migration target falls back to UI_LOGIN`() {
        val bootstrapPrefs = bootstrapPreferences()
        val baseline = captureBaseline(bootstrapPrefs)

        try {
            bootstrapPrefs.edit().remove("version").commit()

            serverConfig.multiUserBootstrapEnabled.value = true
            serverConfig.authModeNoneMigrationTarget.value = AuthMode.BASIC_AUTH
            serverConfig.authMode.value = AuthMode.NONE

            runMigrations(Injekt.get<ApplicationDirs>())

            assertEquals(AuthMode.UI_LOGIN, serverConfig.authMode.value)
            assertEquals(1, bootstrapPrefs.getInt("version", 0))
        } finally {
            restoreBaseline(bootstrapPrefs, baseline)
        }
    }

    @Test
    fun `bootstrap gate disabled does not migrate auth mode or write version`() {
        val bootstrapPrefs = bootstrapPreferences()
        val baseline = captureBaseline(bootstrapPrefs)

        try {
            bootstrapPrefs.edit().remove("version").commit()

            serverConfig.multiUserBootstrapEnabled.value = false
            serverConfig.authModeNoneMigrationTarget.value = AuthMode.UI_LOGIN
            serverConfig.authMode.value = AuthMode.NONE

            runMigrations(Injekt.get<ApplicationDirs>())

            assertEquals(AuthMode.NONE, serverConfig.authMode.value)
            assertFalse(bootstrapPrefs.contains("version"))
        } finally {
            restoreBaseline(bootstrapPrefs, baseline)
        }
    }

    private fun bootstrapPreferences() =
        Injekt
            .get<Application>()
            .getSharedPreferences(
                "multi_user_bootstrap",
                Context.MODE_PRIVATE,
            )

    private fun captureBaseline(bootstrapPrefs: android.content.SharedPreferences): BaselineState =
        BaselineState(
            authMode = serverConfig.authMode.value,
            migrationTarget = serverConfig.authModeNoneMigrationTarget.value,
            bootstrapEnabled = serverConfig.multiUserBootstrapEnabled.value,
            hadBootstrapVersion = bootstrapPrefs.contains("version"),
            bootstrapVersion = bootstrapPrefs.getInt("version", 0),
        )

    private fun restoreBaseline(
        bootstrapPrefs: android.content.SharedPreferences,
        baseline: BaselineState,
    ) {
        serverConfig.authMode.value = baseline.authMode
        serverConfig.authModeNoneMigrationTarget.value = baseline.migrationTarget
        serverConfig.multiUserBootstrapEnabled.value = baseline.bootstrapEnabled

        bootstrapPrefs
            .edit()
            .apply {
                if (baseline.hadBootstrapVersion) {
                    putInt("version", baseline.bootstrapVersion)
                } else {
                    remove("version")
                }
            }.commit()
    }

    private data class BaselineState(
        val authMode: AuthMode,
        val migrationTarget: AuthMode,
        val bootstrapEnabled: Boolean,
        val hadBootstrapVersion: Boolean,
        val bootstrapVersion: Int,
    )
}
