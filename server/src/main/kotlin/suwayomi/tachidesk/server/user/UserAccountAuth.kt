package suwayomi.tachidesk.server.user

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.server.model.table.UserAccountTable
import suwayomi.tachidesk.server.model.table.UserRefreshTokenTable
import suwayomi.tachidesk.server.model.table.UserRole

data class UserAccountRecord(
    val id: Int,
    val username: String,
    val role: UserRole,
    val isActive: Boolean,
    val tokenVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AuthenticatedUser(
    val id: Int,
    val username: String,
    val role: UserRole,
    val tokenVersion: Int,
)

fun authenticateUser(
    username: String,
    password: String,
): AuthenticatedUser? {
    if (username.isBlank()) {
        return null
    }

    return transaction {
        val row =
            UserAccountTable
                .selectAll()
                .where {
                    (UserAccountTable.username eq username) and
                        (UserAccountTable.isActive eq true)
                }.singleOrNull() ?: return@transaction null

        val verification = PasswordSecurity.verifyPassword(password, row[UserAccountTable.passwordHash])
        if (!verification.matches) {
            return@transaction null
        }

        if (verification.needsRehash) {
            val rehashedPassword = PasswordSecurity.hashPassword(password)
            UserAccountTable.update({ UserAccountTable.id eq row[UserAccountTable.id] }) {
                it[passwordHash] = rehashedPassword
                it[updatedAt] = System.currentTimeMillis()
            }
        }

        row.toAuthenticatedUser() ?: row.toRecord()?.toAuthenticatedUser()
    }
}

fun setupInitialAdminUser(
    username: String,
    password: String,
): UserAccountRecord {
    val normalizedUsername = username.trim()
    require(normalizedUsername.isNotBlank()) { "Username must not be blank." }
    PasswordSecurity.validatePasswordPolicy(password)

    return transaction {
        val userCount = UserAccountTable.selectAll().count()
        require(userCount == 0L) { "Initial admin setup can only run when no users exist." }

        val now = System.currentTimeMillis()
        val id =
            UserAccountTable.insertAndGetId {
                it[UserAccountTable.username] = normalizedUsername
                it[passwordHash] = PasswordSecurity.hashPassword(password)
                it[role] = UserRole.ADMIN.name
                it[isActive] = true
                it[tokenVersion] = 1
                it[createdAt] = now
                it[updatedAt] = now
            }.value

        getUserById(id) ?: error("Created initial admin user but could not re-read it.")
    }
}

fun createUserAccount(
    username: String,
    password: String,
    role: UserRole,
    isActive: Boolean,
): UserAccountRecord {
    val normalizedUsername = username.trim()
    require(normalizedUsername.isNotBlank()) { "Username must not be blank." }
    PasswordSecurity.validatePasswordPolicy(password)

    return transaction {
        val exists =
            UserAccountTable
                .selectAll()
                .where { UserAccountTable.username eq normalizedUsername }
                .singleOrNull() != null
        require(!exists) { "Username '$normalizedUsername' is already in use." }

        val now = System.currentTimeMillis()
        val createdId =
            UserAccountTable
                .insertAndGetId {
                    it[UserAccountTable.username] = normalizedUsername
                    it[passwordHash] = PasswordSecurity.hashPassword(password)
                    it[UserAccountTable.role] = role.name
                    it[UserAccountTable.isActive] = isActive
                    it[tokenVersion] = 1
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value

        getUserById(createdId) ?: error("Created user $createdId but could not re-read it.")
    }
}

fun updateUserAccount(
    targetUserId: Int,
    username: String? = null,
    password: String? = null,
    role: UserRole? = null,
    isActive: Boolean? = null,
): UserAccountRecord {
    return transaction {
        val existing = getUserById(targetUserId) ?: throw IllegalArgumentException("User $targetUserId was not found.")
        val normalizedUsername = username?.trim()

        if (normalizedUsername != null) {
            require(normalizedUsername.isNotBlank()) { "Username must not be blank." }
            val usernameTaken =
                UserAccountTable
                    .selectAll()
                    .where {
                        (UserAccountTable.username eq normalizedUsername) and
                            (UserAccountTable.id neq targetUserId)
                    }.singleOrNull() != null
            require(!usernameTaken) { "Username '$normalizedUsername' is already in use." }
        }

        if (password != null) {
            PasswordSecurity.validatePasswordPolicy(password)
        }

        val nextRole = role ?: existing.role
        val nextActive = isActive ?: existing.isActive
        enforceAdminSafety(existing, nextRole, nextActive)

        UserAccountTable.update({ UserAccountTable.id eq targetUserId }) {
            normalizedUsername?.let { updatedUsername ->
                it[UserAccountTable.username] = updatedUsername
            }
            password?.let { updatedPassword ->
                it[passwordHash] = PasswordSecurity.hashPassword(updatedPassword)
                it[tokenVersion] = existing.tokenVersion + 1
            }
            role?.let { updatedRole ->
                it[UserAccountTable.role] = updatedRole.name
            }
            isActive?.let { active ->
                it[UserAccountTable.isActive] = active
                if (!active) {
                    it[tokenVersion] = existing.tokenVersion + 1
                }
            }
            it[updatedAt] = System.currentTimeMillis()
        }

        if ((password != null) || (isActive == false)) {
            revokeAllRefreshTokensForUser(targetUserId)
        }

        getUserById(targetUserId) ?: error("Updated user $targetUserId but could not re-read it.")
    }
}

fun deactivateUserAccount(targetUserId: Int): UserAccountRecord = updateUserAccount(targetUserId, isActive = false)

fun reactivateUserAccount(targetUserId: Int): UserAccountRecord = updateUserAccount(targetUserId, isActive = true)

fun deleteUserAccount(targetUserId: Int): Boolean {
    return transaction {
        val existing = getUserById(targetUserId) ?: return@transaction false
        enforceAdminSafety(existing, existing.role, isActiveAfterOperation = false)

        revokeAllRefreshTokensForUser(targetUserId)
        UserAccountTable.deleteWhere { UserAccountTable.id eq targetUserId } > 0
    }
}

fun listUsers(includeInactive: Boolean = true): List<UserAccountRecord> =
    transaction {
        val query =
            UserAccountTable
                .selectAll()
                .orderBy(UserAccountTable.id to SortOrder.ASC)

        val rows =
            if (includeInactive) {
                query
            } else {
                query.where { UserAccountTable.isActive eq true }
            }

        rows.mapNotNull { it.toRecord() }
    }

fun getUserById(
    userId: Int,
    includeInactive: Boolean = true,
): UserAccountRecord? =
    transaction {
        val predicate =
            if (includeInactive) {
                UserAccountTable.id eq userId
            } else {
                (UserAccountTable.id eq userId) and (UserAccountTable.isActive eq true)
            }

        UserAccountTable
            .selectAll()
            .where { predicate }
            .singleOrNull()
            ?.toRecord()
    }

fun countUsers(): Long =
    transaction {
        UserAccountTable.selectAll().count()
    }

fun findActiveUserById(userId: Int): AuthenticatedUser? =
    transaction {
        UserAccountTable
            .selectAll()
            .where {
                (UserAccountTable.id eq userId) and
                    (UserAccountTable.isActive eq true)
            }.singleOrNull()
            ?.toAuthenticatedUser()
    }

fun findActiveUserByUsername(username: String): AuthenticatedUser? {
    if (username.isBlank()) {
        return null
    }

    return transaction {
        UserAccountTable
            .selectAll()
            .where {
                (UserAccountTable.username eq username) and
                    (UserAccountTable.isActive eq true)
            }.singleOrNull()
            ?.toAuthenticatedUser()
    }
}

fun findDefaultActiveUser(): AuthenticatedUser? =
    transaction {
        val activeAdmin =
            UserAccountTable
                .selectAll()
                .where {
                    (UserAccountTable.role eq UserRole.ADMIN.name) and
                        (UserAccountTable.isActive eq true)
                }.orderBy(UserAccountTable.id to SortOrder.ASC)
                .firstOrNull()
                ?.toAuthenticatedUser()
        if (activeAdmin != null) {
            return@transaction activeAdmin
        }

        UserAccountTable
            .selectAll()
            .where { UserAccountTable.isActive eq true }
            .orderBy(UserAccountTable.id to SortOrder.ASC)
            .firstOrNull()
            ?.toAuthenticatedUser()
    }

fun getUserTokenVersion(userId: Int): Int? = findActiveUserById(userId)?.tokenVersion

fun invalidateUserSessions(userId: Int): Boolean {
    return transaction {
        val existingRow =
            UserAccountTable
                .selectAll()
                .where { UserAccountTable.id eq userId }
                .singleOrNull() ?: return@transaction false
        val existing = existingRow.toRecord() ?: return@transaction false
        val now = System.currentTimeMillis()
        val updated =
            UserAccountTable.update({ UserAccountTable.id eq userId }) {
                it[tokenVersion] = existing.tokenVersion + 1
                it[updatedAt] = now
            } > 0
        if (updated) {
            revokeAllRefreshTokensForUser(userId, revokedAt = now)
        }
        updated
    }
}

fun requireAdminUser(userId: Int): UserAccountRecord {
    val user = getUserById(userId, includeInactive = false) ?: throw UnauthorizedException()
    if (user.role != UserRole.ADMIN) {
        throw ForbiddenException()
    }
    return user
}

fun revokeRefreshTokenJti(jti: String): Boolean =
    transaction {
        UserRefreshTokenTable.update({ UserRefreshTokenTable.jti eq jti }) {
            it[UserRefreshTokenTable.revokedAt] = System.currentTimeMillis()
        } > 0
    }

private fun org.jetbrains.exposed.sql.ResultRow.toRecord(): UserAccountRecord? {
    val parsedRole =
        UserRole.entries.firstOrNull {
            it.name == this[UserAccountTable.role].uppercase()
        } ?: return null

    return UserAccountRecord(
        id = this[UserAccountTable.id].value,
        username = this[UserAccountTable.username],
        role = parsedRole,
        isActive = this[UserAccountTable.isActive],
        tokenVersion = this[UserAccountTable.tokenVersion],
        createdAt = this[UserAccountTable.createdAt],
        updatedAt = this[UserAccountTable.updatedAt],
    )
}

private fun org.jetbrains.exposed.sql.ResultRow.toAuthenticatedUser(): AuthenticatedUser? =
    toRecord()?.toAuthenticatedUser()

private fun UserAccountRecord.toAuthenticatedUser(): AuthenticatedUser =
    AuthenticatedUser(
        id = id,
        username = username,
        role = role,
        tokenVersion = tokenVersion,
    )

private fun enforceAdminSafety(
    existingUser: UserAccountRecord,
    roleAfterOperation: UserRole,
    isActiveAfterOperation: Boolean,
) {
    val isDowngradingActiveAdmin =
        existingUser.role == UserRole.ADMIN &&
            existingUser.isActive &&
            (roleAfterOperation != UserRole.ADMIN || !isActiveAfterOperation)

    if (!isDowngradingActiveAdmin) {
        return
    }

    val activeAdminCount =
        UserAccountTable
            .selectAll()
            .where {
                (UserAccountTable.role eq UserRole.ADMIN.name) and
                    (UserAccountTable.isActive eq true)
            }.count()
    require(activeAdminCount > 1) { "Cannot remove the last active admin user." }
}

private fun revokeAllRefreshTokensForUser(
    userId: Int,
    revokedAt: Long = System.currentTimeMillis(),
) {
    UserRefreshTokenTable.update({
        (UserRefreshTokenTable.userId eq userId) and
            (UserRefreshTokenTable.revokedAt.isNull())
    }) {
        it[UserRefreshTokenTable.revokedAt] = revokedAt
    }
}
