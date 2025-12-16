package suwayomi.tachidesk.server.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.model.dataclass.UserDataClass
import suwayomi.tachidesk.manga.model.dataclass.UserRole
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.User
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.withOperation

/** User Management Controller */
object UserController {
    data class CreateUserRequest(
        val username: String,
        val password: String,
        val email: String? = null,
        val role: UserRole = UserRole.USER,
    )

    data class UpdateUserRequest(
        val username: String? = null,
        val email: String? = null,
        val role: UserRole? = null,
        val isActive: Boolean? = null,
    )

    data class ChangePasswordRequest(
        val newPassword: String,
    )

    /** Get current user information */
    val getCurrentUser =
        handler(
            documentWith = {
                withOperation {
                    summary("Get current user")
                    description("Returns information about the currently authenticated user")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val user = User.getUserById(userId)
                if (user != null) {
                    ctx.json(user)
                } else {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json(mapOf("error" to "User not found"))
                }
            },
            withResults = {
                json<UserDataClass>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.NOT_FOUND)
            },
        )

    /** Get all users (admin only) */
    val getAllUsers =
        handler(
            documentWith = {
                withOperation {
                    summary("Get all users")
                    description("Returns a list of all users (admin only)")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                if (!User.isAdmin(userId)) {
                    throw ForbiddenException()
                }
                ctx.json(User.getAllUsers())
            },
            withResults = {
                json<List<UserDataClass>>(HttpStatus.OK)
            },
        )

    /** Get user by ID (admin only) */
    val getUserById =
        handler(
            documentWith = {
                withOperation {
                    summary("Get user by ID")
                    description("Returns information about a specific user (admin only)")
                }
            },
            behaviorOf = { ctx ->
                val currentUserId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val targetUserId = ctx.pathParam("userId").toInt()

                // Users can view their own profile, admins can view any profile
                if (currentUserId != targetUserId && !User.isAdmin(currentUserId)) {
                    throw ForbiddenException()
                }

                val user = User.getUserById(targetUserId)
                if (user != null) {
                    ctx.json(user)
                } else {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json(mapOf("error" to "User not found"))
                }
            },
            withResults = {
                json<UserDataClass>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.NOT_FOUND)
            },
        )

    /** Create a new user (admin only) */
    val createUser =
        handler(
            documentWith = {
                withOperation {
                    summary("Create new user")
                    description("Creates a new user account (admin only)")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                if (!User.isAdmin(userId)) {
                    throw ForbiddenException()
                }

                val request = ctx.bodyAsClass<CreateUserRequest>()
                try {
                    val newUser =
                        User.createUser(
                            username = request.username,
                            password = request.password,
                            email = request.email,
                            role = request.role,
                        )
                    ctx.status(HttpStatus.CREATED)
                    ctx.json(newUser)
                } catch (e: IllegalArgumentException) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    ctx.json(mapOf("error" to (e.message ?: "Invalid request")))
                }
            },
            withResults = {
                json<UserDataClass>(HttpStatus.CREATED)
                json<Map<String, String>>(HttpStatus.BAD_REQUEST)
            },
        )

    /** Update user information */
    val updateUser =
        handler(
            documentWith = {
                withOperation {
                    summary("Update user")
                    description("Updates user information")
                }
            },
            behaviorOf = { ctx ->
                val currentUserId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val targetUserId = ctx.pathParam("userId").toInt()

                // Users can update their own profile, admins can update any profile
                if (currentUserId != targetUserId && !User.isAdmin(currentUserId)) {
                    throw ForbiddenException()
                }

                val request = ctx.bodyAsClass<UpdateUserRequest>()

                // Only admins can change roles
                if (request.role != null && !User.isAdmin(currentUserId)) {
                    throw ForbiddenException()
                }

                try {
                    val updatedUser =
                        User.updateUser(
                            userId = targetUserId,
                            username = request.username,
                            email = request.email,
                            role = request.role,
                            isActive = request.isActive,
                        )

                    if (updatedUser != null) {
                        ctx.json(updatedUser)
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND)
                        ctx.json(mapOf("error" to "User not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    ctx.json(mapOf("error" to (e.message ?: "Invalid request")))
                }
            },
            withResults = {
                json<UserDataClass>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.NOT_FOUND)
                json<Map<String, String>>(HttpStatus.BAD_REQUEST)
            },
        )

    /** Change user password */
    val changePassword =
        handler(
            documentWith = {
                withOperation {
                    summary("Change password")
                    description("Changes the password for a user")
                }
            },
            behaviorOf = { ctx ->
                val currentUserId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val targetUserId = ctx.pathParam("userId").toInt()

                // Users can change their own password, admins can change any password
                if (currentUserId != targetUserId && !User.isAdmin(currentUserId)) {
                    throw ForbiddenException()
                }

                val request = ctx.bodyAsClass<ChangePasswordRequest>()

                try {
                    val success = User.changePassword(targetUserId, request.newPassword)
                    if (success) {
                        ctx.json(mapOf("success" to true, "message" to "Password changed successfully"))
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND)
                        ctx.json(mapOf("error" to "User not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    ctx.json(mapOf("error" to (e.message ?: "Invalid request")))
                }
            },
            withResults = {
                json<Map<String, Any>>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.NOT_FOUND)
                json<Map<String, String>>(HttpStatus.BAD_REQUEST)
            },
        )

    /** Delete a user (admin only) */
    val deleteUser =
        handler(
            documentWith = {
                withOperation {
                    summary("Delete user")
                    description("Deletes a user account (admin only)")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                if (!User.isAdmin(userId)) {
                    throw ForbiddenException()
                }

                val targetUserId = ctx.pathParam("userId").toInt()

                try {
                    val success = User.deleteUser(targetUserId)
                    if (success) {
                        ctx.json(mapOf("success" to true, "message" to "User deleted successfully"))
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND)
                        ctx.json(mapOf("error" to "User not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    ctx.json(mapOf("error" to (e.message ?: "Invalid request")))
                }
            },
            withResults = {
                json<Map<String, Any>>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.NOT_FOUND)
                json<Map<String, String>>(HttpStatus.BAD_REQUEST)
            },
        )
}
