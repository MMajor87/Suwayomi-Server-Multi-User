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

class MigrationRestartSafetyTest : ApplicationTest() {
    @Test
    fun `phase1 ownership migrations recover from partial apply on restart`() {
        val db = createIsolatedDatabase()
        val allMigrations = loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java)

        runMigrations(allMigrations.filter { migrationVersion(it) <= 57 }, db)
        applySqlFixture(db, "fixtures/pre-multi-user/legacy_full_snapshot.sql")

        // Simulate a crash after the first statement of M0058 and M0059 was applied.
        transaction(db) {
            exec("ALTER TABLE CATEGORY ADD COLUMN USER_ID INT DEFAULT NULL")
            exec("ALTER TABLE TRACKRECORD ADD COLUMN USER_ID INT DEFAULT NULL")
        }

        runMigrations(allMigrations, db)

        val seededAdminId =
            scalarInt(
                db,
                "SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1",
            )
        assertTrue(seededAdminId > 0, "Expected restart run to preserve/create an active admin user")
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM CATEGORY WHERE USER_ID IS NULL"))
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM TRACKRECORD WHERE USER_ID IS NULL"))

        assertTrue(hasIndex(db, "CATEGORY", "IDX_CATEGORY_USER_ID"))
        assertTrue(hasIndex(db, "TRACKRECORD", "IDX_TRACKRECORD_USER_ID"))
        assertTrue(hasForeignKey(db, "CATEGORY", "FK_CATEGORY_USER_ACCOUNT__ID"))
        assertTrue(hasForeignKey(db, "TRACKRECORD", "FK_TRACKRECORD_USER_ACCOUNT__ID"))
    }

    private fun createIsolatedDatabase(): Database {
        val jdbcUrl = "jdbc:h2:mem:migration_restart_safety_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;"
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

    private fun hasIndex(
        db: Database,
        tableName: String,
        indexName: String,
    ): Boolean =
        scalarInt(
            db,
            """
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.INDEXES
            WHERE TABLE_NAME = '${tableName.uppercase()}'
                AND INDEX_NAME = '${indexName.uppercase()}'
            """.trimIndent(),
        ) > 0

    private fun hasForeignKey(
        db: Database,
        tableName: String,
        constraintName: String,
    ): Boolean =
        scalarInt(
            db,
            """
            SELECT COUNT(*)
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
            WHERE TABLE_NAME = '${tableName.uppercase()}'
                AND CONSTRAINT_NAME = '${constraintName.uppercase()}'
            """.trimIndent(),
        ) > 0

    private fun migrationVersion(migration: Any): Int {
        val name = migration.javaClass.simpleName
        val match = Regex("""^M(\d{4})_""").find(name) ?: return Int.MAX_VALUE
        return match.groupValues[1].toInt()
    }
}
