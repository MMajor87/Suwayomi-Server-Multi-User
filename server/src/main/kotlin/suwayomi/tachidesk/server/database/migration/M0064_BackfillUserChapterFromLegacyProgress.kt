package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0064_BackfillUserChapterFromLegacyProgress : SQLMigration() {
    override val sql: String =
        """
        INSERT INTO USER_CHAPTER (USER_ID, CHAPTER_ID, READ, BOOKMARK, LAST_PAGE_READ, LAST_READ_AT, UPDATED_AT)
        SELECT
            OWNER.ID,
            C.ID,
            C.READ,
            C.BOOKMARK,
            C.LAST_PAGE_READ,
            C.LAST_READ_AT,
            CASE
                WHEN C.LAST_READ_AT > 0 THEN C.LAST_READ_AT
                ELSE 0
            END
        FROM CHAPTER C
        CROSS JOIN (
            SELECT COALESCE(
                (SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1),
                (SELECT ID FROM USER_ACCOUNT WHERE IS_ACTIVE = TRUE ORDER BY ID LIMIT 1)
            ) AS ID
        ) OWNER
        WHERE OWNER.ID IS NOT NULL
            AND (
                C.READ = TRUE
                OR C.BOOKMARK = TRUE
                OR C.LAST_PAGE_READ > 0
                OR C.LAST_READ_AT > 0
            )
            AND NOT EXISTS (
                SELECT 1
                FROM USER_CHAPTER UC
                WHERE UC.USER_ID = OWNER.ID
                    AND UC.CHAPTER_ID = C.ID
            );
        """.trimIndent()
}
