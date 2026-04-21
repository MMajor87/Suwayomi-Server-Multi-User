package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table

@Suppress("ClassName", "unused")
class M0055_AddUserAccountTable : AddTableMigration() {
    private class UserAccountTable : IntIdTable("user_account") {
        val username = varchar("username", 255).uniqueIndex()
        val passwordHash = varchar("password_hash", 255)
        val role = varchar("role", 32)
        val isActive = bool("is_active").default(true)
        val createdAt = long("created_at").default(0)
        val updatedAt = long("updated_at").default(0)
    }

    override val tables: Array<Table>
        get() =
            arrayOf(
                UserAccountTable(),
            )
}
