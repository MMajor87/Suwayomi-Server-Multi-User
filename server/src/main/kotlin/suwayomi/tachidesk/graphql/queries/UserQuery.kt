package suwayomi.tachidesk.graphql.queries

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.mutations.UserMutation.UserAccountType
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.UserAccountRecord
import suwayomi.tachidesk.server.user.getUserById
import suwayomi.tachidesk.server.user.listUsers
import suwayomi.tachidesk.server.user.requireAdminUser
import suwayomi.tachidesk.server.user.requireUser

class UserQuery {
    @RequireAuth
    fun me(dataFetchingEnvironment: DataFetchingEnvironment): UserAccountType {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val user = getUserById(userId, includeInactive = false) ?: throw IllegalStateException("Authenticated user not found")
        return user.toType()
    }

    @RequireAuth
    fun users(
        dataFetchingEnvironment: DataFetchingEnvironment,
        includeInactive: Boolean = true,
    ): List<UserAccountType> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        requireAdminUser(userId)
        return listUsers(includeInactive).map { it.toType() }
    }

    @RequireAuth
    fun user(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): UserAccountType? {
        val requesterId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val requester = getUserById(requesterId, includeInactive = false) ?: return null

        if (requester.id != id) {
            requireAdminUser(requesterId)
        }

        return getUserById(id, includeInactive = true)?.toType()
    }

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
