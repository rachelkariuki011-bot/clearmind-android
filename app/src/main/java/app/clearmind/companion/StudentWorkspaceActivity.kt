package app.clearmind.companion

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Origin-restricted WebView: companion credentials never enter JavaScript or a URL. */
class StudentWorkspaceActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var api: ApiClient
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private lateinit var retry: Button
    private var sheet: Dialog? = null
    private var connecting = false
    private var hasLoaded = false
    private val base get() = BuildConfig.API_BASE.trimEnd('/')

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient(this)
        if (!api.isPaired) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        setContentView(R.layout.activity_workspace)
        web = findViewById(R.id.workspaceWeb)
        progress = findViewById(R.id.workspaceProgress)
        error = findViewById(R.id.workspaceError)
        retry = findViewById(R.id.btnRetry)
        findViewById<ImageButton>(R.id.btnShield).setOnClickListener { showProtection() }
        retry.setOnClickListener { connect() }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.addJavascriptInterface(StudyBridge(), "ClearMindStudy")
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value
                progress.visibility = if (value < 100) View.VISIBLE else View.GONE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowed(request.url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame && !allowed(request.url)) return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream("Navigation unavailable".toByteArray()))
                return null
            }
            override fun onPageFinished(view: WebView, url: String) {
                hasLoaded = true
                if (Uri.parse(url).path == "/auth") { showError("Your student session needs reconnecting."); return }
                view.clearHistory() // removes the one-time handoff URL from back navigation
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                if (request.isForMainFrame) showError("Learning needs a connection. Your saved focus rules still run offline.")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) showError("The learning workspace is temporarily unavailable.")
            }
            // Never override SSL errors: WebView's default cancels insecure requests.
        }
        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) hasLoaded = true else connect()
        startProtection()
    }

    private fun allowed(uri: Uri): Boolean {
        val origin = Uri.parse(base)
        return uri.scheme == "https" && uri.host == origin.host && uri.port == origin.port && uri.userInfo == null &&
            uri.path in setOf("/student-mobile", "/companion-connect", "/auth")
    }

    private fun connect() = lifecycleScope.launch {
        if (connecting) return@launch
        connecting = true; retry.visibility = View.GONE; error.visibility = View.GONE
        runCatching { api.learningSession() }
            .onSuccess { hash -> web.loadUrl("$base/companion-connect#token_hash=${Uri.encode(hash)}") }
            .onFailure { showError("Could not reconnect. Check your connection and try again.") }
        connecting = false
    }

    private fun showError(message: String) { error.text = message; error.visibility = View.VISIBLE; retry.visibility = View.VISIBLE }

    private fun startProtection() {
        WatchdogReceiver.schedule(this)
        if (VpnService.prepare(this) == null) runCatching { ContextCompat.startForegroundService(this, Intent(this, DwellTrackingVpnService::class.java)) }
        lifecycleScope.launch { runCatching { api.syncRules(); api.flushOutbox() } }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) { web.onResume(); startProtection(); if (sheet?.isShowing == true) showProtection() }
    }
    override fun onPause() { if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { if (::web.isInitialized && hasLoaded) web.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        sheet?.dismiss()
        if (::web.isInitialized) { web.removeJavascriptInterface("ClearMindStudy"); web.destroy() }
        // Do not stop VPN, accessibility or watchdog when leaving learning.
        super.onDestroy()
    }

    inner class StudyBridge {
        @JavascriptInterface fun schedule(): String {
            val cached = api.cachedRules() ?: return "{}"
            // Only student-safe schedule fields; no device token, allowlist or parent settings.
            return org.json.JSONObject().put("schedules", cached.optJSONArray("schedules") ?: org.json.JSONArray())
                .put("timezone", cached.optString("timezone", "Africa/Nairobi"))
                .put("classLocked", cached.optBoolean("classLocked")).toString()
        }
    }

    private fun showProtection() {
        sheet?.dismiss()
        val dialog = Dialog(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(ContextCompat.getColor(context, R.color.off_white)) }
        fun label(text: String, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = if (bold) 18f else 14f; setTextColor(ContextCompat.getColor(context, R.color.ink))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(12, 12, 12, 12)
        }
        body.addView(label("Protection status", true))
        val a11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(ComponentName(this, FocusLockService::class.java).flattenToString()) == true
        val admin = getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(this, ClearMindAdminReceiver::class.java))
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        for ((name, ready) in listOf("Pairing" to api.isPaired, "Focus Lock" to a11y, "Safe Browsing" to DwellTrackingVpnService.running, "Device Admin" to admin, "Background running" to battery)) {
            body.addView(label("$name · ${if (ready) "Active" else "Needs attention"}").apply { setBackgroundResource(R.drawable.bg_card) })
        }
        body.addView(Button(this).apply { text = "Review permissions"; setOnClickListener { dialog.dismiss(); startActivity(Intent(this@StudentWorkspaceActivity, MainActivity::class.java).putExtra("inspectProtection", true)) } })
        body.addView(Button(this).apply { text = "Return to learning"; setOnClickListener { dialog.dismiss() } })
        dialog.setContentView(body)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM); setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT) }
        sheet = dialog; dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}