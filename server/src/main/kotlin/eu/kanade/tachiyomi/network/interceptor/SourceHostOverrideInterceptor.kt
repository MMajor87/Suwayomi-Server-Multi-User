package eu.kanade.tachiyomi.network.interceptor

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/**
 * Rewrites request hosts using a runtime map from the system property:
 * suwayomi.tachidesk.config.server.sourceHostOverrides
 *
 * Format:
 * old.host=https://new.host,another.host=https://mirror.host
 */
class SourceHostOverrideInterceptor : Interceptor {
    private val logger = KotlinLogging.logger {}

    private val hostOverrides: Map<String, String> by lazy {
        parseHostOverrides(
            System.getProperty("suwayomi.tachidesk.config.server.sourceHostOverrides").orEmpty()
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestUrl = request.url
        val host = requestUrl.host.lowercase(Locale.ROOT)

        val target = hostOverrides[host]
            ?: hostOverrides[host.removePrefix("www.")]
            ?: return chain.proceed(request)

        val targetUrl = target.toHttpUrlOrNull()
        if (targetUrl == null) {
            logger.warn { "Ignoring invalid source host override target: $target" }
            return chain.proceed(request)
        }

        val rewrittenUrl = requestUrl.newBuilder()
            .scheme(targetUrl.scheme)
            .host(targetUrl.host)
            .port(targetUrl.port)
            .build()

        val rewrittenRequest = request.newBuilder()
            .url(rewrittenUrl)
            .build()

        return chain.proceed(rewrittenRequest)
    }

    private fun parseHostOverrides(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()

        return raw.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val separatorIndex = entry.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) {
                    logger.warn { "Ignoring invalid source host override entry: $entry" }
                    return@mapNotNull null
                }

                val left = entry.substring(0, separatorIndex).trim()
                val right = entry.substring(separatorIndex + 1).trim()
                if (left.isEmpty() || right.isEmpty()) {
                    logger.warn { "Ignoring invalid source host override entry: $entry" }
                    return@mapNotNull null
                }

                val normalizedHost = normalizeHost(left) ?: run {
                    logger.warn { "Ignoring invalid source host in override entry: $entry" }
                    return@mapNotNull null
                }

                val normalizedTarget = normalizeTarget(right) ?: run {
                    logger.warn { "Ignoring invalid source target in override entry: $entry" }
                    return@mapNotNull null
                }

                normalizedHost to normalizedTarget
            }
            .toMap()
    }

    private fun normalizeHost(value: String): String? {
        val fromUrl = value.toHttpUrlOrNull()?.host
        if (fromUrl != null) {
            return fromUrl.lowercase(Locale.ROOT)
        }

        val cleaned = value.removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
            .trim()
            .lowercase(Locale.ROOT)

        if (cleaned.isBlank()) return null
        if (cleaned.contains(" ")) return null

        return cleaned
    }

    private fun normalizeTarget(value: String): String? {
        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        return withScheme.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/")?.build()?.toString()
    }
}

