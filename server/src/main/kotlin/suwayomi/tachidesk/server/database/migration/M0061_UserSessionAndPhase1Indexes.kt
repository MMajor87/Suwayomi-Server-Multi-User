package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0061_UserSessionAndPhase1Indexes : SQLMigration() {
    override val sql: String =
        """
        ALTER TABLE USER_ACCOUNT ADD COLUMN IF NOT EXISTS TOKEN_VERSION INT DEFAULT 1 NOT NULL;

        CREATE TABLE IF NOT EXISTS USER_REFRESH_TOKEN (
            JTI VARCHAR(128) PRIMARY KEY,
            USER_ID INT NOT NULL,
            TOKEN_VERSION INT NOT NULL,
            ISSUED_AT BIGINT NOT NULL,
            EXPIRES_AT BIGINT NOT NULL,
            ROTATED_AT BIGINT DEFAULT NULL,
            REVOKED_AT BIGINT DEFAULT NULL,
            REPLACEMENT_JTI VARCHAR(128) DEFAULT NULL,
            CONSTRAINT FK_USER_REFRESH_TOKEN_USER_ACCOUNT__ID
                FOREIGN KEY (USER_ID) REFERENCES USER_ACCOUNT(ID)
                ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS IDX_USER_ACCOUNT_ROLE_IS_ACTIVE ON USER_ACCOUNT (ROLE, IS_ACTIVE);
        CREATE INDEX IF NOT EXISTS IDX_USER_ACCOUNT_IS_ACTIVE ON USER_ACCOUNT (IS_ACTIVE);
        CREATE INDEX IF NOT EXISTS IDX_USER_REFRESH_TOKEN_USER_ID ON USER_REFRESH_TOKEN (USER_ID);
        CREATE INDEX IF NOT EXISTS IDX_USER_REFRESH_TOKEN_EXPIRES_AT ON USER_REFRESH_TOKEN (EXPIRES_AT);
        CREATE INDEX IF NOT EXISTS IDX_CATEGORY_USER_ID_NAME ON CATEGORY (USER_ID, NAME);
        CREATE INDEX IF NOT EXISTS IDX_TRACKRECORD_USER_ID_MANGA_ID_SYNC_ID ON TRACKRECORD (USER_ID, MANGA_ID, SYNC_ID);
        """.trimIndent()
}
