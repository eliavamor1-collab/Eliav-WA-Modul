package com.eliav.wacompletemute

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * NotificationListenerService — שכבת ההגנה השנייה.
 *
 * ── מה היא מבטיחה ──────────────────────────────────────────────────────────
 * מבטלת כל התראה שהגיעה לשורת המצב מקבוצות מושתקות.
 * זה אומר: אין badge, אין ריכוז בלוח ההתראות, אין heads-up.
 *
 * ── מה היא לא מבטיחה ───────────────────────────────────────────────────────
 * ה-OS מפעיל את הצליל/רטט לפני שה-callback onNotificationPosted מופעל.
 * בניסויים (ראה SO #44197274) יש עיכוב של 0–500ms בין הצליל לבין הקריאה.
 * לכן, השתקת הצליל והרטט **לפני** ההגשה מתבצעת ב-MuteHook (שכבה 1) בתוך
 * תהליך WhatsApp, דרך hook על NotificationManager.notify().
 *
 * ── IPC ────────────────────────────────────────────────────────────────────
 * קורא את ה-SharedPreferences ישירות (רצים בתהליך שלנו).
 * מתעדכן גם דרך MuteStateReceiver (Broadcast IPC) לסנכרון מיידי.
 */
class MuteNotificationService : NotificationListenerService() {

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private val GROUP_JID_REGEX = Regex("""[\d\-]+@g\.us""")

        /** singleton reference לצורך עדכון in-memory מ-MuteStateReceiver */
        var instance: MuteNotificationService? = null
            private set
    }

    private val mutedGroups = mutableSetOf<String>()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        reloadMutedGroups(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        reloadMutedGroups(this)
    }

    // ── Notification handling ─────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE) return

        val jid = extractGroupJid(sbn.notification) ?: return
        if (jid in mutedGroups) {
            cancelNotification(sbn.key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { /* no-op */ }

    // ── IPC update ────────────────────────────────────────────────────────

    /** נקרא מ-MuteStateReceiver לאחר Broadcast IPC */
    fun reloadMutedGroups(context: Context) {
        mutedGroups.clear()
        mutedGroups.addAll(MuteStore.getMutedGroups(context))
    }

    // ── JID extraction ────────────────────────────────────────────────────

    /**
     * מנסה לחלץ JID של קבוצה מהתראה של WhatsApp.
     *
     * WhatsApp מכניס מידע לשדות שונים ב-Notification.extras:
     * - "android.conversationTitle" — כותרת קבוצה (לא JID)
     * - "android.title"             — שם הקבוצה (לא JID)
     * - השדות הפנימיים משתנים בין גרסאות ועוברים obfuscation.
     *
     * לכן הגישה: סריקה של כל ערכי ה-extras וחיפוש pattern של @g.us.
     *
     * מגבלה ידועה: אם WhatsApp לא מכניס את ה-JID ל-extras (ייתכן בגרסאות
     * חדשות שמשתמשות ב-MessagingStyle), שכבה זו לא תזהה את הקבוצה.
     * במקרה זה שכבה 1 (Hook בתהליך WhatsApp) עדיין תפעל.
     */
    private fun extractGroupJid(n: Notification): String? {
        val extras: Bundle = n.extras ?: return null

        for (key in extras.keySet()) {
            val v: Any? = @Suppress("DEPRECATION") extras.get(key)
            if (v is String) {
                if (v.contains("@g.us")) {
                    GROUP_JID_REGEX.find(v)?.let { return it.value }
                }
            }
            // MessagingStyle: CharSequence array של הודעות
            if (v is Array<*>) {
                v.filterIsInstance<CharSequence>().forEach { cs ->
                    val s = cs.toString()
                    if (s.contains("@g.us")) GROUP_JID_REGEX.find(s)?.let { return it.value }
                }
            }
        }
        return null
    }
}
