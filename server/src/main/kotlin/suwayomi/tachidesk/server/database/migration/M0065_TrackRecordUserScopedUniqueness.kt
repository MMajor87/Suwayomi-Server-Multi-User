package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0065_TrackRecordUserScopedUniqueness : SQLMigration() {
    override val sql: String =
        """
        UPDATE TRACKRECORD
        SET USER_ID = (
            SELECT COALESCE(
                (SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1),
                (SELECT ID FROM USER_ACCOUNT WHERE IS_ACTIVE = TRUE ORDER BY ID LIMIT 1)
            )
        )
        WHERE USER_ID IS NULL
            AND (
                SELECT COALESCE(
                    (SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1),
                    (SELECT ID FROM USER_ACCOUNT WHERE IS_ACTIVE = TRUE ORDER BY ID LIMIT 1)
                )
            ) IS NOT NULL;

        DELETE FROM TRACKRECORD DEDUP
        WHERE DEDUP.ID NOT IN (
            SELECT MAX(KEEP.ID)
            FROM TRACKRECORD KEEP
            GROUP BY KEEP.USER_ID, KEEP.MANGA_ID, KEEP.SYNC_ID
        );

        CREATE INDEX IF NOT EXISTS IDX_TRACKRECORD_USER_ID_SYNC_ID ON TRACKRECORD (USER_ID, SYNC_ID);
        CREATE UNIQUE INDEX IF NOT EXISTS UQ_TRACKRECORD_USER_MANGA_SYNC_ID
            ON TRACKRECORD (USER_ID, MANGA_ID, SYNC_ID);
        """.trimIndent()
}
