package suwayomi.tachidesk.graphql.mutations

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import graphql.schema.DataFetchingEnvironment
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import suwayomi.tachidesk.graphql.server.toGraphQLContext
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest

class UserMutationSecurityAndRateLimitTest : ApplicationTest() {
    private fun uniqueName(prefix: String = "security") = "$prefix-${System.nanoTime()}-${(1000..9999).random()}"

    private fun mockEnv(
        userType: UserType,
        sourceIp: String,
        path: String = "/api/graphql",
    ): DataFetchingEnvironment {
        val requestContext = mockk<Context>()
        every { requestContext.ip() } returns sourceIp
        every { requestContext.method() } returns HandlerType.POST
        every { requestContext.path() } returns path

        val graphQlContext =
            mapOf(
                Attribute.TachideskUser to userType,
                Context::class to requestContext,
            ).toGraphQLContext()

        return mockk<DataFetchingEnvironment>().also { env ->
            every { env.graphQlContext } returns graphQlContext
        }
    }

    private fun captureSecurityAuditLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("SecurityAudit") as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `login mutation emits login_attempt audit event for invalid credentials`() {
        val mutation = UserMutation()
        val username = uniqueName("missing-user")
        val env = mockEnv(UserType.Visitor, sourceIp = "10.10.10.10")

        val logs =
            captureSecurityAuditLogs {
                assertThrows(Exception::class.java) {
                    mutation.login(
                        env,
                        UserMutation.LoginInput(
                            username = username,
                            password = "wrong-password",
                        ),
                    )
                }
            }

        assertTrue(
            logs.any {
                it.contains("login_attempt") &&
                    it.contains("success=false") &&
                    it.contains("username=$username") &&
                    it.contains("reason=invalid_credentials")
            },
        )
    }

    @Test
    fun `login mutation blocks default admin credentials from non-loopback source`() {
        val mutation = UserMutation()
        val username = serverConfig.authUsername.value.ifBlank { "admin" }
        val password = serverConfig.authPassword.value.ifBlank { "admin" }
        val env = mockEnv(UserType.Visitor, sourceIp = "10.10.10.13")

        val logs =
            captureSecurityAuditLogs {
                val blocked =
                    assertThrows(Exception::class.java) {
                        mutation.login(
                            env,
                            UserMutation.LoginInput(
                                username = username,
                                password = password,
                            ),
                        )
                    }
                assertTrue(blocked.message?.contains("Incorrect username or password.") == true)
            }

        assertTrue(
            logs.any {
                it.contains("login_attempt") &&
                    it.contains("success=false") &&
                    it.contains("username=$username") &&
                    it.contains("reason=default_admin_remote_blocked")
            },
        )
    }

    @Test
    fun `createUser mutation emits admin_action audit event for successful admin mutation`() {
        val mutation = UserMutation()
        val admin = createUserAccount(uniqueName("admin"), "AdminPass1!", UserRole.ADMIN, isActive = true)
        val env = mockEnv(UserType.Admin(admin.id), sourceIp = "10.10.10.11")

        val logs =
            captureSecurityAuditLogs {
                mutation.createUser(
                    env,
                    UserMutation.CreateUserInput(
                        username = uniqueName("created"),
                        password = "UserPass1!!",
                        role = UserRole.USER,
                        isActive = true,
                    ),
                )
            }

        assertTrue(
            logs.any {
                it.contains("admin_action") &&
                    it.contains("adminUserId=${admin.id}") &&
                    it.contains("action=createUser")
            },
        )
    }

    @Test
    fun `createUser mutation emits unauthorized_access audit event when non-admin attempts admin mutation`() {
        val mutation = UserMutation()
        val nonAdmin = createUserAccount(uniqueName("user"), "UserPass1!!", UserRole.USER, isActive = true)
        val env = mockEnv(UserType.Admin(nonAdmin.id), sourceIp = "10.10.10.12")

        val logs =
            captureSecurityAuditLogs {
                assertThrows(ForbiddenException::class.java) {
                    mutation.createUser(
                        env,
                        UserMutation.CreateUserInput(
                            username = uniqueName("blocked"),
                            password = "UserPass1!!",
                        ),
                    )
                }
            }

        assertTrue(
            logs.any {
                it.contains("unauthorized_access") &&
                    it.contains("reason=admin_forbidden:createUser")
            },
        )
    }

    @Test
    fun `login route limiter blocks after threshold`() {
        val mutation = UserMutation()
        val username = uniqueName("rate-login")
        val env = mockEnv(UserType.Visitor, sourceIp = "10.20.0.1")
        val input = UserMutation.LoginInput(username = username, password = "not-correct")

        repeat(12) {
            val thrown =
                assertThrows(Exception::class.java) {
                    mutation.login(env, input)
                }
            assertFalse(
                thrown is IllegalArgumentException && thrown.message?.contains("Too many login attempts") == true,
                "Login should not be rate-limited before reaching threshold",
            )
        }

        val rateLimited =
            assertThrows(IllegalArgumentException::class.java) {
                mutation.login(env, input)
            }
        assertTrue(rateLimited.message?.contains("Too many login attempts") == true)
    }

    @Test
    fun `refresh route limiter blocks after threshold`() {
        val mutation = UserMutation()
        val env = mockEnv(UserType.Visitor, sourceIp = "10.20.0.2")
        val input = UserMutation.RefreshTokenInput(refreshToken = "invalid-refresh-token")

        repeat(20) {
            val thrown =
                assertThrows(Exception::class.java) {
                    mutation.refreshToken(env, input)
                }
            assertFalse(
                thrown is IllegalArgumentException && thrown.message?.contains("Too many refresh requests") == true,
                "Refresh should not be rate-limited before reaching threshold",
            )
        }

        val rateLimited =
            assertThrows(IllegalArgumentException::class.java) {
                mutation.refreshToken(env, input)
            }
        assertTrue(rateLimited.message?.contains("Too many refresh requests") == true)
    }

    @Test
    fun `admin mutation route limiter blocks after threshold`() {
        val mutation = UserMutation()
        val admin = createUserAccount(uniqueName("admin"), "AdminPass1!", UserRole.ADMIN, isActive = true)
        val env = mockEnv(UserType.Admin(admin.id), sourceIp = "10.20.0.3")
        val input = UserMutation.ForceSignOutUserInput(id = admin.id)

        repeat(60) {
            mutation.forceSignOutUser(env, input)
        }

        val rateLimited =
            assertThrows(IllegalArgumentException::class.java) {
                mutation.forceSignOutUser(env, input)
            }
        assertTrue(rateLimited.message?.contains("Too many admin operations") == true)
    }
}
