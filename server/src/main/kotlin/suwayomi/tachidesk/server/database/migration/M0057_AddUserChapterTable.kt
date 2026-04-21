package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import suwayomi.tachidesk.manga.model.table.ChapterTable

@Suppress("ClassName", "unused")
class M0057_AddUserChapterTable : AddTableMigration() {
    private class UserAccountTable : IntIdTable("user_account")

    private class UserChapterTable : IntIdTable("user_chapter") {
        val userId = reference("user_id", UserAccountTable(), ReferenceOption.CASCADE)
        val chapterId = reference("chapter_id", ChapterTable, ReferenceOption.CASCADE)
        val isRead = bool("read").default(false)
        val isBookmarked = bool("bookmark").default(false)
        val lastPageRead = integer("last_page_read").default(0)
        val lastReadAt = long("last_read_at").default(0)
        val updatedAt = long("updated_at").default(0)

        init {
            uniqueIndex(userId, chapterId)
        }
    }

    override val tables: Array<Table>
        get() =
            arrayOf(
                UserChapterTable(),
            )
}
