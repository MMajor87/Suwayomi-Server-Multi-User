package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0063_AddCategoryMangaUserOwnership : SQLMigration() {
    override val sql: String =
        """
        ALTER TABLE CATEGORYMANGA ADD COLUMN IF NOT EXISTS USER_ID INT DEFAULT NULL;

        UPDATE CATEGORYMANGA
        SET USER_ID = (
            SELECT C.USER_ID
            FROM CATEGORY C
            WHERE C.ID = CATEGORYMANGA.CATEGORY
        )
        WHERE USER_ID IS NULL;

        UPDATE CATEGORYMANGA
        SET USER_ID = (
            SELECT COALESCE(
                (SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1),
                (SELECT ID FROM USER_ACCOUNT WHERE IS_ACTIVE = TRUE ORDER BY ID LIMIT 1)
            )
        )
        WHERE USER_ID IS NULL;

        DELETE FROM CATEGORYMANGA CM
        WHERE CM.ID NOT IN (
            SELECT MIN(DEDUP.ID)
            FROM CATEGORYMANGA DEDUP
            GROUP BY DEDUP.USER_ID, DEDUP.CATEGORY, DEDUP.MANGA
        );

        CREATE INDEX IF NOT EXISTS IDX_CATEGORY_MANGA_USER_ID ON CATEGORYMANGA (USER_ID);
        CREATE INDEX IF NOT EXISTS IDX_CATEGORY_MANGA_USER_CATEGORY ON CATEGORYMANGA (USER_ID, CATEGORY);
        CREATE INDEX IF NOT EXISTS IDX_CATEGORY_MANGA_USER_MANGA ON CATEGORYMANGA (USER_ID, MANGA);
        CREATE UNIQUE INDEX IF NOT EXISTS UQ_CATEGORY_MANGA_USER_CATEGORY_MANGA
            ON CATEGORYMANGA (USER_ID, CATEGORY, MANGA);
        """.trimIndent()
}
