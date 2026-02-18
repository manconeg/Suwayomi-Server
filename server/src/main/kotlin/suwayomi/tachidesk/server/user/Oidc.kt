package suwayomi.tachidesk.server.user

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.model.table.OidcConfigTable
import java.net.URI

object Oidc {
    private val logger = KotlinLogging.logger {}

    data class OidcConfig(
        val issuerUrl: String,
        val clientId: String,
        val clientSecret: String,
    )

    /**
     * Returns the stored OIDC configuration, or null if not yet configured.
     */
    fun getConfig(): OidcConfig? =
        transaction {
            OidcConfigTable
                .selectAll()
                .limit(1)
                .map {
                    OidcConfig(
                        issuerUrl = it[OidcConfigTable.issuerUrl],
                        clientId = it[OidcConfigTable.clientId],
                        clientSecret = it[OidcConfigTable.clientSecret],
                    )
                }.firstOrNull()
        }

    /**
     * Stores the OIDC configuration. Can only be called once — throws if already configured.
     */
    fun configure(
        issuerUrl: String,
        clientId: String,
        clientSecret: String,
    ) {
        transaction {
            val alreadyConfigured = OidcConfigTable.selectAll().limit(1).any()
            if (alreadyConfigured) {
                throw IllegalStateException("OIDC is already configured")
            }

            // Validate the issuer URL is reachable and is a real OIDC provider
            fetchProviderMetadata(issuerUrl)

            OidcConfigTable.insert {
                it[OidcConfigTable.issuerUrl] = issuerUrl
                it[OidcConfigTable.clientId] = clientId
                it[OidcConfigTable.clientSecret] = clientSecret
                it[OidcConfigTable.configuredAt] = System.currentTimeMillis()
            }

            logger.info { "OIDC configured with issuer: $issuerUrl" }
        }
    }

    /**
     * Exchanges an authorization code for a validated user identity.
     * Returns the user's email or subject identifier from the ID token.
     */
    fun exchangeCode(
        code: String,
        redirectUri: String,
    ): OidcIdentity {
        val config = getConfig() ?: throw IllegalStateException("OIDC is not configured")
        val metadata = fetchProviderMetadata(config.issuerUrl)

        val tokenRequest =
            TokenRequest(
                metadata.tokenEndpointURI,
                ClientSecretBasic(ClientID(config.clientId), Secret(config.clientSecret)),
                AuthorizationCodeGrant(AuthorizationCode(code), URI(redirectUri)),
            )

        val tokenResponse = OIDCTokenResponseParser.parse(tokenRequest.toHTTPRequest().send())
        if (!tokenResponse.indicatesSuccess()) {
            val error = tokenResponse.toErrorResponse()
            throw IllegalArgumentException("Token exchange failed: ${error.errorObject?.description}")
        }

        val oidcTokens = tokenResponse.toSuccessResponse().toOIDCTokenResponse().oidcTokens

        val validator =
            IDTokenValidator(
                Issuer(config.issuerUrl),
                ClientID(config.clientId),
                oidcTokens.idToken.jwtClaimsSet.getStringClaim("alg")?.let { null }
                    ?: com.nimbusds.jose.JWSAlgorithm.RS256,
                metadata.jwkSetURI.toURL(),
            )

        val claims = validator.validate(oidcTokens.idToken, oidcTokens.accessToken)

        return OidcIdentity(
            subject = claims.subject.value,
            email = claims.getStringClaim("email"),
        )
    }

    private fun fetchProviderMetadata(issuerUrl: String): OIDCProviderMetadata {
        try {
            return OIDCProviderMetadata.resolve(Issuer(issuerUrl))
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to fetch OIDC provider metadata from $issuerUrl: ${e.message}")
        }
    }

    data class OidcIdentity(
        val subject: String,
        val email: String?,
    )
}
