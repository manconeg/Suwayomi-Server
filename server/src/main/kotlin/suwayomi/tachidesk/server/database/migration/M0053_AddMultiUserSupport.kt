package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/**
 * Adds multi-user support to the database.
 *
 * Changes:
 * 1. Creates UserTable for storing user accounts
 * 2. Creates UserLibraryTable to track which manga are in which user's library
 * 3. Creates UserChapterProgressTable for per-user reading progress
 * 4. Adds user_id column to CategoryTable
 * 5. Migrates existing data to a default user (ID=1) for backwards compatibility
 */
@Suppress("ClassName", "unused")
class M0053_AddMultiUserSupport : SQLMigration() {
    override val sql: String =
        """
        -- Create UserTable
        CREATE TABLE IF NOT EXISTS UserTable (
            id INT AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(64) NOT NULL UNIQUE,
            email VARCHAR(255),
            password_hash VARCHAR(255) NOT NULL,
            role VARCHAR(32) NOT NULL DEFAULT 'USER',
            is_active BOOLEAN NOT NULL DEFAULT TRUE,
            created_at BIGINT NOT NULL,
            updated_at BIGINT NOT NULL
        );

        -- Create index on email for faster lookups
        CREATE INDEX IF NOT EXISTS idx_user_email ON UserTable(email);

        -- Create UserLibraryTable (replaces inLibrary boolean in MangaTable)
        CREATE TABLE IF NOT EXISTS UserLibraryTable (
            user_id INT NOT NULL,
            manga_id INT NOT NULL,
            in_library_at BIGINT NOT NULL,
            PRIMARY KEY (user_id, manga_id),
            FOREIGN KEY (user_id) REFERENCES UserTable(id) ON DELETE CASCADE,
            FOREIGN KEY (manga_id) REFERENCES MangaTable(id) ON DELETE CASCADE
        );

        -- Create indexes for UserLibraryTable
        CREATE INDEX IF NOT EXISTS idx_user_library_user ON UserLibraryTable(user_id);
        CREATE INDEX IF NOT EXISTS idx_user_library_manga ON UserLibraryTable(manga_id);

        -- Create UserChapterProgressTable (replaces reading progress in ChapterTable)
        CREATE TABLE IF NOT EXISTS UserChapterProgressTable (
            user_id INT NOT NULL,
            chapter_id INT NOT NULL,
            is_read BOOLEAN NOT NULL DEFAULT FALSE,
            is_bookmarked BOOLEAN NOT NULL DEFAULT FALSE,
            last_page_read INT NOT NULL DEFAULT 0,
            last_read_at BIGINT NOT NULL DEFAULT 0,
            PRIMARY KEY (user_id, chapter_id),
            FOREIGN KEY (user_id) REFERENCES UserTable(id) ON DELETE CASCADE,
            FOREIGN KEY (chapter_id) REFERENCES ChapterTable(id) ON DELETE CASCADE
        );

        -- Create indexes for UserChapterProgressTable
        CREATE INDEX IF NOT EXISTS idx_user_chapter_user ON UserChapterProgressTable(user_id);
        CREATE INDEX IF NOT EXISTS idx_user_chapter_chapter ON UserChapterProgressTable(chapter_id);
        CREATE INDEX IF NOT EXISTS idx_user_chapter_read ON UserChapterProgressTable(user_id, is_read);

        -- Add user_id column to CategoryTable (nullable for migration)
        ALTER TABLE CategoryTable ADD COLUMN IF NOT EXISTS user_id INT;

        -- Create index on user_id for CategoryTable
        CREATE INDEX IF NOT EXISTS idx_category_user ON CategoryTable(user_id);

        -- Create default admin user (ID=1) for backwards compatibility
        -- Username and password will be synced with legacy credentials on first login
        -- Using placeholder username that will be updated when legacy auth is used
        INSERT INTO UserTable (id, username, email, password_hash, role, is_active, created_at, updated_at)
        SELECT 1, 'legacy-user', NULL, '', 'ADMIN', TRUE,
               CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT),
               CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT)
        WHERE NOT EXISTS (SELECT 1 FROM UserTable WHERE id = 1);

        -- Migrate existing library data to default user
        INSERT INTO UserLibraryTable (user_id, manga_id, in_library_at)
        SELECT 1, id, in_library_at
        FROM MangaTable
        WHERE in_library = TRUE
        AND NOT EXISTS (
            SELECT 1 FROM UserLibraryTable
            WHERE user_id = 1 AND manga_id = MangaTable.id
        );

        -- Migrate existing chapter progress to default user
        INSERT INTO UserChapterProgressTable (user_id, chapter_id, is_read, is_bookmarked, last_page_read, last_read_at)
        SELECT 1, id, read, bookmark, last_page_read, last_read_at
        FROM ChapterTable
        WHERE (read = TRUE OR bookmark = TRUE OR last_page_read > 0)
        AND NOT EXISTS (
            SELECT 1 FROM UserChapterProgressTable
            WHERE user_id = 1 AND chapter_id = ChapterTable.id
        );

        -- Migrate existing categories to default user
        UPDATE CategoryTable
        SET user_id = 1
        WHERE user_id IS NULL;

        -- Make user_id NOT NULL after migration
        ALTER TABLE CategoryTable ALTER COLUMN user_id SET NOT NULL;

        -- Add foreign key constraint to CategoryTable
        ALTER TABLE CategoryTable
        ADD CONSTRAINT IF NOT EXISTS fk_category_user
        FOREIGN KEY (user_id) REFERENCES UserTable(id) ON DELETE CASCADE;
        """.trimIndent()
}
