package com.eliav.wacompletemute

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * מקבל Broadcast מה-Hook (שרץ בתהליך WhatsApp) ומעדכן את ה-SharedPreferences
 * של המודול שלנו.
 *
 * מנגנון IPC:
 *   Hook (תהליך WhatsApp) ──sendBroadcast(setPackage)──► MuteStateReceiver (תהליך המודול)
 *                                                                │
 *                                                         כותב SharedPreferences
 *                                                                │
 *                                                    MuteNotificationService קורא
 *
 * הגנה: ה-Intent נשלח עם intent.setPackage("com.eliav.wacompletemute").
 * Android מגביל אוטומטית את ה-Broadcast לרכיבים של אותו package בלבד —
 * ללא צורך ב-permission כלשהו מהשולח (WhatsApp).
 * אפליקציות אחרות לא יכולות לקבל Broadcast עם setPackage שמצביע ל-package אחר.
 *
 * (מקור: developer.android.com/guide/components/broadcasts — "set of apps in
 * the same organization by calling setPackage(String)")
 */
class MuteStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MuteStore.ACTION_MUTE_UPDATE) return

        val jid     = intent.getStringExtra(MuteStore.EXTRA_GROUP_JID) ?: return
        val isMuted = intent.getBooleanExtra(MuteStore.EXTRA_IS_MUTED, true)

        MuteStore.setMuted(context, jid, isMuted)

        // עדכן את ה-Service אם הוא פעיל
        MuteNotificationService.instance?.reloadMutedGroups(context)
    }
}
