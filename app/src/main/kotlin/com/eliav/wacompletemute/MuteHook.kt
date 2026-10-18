package com.eliav.wacompletemute

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class MuteHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG              = "WaCompleteMute"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private val COLOR_RED              = Color.parseColor("#F44336")

        private const val PREFS_FILE = "wa_complete_mute"
        private const val PREFS_KEY  = "muted_jids"
        private const val BELL_TAG   = "wa_red_bell"

        // מזהי משאבים של WhatsApp 2.26.40.72 (נבדקו מול ה-APK). נשמרים רק כ-fallback;
        // קודם כל מפענחים לפי שם עם Resources.getIdentifier (שמות ה-id נשמרים ב-APK).
        private const val FALLBACK_ID_ROW         = 0x7f0b0d59 // id/contact_row_container
        private const val FALLBACK_ID_AVATAR_BOX  = 0x7f0b0d5b // id/contact_selector
        private const val FALLBACK_ID_PHOTO       = 0x7f0b0d37 // id/contact_photo
        private const val FALLBACK_ID_MENU_UNMUTE = 0x7f0b217d // id/menuitem_unmute
        private const val FALLBACK_ID_MENU_MUTE   = 0x7f0b2132 // id/menuitem_mute
        private const val FALLBACK_ID_MUTE_ALWAYS = 0x7f0b2362 // id/mute_duration_always
        private const val FALLBACK_ID_MUTE_DONE   = 0x7f0b2361 // id/mute_done_button
        private const val STR_ALWAYS               = 0x7f122967 // "Always" (אפשרות במשך ההשתקה)
        private const val STR_MUTE_NOTIFICATIONS   = 0x7f1226c6 // "Mute notifications"
        private const val STR_UNMUTE_NOTIFICATIONS = 0x7f1226d9 // "Unmute notifications"

        // מילות מפתח שמזהות תפריט של קבוצה ספציפית (לא תפריט ראשי)
        // חייב להכיל לפחות אחת מהן כדי להיחשב תפריט קבוצה
        private val GROUP_MENU_KEYWORDS = setOf(
            "מחק", "delete",
            "ארכיון", "archive",
            "נעץ", "pin",
            "השתק התראות", "mute notifications",
            "הודעות נעלמות", "disappearing"
        )

        // regex שמחלץ JID נקי — רק עד @g.us, ללא suffix
        private val GROUP_JID_REGEX = Regex("""[\d\-]+@g\.us""")
        private val GROUP_EXACT_REGEX = Regex("""[\d\-]+@g\.us""")

        // כל JID אחר (צ'אט פרטי וכד') — משמש רק כדי להסיר פעמון משורה ממוחזרת
        private val OTHER_JID_REGEX = Regex("""[\w.\-:]+@[a-z.]+""")
    }

    // מיפוי viewHashCode → JID נקי
    private val viewToJid = mutableMapOf<Int, String>()

    // JID האחרון שזוהה מ-row ברשימת הצ'אטים (setTag עם format של row)
    // מתאפס כשנלחץ PopupWindow ונקרא
    @Volatile private var lastChatRowJid: String? = null

    // קבוצות מושתקות — ממופות לפי JID נקי (thread-safe: נקרא גם מ-thread של התראות)
    val mutedGroupsInProcess: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile private var mutedLoaded = false

    // שורות צ'אט שנקשרו לאחרונה ל-JID (רק מ-UI thread) — לעדכון מיידי של הפעמון
    private val boundRows = WeakHashMap<View, String>()

    private val idCache = ConcurrentHashMap<String, Int>()

    // ── פונקציות עזר ────────────────────────────────────────────────────

    /** חולץ JID נקי מ-tag שיכול להכיל suffix כמו _152_-2.8125 */
    private fun extractGroupJid(tag: String): String? {
        return GROUP_JID_REGEX.find(tag)?.value
    }

    // לוג מינימלי (שגיאות בלבד, בלי JID/מידע אישי), רק ל-Xposed log
    private fun log(message: String) = XposedBridge.log("$TAG: $message")


    private fun currentApplication(): Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) { null }

    /** מפענח id של WhatsApp לפי שם; אם לא נמצא — ה-fallback של הגרסה שנבדקה. */
    private fun idOf(ctx: Context, name: String, fallback: Int): Int =
        idCache.getOrPut(name) {
            val r = try { ctx.resources.getIdentifier(name, "id", ctx.packageName) } catch (_: Throwable) { 0 }
            if (r != 0) r else fallback
        }

    /**
     * טעינת הקבוצות המושתקות מ-SharedPreferences של WhatsApp. נקראת בכל נקודת כניסה
     * (הוספת פעמון, תפריט, התראה) ולכן לא תלויה בסדר האתחול של ה-hooks.
     */
    private fun ensureMutedLoaded(context: Context?) {
        if (mutedLoaded) return
        val ctx = context ?: currentApplication() ?: return
        synchronized(this) {
            if (mutedLoaded) return
            try {
                val saved = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
                mutedGroupsInProcess.addAll(saved)
                mutedLoaded = true
            } catch (e: Throwable) {
                log("load prefs error: ${e.message}")
            }
        }
    }

    // ── handleLoadPackage ────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WHATSAPP_PACKAGE) return
        try {
            hookViewSetTag(lpparam.classLoader)
            hookPopupWindow(lpparam.classLoader)
            hookNotificationManager(lpparam.classLoader)
            hookMenuController(lpparam.classLoader)
            hookNativeItemGuard(lpparam.classLoader)
            hookMuteDialog(lpparam.classLoader)
            loadMutedGroups(lpparam.classLoader)
        } catch (e: Throwable) {
            log("setup error: ${e.message}")
        }
    }

    // ── Hook 1: View.setTag ──────────────────────────────────────────────
    // WhatsApp מסמן את ה-View של שורת הצ'אט ב-tag מסוג String שמכיל את ה-JID, בכל bind של שורה.
    // משתמשים בזה כדי (א) לזכור את ה-JID האחרון (לתפריט), (ב) להציג/להסיר פעמון בשורה.
    // tags של הודעות/מפתחות נדחים כמו קודם.

    private fun hookViewSetTag(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod("android.view.View", classLoader,
            "setTag", Any::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tag = param.args[0] as? String ?: return
                    if (!tag.contains('@')) return
                    val view = param.thisObject as? View ?: return

                    if (tag.contains("@g.us")) {
                        // דחה tags שנראים כמו מפתחות הודעה (מכילים "Key", "id=", "isFromMe")
                        if (tag.contains("Key") || tag.contains("isFromMe") || tag.contains("id=")) return
                        val jid = extractGroupJid(tag) ?: return

                        viewToJid[view.hashCode()] = jid
                        lastChatRowJid = jid
                        scheduleBellUpdate(view, jid)
                    } else {
                        // צ'אט שאינו קבוצה: רק כדי להסיר פעמון משורה שמוחזרת לצ'אט אחר
                        val other = OTHER_JID_REGEX.find(tag)?.value ?: return
                        scheduleBellUpdate(view, other)
                    }
                }
            })
    }

    // ── פעמון אדום ברשימת הצ'אטים ───────────────────────────────────────
    // מבנה השורה ב-WhatsApp 2.26.40.72 (נבדק ב-layout של ה-APK):
    //   RelativeLayout#contact_row_container            ← שורת הצ'אט
    //     FrameLayout#contact_selector (80x76dp)        ← תיבת האווטאר
    //       WDSProfilePhoto#contact_photo
    // אינדיקטור ההשתקה המקורי (mute_indicator) הוא ViewStub שמנופח רק להשתקה רגילה,
    // לכן לא ניתן "לצבוע" אותו. התג שלנו נוסף כ-child של תיבת האווטאר, על שפת התמונה,
    // בלי לגעת בתמונה, במונה ההודעות או באייקונים אחרים.

    private fun scheduleBellUpdate(view: View, jid: String) {
        view.post {
            try {
                ensureMutedLoaded(view.context)
                val row = findChatRow(view) ?: return@post
                boundRows[row] = jid
                applyBell(row, jid)
            } catch (e: Throwable) {
            }
        }
    }

    /** מוצא את שורת הצ'אט (id=contact_row_container) מה-View שקיבל את ה-tag: למעלה, ואז למטה. */
    private fun findChatRow(start: View): ViewGroup? {
        val rowId = idOf(start.context, "contact_row_container", FALLBACK_ID_ROW)
        var v: View? = start
        var depth = 0
        while (v != null && depth < 12) {
            if (v.id == rowId && v is ViewGroup) return v
            v = v.parent as? View
            depth++
        }
        return (start as? ViewGroup)?.findViewById<View>(rowId) as? ViewGroup
    }

    /** מוסיף פעמון אם הקבוצה מושתקת לגמרי, ומסיר אותו אחרת (שורות ממוחזרות). */
    private fun applyBell(row: ViewGroup, jid: String) {
        val ctx = row.context
        val box = row.findViewById<View>(idOf(ctx, "contact_selector", FALLBACK_ID_AVATAR_BOX)) as? FrameLayout
        val existing = (box ?: row).findViewWithTag<View>(BELL_TAG)

        if (jid !in mutedGroupsInProcess) {
            if (existing != null) {
                (existing.parent as? ViewGroup)?.removeView(existing)
            }
            return
        }
        if (box == null) {
            return
        }
        if (existing != null) {
            positionBell(box, existing)
            return
        }

        val size = (20 * ctx.resources.displayMetrics.density).toInt()
        val bell = ImageView(ctx).apply {
            tag = BELL_TAG
            setImageDrawable(RedBellDrawable())
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "מושתקת לגמרי"
            isClickable = false
            isFocusable = false
        }
        box.addView(bell, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        positionBell(box, bell)
    }

    /** ממקם את התג על שפת האווטאר (פינה תחתונה בצד ה-start); fallback לפינת התיבה. */
    private fun positionBell(box: FrameLayout, bell: View) {
        val photo = box.findViewById<View>(idOf(box.context, "contact_photo", FALLBACK_ID_PHOTO))
        if (photo != null && photo.visibility == View.VISIBLE) {
            if (photo.width > 0) {
                val off = minOf(photo.width, photo.height) / 2f * 0.7071f
                val rtl = box.layoutDirection == View.LAYOUT_DIRECTION_RTL
                bell.translationX = if (rtl) off else -off
                bell.translationY = off
                return
            }
            // עוד לא נמדד — ננסה שוב אחרי ה-layout
            box.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int,
                                            ol: Int, ot: Int, or2: Int, ob: Int) {
                    box.removeOnLayoutChangeListener(this)
                    if (bell.parent === box && photo.width > 0) positionBell(box, bell)
                }
            })
            return
        }
        (bell.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.BOTTOM or Gravity.START
            bell.layoutParams = it
        }
        bell.translationX = 0f
        bell.translationY = 0f
    }

    /** עדכון מיידי של כל השורות הידועות (אחרי השתקה / ביטול). חייב לרוץ ב-UI thread. */
    private fun refreshBells() {
        for ((row, jid) in boundRows.entries.toList()) {
            try {
                (row as? ViewGroup)?.let { applyBell(it, jid) }
            } catch (e: Throwable) {
            }
        }
    }

    // ── Hook 2: PopupWindow ──────────────────────────────────────────────
    // מטרה: לתפוס תפריט של קבוצה ספציפית ולהוסיף "השתק לגמרי".
    // ה-JID נקבע לפי מסך השיחה (extras של ה-Activity) ורק אם אין — לפי שורת הצ'אט האחרונה.

    private fun hookPopupWindow(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val popup = param.thisObject as? PopupWindow ?: return
                val contentView = popup.contentView ?: return

                val jid = resolveJid(contentView.context) ?: return
                if (!jid.contains("@g.us")) return

                ensureMutedLoaded(contentView.context)
                handlePopupShown(popup, contentView, jid)
            }
        }

        val overloads = listOf(
            arrayOf<Any>("showAsDropDown", View::class.java),
            arrayOf<Any>("showAsDropDown", View::class.java, Int::class.java, Int::class.java),
            arrayOf<Any>("showAsDropDown", View::class.java, Int::class.java, Int::class.java, Int::class.java),
            arrayOf<Any>("showAtLocation", View::class.java, Int::class.java, Int::class.java, Int::class.java)
        )
        for (m in overloads) {
            try {
                val name = m[0] as String
                val types = m.drop(1).map { it as Class<*> }.toTypedArray()
                XposedHelpers.findAndHookMethod("android.widget.PopupWindow", classLoader, name, *types, hook)
            } catch (e: Exception) {
                log("skip PopupWindow.${m[0]}: ${e.message}")
            }
        }
    }

    /**
     * JID של הקבוצה שהתפריט שייך לה. lastChatRowJid הוא משתנה גלובלי שנדרס בכל bind של שורה
     * (גם של קבוצות אחרות) ולכן אינו אמין; במסך שיחה ה-JID נמצא ב-extras של ה-Activity.
     */
    private fun resolveJid(context: Context): String? {
        var c: Context? = context
        var activity: android.app.Activity? = null
        while (c != null) {
            if (c is android.app.Activity) { activity = c; break }
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        if (activity != null && !activity.javaClass.name.endsWith("HomeActivity")) {
            try {
                val extras = activity.intent?.extras
                if (extras != null) {
                    for (k in extras.keySet()) {
                        @Suppress("DEPRECATION") val v = extras.get(k)
                        if (v is String && GROUP_EXACT_REGEX.matches(v)) {
                            return v
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return lastChatRowJid
    }

    private fun handlePopupShown(popup: PopupWindow, contentView: View, jid: String) {
        val context = contentView.context ?: return
        val cleanJid = extractGroupJid(jid) ?: jid
        contentView.postDelayed({
            try {
                val listView = findListView(contentView)
                if (listView != null) {
                    if (!isGroupSpecificMenu(listView)) {
                        return@postDelayed
                    }

                    // אם מושתקת לגמרי — תפריט ה-WhatsApp מוצג כ"בטל השתקת התראות" וחסום (בכל layout מחדש)
                    if (cleanJid in mutedGroupsInProcess) {
                        installMenuGuard(listView, popup)

                        // סנכרון בכל פתיחת תפריט: מצב WhatsApp נקרא טרי. אם הוא לא מושתק למרות שההשתקה
                        // המוחלטת פעילה (למשל בוטל במקום אחר) — מפעילים מחדש את ההשתקה המקורית.
                        val tries = healAttempts[cleanJid] ?: 0
                        if (!opInProgress && tries < 2 && nativeMuteState(activityOf(context)) == false) {
                            val nat = findNativeMute(listView)
                            if (nat != null) {
                                healAttempts[cleanJid] = tries + 1
                                startNativeMute(context, cleanJid, listView, nat.first, true)
                                popup.dismiss()
                                return@postDelayed
                            }
                        }
                    }

                    injectMuteItem(listView, context, cleanJid, popup)
                    return@postDelayed
                }

                val recycler = findRecyclerView(contentView)
                if (recycler != null && isGroupSpecificMenuContainer(recycler)) {
                    if (cleanJid in mutedGroupsInProcess) {
                        blockNativeMuteItemInContainer(recycler, context, popup)
                    }
                    injectMuteItemToParent(recycler, context, cleanJid, popup)
                    return@postDelayed
                }

            } catch (e: Exception) {
            }
        }, 80L)
    }

    // ── מצב התפריט הרגיל כשההשתקה המוחלטת פעילה ──────────────────────────
    // שורש הבעיה הקודם: הטקסט והחסימה הוחלו פעם אחת אחרי 80ms, רק על Views שכבר נמדדו ורק ברמת
    // הילד הישיר, וחסימה ע"י OnClickListener על ה-View שאבדה בכל bind מחדש של הרשימה.
    // עכשיו: (1) אחרי כל layout של הרשימה מיישמים מחדש את הטקסט (idempotent),
    // (2) החסימה ברמת האירוע (AbsListView.performItemClick), שלא תלויה ב-Views.

    private val guardedLists = WeakHashMap<ListView, PopupWindow>()
    @Volatile private var allowNativeClick = false

    private fun installMenuGuard(listView: ListView, popup: PopupWindow) {
        guardedLists[listView] = popup
        val vto = listView.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!popup.isShowing) {
                    try { if (vto.isAlive) vto.removeOnGlobalLayoutListener(this) } catch (_: Throwable) {}
                    guardedLists.remove(listView)
                    return
                }
                applyMenuGuard(listView, popup)
            }
        }
        vto.addOnGlobalLayoutListener(listener)
        applyMenuGuard(listView, popup)
    }

    private fun applyMenuGuard(listView: ListView, popup: PopupWindow) {
        val adapter = listView.adapter
        val ctx = listView.context
        val first = listView.firstVisiblePosition
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i) ?: continue
            // זיהוי לפי פריט ה-adapter, ואם אינו מזוהה — לפי הטקסט שמוצג בפועל בשורה
            var kind = if (adapter != null && first + i < adapter.count) nativeKind(ctx, adapter.getItem(first + i)) else 0
            if (kind == 0) kind = kindOfView(child)
            if (kind == 0) continue
            relabelDeep(child)
            if (!child.hasOnClickListeners()) {
                child.setOnClickListener { showBlockedToast(ctx); popup.dismiss() }
            }
        }
    }

    /** מציג "בטל השתקת התראות" בכל TextView בתוך שורת התפריט (לא רק בילד הישיר). */
    private fun relabelDeep(view: View) {
        if (view is TextView) {
            val cur = view.text?.toString() ?: return
            val lower = cur.lowercase()
            if (!lower.contains("לגמרי") && !lower.contains("unmute") &&
                (lower.contains("mute notifications") || lower.contains("השתקת התראות"))) {
                if (cur != "בטל השתקת התראות") view.text = "בטל השתקת התראות"
            }
            if (!lower.contains("לגמרי") &&
                (lower.contains("mute notifications") || lower.contains("השתקת התראות") || lower.contains("unmute"))) {
                val gray = Color.parseColor("#AAAAAA")
                if (view.currentTextColor != gray) view.setTextColor(gray)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) relabelDeep(view.getChildAt(i))
        }
    }

    private fun showBlockedToast(context: Context) {
        android.widget.Toast.makeText(context, "כבה 'השתק לגמרי' קודם 🔕", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun hookNativeItemGuard(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.widget.AbsListView", classLoader, "performItemClick",
                View::class.java, Int::class.java, Long::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (allowNativeClick) return
                        val lv = param.thisObject as? ListView ?: return
                        val popup = guardedLists[lv] ?: return
                        val pos = param.args[1] as? Int ?: return
                        val item = try { lv.adapter?.getItem(pos) } catch (_: Throwable) { null }
                        var kind = nativeKind(lv.context, item)
                        if (kind == 0) (param.args[0] as? View)?.let { kind = kindOfView(it) }
                        if (kind == 0) return
                        showBlockedToast(lv.context)
                        popup.dismiss()
                        param.result = true
                    }
                })
        } catch (e: Throwable) {
            log("performItemClick hook failed: ${e.message}")
        }
    }

    private fun blockNativeMuteItemInContainer(container: ViewGroup, context: Context, popup: PopupWindow) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val text = getAllText(child).lowercase()
            if (text.contains("השתקת התראות") || text.contains("mute notifications")
                || text.contains("ביטול השתקה") || text.contains("unmute")) {
                relabelDeep(child)
                child.setOnClickListener { showBlockedToast(context); popup.dismiss() }
            }
        }
    }

    /**
     * בדיקה קפדנית שהתפריט הוא של קבוצה ספציפית.
     * תפריט קבוצה מכיל פריטים כמו "מחק", "ארכיון", "השתק התראות".
     * תפריט ראשי מכיל "קבוצה חדשה", "הגדרות" — לא יעבור את הבדיקה.
     * תפריט הודעה מכיל "העבר", "העתק" — לא יעבור.
     */
    private fun isGroupSpecificMenu(listView: ListView): Boolean {
        // בדוק דרך children
        for (i in 0 until listView.childCount) {
            val text = getAllText(listView.getChildAt(i) ?: continue).lowercase()
            if (GROUP_MENU_KEYWORDS.any { text.contains(it) }) return true
        }
        // בדוק דרך adapter
        val adapter = listView.adapter ?: return false
        for (i in 0 until minOf(adapter.count, 12)) {
            try {
                val text = adapter.getItem(i)?.toString()?.lowercase() ?: continue
                if (GROUP_MENU_KEYWORDS.any { text.contains(it) }) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun isGroupSpecificMenuContainer(container: ViewGroup): Boolean {
        for (i in 0 until container.childCount) {
            val text = getAllText(container.getChildAt(i) ?: continue).lowercase()
            if (GROUP_MENU_KEYWORDS.any { text.contains(it) }) return true
        }
        return false
    }

    private fun injectMuteItem(listView: ListView, context: Context, jid: String, popup: PopupWindow) {
        try {
            // קח ישירות את ה-parent של ה-ListView — זה ה-container
            val container = listView.parent as? ViewGroup ?: run {
                return
            }

            // מניעת כפילות
            for (i in 0 until container.childCount) {
                if (container.getChildAt(i)?.tag == "wa_mute_item") {
                    return
                }
            }

            val isMuted = jid in mutedGroupsInProcess
            val tv = buildMuteTextView(context, jid, isMuted, popup)

            val lvIndex = (0 until container.childCount).firstOrNull {
                container.getChildAt(it) === listView
            } ?: (container.childCount - 1)

            container.addView(tv, lvIndex + 1)
        } catch (e: Exception) {
        }
    }

    private fun injectMuteItemToParent(view: View, context: Context, jid: String, popup: PopupWindow) {
        val parent = view.parent as? ViewGroup ?: return
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i)?.tag == "wa_mute_item") return
        }
        val isMuted = jid in mutedGroupsInProcess
        val tv = buildMuteTextView(context, jid, isMuted, popup)
        val idx = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === view }
            ?: parent.childCount - 1
        parent.addView(tv, idx + 1)
    }

    private fun buildMuteTextView(context: Context, jid: String, @Suppress("UNUSED_PARAMETER") isMuted: Boolean, popup: PopupWindow): TextView {
        // עבוד תמיד עם JID נקי
        val cleanJid = extractGroupJid(jid) ?: jid
        val actuallyMuted = cleanJid in mutedGroupsInProcess
        return TextView(context).apply {
            text = if (actuallyMuted) "בטל השתקה לגמרי" else "השתק לגמרי"
            tag  = "wa_mute_item"
            textSize = 16f
            setTextColor(if (actuallyMuted) COLOR_RED else Color.parseColor("#212121"))
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(56, 32, 56, 32)
            try {
                @Suppress("DEPRECATION")
                val bell = context.getDrawable(android.R.drawable.ic_lock_silent_mode)
                if (bell != null) {
                    @Suppress("DEPRECATION")
                    bell.setColorFilter(
                        if (actuallyMuted) COLOR_RED else Color.parseColor("#757575"),
                        PorterDuff.Mode.SRC_IN
                    )
                    setCompoundDrawablesWithIntrinsicBounds(null, null, bell, null)
                    compoundDrawablePadding = 24
                }
            } catch (_: Exception) {}
            setOnClickListener { onToggleClicked(context, cleanJid, popup) }
        }
    }

    // ── הפעלה / ביטול של "השתק לגמרי" עם סנכרון להשתקה המקורית ──────────────
    // שורש הבעיה הקודם: המצב נשמר, הבועה הוצגה וה-Toast "הושתקה" הוצג לפני שההשתקה המקורית
    // (אסינכרונית: דיאלוג) התחילה בכלל, וכישלון נבלע. עכשיו המצב נשמר רק אחרי הצלחה מאומתת.
    //
    // המנגנון המקורי (נבדק ב-dex של WhatsApp 2.26.40.72): פריט התפריט "Mute/Unmute notifications"
    // מטופל ב-LX/8e7.onOptionsItemSelected; המצב נקרא ע"י LX/0Xg.A0V(chatJid).A0A() (isMuted).
    // מפעילים את הפריט האמיתי בתפריט הפתוח (performItemClick) — WhatsApp עצמו מעדכן DB, cache,
    // UI והתראות. זיהוי המצב הנוכחי נעשה לפי הפריט שמוצג בתפריט (Mute / Unmute), בלי Toggle עיוור.

    private class PendingMute(val ctx: Context, val jid: String, val deadline: Long, val heal: Boolean) {
        var driving = false
        var finished = false
    }

    private var opInProgress = false           // UI thread בלבד
    private var pendingMute: PendingMute? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healAttempts = HashMap<String, Int>()

    // ה-controller של תפריט השיחה של WhatsApp (LX/8e7). את A0I() שלו קוראים כדי לדעת את מצב ההשתקה
    // האמיתי של WhatsApp בצורה טרייה (A0I בודק isMuted ובוחר "Unmute"/"Mute notifications").
    @Volatile private var menuControllerRef: java.lang.ref.WeakReference<Any>? = null

    private fun hookMenuController(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("X.8e7", classLoader, "A0I", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    menuControllerRef = java.lang.ref.WeakReference(param.thisObject)
                }
            })
        } catch (e: Throwable) {
            log("menu controller hook failed: ${e.message}")
        }
    }

    private fun activityOf(ctx: Context?): android.app.Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * מצב ההשתקה הרגילה של WhatsApp, נקרא עכשיו מהמנגנון של WhatsApp עצמו (לא מ-cache שלנו):
     * true = מושתק, false = לא מושתק, null = לא ניתן לקרוא (אין controller של המסך הנוכחי).
     */
    private fun nativeMuteState(activity: android.app.Activity?): Boolean? {
        val c = menuControllerRef?.get() ?: return null
        return try {
            if (activity != null) {
                val owner = XposedHelpers.getObjectField(c, "A02")
                if (owner !== activity && owner?.javaClass != activity.javaClass) return null
            }
            when (XposedHelpers.callMethod(c, "A0I") as? Int) {
                STR_UNMUTE_NOTIFICATIONS -> true
                STR_MUTE_NOTIFICATIONS -> false
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** ממתין עד שמצב WhatsApp משתקף כצפוי. cb(true)=אומת, cb(false)=לא השתנה, cb(null)=לא ניתן לאמת. */
    private fun verifyNativeState(activity: android.app.Activity?, expectedMuted: Boolean,
                                  startedAt: Long, cb: (Boolean?) -> Unit) {
        val s = nativeMuteState(activity)
        when {
            s == null -> cb(null)
            s == expectedMuted -> cb(true)
            System.currentTimeMillis() - startedAt > 2800L -> cb(false)
            else -> mainHandler.postDelayed({ verifyNativeState(activity, expectedMuted, startedAt, cb) }, 200L)
        }
    }

    private fun resString(ctx: Context, id: Int): String? =
        try { ctx.getString(id).takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }

    private fun looksLikeUnmuteTitle(lower: String): Boolean =
        lower.contains("unmute") ||
            (lower.contains("השתק") && (lower.contains("ביטול") || lower.contains("בטל")))

    /** 0 = לא פריט השתקה, 1 = "Mute notifications" (הצ'אט לא מושתק), 2 = "Unmute notifications" (מושתק). */
    private fun kindOfText(text: String): Int {
        val lower = text.lowercase()
        if (lower.isEmpty() || lower.contains("לגמרי")) return 0 // הפריט שלנו
        if (looksLikeUnmuteTitle(lower)) return 2
        if (lower.contains("mute notifications") || lower.contains("השתקת התראות")) return 1
        return 0
    }

    private fun kindOfView(view: View): Int = kindOfText(getAllText(view))

    private fun nativeKind(ctx: Context, item: Any?): Int {
        if (item == null) return 0
        val mi = item as? MenuItem
        val title = mi?.title?.toString() ?: item.toString()
        if (title.lowercase().contains("לגמרי")) return 0
        val id = mi?.itemId
        if (id == idOf(ctx, "menuitem_unmute", FALLBACK_ID_MENU_UNMUTE) ||
            title == resString(ctx, STR_UNMUTE_NOTIFICATIONS)) return 2
        if (id == idOf(ctx, "menuitem_mute", FALLBACK_ID_MENU_MUTE) ||
            title == resString(ctx, STR_MUTE_NOTIFICATIONS)) return 1
        return kindOfText(title)
    }

    /** (מיקום, סוג) של פריט ההשתקה המקורי ברשימה: לפי פריט ה-adapter, ואם אין — לפי הטקסט המוצג. */
    private fun findNativeMute(listView: ListView): Pair<Int, Int>? {
        val adapter = listView.adapter ?: return null
        val first = listView.firstVisiblePosition
        for (i in 0 until adapter.count) {
            var k = nativeKind(listView.context, adapter.getItem(i))
            if (k == 0) {
                val child = listView.getChildAt(i - first)
                if (child != null) k = kindOfView(child)
            }
            if (k != 0) return Pair(i, k)
        }
        return null
    }

    private fun clickNative(listView: ListView, pos: Int): Boolean {
        val adapter = listView.adapter ?: return false
        val child = listView.getChildAt(pos - listView.firstVisiblePosition)
        allowNativeClick = true
        return try {
            listView.performItemClick(child, pos, adapter.getItemId(pos))
        } catch (e: Throwable) {
            false
        } finally {
            allowNativeClick = false
        }
    }

    private fun toast(context: Context, msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun onToggleClicked(context: Context, jid: String, popup: PopupWindow) {
        if (opInProgress) {
            toast(context, "הפעולה עדיין מתבצעת")
            popup.dismiss()
            return
        }
        val enabling = jid !in mutedGroupsInProcess
        val listView = popup.contentView?.let { findListView(it) }
        val native = listView?.let { findNativeMute(it) }
        val activity = activityOf(context)

        if (listView == null || native == null) {
            failToggle(context, enabling, "native mute item not found in popup")
            popup.dismiss()
            return
        }

        // המצב הנוכחי של WhatsApp נקרא טרי; אם אי אפשר — לפי הפריט שמוצג בתפריט. לעולם לא Toggle עיוור.
        val nativeMuted = nativeMuteState(activity) ?: (native.second == 2)

        if (enabling) {
            if (nativeMuted) {
                commitState(context, jid, true) // כבר מושתק ב-WhatsApp: לא לוחצים כדי לא לבטל בטעות
                popup.dismiss()
                return
            }
            startNativeMute(context, jid, listView, native.first, false)
            popup.dismiss()
        } else {
            if (!nativeMuted) {
                commitState(context, jid, false) // אין השתקה רגילה לבטל
                popup.dismiss()
                return
            }
            if (!clickNative(listView, native.first)) {
                failToggle(context, false, "native unmute click not handled")
                popup.dismiss()
                return
            }
            opInProgress = true
            verifyNativeState(activity, false, System.currentTimeMillis()) { ok ->
                opInProgress = false
                // null = אי אפשר לקרוא את המצב; הלחיצה על הפריט המקורי בוצעה, ולכן ממשיכים
                if (ok != false) commitState(context, jid, false)
                else failToggle(context, false, "native state still muted after unmute")
            }
            popup.dismiss()
        }
    }

    private fun startNativeMute(context: Context, jid: String, listView: ListView, pos: Int, heal: Boolean) {
        opInProgress = true
        val p = PendingMute(context, jid, System.currentTimeMillis() + 9000L, heal)
        pendingMute = p
        mainHandler.postDelayed({ finishPendingMute(p, false, "timeout waiting for mute dialog") }, 9000L)
        if (!clickNative(listView, pos)) finishPendingMute(p, false, "native mute click not handled")
    }

    /** כתיבת המצב + עדכון UI. נקרא רק אחרי הצלחה. */
    private fun commitState(context: Context, jid: String, muted: Boolean) {
        if (muted) mutedGroupsInProcess.add(jid) else mutedGroupsInProcess.remove(jid)
        saveMutedGroups(context)
        sendMuteUpdate(context, jid, muted)
        refreshBells()
        toast(context, if (muted) "קבוצה הושתקה לגמרי 🔕" else "ההשתקה המלאה בוטלה 🔔")
    }

    private fun failToggle(context: Context, enabling: Boolean, @Suppress("UNUSED_PARAMETER") reason: String) {
        toast(context, if (enabling) "ההשתקה נכשלה, המצב לא שונה" else "הביטול נכשל, ההשתקה נשארה פעילה")
    }

    private fun finishPendingMute(p: PendingMute, success: Boolean, reason: String) {
        if (p.finished) return
        p.finished = true
        opInProgress = false
        if (pendingMute === p) pendingMute = null
        when {
            success && p.heal -> refreshBells() // המצב שלנו כבר שמור; רק הסתנכרן מול WhatsApp
            success -> commitState(p.ctx, p.jid, true)
            p.heal -> toast(p.ctx, "סנכרון ההשתקה של WhatsApp נכשל")
            else -> failToggle(p.ctx, true, reason)
        }
    }

    // הדיאלוג של WhatsApp למשך ההשתקה. בגרסה 2.26.40.72 (נבדק ב-dex): MuteDialogFragment הוא
    // AlertDialog עם קבוצת רדיו (8 שעות / שבוע / תמיד) וכפתור חיובי; קיים גם וריאנט עם ids ייעודיים
    // (mute_duration_always / mute_done_button). שניהם נתמכים. מסתירים את הדיאלוג, בוחרים "תמיד",
    // מאשרים, ורק אחר כך מאמתים מול WhatsApp עצמו שהצ'אט מושתק. פעיל רק בזמן פעולה ממתינה.

    private fun hookMuteDialog(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Dialog", classLoader, "show",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val p = pendingMute ?: return
                        if (p.driving || p.finished || System.currentTimeMillis() > p.deadline) return
                        val dialog = param.thisObject as? android.app.Dialog ?: return
                        val decor = dialog.window?.decorView ?: return
                        tryDriveMuteDialog(p, dialog, decor, 0)
                    }
                })
        } catch (e: Throwable) {
            log("Dialog.show hook failed: ${e.message}")
        }
    }

    private fun collectCompoundButtons(view: View, out: MutableList<android.widget.CompoundButton>) {
        if (view is android.widget.CompoundButton) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) collectCompoundButtons(view.getChildAt(i), out)
    }

    private fun tryDriveMuteDialog(p: PendingMute, dialog: android.app.Dialog, decor: View, attempt: Int) {
        if (p.driving || p.finished) return
        val ctx = decor.context

        // וריאנט עם ids ייעודיים
        val alwaysById = decor.findViewById<View>(idOf(ctx, "mute_duration_always", FALLBACK_ID_MUTE_ALWAYS))
        val doneById = decor.findViewById<View>(idOf(ctx, "mute_done_button", FALLBACK_ID_MUTE_DONE))
        if (alwaysById != null && doneById != null) {
            p.driving = true
            hideDialog(decor, dialog)
            decor.post { chooseAlwaysAndConfirm(p, dialog, decor, alwaysById) { doneById.performClick() } }
            return
        }

        // וריאנט AlertDialog: קבוצת רדיו + כפתור חיובי
        val all = ArrayList<android.widget.CompoundButton>()
        collectCompoundButtons(decor, all)
        val radios = all.filter { it is android.widget.RadioButton }.ifEmpty { all }
        val positive = try { XposedHelpers.callMethod(dialog, "getButton", -1) as? View } catch (_: Throwable) { null }
        if (radios.size >= 2 && positive != null) {
            val alwaysText = resString(ctx, STR_ALWAYS)
            val target = radios.firstOrNull { alwaysText != null && it.text?.toString() == alwaysText }
                ?: radios.last() // האפשרויות לפי הסדר: 8 שעות, שבוע, תמיד
            p.driving = true
            hideDialog(decor, dialog)
            decor.post { chooseAlwaysAndConfirm(p, dialog, decor, target) { positive.performClick() } }
            return
        }

        if (attempt < 5) {
            decor.postDelayed({ tryDriveMuteDialog(p, dialog, decor, attempt + 1) }, 100L)
        }
    }

    private fun hideDialog(decor: View, dialog: android.app.Dialog) {
        decor.alpha = 0f
        dialog.window?.setDimAmount(0f)
    }

    private fun chooseAlwaysAndConfirm(p: PendingMute, dialog: android.app.Dialog, decor: View,
                                       always: View, confirm: () -> Unit) {
        always.performClick()
        val cb = always as? android.widget.CompoundButton
        if (cb != null && !cb.isChecked) cb.isChecked = true
        if (cb != null && !cb.isChecked) {
            decor.alpha = 1f
            finishPendingMute(p, false, "could not select 'always'")
            return
        }
        // נותנים ל-UI לעבד את הבחירה לפני האישור (הכפתור עשוי להיות מושבת עד אז)
        decor.postDelayed({
            confirm()
            decor.postDelayed({
                if (dialog.isShowing) {
                    decor.alpha = 1f
                    finishPendingMute(p, false, "dialog still open after confirm")
                } else {
                    // הדיאלוג נסגר (גם ביטול סוגר אותו) — ההצלחה נקבעת רק לפי המצב האמיתי של WhatsApp
                    verifyNativeState(activityOf(p.ctx), true, System.currentTimeMillis()) { ok ->
                        finishPendingMute(p, ok == true,
                            if (ok == null) "cannot read native mute state" else "native chat not muted after confirm")
                    }
                }
            }, 500L)
        }, 200L)
    }

    // ── Hook 4: NotificationManager.notify ──────────────────────────────

    private fun hookNotificationManager(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val notification = param.args.lastOrNull() as? Notification ?: return
                ensureMutedLoaded(null)
                val jid = extractJidFromNotification(notification) ?: return
                if (jid !in mutedGroupsInProcess) return
                param.result = null  // חסום לגמרי
            }
        }
        XposedHelpers.findAndHookMethod(NotificationManager::class.java,
            "notify", String::class.java, Int::class.java, Notification::class.java, hook)
        XposedHelpers.findAndHookMethod(NotificationManager::class.java,
            "notify", Int::class.java, Notification::class.java, hook)
    }

    private fun extractJidFromNotification(n: Notification): String? {
        val extras = n.extras ?: return null
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION") val v = extras.get(key)
            if (v is String) {
                extractGroupJid(v)?.let { return it }
            }
        }
        return null
    }

    // ── שמירה וטעינה של מצב ─────────────────────────────────────────────
    // שמורים ב-SharedPreferences של WhatsApp עצמו (accessible מהhook)

    private fun loadMutedGroups(classLoader: ClassLoader) {
        // טעינה מוקדמת ב-Application.onCreate; בנוסף ensureMutedLoaded() טוען בעצלות בכל נקודת כניסה
        XposedHelpers.findAndHookMethod("android.app.Application", classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    ensureMutedLoaded(app)
                }
            })
    }

    private fun saveMutedGroups(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(PREFS_KEY, mutedGroupsInProcess.toSet()).apply()
        } catch (e: Exception) {
            log("save prefs error: ${e.message}")
        }
    }

    private fun sendMuteUpdate(context: Context, jid: String, muted: Boolean) {
        try {
            val intent = Intent(MuteStore.ACTION_MUTE_UPDATE).apply {
                setPackage("com.eliav.wacompletemute")
                putExtra(MuteStore.EXTRA_GROUP_JID, jid)
                putExtra(MuteStore.EXTRA_IS_MUTED, muted)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            log("IPC error: ${e.message}")
        }
    }

    // ── View helpers ─────────────────────────────────────────────────────

    private fun findListView(root: View): ListView? {
        if (root is ListView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findListView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findRecyclerView(root: View): ViewGroup? {
        if (root.javaClass.name.contains("RecyclerView")) return root as? ViewGroup
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findRecyclerView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun getAllText(view: View): String {
        if (view is TextView) return view.text?.toString() ?: ""
        if (view is ViewGroup) {
            return (0 until view.childCount).joinToString(" ") { getAllText(view.getChildAt(it)) }
        }
        return ""
    }
}
