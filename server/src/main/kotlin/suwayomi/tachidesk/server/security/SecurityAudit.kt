package suwayomi.tachidesk.server.security

import io.github.oshai.kotlinlogging.KotlinLogging

object SecurityAudit {
    private val logger = KotlinLogging.logger("SecurityAudit")

    fun loginAttempt(
        username: String,
        sourceIp: String?,
        success: Boolean,
        reason: String? = null,
    ) {
        logger.warn {
            "login_attempt success=$success username=$username sourceIp=${sourceIp ?: "unknown"} reason=${reason ?: "-"}"
        }
    }

    fun adminAction(
        adminUserId: Int,
        action: String,
        details: String = "-",
    ) {
        logger.info {
            "admin_action adminUserId=$adminUserId action=$action details=$details"
        }
    }

    fun unauthorizedAccess(
        sourceIp: String?,
        method: String?,
        path: String?,
        userId: Int? = null,
        reason: String? = null,
    ) {
        logger.warn {
            "unauthorized_access method=${method ?: "-"} path=${path ?: "-"} sourceIp=${sourceIp ?: "unknown"} userId=${userId ?: -1} reason=${reason ?: "-"}"
        }
    }
}
