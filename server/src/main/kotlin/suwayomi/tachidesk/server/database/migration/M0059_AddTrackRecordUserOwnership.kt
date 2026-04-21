package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0059_AddTrackRecordUserOwnership : SQLMigration() {
    override val sql: String =
        """
        ALTER TABLE TRACKRECORD ADD COLUMN IF NOT EXISTS USER_ID INT DEFAULT NULL;

        DROP INDEX IF EXISTS IDX_TRACKRECORD_USER_ID;
        CREATE INDEX IDX_TRACKRECORD_USER_ID ON TRACKRECORD (USER_ID);

        ALTER TABLE TRACKRECORD DROP CONSTRAINT IF EXISTS FK_TRACKRECORD_USER_ACCOUNT__ID;
        ALTER TABLE TRACKRECORD
            ADD CONSTRAINT FK_TRACKRECORD_USER_ACCOUNT__ID
            FOREIGN KEY (USER_ID) REFERENCES USER_ACCOUNT(ID)
            ON DELETE SET NULL;
        """.trimIndent()
}
