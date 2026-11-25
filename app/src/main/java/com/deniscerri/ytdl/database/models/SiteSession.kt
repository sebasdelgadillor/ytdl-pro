package com.deniscerri.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "site_sessions",
    indices = [Index(value = ["domain"], unique = true)]
)
data class SiteSession(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var domain: String,
    var displayName: String,
    var url: String,
    var faviconUrl: String = "",
    var localIconPath: String = "",
    var cookieCount: Int = 0,
    var isLoggedIn: Boolean = true,
    var desktopMode: Boolean = false,
    var userAgent: String = "",
    var lastUsedTimestamp: Long = System.currentTimeMillis()
)
