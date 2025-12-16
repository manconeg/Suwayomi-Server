package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Junction table tracking which manga are in which user's library.
 * Replaces the inLibrary boolean field in MangaTable for multi-user support.
 */
object UserLibraryTable : Table() {
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE)
    val manga = reference("manga_id", MangaTable, ReferenceOption.CASCADE)
    val inLibraryAt = long("in_library_at")

    override val primaryKey = PrimaryKey(user, manga)

    init {
        index(false, user)
        index(false, manga)
    }
}
