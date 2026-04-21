package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.server.user.PasswordSecurity
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class PasswordSecurityTest {
    // --- Policy validation ---

    @Test
    fun `validatePasswordPolicy throws when password is too short`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordSecurity.validatePasswordPolicy("Short1A")
        }
    }

    @Test
    fun `validatePasswordPolicy throws when password has no uppercase`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordSecurity.validatePasswordPolicy("alllowercase1!")
        }
    }

    @Test
    fun `validatePasswordPolicy throws when password has no lowercase`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordSecurity.validatePasswordPolicy("ALLUPPERCASE1!")
        }
    }

    @Test
    fun `validatePasswordPolicy throws when password has no digit`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordSecurity.validatePasswordPolicy("NoDigitsHereAtAll!")
        }
    }

    @Test
    fun `validatePasswordPolicy passes for a valid password`() {
        PasswordSecurity.validatePasswordPolicy("ValidPass1!")
    }

    @Test
    fun `validatePasswordPolicy passes for minimum length password meeting all rules`() {
        PasswordSecurity.validatePasswordPolicy("Abcdefgh1!")
    }

    // --- Hashing ---

    @Test
    fun `hashPassword produces a pbkdf2_sha256 format hash`() {
        val hash = PasswordSecurity.hashPassword("ValidPass1!")
        assertTrue(hash.startsWith("pbkdf2_sha256\$"), "Expected hash to start with 'pbkdf2_sha256\$', got: $hash")
        val parts = hash.split("$")
        assertEquals(4, parts.size, "Expected 4 parts in hash string")
    }

    @Test
    fun `hashPassword produces different salts for the same password`() {
        val hash1 = PasswordSecurity.hashPassword("ValidPass1!")
        val hash2 = PasswordSecurity.hashPassword("ValidPass1!")
        // Same password, different salts → different hashes
        assertFalse(hash1 == hash2, "Two hashes of the same password should differ due to random salt")
    }

    // --- Verification ---

    @Test
    fun `verifyPassword returns matches=true for correct password`() {
        val password = "ValidPass1!"
        val hash = PasswordSecurity.hashPassword(password)
        val result = PasswordSecurity.verifyPassword(password, hash)
        assertTrue(result.matches)
    }

    @Test
    fun `verifyPassword returns matches=false for wrong password`() {
        val hash = PasswordSecurity.hashPassword("ValidPass1!")
        val result = PasswordSecurity.verifyPassword("WrongPass9!", hash)
        assertFalse(result.matches)
    }

    @Test
    fun `verifyPassword needsRehash is false for a hash produced at current iteration count`() {
        val password = "ValidPass1!"
        val hash = PasswordSecurity.hashPassword(password)
        val result = PasswordSecurity.verifyPassword(password, hash)
        assertTrue(result.matches)
        assertFalse(result.needsRehash, "Current-iteration hash should not need rehash")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `verifyPassword needsRehash is true for a hash produced with fewer iterations`() {
        val password = "ValidPass1!"
        val oldIterations = 100_000
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, oldIterations, 32 * 8)
        val hashBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val legacyHash =
            "pbkdf2_sha256\$$oldIterations\$${Base64.Default.encode(salt)}\$${Base64.Default.encode(hashBytes)}"

        val result = PasswordSecurity.verifyPassword(password, legacyHash)
        assertTrue(result.matches, "Should still verify correctly with old iterations")
        assertTrue(result.needsRehash, "Should request rehash when stored iterations are below current")
    }

    @Test
    fun `verifyPassword treats a plaintext stored value as a match only if equal`() {
        // Legacy path: stored value is not in pbkdf2_sha256$ format
        val plaintext = "oldstoredplain"
        val matchResult = PasswordSecurity.verifyPassword("oldstoredplain", plaintext)
        assertTrue(matchResult.matches)
        assertTrue(matchResult.needsRehash, "Plaintext match should signal that rehash is needed")

        val mismatchResult = PasswordSecurity.verifyPassword("differentpassword", plaintext)
        assertFalse(mismatchResult.matches)
    }
}
