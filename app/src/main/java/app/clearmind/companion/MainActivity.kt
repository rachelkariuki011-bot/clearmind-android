package app.clearmind.companion

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.View
import android.Manifest
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.ZoneId

/** Setup screen: pair → grant permissions → battery exemption. */
class MainActivity : AppCompatActivity() {
    private lateinit var api: ApiClient
    private lateinit var statusPill: TextView
    private lateinit var scheduleList: TextView
    private var openingWorkspace = false
    private var pairing = false
    private var inspectProtection = false

    private val scan = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { pair(PairingInput.parse(it) ?: "") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inspectProtection = savedInstanceState?.getBoolean("inspectProtection") ?: intent.getBooleanExtra("inspectProtection", false)
        setContentView(R.layout.activity_main)
        api = ApiClient(this)
        statusPill = findViewById(R.id.statusPill)
        scheduleList = findViewById(R.id.scheduleList)
        findViewById<Button>(R.id.btnWorkspace).setOnClickListener { openWorkspace() }
        val code = findViewById<EditText>(R.id.etCode)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scan.launch(ScanOptions().setPrompt("Scan the QR on your parent's phone"))
        }
        findViewById<Button>(R.id.btnPair).setOnClickListener { pair(code.text.toString()) }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnVpn).setOnClickListener {
            VpnService.prepare(this)?.let { startActivityForResult(it, 7) } ?: startVpn()
        }
        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, ClearMindAdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Stops ClearMind from being removed without your parent."))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }

        intent?.data?.toString()?.let { if (!api.isPaired) PairingInput.parse(it)?.let { code -> pair(code) } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (api.isPaired && !inspectProtection) openWorkspace()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("inspectProtection", inspectProtection)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7 && res == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
        ContextCompat.startForegroundService(this, Intent(this, DwellTrackingVpnService::class.java))
    }

    private fun openWorkspace() {
        if (openingWorkspace) return
        openingWorkspace = true
        startActivity(Intent(this, StudentWorkspaceActivity::class.java))
        finish()
    }

    private fun pair(tokenOrCode: String) = lifecycleScope.launch {
        if (pairing) return@launch
        val input = PairingInput.parse(tokenOrCode)
        if (input == null) { statusPill.text = "Enter your parent's six-digit pairing code."; return@launch }
        pairing = true
        findViewById<Button>(R.id.btnPair).isEnabled = false
        statusPill.text = "Pairing…"
        runCatching { api.pair(input, "${Build.MANUFACTURER} ${Build.MODEL}") }
            .onSuccess { runCatching { api.syncRules() }; WatchdogReceiver.schedule(this@MainActivity); openWorkspace() }
            .onFailure { statusPill.text = "Could not connect. Check your connection and ask your parent for a fresh code." }
        pairing = false
        findViewById<Button>(R.id.btnPair).isEnabled = true
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(android.net.Uri.parse("package:$packageName")))
        }
        // OEM auto-start screens (Tecno/Infinix/iTel = Transsion, Xiaomi, Samsung). Falls through silently if absent.
        listOf(
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ).firstOrNull { runCatching { startActivity(Intent().setComponent(it)); true }.getOrDefault(false) }
    }

    private fun refresh() {
        statusPill.text = if (api.isPaired)
            "Connected · Student phone"
        else
            "Not connected yet — pair with your parent's phone"

        val a11yOn = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
        val vpnOn = DwellTrackingVpnService.running
        val adminOn = getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(this, ClearMindAdminReceiver::class.java))
        val batteryOn = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        fun step(id: Int, done: Boolean, label: String) {
            findViewById<TextView>(id).apply {
                text = if (done) "✓ $label" else "Not yet — $label"
                setTextColor(getColor(if (done) R.color.ok_green else R.color.ink_soft))
            }
        }
        step(R.id.stepA11y, a11yOn, "Focus Lock is on")
        step(R.id.stepVpn, vpnOn, "Safe Browsing is on")
        step(R.id.stepAdmin, adminOn, "Tamper protection is on")
        step(R.id.stepBattery, batteryOn, "Background running is allowed")
        findViewById<View>(R.id.pairingCard).visibility = if (api.isPaired) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnWorkspace).visibility = if (api.isPaired) View.VISIBLE else View.GONE

        renderSchedule()
    }

    /** Shows today's focus blocks from the last synced rules — works fully offline. */
    private fun renderSchedule() {
        val rules = api.cachedRules()
        if (rules == null) {
            scheduleList.text = "Focus times appear here after pairing."
            return
        }
        val now = ZonedDateTime.now(ZoneId.of(rules.optString("timezone", "Africa/Nairobi")))
        val dow = now.dayOfWeek.value % 7
        val schedules = rules.optJSONArray("schedules")
        val lines = mutableListOf<String>()
        if (rules.optBoolean("classLocked")) lines += "School focus lock is active now"
        if (schedules != null) {
            for (i in 0 until schedules.length()) {
                val s = schedules.getJSONObject(i)
                val days = s.getJSONArray("days")
                if ((0 until days.length()).none { days.getInt(it) == dow }) continue
                val mode = if (s.getString("mode") == "hard_lock") "Full focus" else "Focus with quiz breaks"
                lines += "${s.getString("start")} – ${s.getString("end")}  ·  $mode"
            }
        }
        scheduleList.text = if (lines.isEmpty()) "No focus blocks today. Free time!" else lines.joinToString("\n")
    }
}
