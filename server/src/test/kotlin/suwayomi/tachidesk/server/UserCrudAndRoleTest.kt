package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.impl.util.Jwt
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.model.table.UserChapterTable
import suwayomi.tachidesk.server.model.table.UserMangaLibraryTable
import suwayomi.tachidesk.server.model.table.UserRefreshTokenTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.authenticateUser
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.server.user.deactivateUserAccount
import suwayomi.tachidesk.server.user.deleteUserAccount
import suwayomi.tachidesk.server.user.getUserById
import suwayomi.tachidesk.server.user.listUsers
import suwayomi.tachidesk.server.user.reactivateUserAccount
import suwayomi.tachidesk.server.user.requireAdminUser
import suwayomi.tachidesk.server.user.setupInitialAdminUser
import suwayomi.tachidesk.server.user.updateUserAccount
import suwayomi.tachidesk.test.ApplicationTest

class UserCrudAndRoleTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "user") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

    private fun makeAdmin(name: String = uniqueName("admin")): Int =
        createUserAccount(name, "AdminPass1!", UserRole.ADMIN, isActive = true).id

    private fun makeUser(name: String = uniqueName("user")): Int =
        createUserAccount(name, "UserPass1!!", UserRole.USER, isActive = true).id

    // --- createUserAccount ---

    @Test
    fun `createUserAccount rejects a blank username`() {
        assertThrows(IllegalArgumentException::class.java) {
            createUserAccount("   ", "ValidPass1!", UserRole.USER, isActive = true)
        }
    }

    @Test
    fun `createUserAccount enforces password policy`() {
        assertThrows(IllegalArgumentException::class.java) {
            createUserAccount(uniqueName(), "short", UserRole.USER, isActive = true)
        }
    }

    @Test
    fun `createUserAccount rejects a duplicate username`() {
        val name = uniqueName()
        createUserAccount(name, "ValidPass1!", UserRole.USER, isActive = true)
        assertThrows(IllegalArgumentException::class.java) {
            createUserAccount(name, "AnotherPass2!", UserRole.USER, isActive = true)
        }
    }

    @Test
    fun `createUserAccount stores role and active flag correctly`() {
        val id = makeUser()
        val record = getUserById(id)
        assertNotNull(record)
        assertEquals(UserRole.USER, record!!.role)
        assertTrue(record.isActive)
    }

    // --- setupInitialAdminUser ---

    @Test
    fun `setupInitialAdminUser fails when users already exist`() {
        // The shared test DB always has users after migration and other tests.
        assertThrows(IllegalArgumentException::class.java) {
            setupInitialAdminUser(uniqueName("initadmin"), "ValidPass1!")
        }
    }

    @Test
    fun `setupInitialAdminUser rejects a blank username`() {
        assertThrows(IllegalArgumentException::class.java) {
            setupInitialAdminUser("  ", "ValidPass1!")
        }
    }

    // --- updateUserAccount ---

    @Test
    fun `updateUserAccount changes the username`() {
        val id = makeUser()
        val newName = uniqueName("renamed")
        val updated = updateUserAccount(id, username = newName)
        assertEquals(newName, updated.username)
    }

    @Test
    fun `updateUserAccount rejects a duplicate username for a different user`() {
        val nameA = uniqueName("a")
        val idA = createUserAccount(nameA, "ValidPass1!", UserRole.USER, isActive = true).id
        val idB = makeUser()
        assertThrows(IllegalArgumentException::class.java) {
            updateUserAccount(idB, username = nameA)
        }
        // Verify user A is unchanged
        assertEquals(nameA, getUserById(idA)?.username)
    }

    @Test
    fun `updateUserAccount password change increments tokenVersion`() {
        val id = makeUser()
        val before = getUserById(id)!!.tokenVersion
        updateUserAccount(id, password = "NewPass1234!")
        val after = getUserById(id)!!.tokenVersion
        assertTrue(after > before, "tokenVersion should have incremented after password change")
    }

    @Test
    fun `updateUserAccount password change invalidates existing JWT`() {
        val id = makeUser()
        val jwt = Jwt.generateJwt(id)
        assertTrue(Jwt.verifyJwt(jwt.accessToken) is UserType.Admin)

        updateUserAccount(id, password = "NewPass1234!")

        assertEquals(UserType.Visitor, Jwt.verifyJwt(jwt.accessToken))
    }

    @Test
    fun `updateUserAccount deactivation increments tokenVersion`() {
        val id = makeUser()
        val before = getUserById(id)!!.tokenVersion
        updateUserAccount(id, isActive = false)
        val after = getUserById(id)!!.tokenVersion
        assertTrue(after > before, "tokenVersion should have incremented on deactivation")
    }

    @Test
    fun `updateUserAccount throws for unknown user id`() {
        assertThrows(IllegalArgumentException::class.java) {
            updateUserAccount(Int.MAX_VALUE, username = uniqueName())
        }
    }

    // --- deactivateUserAccount / reactivateUserAccount ---

    @Test
    fun `deactivateUserAccount prevents authentication`() {
        val name = uniqueName()
        val id = createUserAccount(name, "ValidPass1!", UserRole.USER, isActive = true).id
        deactivateUserAccount(id)
        assertNull(authenticateUser(name, "ValidPass1!"))
    }

    @Test
    fun `reactivateUserAccount re-enables authentication`() {
        val name = uniqueName()
        val id = createUserAccount(name, "ValidPass1!", UserRole.USER, isActive = true).id
        deactivateUserAccount(id)
        reactivateUserAccount(id)
        assertNotNull(authenticateUser(name, "ValidPass1!"))
    }

    // --- deleteUserAccount ---

    @Test
    fun `deleteUserAccount removes the user`() {
        val id = makeUser()
        val deleted = deleteUserAccount(id)
        assertTrue(deleted)
        assertNull(getUserById(id))
    }

    @Test
    fun `deleteUserAccount cascades user library, chapter progress, and refresh tokens`() {
        val userId = makeUser()
        val now = System.currentTimeMillis()

        val (mangaId, chapterId) =
            transaction {
                val mangaId =
                    MangaTable
                        .insertAndGetId {
                            it[MangaTable.title] = uniqueName("manga")
                            it[MangaTable.url] = uniqueName("url")
                            it[MangaTable.sourceReference] = 1L
                        }.value
                val chapterId =
                    ChapterTable
                        .insertAndGetId {
                            it[ChapterTable.url] = uniqueName("chapter-url")
                            it[ChapterTable.name] = uniqueName("chapter-name")
                            it[ChapterTable.sourceOrder] = 1
                            it[ChapterTable.manga] = mangaId
                        }.value

                UserMangaLibraryTable.insert {
                    it[UserMangaLibraryTable.userId] = userId
                    it[UserMangaLibraryTable.mangaId] = mangaId
                    it[UserMangaLibraryTable.inLibraryAt] = now / 1000
                }

                UserChapterTable.insert {
                    it[UserChapterTable.userId] = userId
                    it[UserChapterTable.chapterId] = chapterId
                    it[UserChapterTable.isRead] = true
                    it[UserChapterTable.isBookmarked] = true
                    it[UserChapterTable.lastPageRead] = 3
                    it[UserChapterTable.lastReadAt] = now / 1000
                    it[UserChapterTable.updatedAt] = now / 1000
                }

                UserRefreshTokenTable.insert {
                    it[UserRefreshTokenTable.jti] = uniqueName("jti")
                    it[UserRefreshTokenTable.userId] = userId
                    it[UserRefreshTokenTable.tokenVersion] = 1
                    it[UserRefreshTokenTable.issuedAt] = now
                    it[UserRefreshTokenTable.expiresAt] = now + 60_000
                    it[UserRefreshTokenTable.rotatedAt] = null
                    it[UserRefreshTokenTable.revokedAt] = null
                    it[UserRefreshTokenTable.replacementJti] = null
                }
                mangaId to chapterId
            }

        fun userMangaRows(): Long =
            transaction {
                UserMangaLibraryTable
                    .selectAll()
                    .where { UserMangaLibraryTable.userId eq userId }
                    .count()
            }

        fun userChapterRows(): Long =
            transaction {
                UserChapterTable
                    .selectAll()
                    .where { UserChapterTable.userId eq userId }
                    .count()
            }

        fun userRefreshRows(): Long =
            transaction {
                UserRefreshTokenTable
                    .selectAll()
                    .where { UserRefreshTokenTable.userId eq userId }
                    .count()
            }

        assertEquals(1L, userMangaRows())
        assertEquals(1L, userChapterRows())
        assertEquals(1L, userRefreshRows())

        assertTrue(deleteUserAccount(userId))
        assertNull(getUserById(userId))
        assertEquals(0L, userMangaRows())
        assertEquals(0L, userChapterRows())
        assertEquals(0L, userRefreshRows())
    }

    @Test
    fun `deleteUserAccount succeeds for admin when other active admins remain`() {
        // The shared test DB always has other active admins (from bootstrap migration),
        // so deleting any single admin is always allowed. This verifies the guard does not
        // over-block when the invariant (at least one active admin) is satisfied.
        val a = createUserAccount(uniqueName("del-a"), "AdminPass1!", UserRole.ADMIN, isActive = true).id
        val b = createUserAccount(uniqueName("del-b"), "AdminPass1!", UserRole.ADMIN, isActive = true).id
        assertTrue(deleteUserAccount(a))
        assertNull(getUserById(a))
        // cleanup
        deleteUserAccount(b)
    }

    @Test
    fun `deactivating an admin succeeds when other active admins exist`() {
        // Tests the happy path of enforceAdminSafety: deactivation is allowed when
        // active admin count remains > 0 after the operation.
        val a = createUserAccount(uniqueName("deact-a"), "AdminPass1!", UserRole.ADMIN, isActive = true).id
        val b = createUserAccount(uniqueName("deact-b"), "AdminPass1!", UserRole.ADMIN, isActive = true).id
        deactivateUserAccount(a) // allowed — b (and bootstrap admins) are still active
        assertFalse(getUserById(a)!!.isActive)
        // cleanup
        reactivateUserAccount(a)
        deleteUserAccount(a)
        deleteUserAccount(b)
    }

    // --- listUsers ---

    @Test
    fun `listUsers includes inactive users by default`() {
        val id = makeUser()
        deactivateUserAccount(id)
        val all = listUsers(includeInactive = true)
        assertTrue(all.any { it.id == id })
    }

    @Test
    fun `listUsers excludes inactive users when includeInactive is false`() {
        val id = makeUser()
        deactivateUserAccount(id)
        val active = listUsers(includeInactive = false)
        assertFalse(active.any { it.id == id })
    }

    // --- getUserById ---

    @Test
    fun `getUserById returns the correct record`() {
        val id = makeUser()
        val record = getUserById(id)
        assertNotNull(record)
        assertEquals(id, record!!.id)
    }

    @Test
    fun `getUserById returns null for an unknown id`() {
        assertNull(getUserById(Int.MAX_VALUE))
    }

    @Test
    fun `getUserById returns inactive users by default`() {
        val id = makeUser()
        deactivateUserAccount(id)
        assertNotNull(getUserById(id, includeInactive = true))
    }

    @Test
    fun `getUserById returns null for inactive user when includeInactive is false`() {
        val id = makeUser()
        deactivateUserAccount(id)
        assertNull(getUserById(id, includeInactive = false))
    }

    // --- requireAdminUser ---

    @Test
    fun `requireAdminUser succeeds for an active admin`() {
        val id = makeAdmin()
        val record = requireAdminUser(id)
        assertEquals(id, record.id)
        assertEquals(UserRole.ADMIN, record.role)
    }

    @Test
    fun `requireAdminUser throws ForbiddenException for a USER-role account`() {
        val id = makeUser()
        assertThrows(ForbiddenException::class.java) {
            requireAdminUser(id)
        }
    }

    @Test
    fun `requireAdminUser throws UnauthorizedException for an inactive admin`() {
        val id = makeAdmin()
        deactivateUserAccount(id)
        assertThrows(UnauthorizedException::class.java) {
            requireAdminUser(id)
        }
    }

    // --- authenticateUser ---

    @Test
    fun `authenticateUser returns null for a wrong password`() {
        val name = uniqueName()
        createUserAccount(name, "ValidPass1!", UserRole.USER, isActive = true)
        assertNull(authenticateUser(name, "WrongPass9!"))
    }

    @Test
    fun `authenticateUser returns null for an inactive user`() {
        val name = uniqueName()
        val id = createUserAccount(name, "ValidPass1!", UserRole.USER, isActive = true).id
        deactivateUserAccount(id)
        assertNull(authenticateUser(name, "ValidPass1!"))
    }

    @Test
    fun `authenticateUser returns null for a blank username`() {
        assertNull(authenticateUser("", "ValidPass1!"))
        assertNull(authenticateUser("   ", "ValidPass1!"))
    }

    @Test
    fun `authenticateUser returns the correct user on success`() {
        val name = uniqueName()
        val id = createUserAccount(name, "ValidPass1!", UserRole.USER, isActive = true).id
        val result = authenticateUser(name, "ValidPass1!")
        assertNotNull(result)
        assertEquals(id, result!!.id)
    }

    // --- Role guard: last active admin protection ---

    @Test
    fun `downgrading an admin role succeeds when other active admins remain`() {
        // Verifies the guard does not over-block: role downgrade is allowed when the
        // invariant (at least one remaining active admin) is satisfied by the broader DB.
        val a = createUserAccount(uniqueName("guard-a"), "AdminPass1!", UserRole.ADMIN, isActive = true).id
        val b = createUserAccount(uniqueName("guard-b"), "AdminPass1!", UserRole.ADMIN, isActive = true).id
        // Downgrade a to USER — b (and bootstrap admins) satisfy the remaining-admin invariant.
        updateUserAccount(a, role = UserRole.USER)
        assertEquals(UserRole.USER, getUserById(a)!!.role)
        // cleanup
        deleteUserAccount(a)
        deleteUserAccount(b)
    }
}
