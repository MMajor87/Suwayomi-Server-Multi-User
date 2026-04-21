package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.server.model.table.UserAccountTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest

class JwtUserIdentityTest : ApplicationTest() {
    @Test
    fun `jwt verify resolves the token user id`() {
        val userId = createUser()
        val jwt = Jwt.generateJwt(userId)

        val user = Jwt.verifyJwt(jwt.accessToken)
        assertTrue(user is UserType.Admin)
        assertEquals(userId, (user as UserType.Admin).id)
    }

    @Test
    fun `jwt refresh keeps the same user id`() {
        val userId = createUser()
        val jwt = Jwt.generateJwt(userId)

        val refreshedTokens = Jwt.refreshJwt(jwt.refreshToken)
        val user = Jwt.verifyJwt(refreshedTokens.accessToken)
        assertTrue(user is UserType.Admin)
        assertEquals(userId, (user as UserType.Admin).id)
    }

    @Test
    fun `jwt verify rejects token for deactivated user`() {
        val userId = createUser()
        val jwt = Jwt.generateJwt(userId)

        transaction {
            UserAccountTable.update({ UserAccountTable.id eq userId }) {
                it[isActive] = false
            }
        }

        assertEquals(UserType.Visitor, Jwt.verifyJwt(jwt.accessToken))
    }

    private fun createUser(): Int {
        val timestamp = System.currentTimeMillis()
        val username = "jwt-test-$timestamp-${(1000..9999).random()}"

        return transaction {
            UserAccountTable
                .insertAndGetId {
                    it[UserAccountTable.username] = username
                    it[passwordHash] = "password"
                    it[role] = UserRole.USER.name
                    it[isActive] = true
                    it[tokenVersion] = 1
                    it[createdAt] = timestamp
                    it[updatedAt] = timestamp
                }.value
        }
    }
}
