package com.example.androidmocklocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental DNS guard used to beat WeChat's own network positioning without root.
 *
 * WeChat (Tencent location SDK) ignores the system GPS test provider as soon as it can obtain a
 * WiFi/network fix. This VPN takes over DNS only: its sole route is a virtual DNS server, so every
 * app's name resolution passes through [LocationShieldService], while all other traffic keeps going
 * straight out over WiFi. In observe mode the service just records the hosts apps resolve; in block
 * mode it answers Tencent's location endpoints with NXDOMAIN so the SDK has to fall back to the
 * framework GPS provider, which is our mock.
 */
object DnsShield {
    const val UPSTREAM_DNS = "223.5.5.5"

    /** Tencent location request hosts. Tile/chat/CDN hosts are deliberately not in this list. */
    private val blockedHosts = listOf(
        "lbs.map.qq.com",
        "apis.map.qq.com",
        "rtt.map.qq.com",
        "lookup.map.qq.com",
        "loc.map.qq.com",
        "location.qq.com",
        "lbs.qq.com"
    )

    val running = MutableStateFlow(false)
    val blocking = MutableStateFlow(false)
    val total = MutableStateFlow(0)
    val blockedCount = MutableStateFlow(0)
    /** Newest first, capped; shown in the diagnostics page. */
    val recent = MutableStateFlow<List<String>>(emptyList())

    fun isBlocked(host: String): Boolean {
        val name = host.lowercase(Locale.US).trimEnd('.')
        return blockedHosts.any { name == it || name.endsWith(".$it") }
    }

    fun record(host: String, blocked: Boolean) {
        total.value += 1
        if (blocked) blockedCount.value += 1
        android.util.Log.i("DnsShield", (if (blocked) "BLOCK " else "QUERY ") + host)
        val line = (if (blocked) "拦截 " else "查询 ") + host
        recent.value = (listOf(line) + recent.value).take(80)
    }

    fun reset() {
        total.value = 0
        blockedCount.value = 0
        recent.value = emptyList()
    }
}

class LocationShieldService : VpnService() {
    companion object {
        const val ACTION_START = "shield_start"
        const val ACTION_STOP = "shield_stop"
        const val EXTRA_BLOCK = "shield_block"
        private const val CHANNEL = "location_shield"
        private const val NOTIFICATION_ID = 18273
        private const val DNS_PORT = 53
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val VIRTUAL_ADDRESS = "10.111.222.1"

        fun start(context: Context, block: Boolean) {
            val intent = Intent(context, LocationShieldService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BLOCK, block)
            context.startService(intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, LocationShieldService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                // Service already gone.
            }
        }
    }

    private val active = AtomicBoolean(false)
    private var descriptor: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var blockMode = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        blockMode = intent?.getBooleanExtra(EXTRA_BLOCK, false) ?: false
        startTunnel()
        return START_STICKY
    }

    private fun notificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "网络定位拦截（实验）", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("安卓改定位 · 网络定位拦截")
            .setContentText(if (blockMode) "仅接管 DNS，拦截腾讯定位域名" else "仅接管 DNS，记录应用查询")
            .setContentIntent(open).setOngoing(true)
            .build()
    }

    private fun startTunnel() {
        if (active.get()) {
            DnsShield.blocking.value = blockMode
            return
        }
        notificationChannel()
        val builder = Builder()
            .setSession("安卓改定位网络定位拦截")
            .addAddress(VIRTUAL_ADDRESS, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
            // Older builds may refuse; the app does not rely on its own DNS.
        }
        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            DnsShield.running.value = false
            stopSelf()
            return
        } ?: run {
            stopSelf()
            return
        }
        descriptor = tun
        active.set(true)
        DnsShield.running.value = true
        DnsShield.blocking.value = blockMode
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (_: Exception) {
            // Foreground start may be refused if the app is backgrounded; the tunnel still works.
        }
        worker = Thread({ pump(tun) }, "dns-shield").also { it.start() }
    }

    private fun shutdown() {
        active.set(false)
        DnsShield.running.value = false
        worker?.interrupt()
        worker = null
        try {
            descriptor?.close()
        } catch (_: Exception) {
        }
        descriptor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        active.set(false)
        DnsShield.running.value = false
        try {
            descriptor?.close()
        } catch (_: Exception) {
        }
        descriptor = null
        super.onDestroy()
    }

    private fun pump(tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buffer = ByteArray(65535)
        val upstream = DatagramSocket().apply {
            try {
                protect(this)
            } catch (_: Exception) {
            }
            soTimeout = 3000
        }
        try {
            while (active.get()) {
                val length = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (length <= 0) continue
                try {
                    handlePacket(buffer, length, output, upstream)
                } catch (_: Exception) {
                    // Malformed packet: ignore.
                }
            }
        } finally {
            try {
                upstream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream, upstream: DatagramSocket) {
        if (length < 20) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 8) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return
        val destination = InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress
        if (destination != VIRTUAL_DNS) return
        val udp = headerLength
        val sourcePort = ((packet[udp].toInt() and 0xFF) shl 8) or (packet[udp + 1].toInt() and 0xFF)
        val destinationPort = ((packet[udp + 2].toInt() and 0xFF) shl 8) or (packet[udp + 3].toInt() and 0xFF)
        if (destinationPort != DNS_PORT) return
        val udpLength = ((packet[udp + 4].toInt() and 0xFF) shl 8) or (packet[udp + 5].toInt() and 0xFF)
        val payloadLength = udpLength - 8
        if (payloadLength <= 0 || udp + 8 + payloadLength > length) return
        val query = packet.copyOfRange(udp + 8, udp + 8 + payloadLength)
        val host = questionName(query)
        if (host == null || host.isEmpty()) return
        val blocked = blockMode && DnsShield.isBlocked(host)
        DnsShield.record(host, blocked)
        val reply = if (blocked) {
            nxdomain(query)
        } else {
            forward(upstream, query) ?: servfail(query)
        }
        val sourceIp = packet.copyOfRange(12, 16)
        writeUdpReply(output, sourceIp, packet.copyOfRange(16, 20), sourcePort, reply)
    }

    private fun forward(socket: DatagramSocket, query: ByteArray): ByteArray? {
        return try {
            val packet = DatagramPacket(query, query.size, InetAddress.getByName(DnsShield.UPSTREAM_DNS), DNS_PORT)
            socket.send(packet)
            val response = ByteArray(4096)
            val incoming = DatagramPacket(response, response.size)
            socket.receive(incoming)
            response.copyOf(incoming.length)
        } catch (_: Exception) {
            null
        }
    }

    /** Builds an NXDOMAIN answer that echoes the original question. */
    private fun nxdomain(query: ByteArray): ByteArray {
        val end = questionEnd(query) ?: return servfail(query)
        val requestFlags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        val flags = 0x8000 or (requestFlags and 0x0100) or 0x0080 or 0x0003
        val reply = ByteArray(end)
        System.arraycopy(query, 0, reply, 0, end)
        reply[2] = ((flags ushr 8) and 0xFF).toByte()
        reply[3] = (flags and 0xFF).toByte()
        reply[6] = 0; reply[7] = 0
        reply[8] = 0; reply[9] = 0
        reply[10] = 0; reply[11] = 0
        return reply
    }

    private fun servfail(query: ByteArray): ByteArray {
        val end = questionEnd(query) ?: query.size.coerceAtMost(12)
        val requestFlags = if (query.size >= 4) ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF) else 0
        val flags = 0x8000 or (requestFlags and 0x0100) or 0x0080 or 0x0002
        val reply = ByteArray(end)
        System.arraycopy(query, 0, reply, 0, end)
        if (reply.size >= 12) {
            reply[2] = ((flags ushr 8) and 0xFF).toByte()
            reply[3] = (flags and 0xFF).toByte()
            reply[6] = 0; reply[7] = 0
            reply[8] = 0; reply[9] = 0
            reply[10] = 0; reply[11] = 0
        }
        return reply
    }

    private fun questionName(query: ByteArray): String? {
        if (query.size < 13) return null
        val labels = StringBuilder()
        var offset = 12
        while (offset < query.size) {
            val length = query[offset].toInt() and 0xFF
            if (length == 0) return labels.toString()
            if (length and 0xC0 != 0) return null
            if (offset + 1 + length > query.size) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(query, offset + 1, length, Charsets.US_ASCII))
            offset += 1 + length
        }
        return null
    }

    private fun questionEnd(query: ByteArray): Int? {
        if (query.size < 13) return null
        var offset = 12
        while (offset < query.size) {
            val length = query[offset].toInt() and 0xFF
            if (length == 0) {
                val end = offset + 1 + 4
                return if (end <= query.size) end else null
            }
            if (length and 0xC0 != 0) return null
            offset += 1 + length
        }
        return null
    }

    private fun writeUdpReply(
        output: FileOutputStream,
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        destinationPort: Int,
        payload: ByteArray
    ) {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = ((totalLength ushr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0
        packet[6] = 0x40; packet[7] = 0 // no fragmentation
        packet[8] = 64
        packet[9] = 17
        packet[10] = 0; packet[11] = 0
        System.arraycopy(destinationIp, 0, packet, 12, 4)
        System.arraycopy(sourceIp, 0, packet, 16, 4)
        val checksum = ipChecksum(packet, 0, 20)
        packet[10] = ((checksum ushr 8) and 0xFF).toByte()
        packet[11] = (checksum and 0xFF).toByte()
        val udp = 20
        packet[udp] = ((DNS_PORT ushr 8) and 0xFF).toByte()
        packet[udp + 1] = (DNS_PORT and 0xFF).toByte()
        packet[udp + 2] = ((destinationPort ushr 8) and 0xFF).toByte()
        packet[udp + 3] = (destinationPort and 0xFF).toByte()
        val udpLength = 8 + payload.size
        packet[udp + 4] = ((udpLength ushr 8) and 0xFF).toByte()
        packet[udp + 5] = (udpLength and 0xFF).toByte()
        packet[udp + 6] = 0; packet[udp + 7] = 0 // checksum optional for IPv4 UDP
        System.arraycopy(payload, 0, packet, udp + 8, payload.size)
        output.write(packet)
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i + 1 < offset + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
