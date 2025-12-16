package suwayomi.tachidesk.server.user

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import at.favre.lib.crypto.bcrypt.BCrypt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.model.dataclass.UserDataClass
import suwayomi.tachidesk.manga.model.dataclass.UserRole
import suwayomi.tachidesk.manga.model.table.UserTable
import suwayomi.tachidesk.manga.model.table.toDataClass

object User {
    private val logger = KotlinLogging.logger {}

    /**
     * Password hashing configuration
     */
    private val bcryptHasher = BCrypt.withDefaults()
    private val bcryptVerifier = BCrypt.verifyer()
    private const val BCRYPT_COST = 12

    /**
     * Hash a plain text password using BCrypt
     */
    fun hashPassword(password: String): String = bcryptHasher.hashToString(BCRYPT_COST, password.toCharArray())

    /**
     * Verify a password against a BCrypt hash
     */
    fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean {
        if (hash.isEmpty()) {
            // Empty hash means no password set (backwards compatibility)
            return false
        }
        return bcryptVerifier.verify(password.toCharArray(), hash.toCharArray()).verified
    }

    /**
     * Create a new user
     */
    fun createUser(
        username: String,
        password: String,
        email: String? = null,
        role: UserRole = UserRole.USER,
        isActive: Boolean = true,
    ): UserDataClass {
        require(username.isNotBlank()) { "Username cannot be blank" }
        require(password.isNotBlank()) { "Password cannot be blank" }
        require(username.length >= 3) { "Username must be at least 3 characters long" }
        require(password.length >= 8) { "Password must be at least 8 characters long" }

        val passwordHash = hashPassword(password)
        val now = System.currentTimeMillis()

        return transaction {
            // Check if username already exists
            val existingUser =
                UserTable
                    .selectAll()
                    .where { UserTable.username eq username }
                    .singleOrNull()

            if (existingUser != null) {
                throw IllegalArgumentException("Username already exists")
            }

            // Check if email already exists (if provided)
            if (email != null) {
                val existingEmail =
                    UserTable
                        .selectAll()
                        .where { UserTable.email eq email }
                        .singleOrNull()

                if (existingEmail != null) {
                    throw IllegalArgumentException("Email already exists")
                }
            }

            val userId =
                UserTable.insert {
                    it[UserTable.username] = username
                    it[UserTable.email] = email
                    it[UserTable.passwordHash] = passwordHash
                    it[UserTable.role] = role.name
                    it[UserTable.isActive] = isActive
                    it[UserTable.createdAt] = now
                    it[UserTable.updatedAt] = now
                }[UserTable.id].value

            logger.info { "Created user: $username (ID: $userId, Role: $role)" }

            getUserById(userId) ?: throw IllegalStateException("Failed to create user")
        }
    }

    /**
     * Get user by ID
     */
    fun getUserById(userId: Int): UserDataClass? =
        transaction {
            UserTable
                .selectAll()
                .where { UserTable.id eq userId }
                .map { UserTable.toDataClass(it) }
                .singleOrNull()
        }

    /**
     * Get user by username
     */
    fun getUserByUsername(username: String): UserDataClass? =
        transaction {
            UserTable
                .selectAll()
                .where { UserTable.username eq username }
                .map { UserTable.toDataClass(it) }
                .singleOrNull()
        }

    /**
     * Get user by email
     */
    fun getUserByEmail(email: String): UserDataClass? =
        transaction {
            UserTable
                .selectAll()
                .where { UserTable.email eq email }
                .map { UserTable.toDataClass(it) }
                .singleOrNull()
        }

    /**
     * Get all users
     */
    fun getAllUsers(): List<UserDataClass> =
        transaction {
            UserTable
                .selectAll()
                .map { UserTable.toDataClass(it) }
        }

    /**
     * Authenticate a user with username and password
     * Returns the user if authentication succeeds, null otherwise
     */
    fun authenticateUser(
        username: String,
        password: String,
    ): UserDataClass? {
        val user = getUserByUsername(username) ?: return null

        if (!user.isActive) {
            logger.warn { "Authentication failed: User $username is inactive" }
            return null
        }

        // Get password hash from database
        val passwordHash =
            transaction {
                UserTable
                    .selectAll()
                    .where { UserTable.id eq user.id }
                    .map { it[UserTable.passwordHash] }
                    .singleOrNull()
            } ?: ""

        if (!verifyPassword(password, passwordHash)) {
            logger.warn { "Authentication failed: Invalid password for user $username" }
            return null
        }

        logger.info { "User authenticated: $username" }
        return user
    }

    /**
     * Update user information
     */
    fun updateUser(
        userId: Int,
        username: String? = null,
        email: String? = null,
        role: UserRole? = null,
        isActive: Boolean? = null,
    ): UserDataClass? {
        transaction {
            // Check if user exists
            val existingUser = getUserById(userId) ?: throw IllegalArgumentException("User not found")

            // Check username uniqueness if changing
            if (username != null && username != existingUser.username) {
                val userWithSameUsername =
                    UserTable
                        .selectAll()
                        .where { (UserTable.username eq username) and (UserTable.id neq userId) }
                        .singleOrNull()

                if (userWithSameUsername != null) {
                    throw IllegalArgumentException("Username already exists")
                }
            }

            // Check email uniqueness if changing
            if (email != null && email != existingUser.email) {
                val userWithSameEmail =
                    UserTable
                        .selectAll()
                        .where { (UserTable.email eq email) and (UserTable.id neq userId) }
                        .singleOrNull()

                if (userWithSameEmail != null) {
                    throw IllegalArgumentException("Email already exists")
                }
            }

            val now = System.currentTimeMillis()

            UserTable.update({ UserTable.id eq userId }) {
                if (username != null) it[UserTable.username] = username
                if (email != null) it[UserTable.email] = email
                if (role != null) it[UserTable.role] = role.name
                if (isActive != null) it[UserTable.isActive] = isActive
                it[UserTable.updatedAt] = now
            }

            logger.info { "Updated user: $userId" }
        }

        return getUserById(userId)
    }

    /**
     * Change user password
     */
    fun changePassword(
        userId: Int,
        newPassword: String,
    ): Boolean {
        require(newPassword.length >= 8) { "Password must be at least 8 characters long" }

        val passwordHash = hashPassword(newPassword)
        val now = System.currentTimeMillis()

        return transaction {
            val updated =
                UserTable.update({ UserTable.id eq userId }) {
                    it[UserTable.passwordHash] = passwordHash
                    it[UserTable.updatedAt] = now
                }

            if (updated > 0) {
                logger.info { "Password changed for user: $userId" }
                true
            } else {
                logger.warn { "Failed to change password for user: $userId" }
                false
            }
        }
    }

    /**
     * Delete a user
     */
    fun deleteUser(userId: Int): Boolean {
        // Prevent deleting the default admin user
        if (userId == 1) {
            throw IllegalArgumentException("Cannot delete the default admin user")
        }

        return transaction {
            val deleted = UserTable.deleteWhere { UserTable.id eq userId }

            if (deleted > 0) {
                logger.info { "Deleted user: $userId" }
                true
            } else {
                logger.warn { "Failed to delete user: $userId (not found)" }
                false
            }
        }
    }

    /**
     * Check if a user has a specific role
     */
    fun hasRole(
        userId: Int,
        requiredRole: UserRole,
    ): Boolean {
        val user = getUserById(userId) ?: return false
        return user.role == requiredRole
    }

    /**
     * Check if a user is an admin
     */
    fun isAdmin(userId: Int): Boolean = hasRole(userId, UserRole.ADMIN)

    /**
     * Ensure legacy user (ID=1) exists and matches current legacy credentials
     * Used for backwards compatibility with legacy auth modes
     */
    fun ensureLegacyUser(
        username: String,
        password: String,
    ): UserDataClass =
        transaction {
            val existingUser = getUserById(1)
            val now = System.currentTimeMillis()

            if (existingUser == null) {
                // Create user ID=1 with legacy credentials
                val passwordHash = hashPassword(password)
                UserTable.insert {
                    it[UserTable.id] = 1
                    it[UserTable.username] = username
                    it[UserTable.email] = null
                    it[UserTable.passwordHash] = passwordHash
                    it[UserTable.role] = UserRole.ADMIN.name
                    it[UserTable.isActive] = true
                    it[UserTable.createdAt] = now
                    it[UserTable.updatedAt] = now
                }
                logger.info { "Created legacy user (ID=1) with username: $username" }
            } else {
                // Update existing user ID=1 to match current legacy credentials
                val passwordHash = hashPassword(password)
                UserTable.update({ UserTable.id eq 1 }) {
                    it[UserTable.username] = username
                    it[UserTable.passwordHash] = passwordHash
                    it[UserTable.updatedAt] = now
                }
                logger.info { "Updated legacy user (ID=1) to match current credentials: $username" }
            }

            getUserById(1) ?: throw IllegalStateException("Failed to ensure legacy user")
        }
}
