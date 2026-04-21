package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ExperimentalKeywordApi
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class LegacySnapshotMigrationTest : ApplicationTest() {
    @Test
    fun `legacy pre-multi-user snapshot migrates and backfills ownership correctly`() {
        val db = createIsolatedDatabase()
        val allMigrations = loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java)

        runMigrations(allMigrations.filter { migrationVersion(it) <= 54 }, db)
        applySqlFixture(db, "fixtures/pre-multi-user/legacy_full_snapshot.sql")
        runMigrations(allMigrations, db)

        val seededAdminId =
            scalarInt(
                db,
                "SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1",
            )
        assertTrue(seededAdminId > 0, "Expected migrations to seed at least one active admin user")
        assertEquals(
            1,
            scalarInt(db, "SELECT COUNT(*) FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE"),
            "Expected exactly one active admin seeded for this legacy snapshot",
        )

        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM CATEGORY WHERE USER_ID IS NULL"))
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM TRACKRECORD WHERE USER_ID IS NULL"))

        assertEquals(
            1,
            scalarInt(db, "SELECT COUNT(*) FROM USER_MANGA_LIBRARY WHERE USER_ID = $seededAdminId AND MANGA_ID = 100"),
            "in_library=true manga should backfill into USER_MANGA_LIBRARY for seeded admin",
        )
        assertEquals(
            0,
            scalarInt(db, "SELECT COUNT(*) FROM USER_MANGA_LIBRARY WHERE USER_ID = $seededAdminId AND MANGA_ID = 101"),
            "in_library=false manga should not backfill into USER_MANGA_LIBRARY",
        )

        assertEquals(
            1,
            scalarInt(db, "SELECT COUNT(*) FROM USER_CHAPTER WHERE USER_ID = $seededAdminId AND CHAPTER_ID = 300"),
            "legacy chapter progress should backfill into USER_CHAPTER",
        )
        assertEquals(
            0,
            scalarInt(db, "SELECT COUNT(*) FROM USER_CHAPTER WHERE USER_ID = $seededAdminId AND CHAPTER_ID = 301"),
            "chapter without legacy progress should not create a USER_CHAPTER row",
        )

        assertEquals(
            1,
            scalarInt(db, "SELECT COUNT(*) FROM CATEGORYMANGA WHERE CATEGORY = 200 AND MANGA = 100 AND USER_ID = $seededAdminId"),
            "CATEGORYMANGA rows should inherit ownership during migration",
        )
        assertEquals(
            0,
            scalarInt(
                db,
                """
                SELECT COUNT(*)
                FROM CATEGORYMANGA CM
                JOIN CATEGORY C ON C.ID = CM.CATEGORY
                WHERE COALESCE(CM.USER_ID, -1) <> COALESCE(C.USER_ID, -1)
                """.trimIndent(),
            ),
            "CATEGORYMANGA ownership should match CATEGORY ownership after migration",
        )

        assertEquals(
            2,
            scalarInt(db, "SELECT COUNT(*) FROM TRACKRECORD"),
            "Duplicate legacy tracker rows should be deduplicated by (user_id, manga_id, sync_id)",
        )
        assertEquals(
            0,
            scalarInt(
                db,
                """
                SELECT COUNT(*)
                FROM (
                    SELECT USER_ID, MANGA_ID, SYNC_ID, COUNT(*) AS C
                    FROM TRACKRECORD
                    GROUP BY USER_ID, MANGA_ID, SYNC_ID
                    HAVING COUNT(*) > 1
                ) D
                """.trimIndent(),
            ),
            "No duplicate (user_id, manga_id, sync_id) tracker tuples should remain",
        )
    }

    private fun createIsolatedDatabase(): Database {
        val jdbcUrl = "jdbc:h2:mem:legacy_snapshot_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;"
        return Database.connect(
            jdbcUrl,
            "org.h2.Driver",
            databaseConfig =
                DatabaseConfig {
                    useNestedTransactions = true
                    @OptIn(ExperimentalKeywordApi::class)
                    preserveKeywordCasing = false
                },
        )
    }

    private fun applySqlFixture(
        db: Database,
        resourcePath: String,
    ) {
        val sql =
            javaClass.classLoader.getResourceAsStream(resourcePath)?.bufferedReader()?.use { it.readText() }
                ?: error("Fixture not found: $resourcePath")

        val statements =
            sql
                .lineSequence()
                .map { line -> line.substringBefore("--").trim() }
                .joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        transaction(db) {
            statements.forEach { statement ->
                exec(statement)
            }
        }
    }

    private fun scalarInt(
        db: Database,
        sql: String,
    ): Int =
        transaction(db) {
            exec(sql) { resultSet ->
                if (resultSet.next()) {
                    resultSet.getInt(1)
                } else {
                    0
                }
            } ?: 0
        }

    private fun migrationVersion(migration: Any): Int {
        val name = migration.javaClass.simpleName
        val match = Regex("""^M(\d{4})_""").find(name) ?: return Int.MAX_VALUE
        return match.groupValues[1].toInt()
    }
}
