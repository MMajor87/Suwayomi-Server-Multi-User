package suwayomi.tachidesk.server.user

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val PASSWORD_HASH_ALGO = "pbkdf2_sha256"
private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
private const val PBKDF2_ITERATIONS = 210_000
private const val SALT_BYTES = 16
private const val HASH_BYTES = 32
private const val PASSWORD_MIN_LENGTH = 10

data class PasswordVerificationResult(
    val matches: Boolean,
    val needsRehash: Boolean,
)

@OptIn(ExperimentalEncodingApi::class)
object PasswordSecurity {
    private val secureRandom = SecureRandom()

    fun validatePasswordPolicy(password: String) {
        require(password.length >= PASSWORD_MIN_LENGTH) {
            "Password must be at least $PASSWORD_MIN_LENGTH characters long."
        }
        require(password.any { it.isUpperCase() }) {
            "Password must contain at least one uppercase character."
        }
        require(password.any { it.isLowerCase() }) {
            "Password must contain at least one lowercase character."
        }
        require(password.any { it.isDigit() }) {
            "Password must contain at least one digit."
        }
    }

    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val hash = pbkdf2(password, salt, PBKDF2_ITERATIONS, HASH_BYTES)

        return listOf(
            PASSWORD_HASH_ALGO,
            PBKDF2_ITERATIONS.toString(),
            Base64.Default.encode(salt),
            Base64.Default.encode(hash),
        ).joinToString("$")
    }

    fun verifyPassword(
        password: String,
        storedPasswordHash: String,
    ): PasswordVerificationResult {
        val parts = storedPasswordHash.split('$')
        if (parts.size != 4 || parts[0] != PASSWORD_HASH_ALGO) {
            return PasswordVerificationResult(
                matches = password == storedPasswordHash,
                needsRehash = password == storedPasswordHash,
            )
        }

        val iterations = parts[1].toIntOrNull() ?: return PasswordVerificationResult(false, false)
        val salt = decodeBase64(parts[2]) ?: return PasswordVerificationResult(false, false)
        val expectedHash = decodeBase64(parts[3]) ?: return PasswordVerificationResult(false, false)
        val calculatedHash = pbkdf2(password, salt, iterations, expectedHash.size)
        val matches = MessageDigest.isEqual(calculatedHash, expectedHash)
        val needsRehash = matches && iterations < PBKDF2_ITERATIONS

        return PasswordVerificationResult(matches, needsRehash)
    }

    private fun pbkdf2(
        password: String,
        salt: ByteArray,
        iterations: Int,
        outputBytes: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, outputBytes * 8)
        return SecretKeyFactory
            .getInstance(PBKDF2_ALGO)
            .generateSecret(spec)
            .encoded
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.Default.decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
}
