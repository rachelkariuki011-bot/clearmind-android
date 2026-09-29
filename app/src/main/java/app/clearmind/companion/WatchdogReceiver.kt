package app.clearmind.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** 5-minute watchdog: re-syncs rules, sends heartbeat, restarts the VPN. Missed heartbeats show as a possible bypass. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        schedule(ctx)
        val pending = goAsync()
        Thread { runCatching { tick(ctx) }; pending.finish() }.start()
    }

    companion object {
        private const val INTERVAL = 5 * 60_000L

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WatchdogReceiver::class.java).setAction("app.clearmind.WATCHDOG"), PendingIntent.FLAG_IMMUTABLE)
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL, pi) }
            // 15-minute WorkManager fallback for OEMs that kill alarms.
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build())
        }

        fun tick(ctx: Context) = runBlocking {
            val api = ApiClient(ctx); if (!api.isPaired) return@runBlocking
            runCatching { api.syncRules() }
            val battery = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val conn = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "unknown"
            }
            val a11y = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(ctx.packageName) == true
            val vpnOk = VpnService.prepare(ctx) == null
            if (vpnOk) ctx.startForegroundService(Intent(ctx, DwellTrackingVpnService::class.java))
            val admin = ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(ctx, ClearMindAdminReceiver::class.java))
            runCatching { api.heartbeat(battery, conn, a11y, vpnOk, admin) }
        }
    }
}

class WatchdogWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result { WatchdogReceiver.tick(applicationContext); return Result.success() }
}

class ClearMindAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(ctx: Context, intent: Intent): CharSequence =
        "Turning this off will alert your parent immediately."
}
