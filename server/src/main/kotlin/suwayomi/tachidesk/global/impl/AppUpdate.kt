package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import suwayomi.tachidesk.manga.impl.util.network.await
import suwayomi.tachidesk.server.generated.BuildConfig
import uy.kohesive.injekt.injectLazy

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

data class UpdateDataClass(
    /** [channel] mirrors [suwayomi.tachidesk.server.BuildConfig.BUILD_TYPE] */
    val channel: String,
    val tag: String,
    val url: String,
)

object AppUpdate {
    private const val GITHUB_REPO_PREFIX = "https://github.com/"
    private const val GITHUB_API_REPO_PREFIX = "https://api.github.com/repos/"

    private val json: Json by injectLazy()
    private val network: NetworkHelper by injectLazy()

    suspend fun checkUpdate(): List<UpdateDataClass> {
        val stableRelease =
            fetchLatestReleaseMetadata(
                toGithubApiLatestReleaseUrl(BuildConfig.GITHUB),
            )
        val previewRelease =
            fetchLatestReleaseMetadata(
                toGithubApiLatestReleaseUrl(toPreviewRepositoryUrl(BuildConfig.GITHUB)),
            )

        val fallbackRelease = stableRelease ?: previewRelease
        val resolvedStable = stableRelease ?: fallbackRelease
        val resolvedPreview = previewRelease ?: fallbackRelease

        return buildList {
            resolvedStable?.let { add(UpdateDataClass("Stable", it.tag, it.url)) }
            resolvedPreview?.let { add(UpdateDataClass("Preview", it.tag, it.url)) }
        }
    }

    private fun toGithubApiLatestReleaseUrl(repositoryUrl: String): String {
        val normalizedRepository =
            repositoryUrl
                .trim()
                .removeSuffix("/")
                .removePrefix(GITHUB_REPO_PREFIX)
                .removePrefix("http://github.com/")
                .removePrefix(GITHUB_API_REPO_PREFIX)
                .removePrefix("repos/")

        return "$GITHUB_API_REPO_PREFIX$normalizedRepository/releases/latest"
    }

    private fun toPreviewRepositoryUrl(repositoryUrl: String): String {
        val normalizedRepositoryUrl = repositoryUrl.trim().removeSuffix("/")
        return if (normalizedRepositoryUrl.endsWith("-preview")) {
            normalizedRepositoryUrl
        } else {
            "$normalizedRepositoryUrl-preview"
        }
    }

    private suspend fun fetchLatestReleaseMetadata(apiUrl: String): ReleaseMetadata? =
        runCatching {
            json
                .parseToJsonElement(
                    network.client
                        .newCall(GET(apiUrl))
                        .await()
                        .body
                        .string(),
                ).jsonObject
        }.getOrNull()
            ?.toReleaseMetadata()

    private fun JsonObject.toReleaseMetadata(): ReleaseMetadata? {
        val tag = this["tag_name"]?.jsonPrimitive?.content ?: return null
        val url = this["html_url"]?.jsonPrimitive?.content ?: return null
        return ReleaseMetadata(tag = tag, url = url)
    }

    private data class ReleaseMetadata(
        val tag: String,
        val url: String,
    )
}
