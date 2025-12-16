# Multi-User Support Design Document

## Overview
This document outlines the database schema and implementation strategy for adding multi-user support to Suwayomi-Server while maintaining backwards compatibility.

## Design Principles

1. **Shared Cache/Data**: All users share the same manga metadata, sources, and extensions
2. **User-Specific Libraries**: Each user has their own library, categories, and reading progress
3. **Backwards Compatibility**: Existing single-user installations must continue to work seamlessly

## Database Schema Changes

### New Tables

#### 1. UserTable
Stores user account information.

```kotlin
object UserTable : IntIdTable() {
    val username = varchar("username", 64).uniqueIndex()
    val email = varchar("email", 255).nullable().uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 32).default("USER") // USER or ADMIN
    val isActive = bool("is_active").default(true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}
```

**Roles:**
- `USER`: Standard user with access to their own library
- `ADMIN`: Can manage all users and access admin APIs

#### 2. UserLibraryTable
Junction table tracking which manga are in which user's library.
Replaces the `inLibrary` boolean in MangaTable.

```kotlin
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
```

#### 3. UserChapterProgressTable
Stores per-user chapter reading progress.
Replaces the reading progress fields in ChapterTable.

```kotlin
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
```

### Modified Tables

#### CategoryTable
Add user reference to make categories user-specific.

```kotlin
object CategoryTable : IntIdTable() {
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE) // NEW
    val name = varchar("name", 64)
    val order = integer("sort_order").default(0)
    val isDefault = bool("is_default").default(false)
    val includeInUpdate = integer("include_in_update").default(IncludeOrExclude.UNSET.value)
    val includeInDownload = integer("include_in_download").default(IncludeOrExclude.UNSET.value)

    init {
        index(false, user) // NEW
    }
}
```

### Deprecated Fields

The following fields will be **kept for backwards compatibility** but will not be used in multi-user mode:

- `MangaTable.inLibrary` - replaced by UserLibraryTable
- `MangaTable.inLibraryAt` - replaced by UserLibraryTable
- `ChapterTable.isRead` - replaced by UserChapterProgressTable
- `ChapterTable.isBookmarked` - replaced by UserChapterProgressTable
- `ChapterTable.lastPageRead` - replaced by UserChapterProgressTable
- `ChapterTable.lastReadAt` - replaced by UserChapterProgressTable

## Migration Strategy (M0053_AddMultiUserSupport)

### Step 1: Create New Tables
1. Create UserTable
2. Create UserLibraryTable
3. Create UserChapterProgressTable

### Step 2: Modify Existing Tables
1. Add nullable `user_id` column to CategoryTable

### Step 3: Create Default User
Create a default user with ID=1:
```sql
INSERT INTO UserTable (id, username, email, password_hash, role, is_active, created_at, updated_at)
VALUES (1, 'admin', null, '<existing auth password hash or empty>', 'ADMIN', true, <current_time>, <current_time>)
```

### Step 4: Migrate Existing Data

**Migrate Library Data:**
```sql
INSERT INTO UserLibraryTable (user_id, manga_id, in_library_at)
SELECT 1, id, in_library_at
FROM MangaTable
WHERE in_library = true
```

**Migrate Chapter Progress:**
```sql
INSERT INTO UserChapterProgressTable (user_id, chapter_id, is_read, is_bookmarked, last_page_read, last_read_at)
SELECT 1, id, read, bookmark, last_page_read, last_read_at
FROM ChapterTable
WHERE read = true OR bookmark = true OR last_page_read > 0
```

**Migrate Categories:**
```sql
UPDATE CategoryTable SET user_id = 1 WHERE user_id IS NULL
```

Then make user_id NOT NULL:
```sql
ALTER TABLE CategoryTable ALTER COLUMN user_id SET NOT NULL
```

## API Changes

### New Endpoints

#### User Management (Admin Only)
- `POST /api/v1/admin/users` - Create new user
- `GET /api/v1/admin/users` - List all users
- `GET /api/v1/admin/users/{id}` - Get user details
- `PUT /api/v1/admin/users/{id}` - Update user
- `DELETE /api/v1/admin/users/{id}` - Delete user
- `POST /api/v1/admin/users/{id}/activate` - Activate user
- `POST /api/v1/admin/users/{id}/deactivate` - Deactivate user

#### User Self-Management
- `GET /api/v1/user/me` - Get current user info
- `PUT /api/v1/user/me` - Update current user profile
- `PUT /api/v1/user/me/password` - Change password

#### Authentication (Updated)
- `POST /api/v1/auth/register` - Register new user (if registration is enabled)
- `POST /api/v1/auth/login` - Login (updated to return user info)

### Modified Endpoints

All existing library and reading progress endpoints will be automatically scoped to the authenticated user:
- `/api/v1/manga/*` - Only returns manga in current user's library
- `/api/v1/category/*` - Only user's categories
- `/api/v1/chapter/*` - Reading progress for current user

## Data Access Layer Changes

### User Context
All data access operations must include user context. Extract user ID from:
1. JWT token (for UI_LOGIN mode)
2. Session (for SIMPLE_LOGIN mode)
3. Default to user ID 1 for NONE/BASIC_AUTH modes (backwards compatibility)

### Query Modifications

**Example: Get user's library**
```kotlin
// OLD
MangaTable.select { MangaTable.inLibrary eq true }

// NEW
MangaTable
    .innerJoin(UserLibraryTable)
    .select { UserLibraryTable.user eq userId }
```

**Example: Get chapter reading progress**
```kotlin
// OLD
ChapterTable.select { ChapterTable.id eq chapterId }.map { it[ChapterTable.isRead] }

// NEW
UserChapterProgressTable
    .select {
        (UserChapterProgressTable.user eq userId) and
        (UserChapterProgressTable.chapter eq chapterId)
    }
    .map { it[UserChapterProgressTable.isRead] }
    .firstOrNull() ?: false
```

## Configuration Changes

### New Settings

Add to ServerConfig:
```kotlin
val multiUserEnabled = BooleanSetting(
    group = ConfigGroups.MULTI_USER,
    description = "Enable multi-user mode",
    default = false
)

val allowUserRegistration = BooleanSetting(
    group = ConfigGroups.MULTI_USER,
    description = "Allow new users to self-register",
    default = false
)

val requireEmailVerification = BooleanSetting(
    group = ConfigGroups.MULTI_USER,
    description = "Require email verification for new users",
    default = false
)
```

## Backwards Compatibility

### Single-User Mode (Default)
When `multiUserEnabled = false` or for existing installations:
1. All operations default to user ID 1
2. User management APIs return 404
3. Registration endpoint returns 404
4. Authentication works as before

### Migration Process
1. Existing installations automatically get default user (ID=1)
2. All existing data is migrated to default user
3. Multi-user mode is opt-in via configuration
4. If auth was configured, default user gets that password

### Shared Resources
These remain global and shared across all users:
- **ExtensionTable** - Extensions are installed server-wide
- **SourceTable** - Sources available to all users
- **MangaTable** (metadata only) - Manga info fetched from sources is cached globally
- **Downloads** - Downloaded chapters can be shared (saves disk space)
- **Thumbnails cache** - Shared thumbnail cache
- **Backup** - Per-user backups (each user can backup/restore their own library)

## Security Considerations

1. **Password Hashing**: Use BCrypt with appropriate cost factor
2. **JWT Tokens**: Use existing JWT implementation with user ID in claims
3. **Authorization**: Check user ownership for all data access
4. **Admin Access**: Only ADMIN role can access user management APIs
5. **Rate Limiting**: Consider adding rate limiting for auth endpoints
6. **SQL Injection**: Use parameterized queries (already done by Exposed)

## Testing Strategy

1. **Migration Testing**: Test migration with various data states
2. **API Testing**: Test all endpoints with different user contexts
3. **Permission Testing**: Ensure users can't access other users' data
4. **Backwards Compatibility**: Test single-user mode still works
5. **Performance Testing**: Ensure queries remain performant with user scoping

## Implementation Order

1. ✅ Create database schema (UserTable, UserLibraryTable, UserChapterProgressTable)
2. ✅ Create migration M0053_AddMultiUserSupport
3. ✅ Update UserType model and authentication
4. ✅ Implement user management service layer
5. ✅ Add user management API endpoints
6. ✅ Update data access layer with user scoping
7. ✅ Update GraphQL schema
8. ✅ Add configuration options
9. ✅ Write tests
10. ✅ Update documentation
