package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest

class UserSessionSchemaMigrationTest : ApplicationTest() {
    @Test
    fun `user account has token version column`() {
        assertTrue(hasColumn("USER_ACCOUNT", "TOKEN_VERSION"))
    }

    @Test
    fun `user refresh token table and indexes exist`() {
        assertTrue(hasTable("USER_REFRESH_TOKEN"))
        assertTrue(hasIndex("USER_REFRESH_TOKEN", "IDX_USER_REFRESH_TOKEN_USER_ID"))
        assertTrue(hasIndex("USER_REFRESH_TOKEN", "IDX_USER_REFRESH_TOKEN_EXPIRES_AT"))
    }

    private fun hasColumn(
        tableName: String,
        columnName: String,
    ): Boolean =
        transaction {
            InformationSchemaColumns
                .selectAll()
                .where {
                    (InformationSchemaColumns.tableNameCol eq tableName.uppercase()) and
                        (InformationSchemaColumns.columnName eq columnName.uppercase())
                }.count() > 0
        }

    private fun hasTable(tableName: String): Boolean =
        transaction {
            InformationSchemaTables
                .selectAll()
                .where { InformationSchemaTables.tableNameCol eq tableName.uppercase() }
                .count() > 0
        }

    private fun hasIndex(
        tableName: String,
        indexName: String,
    ): Boolean =
        transaction {
            InformationSchemaIndexes
                .selectAll()
                .where {
                    (InformationSchemaIndexes.tableNameCol eq tableName.uppercase()) and
                        (InformationSchemaIndexes.indexName eq indexName.uppercase())
                }.count() > 0
        }

    private object InformationSchemaColumns : Table("INFORMATION_SCHEMA.COLUMNS") {
        val tableNameCol = varchar("TABLE_NAME", 255)
        val columnName = varchar("COLUMN_NAME", 255)
    }

    private object InformationSchemaTables : Table("INFORMATION_SCHEMA.TABLES") {
        val tableNameCol = varchar("TABLE_NAME", 255)
    }

    private object InformationSchemaIndexes : Table("INFORMATION_SCHEMA.INDEXES") {
        val tableNameCol = varchar("TABLE_NAME", 255)
        val indexName = varchar("INDEX_NAME", 255)
    }
}
