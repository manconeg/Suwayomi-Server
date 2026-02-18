package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

@Suppress("ClassName", "unused")
class M0054_AddOidcConfig : SQLMigration() {
    override val sql =
        """
        CREATE TABLE IF NOT EXISTS OidcConfigTable (
            id          INT AUTO_INCREMENT PRIMARY KEY,
            issuer_url  VARCHAR(512) NOT NULL,
            client_id   VARCHAR(256) NOT NULL,
            client_secret VARCHAR(512) NOT NULL,
            configured_at BIGINT NOT NULL
        );
        """.trimIndent()
}
