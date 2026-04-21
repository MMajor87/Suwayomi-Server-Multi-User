package suwayomi.tachidesk.server.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable

object UserAccountTable : IntIdTable("user_account") {
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 32)
    val isActive = bool("is_active").default(true)
    val tokenVersion = integer("token_version").default(1)
    val createdAt = long("created_at").default(0)
    val updatedAt = long("updated_at").default(0)
}

enum class UserRole {
    ADMIN,
    USER,
}
