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

class UserOwnershipSchemaMigrationTest : ApplicationTest() {
    @Test
    fun `category has user ownership column index and foreign key`() {
        assertTrue(hasColumn("CATEGORY", "USER_ID"))
        assertTrue(hasIndex("CATEGORY", "IDX_CATEGORY_USER_ID"))
        assertTrue(hasForeignKey("CATEGORY", "FK_CATEGORY_USER_ACCOUNT__ID"))
    }

    @Test
    fun `track record has user ownership column index and foreign key`() {
        assertTrue(hasColumn("TRACKRECORD", "USER_ID"))
        assertTrue(hasIndex("TRACKRECORD", "IDX_TRACKRECORD_USER_ID"))
        assertTrue(hasForeignKey("TRACKRECORD", "FK_TRACKRECORD_USER_ACCOUNT__ID"))
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

    private fun hasForeignKey(
        tableName: String,
        constraintName: String,
    ): Boolean =
        transaction {
            InformationSchemaConstraints
                .selectAll()
                .where {
                    (InformationSchemaConstraints.tableNameCol eq tableName.uppercase()) and
                        (InformationSchemaConstraints.constraintName eq constraintName.uppercase())
                }.count() > 0
        }

    private object InformationSchemaColumns : Table("INFORMATION_SCHEMA.COLUMNS") {
        val tableNameCol = varchar("TABLE_NAME", 255)
        val columnName = varchar("COLUMN_NAME", 255)
    }

    private object InformationSchemaIndexes : Table("INFORMATION_SCHEMA.INDEXES") {
        val tableNameCol = varchar("TABLE_NAME", 255)
        val indexName = varchar("INDEX_NAME", 255)
    }

    private object InformationSchemaConstraints : Table("INFORMATION_SCHEMA.TABLE_CONSTRAINTS") {
        val tableNameCol = varchar("TABLE_NAME", 255)
        val constraintName = varchar("CONSTRAINT_NAME", 255)
    }
}
