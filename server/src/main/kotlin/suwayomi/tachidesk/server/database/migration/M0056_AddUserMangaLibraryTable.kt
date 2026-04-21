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
import suwayomi.tachidesk.manga.model.table.MangaTable

@Suppress("ClassName", "unused")
class M0056_AddUserMangaLibraryTable : AddTableMigration() {
    private class UserAccountTable : IntIdTable("user_account")

    private class UserMangaLibraryTable : IntIdTable("user_manga_library") {
        val userId = reference("user_id", UserAccountTable(), ReferenceOption.CASCADE)
        val mangaId = reference("manga_id", MangaTable, ReferenceOption.CASCADE)
        val inLibraryAt = long("in_library_at").default(0)

        init {
            uniqueIndex(userId, mangaId)
        }
    }

    override val tables: Array<Table>
        get() =
            arrayOf(
                UserMangaLibraryTable(),
            )
}
