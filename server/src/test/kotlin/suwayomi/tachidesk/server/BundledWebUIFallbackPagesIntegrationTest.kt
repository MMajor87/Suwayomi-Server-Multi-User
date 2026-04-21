package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.graphql.types.WebUIFlavor
import suwayomi.tachidesk.server.generated.BuildConfig
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.BASE_PATH
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class BundledWebUIFallbackPagesIntegrationTest : ApplicationTest() {
    @Test
    fun `login page still renders while bundled webui is active`() {
        val response = get("/login.html")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("<form method=\"POST\">"), response.body())
    }

    @Test
    fun `setup page still renders while bundled webui is active`() {
        val response = get("/setup.html")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Create Admin Account"), response.body())
    }

    @Test
    fun `admin users page still renders while bundled webui is active`() {
        val response = get("/admin/users.html")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Open User Management in the WebUI"), response.body())
        assertTrue(response.body().contains("../settings/users"), response.body())
    }

    private fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$baseHttpUrl$path"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private lateinit var httpClient: HttpClient
        private lateinit var baseHttpUrl: String

        private lateinit var previousAuthMode: AuthMode
        private lateinit var previousWebUIFlavor: WebUIFlavor
        private var previousWebUIUpdateInterval: Double = 0.0

        @JvmStatic
        @BeforeAll
        fun startServer() {
            val port =
                ServerSocket(0).use {
                    it.localPort
                }

            previousAuthMode = serverConfig.authMode.value
            previousWebUIFlavor = serverConfig.webUIFlavor.value
            previousWebUIUpdateInterval = serverConfig.webUIUpdateCheckInterval.value

            serverConfig.initialOpenInBrowserEnabled.value = false
            serverConfig.ip.value = "127.0.0.1"
            serverConfig.port.value = port
            serverConfig.authMode.value = AuthMode.UI_LOGIN
            serverConfig.webUIFlavor.value = WebUIFlavor.BUNDLED
            serverConfig.webUIUpdateCheckInterval.value = 0.0

            prepareBundledWebUIFilesForTest()
            seedAdminUser()
            JavalinSetup.javalinSetup()

            httpClient =
                HttpClient
                    .newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            baseHttpUrl = "http://127.0.0.1:$port"
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            JavalinSetup.stopForTests()

            serverConfig.authMode.value = previousAuthMode
            serverConfig.webUIFlavor.value = previousWebUIFlavor
            serverConfig.webUIUpdateCheckInterval.value = previousWebUIUpdateInterval
        }

        private fun seedAdminUser() {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
            createUserAccount(
                username = "bundled_admin_$suffix",
                password = "PassWord1234!",
                role = UserRole.ADMIN,
                isActive = true,
            )
        }

        private fun prepareBundledWebUIFilesForTest() {
            val applicationDirs = ApplicationDirs(dataRoot = BASE_PATH)
            val webUIRoot = Path.of(applicationDirs.webUIRoot)
            Files.createDirectories(webUIRoot)
            Files.writeString(webUIRoot.resolve("revision"), BuildConfig.WEBUI_TAG)
            if (!Files.exists(webUIRoot.resolve("index.html"))) {
                Files.writeString(webUIRoot.resolve("index.html"), "<!doctype html><html><body>bundled test ui</body></html>")
            }
        }
    }
}
