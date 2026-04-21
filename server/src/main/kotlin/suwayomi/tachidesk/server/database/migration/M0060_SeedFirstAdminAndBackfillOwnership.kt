package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.PasswordSecurity

@Suppress("ClassName", "unused")
class M0060_SeedFirstAdminAndBackfillOwnership : SQLMigration() {
    override val sql: String
        get() {
            val seedUsername = serverConfig.authUsername.value.ifBlank { "admin" }.escapeSql()
            val seedPasswordHash =
                PasswordSecurity
                    .hashPassword(serverConfig.authPassword.value.ifBlank { "admin" })
                    .escapeSql()
            val now = System.currentTimeMillis()

            return """
                INSERT INTO USER_ACCOUNT (USERNAME, PASSWORD_HASH, ROLE, IS_ACTIVE, CREATED_AT, UPDATED_AT)
                SELECT '$seedUsername', '$seedPasswordHash', 'ADMIN', TRUE, $now, $now
                WHERE NOT EXISTS (SELECT 1 FROM USER_ACCOUNT);

                UPDATE CATEGORY
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
                """.trimIndent()
        }

    private fun String.escapeSql(): String = replace("'", "''")
}
