package suwayomi.tachidesk.server.user

import io.javalin.http.Context
import io.javalin.http.Header
import io.javalin.websocket.WsConnectContext
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig

sealed class UserType {
    class Admin(
        val id: Int,
    ) : UserType()

    data object Visitor : UserType()
}

fun UserType.requireUser(): Int =
    when (this) {
        is UserType.Admin -> id
        UserType.Visitor -> throw UnauthorizedException()
    }

fun UserType.requireUserWithBasicFallback(ctx: Context): Int =
    when (this) {
        is UserType.Admin -> {
            id
        }

        UserType.Visitor if ctx.getAttribute(Attribute.TachideskBasicUserId) > 0 -> {
            ctx.getAttribute(Attribute.TachideskBasicUserId)
        }

        UserType.Visitor -> {
            ctx.header("WWW-Authenticate", "Basic")
            throw UnauthorizedException()
        }
    }

fun getUserFromToken(token: String?): UserType {
    if (serverConfig.authMode.value != AuthMode.UI_LOGIN) {
        return findDefaultActiveUser()?.let { UserType.Admin(it.id) } ?: UserType.Visitor
    }

    if (token.isNullOrBlank()) {
        return UserType.Visitor
    }

    return Jwt.verifyJwt(token)
}

fun getUserFromContext(
    ctx: Context,
    basicAuthUserId: Int = 0,
): UserType {
    fun cookieUser(): AuthenticatedUser? {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return null
        return findActiveUserByUsername(username)
    }

    return when (serverConfig.authMode.value) {
        AuthMode.NONE -> {
            findDefaultActiveUser()?.let { UserType.Admin(it.id) } ?: UserType.Visitor
        }

        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.BASIC_AUTH -> {
            if (basicAuthUserId > 0) {
                UserType.Admin(basicAuthUserId)
            } else {
                UserType.Visitor
            }
        }

        AuthMode.SIMPLE_LOGIN -> {
            cookieUser()?.let { UserType.Admin(it.id) } ?: UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            val authentication = ctx.header(Header.AUTHORIZATION) ?: ctx.cookie("suwayomi-server-token")
            val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

            getUserFromToken(token)
        }
    }
}

fun getUserFromWsContext(ctx: WsConnectContext): UserType {
    fun cookieUser(): AuthenticatedUser? {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return null
        return findActiveUserByUsername(username)
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            findDefaultActiveUser()?.let { UserType.Admin(it.id) } ?: UserType.Visitor
        }

        AuthMode.SIMPLE_LOGIN -> {
            cookieUser()?.let { UserType.Admin(it.id) } ?: UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            val authentication =
                ctx.header(Header.AUTHORIZATION) ?: ctx.header("Sec-WebSocket-Protocol") ?: ctx.cookie("suwayomi-server-token")
            val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

            getUserFromToken(token)
        }
    }
}

class UnauthorizedException : IllegalStateException("Unauthorized")

class ForbiddenException : IllegalStateException("Forbidden")
