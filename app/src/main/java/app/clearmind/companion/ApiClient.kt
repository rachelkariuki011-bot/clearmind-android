package app.clearmind.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to ClearMind's /api/public/v1 endpoints. The device token is stored privately on the phone. */
class ApiClient(private val ctx: Context) {
    private val base = BuildConfig.API_BASE + "/api/public/v1"
    private val prefs get() = ctx.getSharedPreferences("clearmind", Context.MODE_PRIVATE)
    val token: String? get() = prefs.getString("deviceToken", null)
    val isPaired get() = token != null

    suspend fun pair(tokenOrCode: String, device: String): JSONObject {
        val body = JSONObject().put("device", device)
        if (tokenOrCode.length > 20) body.put("token", tokenOrCode) else body.put("code", tokenOrCode)
        val res = call("POST", "/pair", body, auth = false)
        prefs.edit().putString("deviceToken", res.getString("deviceToken")).putString("childId", res.getString("childId")).apply()
        return res
    }

    suspend fun syncRules(): JSONObject = call("GET", "/sync-rules", null).also {
        prefs.edit().putString("rules", it.toString()).apply() // offline cache
    }
    fun cachedRules(): JSONObject? = prefs.getString("rules", null)?.let { JSONObject(it) }
    suspend fun learningSession(): String = call("GET", "/pair", null).getString("tokenHash")

    suspend fun reportIncident(category: String, domain: String, visitedAtIso: String, dwellSeconds: Int?, exitMeasured: Boolean) {
        val body = JSONObject().put("incidents", JSONArray().put(
            JSONObject().put("category", category).put("domain", domain).put("visitedAt", visitedAtIso)
                .put("dwellSeconds", dwellSeconds ?: JSONObject.NULL).put("exitMeasured", exitMeasured)))
        sendOrQueue("/report-incident", body)
    }

    suspend fun heartbeat(battery: Int, connection: String, a11y: Boolean, vpn: Boolean, admin: Boolean) {
        val body = JSONObject().put("battery", battery).put("connection", connection)
            .put("tamper", JSONObject().put("accessibility", a11y).put("vpn", vpn).put("deviceAdmin", admin))
        sendOrQueue("/heartbeat", body)
    }

    /** Sends now if online; otherwise stores it in the outbox to send when the phone is back online. */
    private suspend fun sendOrQueue(path: String, body: JSONObject) {
        try {
            call("POST", path, body)
        } catch (e: Exception) {
            val arr = JSONArray(prefs.getString("outbox", "[]"))
            arr.put(JSONObject().put("path", path).put("body", body.toString()))
            // Keep the outbox bounded so a long offline period can't grow it forever.
            while (arr.length() > 200) arr.remove(0)
            prefs.edit().putString("outbox", arr.toString()).apply()
        }
    }

    /** Replays anything queued while offline. Called by the watchdog after a successful rule sync. */
    suspend fun flushOutbox() {
        val arr = JSONArray(prefs.getString("outbox", "[]"))
        if (arr.length() == 0) return
        val remaining = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            try {
                call("POST", item.getString("path"), JSONObject(item.getString("body")))
            } catch (e: Exception) {
                remaining.put(item)
            }
        }
        prefs.edit().putString("outbox", remaining.toString()).apply()
    }

    fun pendingOutboxCount(): Int = JSONArray(prefs.getString("outbox", "[]")).length()

    private suspend fun call(method: String, path: String, body: JSONObject?, auth: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        if (auth) c.setRequestProperty("Authorization", "Bearer ${token ?: error("Not paired")}")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) throw RuntimeException("[$code] $text")
        JSONObject(text)
    }
}
