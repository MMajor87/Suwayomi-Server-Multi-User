package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0062_BackfillUserMangaLibraryFromLegacyInLibrary : SQLMigration() {
    override val sql: String =
        """
        INSERT INTO USER_MANGA_LIBRARY (USER_ID, MANGA_ID, IN_LIBRARY_AT)
        SELECT
            OWNER.ID,
            M.ID,
            COALESCE(NULLIF(M.IN_LIBRARY_AT, 0), 0)
        FROM MANGA M
        CROSS JOIN (
            SELECT COALESCE(
                (SELECT ID FROM USER_ACCOUNT WHERE ROLE = 'ADMIN' AND IS_ACTIVE = TRUE ORDER BY ID LIMIT 1),
                (SELECT ID FROM USER_ACCOUNT WHERE IS_ACTIVE = TRUE ORDER BY ID LIMIT 1)
            ) AS ID
        ) OWNER
        WHERE M.IN_LIBRARY = TRUE
            AND OWNER.ID IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM USER_MANGA_LIBRARY UML
                WHERE UML.USER_ID = OWNER.ID
                    AND UML.MANGA_ID = M.ID
            );
        """.trimIndent()
}
