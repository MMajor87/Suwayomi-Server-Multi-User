package suwayomi.tachidesk.graphql.mutations

import graphql.schema.DataFetchingEnvironment
import io.javalin.http.Context
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.security.ActionRateLimiter
import suwayomi.tachidesk.server.security.SecurityAudit
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.PasswordSecurity
import suwayomi.tachidesk.server.user.UserAccountRecord
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.authenticateUser
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.server.user.deleteUserAccount
import suwayomi.tachidesk.server.user.deactivateUserAccount
import suwayomi.tachidesk.server.user.getUserById
import suwayomi.tachidesk.server.user.invalidateUserSessions
import suwayomi.tachidesk.server.user.reactivateUserAccount
import suwayomi.tachidesk.server.user.requireAdminUser
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.user.setupInitialAdminUser
import suwayomi.tachidesk.server.user.updateUserAccount

class UserMutation {
    companion object {
        private val loginRateLimiter = ActionRateLimiter(maxAttempts = 12, windowMs = 60_000)
        private val refreshTokenRateLimiter = ActionRateLimiter(maxAttempts = 20, windowMs = 60_000)
        private val setupRateLimiter = ActionRateLimiter(maxAttempts = 5, windowMs = 60_000)
        private val adminMutationRateLimiter = ActionRateLimiter(maxAttempts = 60, windowMs = 60_000)
    }

    data class UserAccountType(
        val id: Int,
        val username: String,
        val role: UserRole,
        val isActive: Boolean,
        val tokenVersion: Int,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class LoginInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
    )

    data class LoginPayload(
        val clientMutationId: String?,
        val accessToken: String,
        val refreshToken: String,
    )

    fun login(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LoginInput,
    ): LoginPayload {
        val requestContext = requestContext(dataFetchingEnvironment)
        val sourceIp = requestContext?.ip()
        val rateLimitKey = "login:${sourceIp ?: "unknown"}:${input.username.lowercase()}"
        if (!loginRateLimiter.tryAcquire(rateLimitKey)) {
            SecurityAudit.loginAttempt(
                username = input.username,
                sourceIp = sourceIp,
                success = false,
                reason = "rate_limited",
            )
            throw IllegalArgumentException("Too many login attempts. Please wait and try again.")
        }

        if (dataFetchingEnvironment.getAttribute(Attribute.TachideskUser) !is UserType.Visitor) {
            throw IllegalArgumentException("Cannot login while already logged-in")
        }
        val authenticatedUser = authenticateUser(input.username, input.password)
        if (authenticatedUser != null) {
            SecurityAudit.loginAttempt(
                username = input.username,
                sourceIp = sourceIp,
                success = true,
            )
            val jwt = Jwt.generateJwt(authenticatedUser.id)
            return LoginPayload(
                clientMutationId = input.clientMutationId,
                accessToken = jwt.accessToken,
                refreshToken = jwt.refreshToken,
            )
        } else {
            SecurityAudit.loginAttempt(
                username = input.username,
                sourceIp = sourceIp,
                success = false,
                reason = "invalid_credentials",
            )
            throw Exception("Incorrect username or password.")
        }
    }

    data class RefreshTokenInput(
        val clientMutationId: String? = null,
        val refreshToken: String,
    )

    data class RefreshTokenPayload(
        val clientMutationId: String?,
        val accessToken: String,
        val refreshToken: String,
    )

    fun refreshToken(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RefreshTokenInput,
    ): RefreshTokenPayload {
        val sourceIp = requestContext(dataFetchingEnvironment)?.ip()
        val rateLimitKey = "refresh:${sourceIp ?: "unknown"}"
        if (!refreshTokenRateLimiter.tryAcquire(rateLimitKey)) {
            SecurityAudit.unauthorizedAccess(
                sourceIp = sourceIp,
                method = "POST",
                path = "/api/graphql",
                reason = "refresh_token_rate_limited",
            )
            throw IllegalArgumentException("Too many refresh requests. Please wait and try again.")
        }

        val jwt = Jwt.refreshJwt(input.refreshToken)

        return RefreshTokenPayload(
            clientMutationId = input.clientMutationId,
            accessToken = jwt.accessToken,
            refreshToken = jwt.refreshToken,
        )
    }

    data class LogoutInput(
        val clientMutationId: String? = null,
    )

    data class LogoutPayload(
        val clientMutationId: String?,
        val success: Boolean,
    )

    @RequireAuth
    fun logout(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LogoutInput,
    ): LogoutPayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val invalidated = invalidateUserSessions(userId)
        invalidateRequestSession(dataFetchingEnvironment)

        return LogoutPayload(
            clientMutationId = input.clientMutationId,
            success = invalidated,
        )
    }

    data class SetupInitialAdminInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
    )

    data class SetupInitialAdminPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    fun setupInitialAdmin(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetupInitialAdminInput,
    ): SetupInitialAdminPayload {
        val sourceIp = requestContext(dataFetchingEnvironment)?.ip()
        val rateLimitKey = "setup_initial_admin:${sourceIp ?: "unknown"}"
        if (!setupRateLimiter.tryAcquire(rateLimitKey)) {
            SecurityAudit.unauthorizedAccess(
                sourceIp = sourceIp,
                method = "POST",
                path = "/api/graphql",
                reason = "setup_initial_admin_rate_limited",
            )
            throw IllegalArgumentException("Too many setup requests. Please wait and try again.")
        }
        PasswordSecurity.validatePasswordPolicy(input.password)
        val user = setupInitialAdminUser(input.username, input.password)
        SecurityAudit.adminAction(
            adminUserId = user.id,
            action = "setupInitialAdmin",
            details = "username=${user.username}",
        )
        return SetupInitialAdminPayload(
            clientMutationId = input.clientMutationId,
            user = user.toType(),
        )
    }

    data class CreateUserInput(
        val clientMutationId: String? = null,
        val username: String,
        val password: String,
        val role: UserRole = UserRole.USER,
        val isActive: Boolean = true,
    )

    data class CreateUserPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    @RequireAuth
    fun createUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateUserInput,
    ): CreateUserPayload {
        val adminUserId = requireAdmin(dataFetchingEnvironment, "createUser")
        val user = createUserAccount(input.username, input.password, input.role, input.isActive)
        SecurityAudit.adminAction(
            adminUserId = adminUserId,
            action = "createUser",
            details = "createdUserId=${user.id}",
        )
        return CreateUserPayload(
            clientMutationId = input.clientMutationId,
            user = user.toType(),
        )
    }

    data class UpdateUserPatch(
        val username: String? = null,
        val password: String? = null,
        val role: UserRole? = null,
        val isActive: Boolean? = null,
    )

    data class UpdateUserInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateUserPatch,
    )

    data class UpdateUserPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    @RequireAuth
    fun updateUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateUserInput,
    ): UpdateUserPayload {
        val adminUserId = requireAdmin(dataFetchingEnvironment, "updateUser")
        val user =
            updateUserAccount(
                targetUserId = input.id,
                username = input.patch.username,
                password = input.patch.password,
                role = input.patch.role,
                isActive = input.patch.isActive,
            )
        SecurityAudit.adminAction(
            adminUserId = adminUserId,
            action = "updateUser",
            details = "targetUserId=${input.id}",
        )

        return UpdateUserPayload(
            clientMutationId = input.clientMutationId,
            user = user.toType(),
        )
    }

    data class DeactivateUserInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class DeactivateUserPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    @RequireAuth
    fun deactivateUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeactivateUserInput,
    ): DeactivateUserPayload {
        val adminUserId = requireAdmin(dataFetchingEnvironment, "deactivateUser")
        val user = deactivateUserAccount(input.id)
        SecurityAudit.adminAction(
            adminUserId = adminUserId,
            action = "deactivateUser",
            details = "targetUserId=${input.id}",
        )
        return DeactivateUserPayload(input.clientMutationId, user.toType())
    }

    data class ReactivateUserInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class ReactivateUserPayload(
        val clientMutationId: String?,
        val user: UserAccountType,
    )

    @RequireAuth
    fun reactivateUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ReactivateUserInput,
    ): ReactivateUserPayload {
        val adminUserId = requireAdmin(dataFetchingEnvironment, "reactivateUser")
        val user = reactivateUserAccount(input.id)
        SecurityAudit.adminAction(
            adminUserId = adminUserId,
            action = "reactivateUser",
            details = "targetUserId=${input.id}",
        )
        return ReactivateUserPayload(input.clientMutationId, user.toType())
    }

    data class DeleteUserInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class DeleteUserPayload(
        val clientMutationId: String?,
        val success: Boolean,
    )

    @RequireAuth
    fun deleteUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteUserInput,
    ): DeleteUserPayload {
        val adminUserId = requireAdmin(dataFetchingEnvironment, "deleteUser")
        val success = deleteUserAccount(input.id)
        SecurityAudit.adminAction(
            adminUserId = adminUserId,
            action = "deleteUser",
            details = "targetUserId=${input.id};success=$success",
        )
        return DeleteUserPayload(
            clientMutationId = input.clientMutationId,
            success = success,
        )
    }

    data class ForceSignOutUserInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class ForceSignOutUserPayload(
        val clientMutationId: String?,
        val success: Boolean,
    )

    @RequireAuth
    fun forceSignOutUser(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ForceSignOutUserInput,
    ): ForceSignOutUserPayload {
        val adminUserId = requireAdmin(dataFetchingEnvironment, "forceSignOutUser")
        val success = invalidateUserSessions(input.id)
        SecurityAudit.adminAction(
            adminUserId = adminUserId,
            action = "forceSignOutUser",
            details = "targetUserId=${input.id};success=$success",
        )
        return ForceSignOutUserPayload(input.clientMutationId, success)
    }

    data class ChangePasswordInput(
        val clientMutationId: String? = null,
        val currentPassword: String,
        val newPassword: String,
    )

    data class ChangePasswordPayload(
        val clientMutationId: String?,
        val success: Boolean,
    )

    @RequireAuth
    fun changePassword(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ChangePasswordInput,
    ): ChangePasswordPayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val user = getUserById(userId, includeInactive = false) ?: throw ForbiddenException()
        val authenticated = authenticateUser(user.username, input.currentPassword)
        require(authenticated != null && authenticated.id == userId) { "Current password is incorrect." }

        PasswordSecurity.validatePasswordPolicy(input.newPassword)
        updateUserAccount(targetUserId = userId, password = input.newPassword)
        invalidateRequestSession(dataFetchingEnvironment)

        return ChangePasswordPayload(
            clientMutationId = input.clientMutationId,
            success = true,
        )
    }

    private fun requireAdmin(
        dataFetchingEnvironment: DataFetchingEnvironment,
        action: String,
    ): Int {
        val context = requestContext(dataFetchingEnvironment)
        val sourceIp = context?.ip()
        val userId =
            runCatching { dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser() }
                .getOrElse {
                    SecurityAudit.unauthorizedAccess(
                        sourceIp = sourceIp,
                        method = context?.method().toString(),
                        path = context?.path(),
                        reason = "admin_required:$action",
                    )
                    throw it
                }

        runCatching { requireAdminUser(userId) }
            .getOrElse {
                SecurityAudit.unauthorizedAccess(
                    sourceIp = sourceIp,
                    method = context?.method().toString(),
                    path = context?.path(),
                    userId = userId,
                    reason = "admin_forbidden:$action",
                )
                throw it
            }

        if (!adminMutationRateLimiter.tryAcquire("admin:$userId")) {
            SecurityAudit.unauthorizedAccess(
                sourceIp = sourceIp,
                method = context?.method().toString(),
                path = context?.path(),
                userId = userId,
                reason = "admin_rate_limited:$action",
            )
            throw IllegalArgumentException("Too many admin operations. Please wait and try again.")
        }

        return userId
    }

    private fun invalidateRequestSession(dataFetchingEnvironment: DataFetchingEnvironment) {
        val requestContext = requestContext(dataFetchingEnvironment)
        requestContext?.req()?.session?.invalidate()
    }

    private fun requestContext(dataFetchingEnvironment: DataFetchingEnvironment): Context? =
        runCatching {
            dataFetchingEnvironment.graphQlContext.get(Context::class) as? Context
        }.getOrNull()

    private fun UserAccountRecord.toType(): UserAccountType =
        UserAccountType(
            id = id,
            username = username,
            role = role,
            isActive = isActive,
            tokenVersion = tokenVersion,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
