package com.eliav.wacompletemute

import android.content.Context
import android.content.SharedPreferences

/**
 * מאגר ההגדרות המרכזי.
 *
 * שומר Set<String> של JID-ים מושתקים (פורמט: "XXXXXXXXXXX-XXXXXXXXXX@g.us").
 * SharedPreferences נשמרים ב-data dir של **המודול שלנו** (MODE_PRIVATE).
 * ה-Hook כותב אליהם דרך IPC (Broadcast עם signature-level permission),
 * ולא ישירות, כדי להימנע מגישה חוצת-תהליכים לקבצים.
 */
object MuteStore {

    const val PREFS_NAME            = "mute_prefs"
    const val KEY_MUTED_GROUPS      = "muted_groups"

    // ── IPC constants (Broadcast) ─────────────────────────────────────────
    const val ACTION_MUTE_UPDATE    = "com.eliav.wacompletemute.ACTION_MUTE_UPDATE"
    const val EXTRA_GROUP_JID       = "group_jid"
    const val EXTRA_IS_MUTED        = "is_muted"
    // הגנה מתבצעת ע"י intent.setPackage(MODULE_PACKAGE) בשולח — אין צורך ב-permission

    // ── Read ──────────────────────────────────────────────────────────────

    fun getMutedGroups(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_MUTED_GROUPS, emptySet()) ?: emptySet()
    }

    fun isMuted(context: Context, jid: String): Boolean =
        getMutedGroups(context).contains(jid)

    // ── Write ─────────────────────────────────────────────────────────────

    fun setMuted(context: Context, jid: String, muted: Boolean) {
        val prefs   = prefs(context)
        val current = prefs.getStringSet(KEY_MUTED_GROUPS, emptySet())!!.toMutableSet()
        if (muted) current.add(jid) else current.remove(jid)
        prefs.edit().putStringSet(KEY_MUTED_GROUPS, current).apply()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
