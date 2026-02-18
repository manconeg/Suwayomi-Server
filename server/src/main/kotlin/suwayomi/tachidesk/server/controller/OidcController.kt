package suwayomi.tachidesk.server.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.manga.model.dataclass.UserRole
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.Oidc
import suwayomi.tachidesk.server.user.User
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.withOperation

object OidcController {
    data class ConfigureRequest(
        val issuerUrl: String,
        val clientId: String,
        val clientSecret: String,
    )

    data class ConfigureResponse(
        val issuerUrl: String,
        val clientId: String,
    )

    data class CallbackRequest(
        val code: String,
        val redirectUri: String,
    )

    data class CallbackResponse(
        val accessToken: String,
        val refreshToken: String,
    )

    /**
     * One-time OIDC configuration endpoint. Admin only. Fails if already configured.
     */
    val configure =
        handler(
            documentWith = {
                withOperation {
                    summary("Configure OIDC")
                    description("One-time setup of the OIDC provider. Admin only. Fails if already configured.")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                if (!User.isAdmin(userId)) throw ForbiddenException()

                if (Oidc.getConfig() != null) {
                    ctx.status(HttpStatus.CONFLICT)
                    ctx.json(mapOf("error" to "OIDC is already configured"))
                    return@handler
                }

                val req = ctx.bodyAsClass<ConfigureRequest>()
                try {
                    Oidc.configure(req.issuerUrl, req.clientId, req.clientSecret)
                    ctx.json(ConfigureResponse(issuerUrl = req.issuerUrl, clientId = req.clientId))
                } catch (e: IllegalArgumentException) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    ctx.json(mapOf("error" to (e.message ?: "Invalid OIDC configuration")))
                }
            },
            withResults = {
                json<ConfigureResponse>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.CONFLICT)
                json<Map<String, String>>(HttpStatus.BAD_REQUEST)
            },
        )

    /**
     * Exchanges an OIDC authorization code for a Suwayomi JWT.
     * Creates the user account on first login if they don't exist yet.
     */
    val callback =
        handler(
            documentWith = {
                withOperation {
                    summary("OIDC callback")
                    description("Exchange an OIDC authorization code for a Suwayomi access token.")
                }
            },
            behaviorOf = { ctx ->
                if (Oidc.getConfig() == null) {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json(mapOf("error" to "OIDC is not configured"))
                    return@handler
                }

                val req = ctx.bodyAsClass<CallbackRequest>()
                try {
                    val identity = Oidc.exchangeCode(req.code, req.redirectUri)

                    // Find or create a user for this OIDC identity.
                    // We use the email as username, falling back to the subject identifier.
                    val username = identity.email ?: identity.subject
                    val user =
                        User.getUserByUsername(username) ?: User.createUser(
                            username = username,
                            // OIDC users have no local password — generate a random one
                            // since authentication is handled by the provider
                            password = java.util.UUID.randomUUID().toString(),
                            email = identity.email,
                            role = UserRole.USER,
                        )

                    val jwt = Jwt.generateJwt(user.id)
                    ctx.json(CallbackResponse(accessToken = jwt.accessToken, refreshToken = jwt.refreshToken))
                } catch (e: IllegalArgumentException) {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    ctx.json(mapOf("error" to (e.message ?: "OIDC login failed")))
                } catch (e: IllegalStateException) {
                    ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                    ctx.json(mapOf("error" to (e.message ?: "OIDC error")))
                }
            },
            withResults = {
                json<CallbackResponse>(HttpStatus.OK)
                json<Map<String, String>>(HttpStatus.NOT_FOUND)
                json<Map<String, String>>(HttpStatus.BAD_REQUEST)
                json<Map<String, String>>(HttpStatus.SERVICE_UNAVAILABLE)
            },
        )
}
