/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

/**
 * The installed-app OAuth client the Reddit source logs in with. These three
 * values are the only place the client is named, so a blocked client id is a one-place change.
 */
object RedditAuth {
    /** RedReader's public installed-app client id; it has no secret. */
    const val CLIENT_ID = "yH0aTnJEt6qUgGn835B4vg"

    /** Fixed by the client id's registration. Nothing in the manifest handles this scheme. */
    const val REDIRECT_URI = "redreader://rr_oauth_redir"

    const val USER_AGENT = "org.quantumbadger.redreader/1.25.2"
}
