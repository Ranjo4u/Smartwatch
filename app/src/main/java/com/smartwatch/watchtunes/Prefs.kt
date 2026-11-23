package com.smartwatch.watchtunes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("watchtunes", Context.MODE_PRIVATE)

    fun songs(c: Context): List<Uri> =
        sp(c).getString("songs", "").orEmpty().split('\n').filter { it.isNotBlank() }.map(Uri::parse)

    fun setSongs(c: Context, songs: List<Uri>) =
        sp(c).edit().putString("songs", songs.joinToString("\n")).apply()

    fun toWatch(c: Context) = sp(c).getBoolean("to_watch", true)
    fun setToWatch(c: Context, on: Boolean) = sp(c).edit().putBoolean("to_watch", on).apply()

    fun routeAll(c: Context) = sp(c).getBoolean("route_all", false)
    fun setRouteAll(c: Context, on: Boolean) = sp(c).edit().putBoolean("route_all", on).apply()

    /** Loudness boost in millibels (0..1500). */
    fun boost(c: Context) = sp(c).getInt("boost", 600)
    fun setBoost(c: Context, mB: Int) = sp(c).edit().putInt("boost", mB).apply()

    fun title(c: Context, uri: Uri): String =
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: uri.lastPathSegment ?: "Song"
}
