package suwayomi.tachidesk.global

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.apibuilder.ApiBuilder.delete
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.patch
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.apibuilder.ApiBuilder.put
import io.javalin.apibuilder.ApiBuilder.ws
import suwayomi.tachidesk.global.controller.GlobalMetaController
import suwayomi.tachidesk.global.controller.SettingsController
import suwayomi.tachidesk.global.controller.WebViewController
import suwayomi.tachidesk.server.controller.OidcController
import suwayomi.tachidesk.server.controller.UserController

object GlobalAPI {
    fun defineEndpoints() {
        path("meta") {
            get("", GlobalMetaController.getMeta)
            patch("", GlobalMetaController.modifyMeta)
        }
        path("settings") {
            get("about", SettingsController.about)
            get("check-update", SettingsController.checkUpdate)
        }
        path("webview") {
            get("", WebViewController.webview)
            ws("", WebViewController::webviewWS)
        }
        path("user") {
            get("me", UserController.getCurrentUser)
            path("{userId}") {
                get("", UserController.getUserById)
                put("", UserController.updateUser)
                delete("", UserController.deleteUser)
                post("password", UserController.changePassword)
            }
        }
        path("users") {
            get("", UserController.getAllUsers)
            post("", UserController.createUser)
        }
        path("oidc") {
            post("configure", OidcController.configure)
            post("callback", OidcController.callback)
        }
    }
}
