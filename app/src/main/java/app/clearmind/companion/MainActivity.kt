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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/** Setup screen: pair → grant permissions → battery exemption. */
class MainActivity : AppCompatActivity() {
    private lateinit var api: ApiClient
    private lateinit var status: TextView

    private val scan = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { pair(it.substringAfterLast("/pair/")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f }
        val code = EditText(this).apply { hint = "CLM-000000" }
        fun btn(label: String, f: () -> Unit) = Button(this).apply { text = label; setOnClickListener { f() } }
        root.addView(status)
        root.addView(btn("1. Scan parent's pairing QR") { scan.launch(ScanOptions().setPrompt("Scan the QR on your parent's phone")) })
        root.addView(code)
        root.addView(btn("…or pair with code") { pair(code.text.toString()) })
        root.addView(btn("2. Turn on Focus Lock (Accessibility)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(btn("3. Turn on Safe Browsing (VPN)") {
            VpnService.prepare(this)?.let { startActivityForResult(it, 7) } ?: startVpn()
        })
        root.addView(btn("4. Enable Device Admin (anti-uninstall)") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, ClearMindAdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Stops ClearMind from being removed without your parent."))
        })
        root.addView(btn("5. Keep running in background") { requestBatteryExemption() })
        setContentView(root)
        intent?.data?.lastPathSegment?.let { if (!api.isPaired) pair(it) }
        refresh()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7 && res == RESULT_OK) startVpn()
    }

    private fun startVpn() = startService(Intent(this, DwellTrackingVpnService::class.java))

    private fun pair(tokenOrCode: String) = lifecycleScope.launch {
        status.text = "Pairing…"
        runCatching { api.pair(tokenOrCode.trim(), "${Build.MANUFACTURER} ${Build.MODEL}"); api.syncRules() }
            .onSuccess { WatchdogReceiver.schedule(this@MainActivity); refresh() }
            .onFailure { status.text = "Pairing failed: ${it.message}" }
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
        status.text = if (api.isPaired) "✅ Paired. Finish steps 2–5 so ClearMind can protect study time." else "Welcome! Start by pairing with your parent's phone."
    }
}
