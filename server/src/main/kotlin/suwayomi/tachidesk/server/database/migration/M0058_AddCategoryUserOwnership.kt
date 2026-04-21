package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0058_AddCategoryUserOwnership : SQLMigration() {
    override val sql: String =
        """
        ALTER TABLE CATEGORY ADD COLUMN IF NOT EXISTS USER_ID INT DEFAULT NULL;

        DROP INDEX IF EXISTS IDX_CATEGORY_USER_ID;
        CREATE INDEX IDX_CATEGORY_USER_ID ON CATEGORY (USER_ID);

        ALTER TABLE CATEGORY DROP CONSTRAINT IF EXISTS FK_CATEGORY_USER_ACCOUNT__ID;
        ALTER TABLE CATEGORY
            ADD CONSTRAINT FK_CATEGORY_USER_ACCOUNT__ID
            FOREIGN KEY (USER_ID) REFERENCES USER_ACCOUNT(ID)
            ON DELETE SET NULL;
        """.trimIndent()
}
