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
 * Stores per-user chapter reading progress.
 * Replaces the reading progress fields in ChapterTable for multi-user support.
 */
object UserChapterProgressTable : Table() {
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE)
    val chapter = reference("chapter_id", ChapterTable, ReferenceOption.CASCADE)
    val isRead = bool("is_read").default(false)
    val isBookmarked = bool("is_bookmarked").default(false)
    val lastPageRead = integer("last_page_read").default(0)
    val lastReadAt = long("last_read_at").default(0)

    override val primaryKey = PrimaryKey(user, chapter)

    init {
        index(false, user)
        index(false, chapter)
        index(false, user, isRead)
    }
}
