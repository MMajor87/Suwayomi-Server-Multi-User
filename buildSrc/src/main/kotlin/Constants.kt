import java.io.BufferedReader

/*
 * Copyright (C) Contributors to the Suwayomi project
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

const val MainClass = "suwayomi.tachidesk.MainKt"

// should be bumped with each stable release
val getTachideskVersion = { "v2.1.${getCommitCount()}" }

val webUIRevisionTag = "r2643"

// Short SHA of the webUI/ submodule HEAD — embedded in BuildConfig so the server
// can display which WebUI commit is bundled. Falls back to "unknown" when the
// submodule has not been initialised (e.g. fresh clone without --recurse-submodules).
val getWebUIBuildCommit = {
    runCatching {
        ProcessBuilder()
            .command("git", "rev-parse", "HEAD:webUI")
            .start()
            .let { process ->
                process.waitFor()
                val output = process.inputStream.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                process.destroy()
                output.trim().take(8)
            }
    }.getOrDefault("unknown")
}

private val getCommitCount = {
    runCatching {
        ProcessBuilder()
            .command("git", "rev-list", "HEAD", "--count")
            .start()
            .let { process ->
                process.waitFor()
                val output = process.inputStream.use {
                    it.bufferedReader().use(BufferedReader::readText)
                }
                process.destroy()
                output.trim()
            }
    }.getOrDefault("0")
}

// counts commits on the current checked out branch
val getTachideskRevision = { "r${getCommitCount()}" }

