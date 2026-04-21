package suwayomi.tachidesk.server.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import suwayomi.tachidesk.manga.model.table.ChapterTable

object UserChapterTable : IntIdTable("user_chapter") {
    val userId = reference("user_id", UserAccountTable, onDelete = ReferenceOption.CASCADE)
    val chapterId = reference("chapter_id", ChapterTable, onDelete = ReferenceOption.CASCADE)
    val isRead = bool("read").default(false)
    val isBookmarked = bool("bookmark").default(false)
    val lastPageRead = integer("last_page_read").default(0)
    val lastReadAt = long("last_read_at").default(0)
    val updatedAt = long("updated_at").default(0)

    init {
        uniqueIndex(userId, chapterId)
        index(false, userId, chapterId)
        index(false, userId, isRead)
        index(false, userId, isBookmarked)
    }
}
