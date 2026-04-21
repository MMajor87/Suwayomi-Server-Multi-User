package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.auth0.jwt.JWT as JwtDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.server.model.table.UserAccountTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.authenticateUser
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.server.user.invalidateUserSessions
import suwayomi.tachidesk.server.user.revokeRefreshTokenJti
import suwayomi.tachidesk.test.ApplicationTest

class UserAccountAuthPhase1Test : ApplicationTest() {
    @Test
    fun `create user stores hashed password and authenticates`() {
        val username = "phase1-user-${System.currentTimeMillis()}"
        val password = "Password1234"
        val created = createUserAccount(username, password, UserRole.USER, isActive = true)

        val rowPasswordHash =
            transaction {
                UserAccountTable
                    .selectAll()
                    .where { UserAccountTable.id eq created.id }
                    .single()[UserAccountTable.passwordHash]
            }

        assertTrue(rowPasswordHash.startsWith("pbkdf2_sha256$"))
        val authenticated = authenticateUser(username, password)
        assertNotNull(authenticated)
        assertEquals(created.id, authenticated!!.id)
    }

    @Test
    fun `invalidating user sessions revokes access tokens`() {
        val username = "phase1-token-${System.currentTimeMillis()}"
        val user = createUserAccount(username, "Password1234", UserRole.USER, isActive = true)

        val jwt = Jwt.generateJwt(user.id)
        val beforeInvalidation = Jwt.verifyJwt(jwt.accessToken)
        assertTrue(beforeInvalidation is UserType.Admin)

        val invalidated = invalidateUserSessions(user.id)
        assertTrue(invalidated)
        assertEquals(UserType.Visitor, Jwt.verifyJwt(jwt.accessToken))
    }

    @Test
    fun `refresh token rotates and old refresh token is rejected`() {
        val username = "phase1-rotate-${System.currentTimeMillis()}"
        val user = createUserAccount(username, "Password1234", UserRole.USER, isActive = true)
        val jwt = Jwt.generateJwt(user.id)

        val rotated = Jwt.refreshJwt(jwt.refreshToken)
        assertTrue(rotated.refreshToken.isNotBlank())
        assertTrue(rotated.refreshToken != jwt.refreshToken)

        assertThrows(IllegalArgumentException::class.java) {
            Jwt.refreshJwt(jwt.refreshToken)
        }
    }

    @Test
    fun `using an access token as a refresh token is rejected`() {
        val username = "phase1-access-as-refresh-${System.currentTimeMillis()}"
        val user = createUserAccount(username, "Password1234", UserRole.USER, isActive = true)
        val jwt = Jwt.generateJwt(user.id)

        assertThrows(IllegalArgumentException::class.java) {
            Jwt.refreshJwt(jwt.accessToken)
        }
    }

    @Test
    fun `explicit token revocation without rotation prevents refresh but access token remains valid`() {
        val username = "phase1-revoke-${System.currentTimeMillis()}"
        val user = createUserAccount(username, "Password1234", UserRole.USER, isActive = true)
        val jwt = Jwt.generateJwt(user.id)

        val jti = JwtDecoder.decode(jwt.refreshToken).id
        val revoked = revokeRefreshTokenJti(jti)
        assertTrue(revoked, "revokeRefreshTokenJti should return true when the row was updated")

        // tokenVersion is unchanged — access token must still verify
        assertTrue(Jwt.verifyJwt(jwt.accessToken) is UserType.Admin, "Access token should still be valid after JTI revocation")

        // Revoked refresh token must be rejected
        assertThrows(IllegalArgumentException::class.java) {
            Jwt.refreshJwt(jwt.refreshToken)
        }
    }
}
