package suwayomi.tachidesk.global.impl.util

import android.app.Application
import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.model.table.UserRefreshTokenTable
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.findActiveUserById
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object Jwt {
    private val preferenceStore =
        Injekt.get<Application>().getSharedPreferences("jwt", Context.MODE_PRIVATE)
    private val logger = KotlinLogging.logger {}

    private const val ALGORITHM = "HmacSHA256"
    private val accessTokenExpiry get() = serverConfig.jwtTokenExpiry.value
    private val refreshTokenExpiry get() = serverConfig.jwtRefreshExpiry.value
    private const val ISSUER = "suwayomi-server"
    private val AUDIENCE get() = serverConfig.jwtAudience.value

    private const val PREF_KEY = "jwt_key"

    @OptIn(ExperimentalEncodingApi::class)
    fun generateSecret(): String {
        val byteString = preferenceStore.getString(PREF_KEY, "")
        val decodedKeyBytes =
            try {
                Base64.Default.decode(byteString)
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Invalid key specified, regenerating" }
                null
            }

        val keyBytes =
            if (decodedKeyBytes?.size == 32) {
                decodedKeyBytes
            } else {
                val k = ByteArray(32)
                SecureRandom().nextBytes(k)
                preferenceStore.edit().putString(PREF_KEY, Base64.Default.encode(k)).apply()
                k
            }

        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)

        return Base64.encode(secretKey.encoded)
    }

    private val algorithm: Algorithm = Algorithm.HMAC256(generateSecret())
    private val verifier: JWTVerifier = JWT.require(algorithm).build()

    class JwtTokens(
        val accessToken: String,
        val refreshToken: String,
    )

    fun generateJwt(userId: Int): JwtTokens {
        val user = findActiveUserById(userId) ?: throw IllegalArgumentException("Cannot create token for unknown/inactive user $userId")
        val nowMs = System.currentTimeMillis()
        val expiresAtMs = nowMs + refreshTokenExpiry.inWholeMilliseconds
        val refreshJti = UUID.randomUUID().toString()
        val accessToken = createAccessToken(user.id, user.tokenVersion)
        val refreshToken = createRefreshToken(user.id, user.tokenVersion, refreshJti, expiresAtMs)
        persistRefreshToken(user.id, user.tokenVersion, refreshJti, expiresAtMs, issuedAtMs = nowMs)

        return JwtTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }

    fun refreshJwt(refreshToken: String): JwtTokens {
        val jwt = verifier.verify(refreshToken)
        require(jwt.getClaim("token_type").asString() == "refresh") {
            "Cannot use access token to refresh"
        }
        require(jwt.audience.single() == AUDIENCE) {
            "Token intended for different audience ${jwt.audience}"
        }

        val userId = extractUserId(jwt)
        val tokenVersion = extractTokenVersion(jwt)
        val refreshJti = extractRefreshJti(jwt)
        val user = findActiveUserById(userId)
        require(user != null) { "Token user no longer exists or is inactive" }
        require(user.tokenVersion == tokenVersion) { "Token version mismatch for user $userId" }

        val nowMs = System.currentTimeMillis()
        val expiresAtMs = nowMs + refreshTokenExpiry.inWholeMilliseconds
        val nextRefreshJti = UUID.randomUUID().toString()

        transaction {
            val refreshTokenRow =
                UserRefreshTokenTable
                    .selectAll()
                    .where {
                        (UserRefreshTokenTable.jti eq refreshJti) and
                            (UserRefreshTokenTable.userId eq userId)
                    }.singleOrNull()
                    ?: throw IllegalArgumentException("Refresh token session was not found.")

            require(refreshTokenRow[UserRefreshTokenTable.tokenVersion] == tokenVersion) {
                "Refresh token version mismatch."
            }
            require(refreshTokenRow[UserRefreshTokenTable.revokedAt] == null) {
                "Refresh token has been revoked."
            }
            require(refreshTokenRow[UserRefreshTokenTable.rotatedAt] == null) {
                "Refresh token has already been rotated."
            }
            require(refreshTokenRow[UserRefreshTokenTable.expiresAt] > nowMs) {
                "Refresh token session is expired."
            }

            UserRefreshTokenTable.update({ UserRefreshTokenTable.jti eq refreshJti }) {
                it[UserRefreshTokenTable.rotatedAt] = nowMs
                it[UserRefreshTokenTable.revokedAt] = nowMs
                it[UserRefreshTokenTable.replacementJti] = nextRefreshJti
            }

            UserRefreshTokenTable.insert {
                it[UserRefreshTokenTable.jti] = nextRefreshJti
                it[UserRefreshTokenTable.userId] = userId
                it[UserRefreshTokenTable.tokenVersion] = tokenVersion
                it[UserRefreshTokenTable.issuedAt] = nowMs
                it[UserRefreshTokenTable.expiresAt] = expiresAtMs
                it[UserRefreshTokenTable.rotatedAt] = null
                it[UserRefreshTokenTable.revokedAt] = null
                it[UserRefreshTokenTable.replacementJti] = null
            }
        }

        return JwtTokens(
            accessToken = createAccessToken(userId, tokenVersion),
            refreshToken = createRefreshToken(userId, tokenVersion, nextRefreshJti, expiresAtMs),
        )
    }

    fun verifyJwt(jwt: String): UserType {
        try {
            val decodedJWT = verifier.verify(jwt)

            require(decodedJWT.getClaim("token_type").asString() == "access") {
                "Cannot use refresh token to access"
            }
            require(decodedJWT.audience.single() == AUDIENCE) {
                "Token intended for different audience ${decodedJWT.audience}"
            }

            val userId = extractUserId(decodedJWT)
            val tokenVersion = extractTokenVersion(decodedJWT)
            val authenticatedUser = findActiveUserById(userId) ?: return UserType.Visitor
            if (authenticatedUser.tokenVersion != tokenVersion) {
                return UserType.Visitor
            }

            return UserType.Admin(authenticatedUser.id)
        } catch (e: JWTVerificationException) {
            logger.warn(e) { "Received invalid token" }
            return UserType.Visitor
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Received token with invalid claims" }
            return UserType.Visitor
        }
    }

    private fun extractUserId(decodedJWT: DecodedJWT): Int {
        val claimUserId = decodedJWT.getClaim("user_id").asInt()
        val subjectUserId = decodedJWT.subject?.toIntOrNull()
        val userId = claimUserId ?: subjectUserId
        require(userId != null && userId > 0) { "Token missing valid user id" }
        return userId
    }

    private fun extractTokenVersion(decodedJWT: DecodedJWT): Int {
        val version = decodedJWT.getClaim("token_version").asInt()
        require(version != null && version > 0) { "Token missing valid token_version claim" }
        return version
    }

    private fun extractRefreshJti(decodedJWT: DecodedJWT): String {
        val jti = decodedJWT.id
        require(!jti.isNullOrBlank()) { "Refresh token missing jti claim" }
        return jti
    }

    private fun createAccessToken(
        userId: Int,
        tokenVersion: Int,
    ): String {
        val jwt =
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(userId.toString())
                .withClaim("user_id", userId)
                .withClaim("token_version", tokenVersion)
                .withClaim("token_type", "access")
                .withExpiresAt(Instant.now().plusSeconds(accessTokenExpiry.inWholeSeconds))

        return jwt.sign(algorithm)
    }

    private fun createRefreshToken(
        userId: Int,
        tokenVersion: Int,
        jti: String,
        expiresAtMs: Long,
    ): String =
        JWT
            .create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(userId.toString())
            .withJWTId(jti)
            .withClaim("user_id", userId)
            .withClaim("token_version", tokenVersion)
            .withClaim("token_type", "refresh")
            .withExpiresAt(Instant.ofEpochMilli(expiresAtMs))
            .sign(algorithm)

    private fun persistRefreshToken(
        userId: Int,
        tokenVersion: Int,
        jti: String,
        expiresAtMs: Long,
        issuedAtMs: Long = System.currentTimeMillis(),
    ) {
        transaction {
            UserRefreshTokenTable.insert {
                it[UserRefreshTokenTable.jti] = jti
                it[UserRefreshTokenTable.userId] = userId
                it[UserRefreshTokenTable.tokenVersion] = tokenVersion
                it[UserRefreshTokenTable.issuedAt] = issuedAtMs
                it[UserRefreshTokenTable.expiresAt] = expiresAtMs
                it[UserRefreshTokenTable.rotatedAt] = null
                it[UserRefreshTokenTable.revokedAt] = null
                it[UserRefreshTokenTable.replacementJti] = null
            }
        }
    }
}
