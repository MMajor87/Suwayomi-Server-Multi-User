package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.server.model.table.UserAccountTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.test.ApplicationTest

class UserAccountBootstrapMigrationTest : ApplicationTest() {
    @Test
    fun `bootstrap migration seeds at least one active admin user`() {
        val activeAdminCount =
            transaction {
                UserAccountTable
                    .selectAll()
                    .where {
                        (UserAccountTable.role eq UserRole.ADMIN.name) and
                            (UserAccountTable.isActive eq true)
                    }.count()
            }

        assertTrue(activeAdminCount > 0)
    }
}
