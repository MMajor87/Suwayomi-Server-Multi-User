package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.download.DownloadManager.EnqueueInput
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.model.table.UserRole
import suwayomi.tachidesk.server.user.createUserAccount
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NonRestSurfaceIsolationIntegrationTest : ApplicationTest() {
    @BeforeEach
    fun clearDownloadQueue() {
        runBlocking {
            DownloadManager.clear()
            DownloadManager.stop()
        }
    }

    @Test
    fun `opds library series feed is user scoped`() {
        val scenario = createTwoUserTwoMangaScenario()

        val responseA = get("/api/opds/v1.2/library/series", scenario.userAToken)
        assertEquals(200, responseA.statusCode())
        assertTrue(responseA.body().contains(scenario.mangaATitle))
        assertFalse(responseA.body().contains(scenario.mangaBTitle))

        val responseB = get("/api/opds/v1.2/library/series", scenario.userBToken)
        assertEquals(200, responseB.statusCode())
        assertTrue(responseB.body().contains(scenario.mangaBTitle))
        assertFalse(responseB.body().contains(scenario.mangaATitle))
    }

    @Test
    fun `download websocket status payload is user scoped`() {
        val scenario = createTwoUserTwoMangaScenario()

        val messageA = receiveDownloadWsStatus(scenario.userAToken)
        assertTrue(messageA.contains("\"mangaId\":${scenario.mangaAId}"))
        assertFalse(messageA.contains("\"mangaId\":${scenario.mangaBId}"))

        val messageB = receiveDownloadWsStatus(scenario.userBToken)
        assertTrue(messageB.contains("\"mangaId\":${scenario.mangaBId}"))
        assertFalse(messageB.contains("\"mangaId\":${scenario.mangaAId}"))
    }

    @Test
    fun `graphql download subscription payload is user scoped`() {
        val scenario = createTwoUserTwoMangaScenario()

        val messageA = receiveGraphqlDownloadSubscriptionEvent(scenario.userAToken)
        assertTrue(messageA.contains(scenario.mangaATitle))
        assertFalse(messageA.contains(scenario.mangaBTitle))

        val messageB = receiveGraphqlDownloadSubscriptionEvent(scenario.userBToken)
        assertTrue(messageB.contains(scenario.mangaBTitle))
        assertFalse(messageB.contains(scenario.mangaATitle))
    }

    @Test
    fun `graphql chapter updates query is user scoped`() {
        val scenario = createTwoUserTwoMangaScenario()
        val query =
            """
            query {
              chapters(
                filter: { inLibrary: { equalTo: true } }
                order: [{ by: FETCHED_AT, byType: DESC }, { by: SOURCE_ORDER, byType: DESC }]
                first: 50
              ) {
                nodes {
                  id
                  manga {
                    id
                    title
                  }
                }
              }
            }
            """.trimIndent()

        val responseA = postGraphql(query, scenario.userAToken)
        assertEquals(200, responseA.statusCode())
        assertTrue(responseA.body().contains(scenario.mangaATitle), responseA.body())
        assertFalse(responseA.body().contains(scenario.mangaBTitle), responseA.body())

        val responseB = postGraphql(query, scenario.userBToken)
        assertEquals(200, responseB.statusCode())
        assertTrue(responseB.body().contains(scenario.mangaBTitle), responseB.body())
        assertFalse(responseB.body().contains(scenario.mangaATitle), responseB.body())
    }

    @Test
    fun `graphql login mutation is reachable without auth header in UI_LOGIN mode`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val username = "graphql_login_$suffix"
        val password = "PassWord1234!"
        createUserAccount(username, password, UserRole.USER, isActive = true)

        val response =
            postGraphql(
                """
                mutation {
                  login(input: { username: "$username", password: "$password" }) {
                    accessToken
                    refreshToken
                  }
                }
                """.trimIndent(),
            )

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"accessToken\""), response.body())
        assertFalse(response.body().contains("\"Unauthorized\""), response.body())
    }

    private fun createTwoUserTwoMangaScenario(): Scenario {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val userA = createUserAccount("opds_user_a_$suffix", "PassWord1234", UserRole.USER, isActive = true)
        val userB = createUserAccount("opds_user_b_$suffix", "PassWord1234", UserRole.USER, isActive = true)
        val sourceId = ensureOpdsTestSource()

        val mangaATitle = "Scoped Manga A $suffix"
        val mangaBTitle = "Scoped Manga B $suffix"
        val mangaAId = createMangaForSource(mangaATitle, sourceId)
        val mangaBId = createMangaForSource(mangaBTitle, sourceId)

        runBlocking {
            Library.addMangaToLibrary(userA.id, mangaAId)
            Library.addMangaToLibrary(userB.id, mangaBId)
        }

        createChapters(mangaAId, amount = 1, read = false)
        createChapters(mangaBId, amount = 1, read = false)

        val chapterAId = firstChapterIdForManga(mangaAId)
        val chapterBId = firstChapterIdForManga(mangaBId)
        DownloadManager.enqueue(EnqueueInput(listOf(chapterAId, chapterBId)))
        runBlocking { DownloadManager.stop() }

        return Scenario(
            userAToken = Jwt.generateJwt(userA.id).accessToken,
            userBToken = Jwt.generateJwt(userB.id).accessToken,
            mangaAId = mangaAId,
            mangaBId = mangaBId,
            mangaATitle = mangaATitle,
            mangaBTitle = mangaBTitle,
        )
    }

    private fun firstChapterIdForManga(mangaId: Int): Int =
        transaction {
            ChapterTable
                .select(ChapterTable.id)
                .where { ChapterTable.manga eq mangaId }
                .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                .first()[ChapterTable.id]
                .value
        }

    private fun ensureOpdsTestSource(): Long =
        transaction {
            val existing =
                SourceTable
                    .select(SourceTable.id)
                    .where { SourceTable.id eq TEST_SOURCE_ID }
                    .firstOrNull()
                    ?.get(SourceTable.id)
                    ?.value
            if (existing != null) {
                return@transaction existing
            }

            val extensionId =
                ExtensionTable.insertAndGetId {
                    it[apkName] = "opds-test-$TEST_SOURCE_ID.apk"
                    it[name] = "OPDS Test Extension"
                    it[pkgName] = "opds.test.$TEST_SOURCE_ID"
                    it[versionName] = "1.0.0"
                    it[versionCode] = 1
                    it[lang] = "en"
                    it[isNsfw] = false
                }.value

            SourceTable.insert {
                it[id] = TEST_SOURCE_ID
                it[name] = "OPDS Test Source"
                it[lang] = "en"
                it[extension] = extensionId
                it[isNsfw] = false
            }
            TEST_SOURCE_ID
        }

    private fun createMangaForSource(
        title: String,
        sourceId: Long,
    ): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[url] = "/$title"
                    it[MangaTable.title] = title
                    it[sourceReference] = sourceId
                    it[inLibrary] = false
                }.value
        }

    private fun get(
        path: String,
        token: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("$baseHttpUrl$path"))
                .header("Authorization", "Bearer $token")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun postGraphql(
        query: String,
        token: String? = null,
    ): HttpResponse<String> {
        val escapedQuery =
            query
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        val payload = """{"query":"$escapedQuery"}"""

        val requestBuilder =
            HttpRequest
                .newBuilder(URI("$baseHttpUrl/api/graphql"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val request =
            requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun receiveDownloadWsStatus(token: String): String {
        val listener = QueueingWebSocketListener()
        val webSocket =
            httpClient
                .newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .buildAsync(URI("$baseWsUrl/api/v1/downloads"), listener)
                .join()

        webSocket.sendText("STATUS", true).join()
        val payload = listener.awaitMessageContaining("mangaId")
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
        return payload
    }

    private fun receiveGraphqlDownloadSubscriptionEvent(token: String): String {
        val listener = QueueingWebSocketListener()
        val webSocket =
            httpClient
                .newWebSocketBuilder()
                .buildAsync(URI("$baseWsUrl/api/graphql"), listener)
                .join()

        val initMessage = """{"type":"connection_init","payload":{"Authorization":"$token"}}"""
        webSocket.sendText(initMessage, true).join()
        listener.awaitMessageContaining("\"type\":\"connection_ack\"")

        val subscribeMessage =
            """
            {
              "id":"1",
              "type":"subscribe",
              "payload":{
                "query":"subscription ScopedDownloadUpdates { downloadStatusChanged(input: { maxUpdates: 50 }) { initial { manga { id title } } } }"
              }
            }
            """.trimIndent().replace("\n", "")

        webSocket.sendText(subscribeMessage, true).join()
        val nextMessage = listener.awaitMessageContaining("\"type\":\"next\"")
        webSocket.sendText("""{"id":"1","type":"complete"}""", true).join()
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
        return nextMessage
    }

    private data class Scenario(
        val userAToken: String,
        val userBToken: String,
        val mangaAId: Int,
        val mangaBId: Int,
        val mangaATitle: String,
        val mangaBTitle: String,
    )

    private class QueueingWebSocketListener : WebSocket.Listener {
        private val queue = LinkedBlockingQueue<String>()
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            textBuffer.append(data)
            if (last) {
                queue.offer(textBuffer.toString())
                textBuffer.clear()
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        fun awaitMessageContaining(snippet: String): String {
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
            while (System.currentTimeMillis() < deadline) {
                val message = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (message.contains(snippet)) {
                    return message
                }
            }
            error("Timed out waiting for websocket message containing '$snippet'")
        }
    }

    companion object {
        private const val TEST_SOURCE_ID = 9_000_001L
        private lateinit var httpClient: HttpClient
        private lateinit var baseHttpUrl: String
        private lateinit var baseWsUrl: String

        @JvmStatic
        @BeforeAll
        fun startServer() {
            val port =
                ServerSocket(0).use {
                    it.localPort
                }

            serverConfig.initialOpenInBrowserEnabled.value = false
            serverConfig.ip.value = "127.0.0.1"
            serverConfig.port.value = port
            serverConfig.authMode.value = AuthMode.UI_LOGIN

            JavalinSetup.javalinSetup()

            httpClient =
                HttpClient
                    .newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
            baseHttpUrl = "http://127.0.0.1:$port"
            baseWsUrl = "ws://127.0.0.1:$port"
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            runBlocking {
                DownloadManager.clear()
                DownloadManager.stop()
            }
            JavalinSetup.stopForTests()
        }
    }
}
