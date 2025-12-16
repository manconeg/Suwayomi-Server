package suwayomi.tachidesk.server.user

import io.javalin.http.Context
import io.javalin.http.Header
import io.javalin.websocket.WsConnectContext
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.manga.model.dataclass.UserRole
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.serverConfig

sealed class UserType {
    class Admin(
        val id: Int,
    ) : UserType()

    class NormalUser(
        val id: Int,
    ) : UserType()

    data object Visitor : UserType()
}

fun UserType.requireUser(): Int =
    when (this) {
        is UserType.Admin -> id
        is UserType.NormalUser -> id
        UserType.Visitor -> throw UnauthorizedException()
    }

fun UserType.requireUserWithBasicFallback(ctx: Context): Int =
    when (this) {
        is UserType.Admin, is UserType.NormalUser -> {
            requireUser()
        }

        UserType.Visitor if ctx.getAttribute(Attribute.TachideskBasic) -> {
            1
        }

        UserType.Visitor -> {
            ctx.header("WWW-Authenticate", "Basic")
            throw UnauthorizedException()
        }
    }

fun getUserFromToken(token: String?): UserType {
    if (serverConfig.authMode.value != AuthMode.UI_LOGIN) {
        // Legacy auth modes - look up default user from database
        return getUserTypeForId(1)
    }

    if (token.isNullOrBlank()) {
        return UserType.Visitor
    }

    return Jwt.verifyJwt(token)
}

fun getUserFromContext(ctx: Context): UserType {
    fun cookieValid(): Boolean {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return false
        return username == serverConfig.authUsername.value
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            // Legacy auth - look up default user from database
            getUserTypeForId(1)
        }

        AuthMode.SIMPLE_LOGIN -> {
            if (cookieValid()) getUserTypeForId(1) else UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            val authentication = ctx.header(Header.AUTHORIZATION) ?: ctx.cookie("suwayomi-server-token")
            val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

            getUserFromToken(token)
        }
    }
}

fun getUserFromWsContext(ctx: WsConnectContext): UserType {
    fun cookieValid(): Boolean {
        val username = ctx.sessionAttribute<String>("logged-in") ?: return false
        return username == serverConfig.authUsername.value
    }

    return when (serverConfig.authMode.value) {
        // NOTE: Basic Auth is expected to have been validated by JavalinSetup
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            // Legacy auth - look up default user from database
            getUserTypeForId(1)
        }

        AuthMode.SIMPLE_LOGIN -> {
            if (cookieValid()) getUserTypeForId(1) else UserType.Visitor
        }

        AuthMode.UI_LOGIN -> {
            val authentication =
                ctx.header(Header.AUTHORIZATION) ?: ctx.header("Sec-WebSocket-Protocol") ?: ctx.cookie("suwayomi-server-token")
            val token = authentication?.substringAfter("Bearer ") ?: ctx.queryParam("token")

            getUserFromToken(token)
        }
    }
}

/**
 * Helper function to get the appropriate UserType for a user ID by looking up their role in the database
 */
private fun getUserTypeForId(userId: Int): UserType {
    val user = User.getUserById(userId) ?: return UserType.Visitor
    if (!user.isActive) return UserType.Visitor

    return when (user.role) {
        UserRole.ADMIN -> UserType.Admin(userId)
        UserRole.USER -> UserType.NormalUser(userId)
    }
}

class UnauthorizedException : IllegalStateException("Unauthorized")

class ForbiddenException : IllegalStateException("Forbidden")
