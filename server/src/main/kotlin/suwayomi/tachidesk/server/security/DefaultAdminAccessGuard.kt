package suwayomi.tachidesk.server.security

import suwayomi.tachidesk.server.serverConfig
import java.net.InetAddress

private fun String?.normalizedDefault(value: String): String = this?.takeIf { it.isNotBlank() } ?: value

fun isDefaultAdminCredentialPair(
    username: String?,
    password: String?,
): Boolean {
    if (username == null || password == null) {
        return false
    }

    val defaultUsername = serverConfig.authUsername.value.normalizedDefault("admin")
    val defaultPassword = serverConfig.authPassword.value.normalizedDefault("admin")

    return username == defaultUsername && password == defaultPassword
}

fun isLoopbackSourceIp(sourceIp: String?): Boolean {
    if (sourceIp.isNullOrBlank()) {
        return false
    }

    if (sourceIp.equals("localhost", ignoreCase = true)) {
        return true
    }

    val host =
        sourceIp
            .removePrefix("[")
            .substringBefore("]")
            .substringBefore('%')

    return runCatching {
        InetAddress.getByName(host).isLoopbackAddress
    }.getOrDefault(false)
}
