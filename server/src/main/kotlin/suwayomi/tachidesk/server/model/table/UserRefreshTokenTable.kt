package suwayomi.tachidesk.server.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object UserRefreshTokenTable : Table("user_refresh_token") {
    val jti = varchar("jti", 128)
    val userId = reference("user_id", UserAccountTable, onDelete = ReferenceOption.CASCADE)
    val tokenVersion = integer("token_version")
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at")
    val rotatedAt = long("rotated_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    val replacementJti = varchar("replacement_jti", 128).nullable()

    override val primaryKey = PrimaryKey(jti)
}
