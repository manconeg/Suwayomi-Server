package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import suwayomi.tachidesk.manga.model.dataclass.UserDataClass
import suwayomi.tachidesk.manga.model.dataclass.UserRole

object UserTable : IntIdTable() {
    val username = varchar("username", 64).uniqueIndex()
    val email = varchar("email", 255).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 32).default(UserRole.USER.name)
    val isActive = bool("is_active").default(true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

fun UserTable.toDataClass(userEntry: ResultRow) =
    UserDataClass(
        id = userEntry[id].value,
        username = userEntry[username],
        email = userEntry[email],
        role = UserRole.valueOf(userEntry[role]),
        isActive = userEntry[isActive],
        createdAt = userEntry[createdAt],
        updatedAt = userEntry[updatedAt],
    )
