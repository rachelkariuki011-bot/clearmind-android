package app.clearmind.companion

import android.net.VpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Minimal IPv4/UDP/DNS helpers for the local DNS tunnel. */
object DnsPacket {
    private const val IP = 20; private const val UDP = 8

    fun queryName(p: ByteArray, n: Int): String? {
        if (n < IP + UDP + 13 || p[9].toInt() != 17) return null
        var i = IP + UDP + 12; val parts = mutableListOf<String>()
        while (i < n && p[i].toInt() != 0) { val len = p[i].toInt(); parts += String(p, i + 1, len); i += len + 1 }
        return parts.joinToString(".").lowercase()
    }

    fun nxdomain(p: ByteArray, n: Int): ByteArray {
        val dns = p.copyOfRange(IP + UDP, n)
        dns[2] = (dns[2].toInt() or 0x80).toByte(); dns[3] = 0x83.toByte() // response + NXDOMAIN
        return wrap(p, dns)
    }

    fun forward(p: ByteArray, n: Int, resolver: String, vpn: VpnService): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            vpn.protect(s); s.soTimeout = 4000
            val q = p.copyOfRange(IP + UDP, n)
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(resolver), 53))
            val r = DatagramPacket(ByteArray(4096), 4096); s.receive(r)
            wrap(p, r.data.copyOf(r.length))
        }
    }.getOrNull()

    /** Builds a reply packet by swapping src/dst of the original IP/UDP headers. */
    private fun wrap(orig: ByteArray, dns: ByteArray): ByteArray {
        val out = ByteArray(IP + UDP + dns.size)
        System.arraycopy(orig, 0, out, 0, IP)
        System.arraycopy(orig, 16, out, 12, 4); System.arraycopy(orig, 12, out, 16, 4)
        val total = out.size; out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[10] = 0; out[11] = 0
        var sum = 0; for (i in 0 until IP step 2) sum += ((out[i].toInt() and 0xff) shl 8) or (out[i + 1].toInt() and 0xff)
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        sum = sum.inv(); out[10] = (sum shr 8).toByte(); out[11] = sum.toByte()
        out[IP] = orig[IP + 2]; out[IP + 1] = orig[IP + 3]; out[IP + 2] = orig[IP]; out[IP + 3] = orig[IP + 1]
        val ulen = UDP + dns.size; out[IP + 4] = (ulen shr 8).toByte(); out[IP + 5] = ulen.toByte()
        out[IP + 6] = 0; out[IP + 7] = 0 // UDP checksum optional on IPv4
        System.arraycopy(dns, 0, out, IP + UDP, dns.size)
        return out
    }
}
