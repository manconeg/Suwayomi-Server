package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable

object OidcConfigTable : IntIdTable() {
    val issuerUrl = varchar("issuer_url", 512)
    val clientId = varchar("client_id", 256)
    val clientSecret = varchar("client_secret", 512)
    val configuredAt = long("configured_at")
}
