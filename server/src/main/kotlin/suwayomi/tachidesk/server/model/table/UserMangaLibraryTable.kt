package suwayomi.tachidesk.server.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import suwayomi.tachidesk.manga.model.table.MangaTable

object UserMangaLibraryTable : IntIdTable("user_manga_library") {
    val userId = reference("user_id", UserAccountTable, onDelete = ReferenceOption.CASCADE)
    val mangaId = reference("manga_id", MangaTable, onDelete = ReferenceOption.CASCADE)
    val inLibraryAt = long("in_library_at").default(0)

    init {
        uniqueIndex(userId, mangaId)
        index(false, userId, inLibraryAt)
        index(false, mangaId)
    }
}
