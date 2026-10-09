package app.clearmind.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Build
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant

/**
 * Local DNS-only VPN. Only DNS packets (to 10.111.0.1) enter the tunnel, so normal traffic is untouched.
 * Queries for blocked domains get NXDOMAIN; allowed queries are forwarded to a family-safe resolver.
 * Dwell = time from first blocked lookup until lookups for that domain stop (or screen off → exitMeasured=false).
 */
class DwellTrackingVpnService : VpnService() {
    companion object { @Volatile var running = false; private set }
    private var tun: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val open = mutableMapOf<String, Pair<String, Long>>() // domain -> (category, firstSeenMs)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, notification())
        if (tun == null) {
            tun = Builder().setSession("ClearMind Safe Browsing")
                .addAddress("10.111.0.2", 32).addDnsServer("10.111.0.1").addRoute("10.111.0.1", 32)
                .establish()
            running = tun != null
            if (tun == null) { stopSelf(); return START_NOT_STICKY }
            scope.launch { loop() }
            scope.launch { flushDwell() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val descriptor = tun ?: return
        val input = FileInputStream(descriptor.fileDescriptor); val output = FileOutputStream(descriptor.fileDescriptor)
        val buf = ByteArray(32767)
        while (currentCoroutineContext().isActive) {
            val n = input.read(buf); if (n <= 0) continue
            val domain = DnsPacket.queryName(buf, n) ?: continue
            val category = categoryFor(domain)
            if (category != null) {
                open.putIfAbsent(domain, category to System.currentTimeMillis())
                output.write(DnsPacket.nxdomain(buf, n))
            } else {
                DnsPacket.forward(buf, n, "1.1.1.3", this@DwellTrackingVpnService)?.let { output.write(it) }
            }
        }
    }

    /** Reports visits whose lookups stopped for 60s. */
    private suspend fun flushDwell() {
        val api = ApiClient(this)
        while (currentCoroutineContext().isActive) {
            delay(30_000)
            val now = System.currentTimeMillis()
            open.entries.filter { now - it.value.second > 60_000 }.forEach { (domain, v) ->
                open.remove(domain)
                runCatching { api.reportIncident(v.first, domain, Instant.ofEpochMilli(v.second).toString(), ((now - v.second) / 1000).toInt() - 60, true) }
            }
        }
    }

    private fun categoryFor(domain: String): String? {
        val byCat = ApiClient(this).cachedRules()?.optJSONObject("safeBrowsing")?.optJSONObject("domainsByCategory") ?: return null
        for (cat in byCat.keys()) {
            val list = byCat.getJSONArray(cat)
            if ((0 until list.length()).any { domain == list.getString(it) || domain.endsWith("." + list.getString(it)) }) return cat
        }
        return null
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("shield", "Safe Browsing", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "shield") else Notification.Builder(this)
        return builder.setContentTitle("ClearMind Safe Browsing is on").setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()
    }

    override fun onDestroy() { running = false; scope.cancel(); tun?.close(); tun = null; super.onDestroy() }
}
