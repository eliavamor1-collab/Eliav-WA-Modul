package com.eliav.wacompletemute

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * MuteHook v3.4 — LSPatch module for complete group muting.
 *
 * KEY DESIGN DECISIONS:
 * - JID is captured at the moment the popup's anchor View is clicked, stored per-popup,
 *   never from a stale global. lastChatRowJid is only a last-resort fallback for the
 *   chat-list context; it is NEVER used to drive a state-changing action.
 * - State changes (mute/unmute) happen ONLY on explicit user click on our injected item.
 *   Opening a menu, scrolling, or View recycling never triggers a state change.
 * - menuControllerRef is validated against the current Activity before use; if the
 *   controller doesn't match the foreground conversation it returns null (= unknown).
 * - The heal mechanism has been removed. It was the source of spurious state changes.
 * - The BroadcastReceiver is protected by android:exported=false + action filter;
 *   the Hook additionally sends with setPackage so only this package can receive it.
 */
class MuteHook : IXposedHookLoadPackage {

    // ── Constants ────────────────────────────────────────────────────────
    companion object {
        private const val TAG              = "WaCompleteMute"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private val   COLOR_RED            = Color.parseColor("#F44336")
        private const val PREFS_FILE       = "wa_complete_mute"
        private const val PREFS_KEY        = "muted_jids"
        private const val BELL_TAG         = "wa_red_bell"
        private const val MENU_ITEM_TAG    = "wa_mute_item"

        // WhatsApp 2.26.40.72 resource ids (verified against APK).
        // getIdentifier() is tried first; these are fallbacks.
        private const val FB_ROW           = 0x7f0b0d59 // id/contact_row_container
        private const val FB_AVATAR_BOX   = 0x7f0b0d5b // id/contact_selector
        private const val FB_PHOTO        = 0x7f0b0d37 // id/contact_photo
        private const val FB_MENU_UNMUTE  = 0x7f0b217d // id/menuitem_unmute
        private const val FB_MENU_MUTE    = 0x7f0b2132 // id/menuitem_mute
        private const val FB_MUTE_ALWAYS  = 0x7f0b2362 // id/mute_duration_always
        private const val FB_MUTE_DONE    = 0x7f0b2361 // id/mute_done_button
        private const val STR_ALWAYS      = 0x7f122967 // "Always"
        private const val STR_MUTE        = 0x7f1226c6 // "Mute notifications"
        private const val STR_UNMUTE      = 0x7f1226d9 // "Unmute notifications"

        // Regex: normalised group JID (digits-digits@g.us, no suffix)
        private val JID_RE   = Regex("""[\d\-]+@g\.us""")
        // Any JID-like token (used only for bell removal on recycled rows)
        private val ANY_JID  = Regex("""[\w.\-:]+@[a-z.]+""")

        // Keywords that confirm a popup is a per-group context menu, not the main menu
        // or a single-message menu.  We check BOTH the adapter items AND the visible
        // children — intentionally broad so we survive Hebrew/English locale changes.
        // Items that identify the *main* menu (new group, settings…) or a message menu
        // (forward, copy…) will NOT appear here.
        private val GROUP_MENU_IDS = setOf(
            FB_MENU_MUTE, FB_MENU_UNMUTE
        )
        // String-based confirmation keywords (checked in addition to item ids)
        private val GROUP_MENU_KW = setOf(
            "מחק", "delete", "ארכיון", "archive",
            "נעץ", "pin", "הודעות נעלמות", "disappearing",
            "השתק התראות", "השתקת התראות", "mute notifications",
            "בטל השתקת התראות", "unmute notifications"
        )
    }

    // ── Instance state ────────────────────────────────────────────────────

    /** Normalised JIDs of completely-muted groups. Written from UI thread and notification thread. */
    val mutedGroupsInProcess: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var mutedLoaded = false

    /** Maps view.hashCode() → normalised JID for the chat row that owns the view. */
    private val viewToJid = mutableMapOf<Int, String>()

    /** Last JID seen from a chat-row setTag — removed, no longer used. */

    /** Known chat-row ViewGroups → their normalised JID (for bell refresh). */
    private val boundRows = WeakHashMap<View, String>()

    /** popup → normalised JID captured at show() time, before any postDelayed. */
    private val popupJid = WeakHashMap<PopupWindow, String>()

    /** ListViews that are guarded (completely-muted group menu open) → popup. */
    private val guardedLists = WeakHashMap<ListView, PopupWindow>()

    /** Set to true only while we are programmatically clicking a native mute item. */
    @Volatile private var allowNativeClick = false

    private val idCache = ConcurrentHashMap<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Pending mute operation ────────────────────────────────────────────

    private class PendingMute(val ctx: Context, val jid: String, val deadline: Long) {
        @Volatile var driving = false
        @Volatile var finished = false
    }

    private var opInProgress = false   // UI thread only
    @Volatile private var pendingMute: PendingMute? = null

    // ── LX/8e7 menu controller reference (for reading native mute state) ──
    // This is the menu controller for the currently-open conversation screen.
    // We ONLY use it when it is associated with the foreground Activity, and
    // only to READ the current native mute state — never to write.
    @Volatile private var menuControllerRef: java.lang.ref.WeakReference<Any>? = null

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Extract a normalised group JID from an arbitrary string that may have suffixes. */
    private fun extractJid(s: String): String? = JID_RE.find(s)?.value

    private fun log(msg: String) {
        android.util.Log.d(TAG, msg)  // Android logcat
        XposedBridge.log("$TAG: $msg")  // Xposed log
    }

    private fun idOf(ctx: Context, name: String, fb: Int): Int =
        idCache.getOrPut(name) {
            try { ctx.resources.getIdentifier(name, "id", ctx.packageName).takeIf { it != 0 } ?: fb }
            catch (_: Throwable) { fb }
        }

    private fun resStr(ctx: Context, id: Int): String? =
        try { ctx.getString(id).takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }

    private fun currentApp(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) { null }

    private fun activityOf(ctx: Context?): android.app.Activity? {
        var c = ctx
        while (c != null) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    private fun ensureMutedLoaded(ctx: Context?) {
        if (mutedLoaded) return
        val c = ctx ?: currentApp() ?: return
        synchronized(this) {
            if (mutedLoaded) return
            try {
                val saved = c.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
                mutedGroupsInProcess.addAll(saved)
                mutedLoaded = true
            } catch (e: Throwable) { log("load prefs error: ${e.message}") }
        }
    }

    private fun toast(ctx: Context, msg: String) =
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ── Entry point ───────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("handleLoadPackage: pkg=${lpparam.packageName} process=${lpparam.processName}")
        if (lpparam.packageName != WHATSAPP_PACKAGE) {
            log("handleLoadPackage: not WhatsApp, skipping")
            return
        }
        log("handleLoadPackage: WhatsApp detected, installing hooks")
        try {
            hookViewSetTag(lpparam.classLoader)
            log("handleLoadPackage: hookViewSetTag OK")
            hookPopupWindow(lpparam.classLoader)
            log("handleLoadPackage: hookPopupWindow OK")
            hookNotificationManager(lpparam.classLoader)
            log("handleLoadPackage: hookNotificationManager OK")
            hookStartActivity(lpparam.classLoader)
            log("handleLoadPackage: hookStartActivity OK")
            hookMenuController(lpparam.classLoader)
            log("handleLoadPackage: hookMenuController OK")
            hookNativeItemGuard(lpparam.classLoader)
            log("handleLoadPackage: hookNativeItemGuard OK")
            hookMuteDialog(lpparam.classLoader)
            log("handleLoadPackage: hookMuteDialog OK")
            loadMutedGroups(lpparam.classLoader)
            log("handleLoadPackage: loadMutedGroups OK")
            log("handleLoadPackage: ALL HOOKS INSTALLED SUCCESSFULLY")
        } catch (e: Throwable) {
            log("setup error: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
        }
    }

    // ── Hook 1: View.setTag — bell updates ───────────────────────────────

    private fun hookViewSetTag(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod("android.view.View", cl, "setTag", Any::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tag = param.args[0] as? String ?: return
                    if (!tag.contains('@')) return
                    val view = param.thisObject as? View ?: return

                    if (tag.contains("@g.us")) {
                        if (tag.contains("Key") || tag.contains("isFromMe") || tag.contains("id=")) return
                        val jid = extractJid(tag) ?: return
                        viewToJid[view.hashCode()] = jid
                        scheduleBellUpdate(view, jid)
                    } else {
                        // Clear stale group identity when a View is reused for another chat.
                        viewToJid.remove(view.hashCode())
                        val other = ANY_JID.find(tag)?.value
                        if (other != null) {
                            scheduleBellUpdate(view, other)
                        }
                    }
                }
            })
    }

    // ── Bell: display & management ────────────────────────────────────────

    private fun scheduleBellUpdate(view: View, jid: String) {
        view.post {
            try {
                ensureMutedLoaded(view.context)
                val row = findChatRow(view) ?: return@post
                // Clean up stale mapping: the row's JID may have changed due to recycling
                val prevJid = boundRows[row]
                if (prevJid != null && prevJid != jid) {
                    // Different JID on this row now — make sure old bell is gone
                    (row.findViewWithTag<View>(BELL_TAG)?.parent as? ViewGroup)?.removeView(
                        row.findViewWithTag(BELL_TAG))
                }
                boundRows[row] = jid
                applyBell(row, jid)
            } catch (_: Throwable) {}
        }
    }

    private fun findChatRow(start: View): ViewGroup? {
        val rowId = idOf(start.context, "contact_row_container", FB_ROW)
        var v: View? = start
        for (i in 0..11) {
            if (v?.id == rowId && v is ViewGroup) return v
            v = v?.parent as? View ?: break
        }
        return (start as? ViewGroup)?.findViewById<View>(rowId) as? ViewGroup
    }

    private fun applyBell(row: ViewGroup, jid: String) {
        val ctx = row.context
        val box = row.findViewById<View>(idOf(ctx, "contact_selector", FB_AVATAR_BOX)) as? FrameLayout
        val existing = (box ?: row).findViewWithTag<View>(BELL_TAG)

        if (jid !in mutedGroupsInProcess) {
            existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return
        }
        if (box == null) return
        if (existing != null) { positionBell(box, existing); return }

        val size = (20 * ctx.resources.displayMetrics.density).toInt()
        val bell = ImageView(ctx).apply {
            tag = BELL_TAG
            setImageDrawable(RedBellDrawable())
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "מושתקת לגמרי"
            isClickable = false; isFocusable = false
        }
        box.addView(bell, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        positionBell(box, bell)
    }

    private fun positionBell(box: FrameLayout, bell: View) {
        val photo = box.findViewById<View>(idOf(box.context, "contact_photo", FB_PHOTO))
        if (photo != null && photo.visibility == View.VISIBLE && photo.width > 0) {
            val off = minOf(photo.width, photo.height) / 2f * 0.7071f
            bell.translationX = if (box.layoutDirection == View.LAYOUT_DIRECTION_RTL) off else -off
            bell.translationY = off
            return
        }
        if (photo != null && photo.width == 0) {
            box.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int,
                                            ol: Int, ot: Int, or2: Int, ob: Int) {
                    box.removeOnLayoutChangeListener(this)
                    if (bell.parent === box && photo.width > 0) positionBell(box, bell)
                }
            })
            return
        }
        (bell.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.BOTTOM or Gravity.START
        bell.translationX = 0f; bell.translationY = 0f
    }

    private fun refreshBells() {
        for ((row, jid) in boundRows.entries.toList()) {
            try { (row as? ViewGroup)?.let { applyBell(it, jid) } } catch (_: Throwable) {}
        }
    }

    // ── Hook 2: PopupWindow — inject our menu item ────────────────────────

    private fun hookPopupWindow(cl: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val popup = param.thisObject as? PopupWindow ?: return
                val cv    = popup.contentView ?: return
                // Resolve JID at the exact moment the popup is shown, from the anchor view.
                val jid   = resolveJidAtShow(cv.context, param.args[0] as? View) ?: return
                if (!jid.contains("@g.us")) return
                popupJid[popup] = jid
                ensureMutedLoaded(cv.context)
                handlePopup(popup, cv, jid)
            }
        }
        listOf(
            arrayOf<Any>("showAsDropDown", View::class.java),
            arrayOf<Any>("showAsDropDown", View::class.java, Int::class.java, Int::class.java),
            arrayOf<Any>("showAsDropDown", View::class.java, Int::class.java, Int::class.java, Int::class.java),
            arrayOf<Any>("showAtLocation", View::class.java, Int::class.java, Int::class.java, Int::class.java)
        ).forEach { m ->
            try {
                val name = m[0] as String
                val types = m.drop(1).map { it as Class<*> }.toTypedArray()
                XposedHelpers.findAndHookMethod("android.widget.PopupWindow", cl, name, *types, hook)
            } catch (e: Exception) { log("skip PopupWindow.${m[0]}: ${e.message}") }
        }
    }

    /**
     * Resolves the group JID at popup-show time using three sources in order:
     * 1. Anchor view hierarchy (most reliable — the thing the user long-pressed).
     * 2. Foreground Activity intent extras (reliable when in a conversation screen).
     * 3. lastChatRowJid — fallback only, never drives a state change on its own.
     * Returns null if no JID can be found — in that case we do NOT inject our item.
     */
    private fun resolveJidAtShow(ctx: Context, anchor: View?): String? {
        // 1. Walk up anchor hierarchy
        if (anchor != null) {
            var v: View? = anchor
            for (i in 0..8) {
                val t = v?.tag as? String
                if (t != null && t.contains("@g.us") &&
                    !t.contains("Key") && !t.contains("isFromMe") && !t.contains("id=")) {
                    extractJid(t)?.let { return it }
                }
                viewToJid[v?.hashCode()]?.let { return it }
                v = v?.parent as? View ?: break
            }
        }
        // 2. Activity intent
        var c: Context? = ctx
        while (c != null) {
            val act = c as? android.app.Activity
            if (act != null && !act.javaClass.name.endsWith("HomeActivity")) {
                try {
                    act.intent?.extras?.keySet()?.forEach { k ->
                        @Suppress("DEPRECATION")
                        val v = act.intent.extras?.get(k)
                        if (v is String) extractJid(v)?.let { return it }
                    }
                } catch (_: Throwable) {}
                break
            }
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        // No reliable chat identity: fail safely instead of targeting another group.
        return null
    }

    private fun handlePopup(popup: PopupWindow, cv: View, jid: String) {
        val ctx = cv.context ?: return
        val cleanJid = extractJid(jid) ?: jid
        cv.postDelayed({
            try {
                val lv = findListView(cv)
                if (lv != null) {
                    if (!isGroupMenu(lv, ctx)) return@postDelayed
                    if (cleanJid in mutedGroupsInProcess) installMenuGuard(lv, popup)
                    injectItem(lv, ctx, cleanJid, popup)
                    return@postDelayed
                }
                val rv = findRecyclerView(cv)
                if (rv != null && isGroupMenuContainer(rv)) {
                    if (cleanJid in mutedGroupsInProcess) blockInContainer(rv, ctx, popup)
                    injectItemToParent(rv, ctx, cleanJid, popup)
                }
            } catch (_: Exception) {}
        }, 80L)
    }

    // ── Menu guard: relabel + block native mute item ──────────────────────

    private fun installMenuGuard(lv: ListView, popup: PopupWindow) {
        guardedLists[lv] = popup
        val vto = lv.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!popup.isShowing) {
                    try { if (vto.isAlive) vto.removeOnGlobalLayoutListener(this) } catch (_: Throwable) {}
                    guardedLists.remove(lv)
                    return
                }
                applyGuard(lv, popup)
            }
        }
        vto.addOnGlobalLayoutListener(listener)
        applyGuard(lv, popup)
    }

    private fun applyGuard(lv: ListView, popup: PopupWindow) {
        val adapter = lv.adapter
        val ctx = lv.context
        val first = lv.firstVisiblePosition
        for (i in 0 until lv.childCount) {
            val child = lv.getChildAt(i) ?: continue
            var kind = if (adapter != null && first + i < adapter.count)
                nativeKind(ctx, adapter.getItem(first + i)) else 0
            if (kind == 0) kind = kindOfView(child)
            if (kind == 0) continue
            relabelDeep(child)
            if (!child.hasOnClickListeners())
                child.setOnClickListener { showBlockedToast(ctx); popup.dismiss() }
        }
    }

    private fun relabelDeep(v: View) {
        when (v) {
            is TextView -> {
                val cur = v.text?.toString() ?: return
                val lo = cur.lowercase()
                if (lo.contains("לגמרי")) return
                if (lo.contains("mute notifications") || lo.contains("השתקת התראות")) {
                    if (cur != "בטל השתקת התראות") v.text = "בטל השתקת התראות"
                }
                if (lo.contains("mute notifications") || lo.contains("השתקת התראות")
                    || lo.contains("unmute notifications") || lo.contains("בטל השתקת")) {
                    val gray = Color.parseColor("#AAAAAA")
                    if (v.currentTextColor != gray) v.setTextColor(gray)
                }
            }
            is ViewGroup -> for (i in 0 until v.childCount) relabelDeep(v.getChildAt(i))
        }
    }

    private fun showBlockedToast(ctx: Context) =
        android.widget.Toast.makeText(ctx, "כבה 'השתק לגמרי' קודם 🔕", android.widget.Toast.LENGTH_LONG).show()

    private fun hookNativeItemGuard(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.widget.AbsListView", cl, "performItemClick",
                View::class.java, Int::class.java, Long::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (allowNativeClick) return
                        val lv = param.thisObject as? ListView ?: return
                        val popup = guardedLists[lv] ?: return
                        val pos = param.args[1] as? Int ?: return
                        val item = try { lv.adapter?.getItem(pos) } catch (_: Throwable) { null }
                        var kind = nativeKind(lv.context, item)
                        if (kind == 0) kind = kindOfView(param.args[0] as? View ?: return)
                        if (kind == 0) return
                        showBlockedToast(lv.context)
                        popup.dismiss()
                        param.result = true
                    }
                })
        } catch (e: Throwable) { log("performItemClick hook failed: ${e.message}") }
    }

    private fun blockInContainer(container: ViewGroup, ctx: Context, popup: PopupWindow) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (kindOfView(child) == 0) continue
            relabelDeep(child)
            child.setOnClickListener { showBlockedToast(ctx); popup.dismiss() }
        }
    }

    // ── Group-menu detection ──────────────────────────────────────────────

    /**
     * Returns true if this ListView is a per-group context menu.
     * We check item IDs first (reliable), then text content (locale-fallback).
     * We explicitly reject the main WhatsApp menu (which has "New group", "Settings" etc.)
     * and single-message menus (which have "Forward", "Copy" etc.).
     */
    private fun isGroupMenu(lv: ListView, ctx: Context): Boolean {
        val adapter = lv.adapter ?: return false
        val muteId   = idOf(ctx, "menuitem_mute",   FB_MENU_MUTE)
        val unmuteId = idOf(ctx, "menuitem_unmute", FB_MENU_UNMUTE)
        // Check adapter items by id
        for (i in 0 until adapter.count) {
            val item = try { adapter.getItem(i) as? MenuItem } catch (_: Exception) { null }
            if (item != null && (item.itemId == muteId || item.itemId == unmuteId)) return true
        }
        // Fallback: keyword in adapter item strings
        for (i in 0 until minOf(adapter.count, 16)) {
            val text = try { adapter.getItem(i)?.toString()?.lowercase() } catch (_: Exception) { null } ?: continue
            if (GROUP_MENU_KW.any { text.contains(it) }) return true
        }
        // Fallback: keyword in visible children
        for (i in 0 until lv.childCount) {
            val text = getAllText(lv.getChildAt(i) ?: continue).lowercase()
            if (GROUP_MENU_KW.any { text.contains(it) }) return true
        }
        return false
    }

    private fun isGroupMenuContainer(vg: ViewGroup): Boolean {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            if (GROUP_MENU_KW.any { getAllText(child).lowercase().contains(it) }) return true
        }
        return false
    }

    // ── Inject our menu item ──────────────────────────────────────────────

    private fun injectItem(lv: ListView, ctx: Context, jid: String, popup: PopupWindow) {
        try {
            val container = lv.parent as? ViewGroup ?: return
            val muted = jid in mutedGroupsInProcess
            // Update existing item if present
            for (i in 0 until container.childCount) {
                val c = container.getChildAt(i)
                if (c?.tag == MENU_ITEM_TAG) {
                    if (c is TextView) updateItemView(c, ctx, jid, muted, popup)
                    return
                }
            }
            // Add new item after the ListView
            val tv = buildItem(ctx, jid, muted, popup)
            val idx = (0 until container.childCount).firstOrNull { container.getChildAt(it) === lv }
                ?: (container.childCount - 1)
            container.addView(tv, idx + 1)
        } catch (_: Exception) {}
    }

    private fun injectItemToParent(anchor: View, ctx: Context, jid: String, popup: PopupWindow) {
        val parent = anchor.parent as? ViewGroup ?: return
        val muted = jid in mutedGroupsInProcess
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c?.tag == MENU_ITEM_TAG) {
                if (c is TextView) updateItemView(c, ctx, jid, muted, popup)
                return
            }
        }
        val tv = buildItem(ctx, jid, muted, popup)
        val idx = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === anchor }
            ?: parent.childCount - 1
        parent.addView(tv, idx + 1)
    }

    private fun updateItemView(tv: TextView, ctx: Context, jid: String, muted: Boolean, popup: PopupWindow) {
        val label = if (muted) "בטל השתקה לגמרי" else "השתק לגמרי"
        if (tv.text?.toString() != label) tv.text = label
        val color = if (muted) COLOR_RED else Color.parseColor("#212121")
        if (tv.currentTextColor != color) tv.setTextColor(color)
        tv.setOnClickListener { onToggle(ctx, jid, popup) }
    }

    private fun buildItem(ctx: Context, jid: String, muted: Boolean, popup: PopupWindow): TextView {
        val label = if (muted) "בטל השתקה לגמרי" else "השתק לגמרי"
        return TextView(ctx).apply {
            text = label
            tag  = MENU_ITEM_TAG
            textSize = 16f
            setTextColor(if (muted) COLOR_RED else Color.parseColor("#212121"))
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(56, 32, 56, 32)
            try {
                @Suppress("DEPRECATION")
                context.getDrawable(android.R.drawable.ic_lock_silent_mode)?.also {
                    @Suppress("DEPRECATION")
                    it.setColorFilter(if (muted) COLOR_RED else Color.parseColor("#757575"),
                        PorterDuff.Mode.SRC_IN)
                    setCompoundDrawablesWithIntrinsicBounds(null, null, it, null)
                    compoundDrawablePadding = 24
                }
            } catch (_: Exception) {}
            setOnClickListener { onToggle(ctx, jid, popup) }
        }
    }

    // ── Toggle: enable / disable complete mute ────────────────────────────

    private fun onToggle(ctx: Context, jid: String, popup: PopupWindow) {
        if (opInProgress) { toast(ctx, "הפעולה עדיין מתבצעת"); popup.dismiss(); return }
        val lv = popup.contentView?.let { findListView(it) }
        val native = lv?.let { findNativeMute(it) }
        if (lv == null || native == null) {
            failToggle(ctx, jid !in mutedGroupsInProcess); popup.dismiss(); return
        }
        val enabling = jid !in mutedGroupsInProcess
        // Read native state from the menu item itself (id-based), not from the relabelled text
        val nativeMuted: Boolean = run {
            val fromController = nativeMuteState(activityOf(ctx))
            // If the controller is unavailable, fall back to what the menu item id says
            fromController ?: (native.second == 2)
        }

        if (enabling) {
            if (nativeMuted) {
                // Already natively muted — just record our state, no toggle
                commitState(ctx, jid, true)
                popup.dismiss()
            } else {
                startNativeMute(ctx, jid, lv, native.first)
                popup.dismiss()
            }
        } else {
            if (!nativeMuted) {
                // Not natively muted (shouldn't happen if we set it, but handle gracefully)
                commitState(ctx, jid, false)
                popup.dismiss()
            } else {
                if (!clickNative(lv, native.first)) {
                    failToggle(ctx, false); popup.dismiss(); return
                }
                opInProgress = true
                verifyNative(activityOf(ctx), false, System.currentTimeMillis()) { ok ->
                    opInProgress = false
                    if (ok == true) {
                        commitState(ctx, jid, false)
                    } else {
                        failToggle(ctx, false)
                    }
                }
                popup.dismiss()
            }
        }
    }

    private fun commitState(ctx: Context, jid: String, muted: Boolean) {
        if (muted) mutedGroupsInProcess.add(jid) else mutedGroupsInProcess.remove(jid)
        saveMutedGroups(ctx)
        sendMuteUpdate(ctx, jid, muted)
        refreshBells()
        toast(ctx, if (muted) "קבוצה הושתקה לגמרי 🔕" else "ההשתקה המלאה בוטלה 🔔")
    }

    private fun failToggle(ctx: Context, enabling: Boolean) =
        toast(ctx, if (enabling) "ההשתקה נכשלה, המצב לא שונה" else "הביטול נכשל, ההשתקה נשארה פעילה")

    // ── Native mute state ─────────────────────────────────────────────────

    private fun hookMenuController(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("X.8e7", cl, "A0I", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    menuControllerRef = java.lang.ref.WeakReference(param.thisObject)
                }
            })
        } catch (e: Throwable) { log("menu controller hook failed: ${e.message}") }
    }

    /**
     * Reads the NATIVE mute state from WhatsApp's own menu controller.
     * Returns true=muted, false=not muted, null=cannot determine.
     * The controller is validated against the foreground activity to ensure
     * it belongs to the current conversation — not a stale reference.
     */
    private fun nativeMuteState(activity: android.app.Activity?): Boolean? {
        val c = menuControllerRef?.get() ?: return null
        return try {
            if (activity != null) {
                val owner = try { XposedHelpers.getObjectField(c, "A02") } catch (_: Throwable) { null }
                if (owner !== activity && owner?.javaClass != activity.javaClass) return null
            }
            when (XposedHelpers.callMethod(c, "A0I") as? Int) {
                STR_UNMUTE -> true
                STR_MUTE   -> false
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    private fun verifyNative(activity: android.app.Activity?, expected: Boolean,
                              start: Long, cb: (Boolean?) -> Unit) {
        val s = nativeMuteState(activity)
        when {
            s == null -> cb(null)
            s == expected -> cb(true)
            System.currentTimeMillis() - start > 2800L -> cb(false)
            else -> mainHandler.postDelayed({ verifyNative(activity, expected, start, cb) }, 200L)
        }
    }

    // ── Native mute click helpers ─────────────────────────────────────────

    /** 0=not a mute item, 1="Mute notifications", 2="Unmute notifications" */
    private fun nativeKind(ctx: Context, item: Any?): Int {
        if (item == null) return 0
        val mi    = item as? MenuItem
        val title = mi?.title?.toString() ?: item.toString()
        if (title.lowercase().contains("לגמרי")) return 0
        val id = mi?.itemId
        if (id == idOf(ctx, "menuitem_unmute", FB_MENU_UNMUTE) || title == resStr(ctx, STR_UNMUTE)) return 2
        if (id == idOf(ctx, "menuitem_mute",   FB_MENU_MUTE)   || title == resStr(ctx, STR_MUTE))   return 1
        return kindOfText(title)
    }

    private fun kindOfText(text: String): Int {
        val lo = text.lowercase()
        if (lo.isEmpty() || lo.contains("לגמרי")) return 0
        if (lo.contains("unmute") || (lo.contains("השתק") && (lo.contains("ביטול") || lo.contains("בטל")))) return 2
        if (lo.contains("mute notifications") || lo.contains("השתקת התראות")) return 1
        return 0
    }

    private fun kindOfView(v: View): Int = kindOfText(getAllText(v))

    private fun findNativeMute(lv: ListView): Pair<Int, Int>? {
        val adapter = lv.adapter ?: return null
        val first = lv.firstVisiblePosition
        for (i in 0 until adapter.count) {
            var k = nativeKind(lv.context, adapter.getItem(i))
            if (k == 0) k = kindOfView(lv.getChildAt(i - first) ?: continue)
            if (k != 0) return Pair(i, k)
        }
        return null
    }

    private fun clickNative(lv: ListView, pos: Int): Boolean {
        val adapter = lv.adapter ?: return false
        allowNativeClick = true
        return try {
            lv.performItemClick(lv.getChildAt(pos - lv.firstVisiblePosition),
                pos, adapter.getItemId(pos))
        } catch (e: Throwable) { false } finally { allowNativeClick = false }
    }

    // ── Native mute dialog automation ────────────────────────────────────

    private fun startNativeMute(ctx: Context, jid: String, lv: ListView, pos: Int) {
        opInProgress = true
        val p = PendingMute(ctx, jid, System.currentTimeMillis() + 9000L)
        pendingMute = p
        mainHandler.postDelayed({ finishMute(p, false) }, 9000L)
        if (!clickNative(lv, pos)) finishMute(p, false)
    }

    private fun finishMute(p: PendingMute, ok: Boolean) {
        if (p.finished) return
        p.finished = true
        opInProgress = false
        if (pendingMute === p) pendingMute = null
        if (ok) commitState(p.ctx, p.jid, true)
        else failToggle(p.ctx, true)
    }

    private fun hookMuteDialog(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Dialog", cl, "show",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val p = pendingMute ?: return
                        // Only intercept if a mute operation is pending AND not yet driving
                        if (p.driving || p.finished || System.currentTimeMillis() > p.deadline) return
                        val dialog = param.thisObject as? android.app.Dialog ?: return
                        val decor  = dialog.window?.decorView ?: return
                        tryDriveDialog(p, dialog, decor, 0)
                    }
                })
        } catch (e: Throwable) { log("Dialog.show hook failed: ${e.message}") }
    }

    private fun tryDriveDialog(p: PendingMute, dialog: android.app.Dialog, decor: View, attempt: Int) {
        if (p.driving || p.finished) return
        val ctx = decor.context

        // Variant A: dedicated ids
        val alwaysById = decor.findViewById<View>(idOf(ctx, "mute_duration_always", FB_MUTE_ALWAYS))
        val doneById   = decor.findViewById<View>(idOf(ctx, "mute_done_button",    FB_MUTE_DONE))
        if (alwaysById != null && doneById != null) {
            p.driving = true
            hideDialog(decor, dialog)
            decor.post { driveWithButton(p, dialog, decor, alwaysById) { doneById.performClick() } }
            return
        }

        // Variant B: AlertDialog with RadioGroup + positive button
        val buttons = mutableListOf<android.widget.CompoundButton>()
        collectButtons(decor, buttons)
        val radios   = buttons.filterIsInstance<android.widget.RadioButton>().ifEmpty { buttons }
        val positive = try { XposedHelpers.callMethod(dialog, "getButton", -1) as? View } catch (_: Throwable) { null }
        if (radios.size >= 2 && positive != null) {
            val alwaysText = resStr(ctx, STR_ALWAYS)
            val target = radios.firstOrNull { alwaysText != null && it.text?.toString() == alwaysText }
                ?: radios.last()
            p.driving = true
            hideDialog(decor, dialog)
            decor.post { driveWithButton(p, dialog, decor, target) { positive.performClick() } }
            return
        }

        // Not the mute dialog yet — retry a few times (dialog may still be inflating)
        if (attempt < 5)
            decor.postDelayed({ tryDriveDialog(p, dialog, decor, attempt + 1) }, 100L)
        // If we exhaust retries we leave p.driving=false; the timeout handler will call finishMute
    }

    private fun hideDialog(decor: View, dialog: android.app.Dialog) {
        decor.alpha = 0f
        dialog.window?.setDimAmount(0f)
    }

    private fun driveWithButton(p: PendingMute, dialog: android.app.Dialog, decor: View,
                                 always: View, confirm: () -> Unit) {
        always.performClick()
        (always as? android.widget.CompoundButton)?.let { if (!it.isChecked) it.isChecked = true }
        val cb = always as? android.widget.CompoundButton
        if (cb != null && !cb.isChecked) {
            decor.alpha = 1f; finishMute(p, false); return
        }
        decor.postDelayed({
            confirm()
            decor.postDelayed({
                if (dialog.isShowing) {
                    decor.alpha = 1f; finishMute(p, false)
                } else {
                    verifyNative(activityOf(p.ctx), true, System.currentTimeMillis()) { ok ->
                        finishMute(p, ok == true)
                    }
                }
            }, 500L)
        }, 200L)
    }

    private fun collectButtons(v: View, out: MutableList<android.widget.CompoundButton>) {
        if (v is android.widget.CompoundButton) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectButtons(v.getChildAt(i), out)
    }

    // ── Hook 5: Block call activities ────────────────────────────────────

    private fun hookStartActivity(cl: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args.getOrNull(0) as? android.content.Intent ?: return
                    val component = intent.component ?: return
                    val className = component.className
                    
                    // Block VoipActivityV2 (incoming call screen) for muted groups
                    if (className.contains("VoipActivityV2") || className.contains("CallActivity")) {
                        val jidString = intent.getStringExtra("jid") ?: return
                        val jid = extractJid(jidString) ?: return
                        
                        ensureMutedLoaded(null)
                        if (jid in mutedGroupsInProcess) {
                            param.result = null
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        
        XposedHelpers.findAndHookMethod("android.app.Activity", cl, "startActivity",
            android.content.Intent::class.java, hook)
        XposedHelpers.findAndHookMethod("android.content.Context", cl, "startActivity",
            android.content.Intent::class.java, hook)
    }

    // ── Hook 6: NotificationManager.notify ───────────────────────────────

    private fun hookNotificationManager(@Suppress("UNUSED_PARAMETER") cl: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val n = param.args.lastOrNull() as? Notification ?: return
                    val extras = n.extras ?: return
                    
                    ensureMutedLoaded(null)
                    if (mutedGroupsInProcess.isEmpty()) return
                    
                    // Get last_row_id for both messages and calls
                    val lastRowId = extras.getLong("last_row_id", -1L)
                    if (lastRowId <= 0) return
                    
                    val ctx = currentApp() ?: return
                    val jid = getJidFromRowId(ctx, lastRowId)
                    
                    // Block if muted group (handles messages, calls, and any notification with JID)
                    if (jid != null && jid in mutedGroupsInProcess) {
                        param.result = null
                    }
                } catch (_: Throwable) {}
            }
        }
        
        XposedHelpers.findAndHookMethod(NotificationManager::class.java,
            "notify", String::class.java, Int::class.java, Notification::class.java, hook)
        XposedHelpers.findAndHookMethod(NotificationManager::class.java,
            "notify", Int::class.java, Notification::class.java, hook)
    }
    
    private fun getJidFromRowId(ctx: Context, rowId: Long): String? {
        return try {
            val dbPath = ctx.getDatabasePath("msgstore.db")
            if (!dbPath.exists()) return null
            
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            
            try {
                // Query: message._id -> message.chat_row_id -> chat.jid_row_id -> jid.raw_string
                val cursor = db.rawQuery(
                    """
                    SELECT j.raw_string 
                    FROM message m
                    JOIN chat c ON m.chat_row_id = c._id
                    JOIN jid j ON c.jid_row_id = j._id
                    WHERE m._id = ?
                    """.trimIndent(),
                    arrayOf(rowId.toString())
                )
                
                val jidStr = if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
                
                cursor.close()
                jidStr?.let { extractJid(it) }
            } finally {
                db.close()
            }
        } catch (e: Throwable) {
            log("getJidFromRowId error: ${e.message}")
            null
        }
    }

    private fun extractJidFromNotification(n: Notification): String? {
        // Removed - JID extraction moved to hookNotificationManager
        return null
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun loadMutedGroups(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    ensureMutedLoaded(param.thisObject as? Context)
                }
            })
    }

    private fun saveMutedGroups(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putStringSet(PREFS_KEY, mutedGroupsInProcess.toSet()).apply()
        } catch (e: Exception) { log("save prefs error: ${e.message}") }
    }

    private fun sendMuteUpdate(ctx: Context, jid: String, muted: Boolean) {
        try {
            ctx.sendBroadcast(Intent(MuteStore.ACTION_MUTE_UPDATE).apply {
                setPackage("com.eliav.wacompletemute")
                putExtra(MuteStore.EXTRA_GROUP_JID, jid)
                putExtra(MuteStore.EXTRA_IS_MUTED, muted)
            })
        } catch (e: Exception) { log("IPC error: ${e.message}") }
    }

    // ── View utils ────────────────────────────────────────────────────────

    private fun findListView(root: View): ListView? {
        if (root is ListView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount)
            findListView(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun findRecyclerView(root: View): ViewGroup? {
        if (root.javaClass.name.contains("RecyclerView")) return root as? ViewGroup
        if (root is ViewGroup) for (i in 0 until root.childCount)
            findRecyclerView(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun getAllText(v: View): String = when (v) {
        is TextView  -> v.text?.toString() ?: ""
        is ViewGroup -> (0 until v.childCount).joinToString(" ") { getAllText(v.getChildAt(it)) }
        else         -> ""
    }
}
