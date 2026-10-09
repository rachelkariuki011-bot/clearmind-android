package app.clearmind.companion

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Watches which app is in front. During a study block, distracting apps are sent to the Toll Booth. */
class FocusLockService : AccessibilityService() {
    private var tollPassUntil = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val rules = ApiClient(this).cachedRules() ?: return
        val mode = activeMode(rules) ?: return
        if (pkg == packageName || pkg == "com.android.dialer" || pkg == "com.google.android.dialer" || pkg == "com.android.phone" || pkg == "com.android.systemui" || pkg == "com.android.settings") return
        val blocked = rules.optJSONArray("blockedApps") ?: return
        val isBlocked = (0 until blocked.length()).any { blocked.getString(it) == pkg }
        val allowed = rules.optJSONArray("allowedApps")
        val homeFocus = mode != "hard_lock" && rules.optBoolean("ultraFocus")
        val isAllowed = allowed != null && (0 until allowed.length()).any { allowed.getString(it) == pkg }
        if (!isBlocked && !(homeFocus && !isAllowed)) return
        if (homeFocus && !isAllowed) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.API_BASE + "/focus-lock")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (mode == "toll_booth" && System.currentTimeMillis() < tollPassUntil) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.API_BASE + if (mode == "hard_lock") "/focus-lock" else "/tollbooth"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Returns "hard_lock", "toll_booth" or null based on synced schedules (Nairobi time). */
    private fun activeMode(rules: org.json.JSONObject): String? {
        if (rules.optBoolean("classLocked")) return "hard_lock"
        val now = ZonedDateTime.now(ZoneId.of(rules.optString("timezone", "Africa/Nairobi")))
        val schedules = rules.optJSONArray("schedules") ?: return null
        for (i in 0 until schedules.length()) {
            val s = schedules.getJSONObject(i)
            val days = s.getJSONArray("days"); val dow = now.dayOfWeek.value % 7
            if ((0 until days.length()).none { days.getInt(it) == dow }) continue
            val t = now.toLocalTime()
            if (t >= LocalTime.parse(s.getString("start")) && t < LocalTime.parse(s.getString("end"))) return s.getString("mode")
        }
        return null
    }

    override fun onInterrupt() {}
}
