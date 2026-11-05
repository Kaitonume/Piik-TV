package it.mbs.piiktv.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Scoperta dei PC con Piik in rete locale (Wi-Fi o LAN).
 *
 * Piik non annuncia le stanze in rete (niente mDNS/broadcast), ma piik-app avvia sempre il server
 * stanze su TCP 8787 su tutte le interfacce. Si scandisce quindi la sottorete su quella porta e si
 * conferma con GET /api/capabilities. I nomi dei PC Windows si ricavano con una query NetBIOS.
 */
class Discovery(
    private val onFound: (PiikServer) -> Unit,
    private val onProgress: (done: Int, total: Int) -> Unit,
    private val onFinished: (found: Int) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(48)

    fun cancel() {
        cancelled.set(true)
        pool.shutdownNow()
    }

    fun start(priorityHosts: Collection<String>) {
        Thread({
            try {
                run(priorityHosts)
            } catch (e: Exception) {
                // Scansione annullata (es. nuova ricerca o uscita dalla schermata): mai far cadere l'app.
                Log.i(TAG, "discovery stopped: $e")
            }
        }, "piik-discovery").start()
    }

    private fun run(priorityHosts: Collection<String>) {
        val seen = Collections.synchronizedSet(HashSet<String>())
        val found = AtomicInteger(0)
        val candidates = LinkedHashSet<String>()
        candidates.addAll(priorityHosts)
        val own = localIpv4()
        for ((addr, prefix) in own) {
            candidates.addAll(subnetHosts(addr, prefix))
        }
        own.forEach { candidates.remove(it.first.hostAddress) }
        val total = candidates.size
        val done = AtomicInteger(0)
        Log.i(TAG, "scan ${candidates.size} hosts, own=${own.map { "${it.first.hostAddress}/${it.second}" }}")
        val futures = candidates.mapNotNull { host ->
            if (cancelled.get()) return@mapNotNull null
            pool.submit {
                if (cancelled.get()) return@submit
                try {
                    if (isPortOpen(host, PiikHttp.DEFAULT_PORT, if (host in priorityHosts) 800 else 350)) {
                        val server = PiikHttp.probe(host)
                        if (server != null && seen.add(host)) {
                            server.name = netbiosName(host)
                            found.incrementAndGet()
                            if (!cancelled.get()) onFound(server)
                        }
                    }
                } finally {
                    val d = done.incrementAndGet()
                    if (d % 8 == 0 || d == total) onProgress(d, total)
                }
            }
        }
        for (f in futures) {
            try {
                f.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
        pool.shutdown()
        if (!cancelled.get()) onFinished(found.get())
    }

    companion object {
        private const val TAG = "PiikDiscovery"

        fun localIpv4(): List<Pair<Inet4Address, Int>> {
            val out = ArrayList<Pair<Inet4Address, Int>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address
                        if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                            out.add(a to ia.networkPrefixLength.toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "interfaces: $e")
            }
            return out
        }

        /** Host della sottorete; per reti più grandi di /23 si limita alla /24 attorno al proprio IP. */
        fun subnetHosts(addr: Inet4Address, prefixIn: Int): List<String> {
            val prefix = prefixIn.coerceIn(23, 30)
            val ip = addr.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val net = ip and mask
            val count = (1L shl (32 - prefix))
            val hosts = ArrayList<String>()
            for (i in 1 until count - 1) {
                val h = net + i
                hosts.add("${(h shr 24) and 0xFF}.${(h shr 16) and 0xFF}.${(h shr 8) and 0xFF}.${h and 0xFF}")
            }
            // Ordina per vicinanza all'ultimo ottetto del router/gateway tipico (prima .2-.50)
            return hosts
        }

        fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

        /** Query NetBIOS "node status" (UDP 137): restituisce il nome del PC Windows, se risponde. */
        fun netbiosName(host: String): String? {
            return try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 400
                    val txId = Random.nextInt(0, 0xFFFF)
                    val q = ByteArray(50)
                    q[0] = (txId shr 8).toByte(); q[1] = txId.toByte()
                    q[5] = 1 // QDCOUNT = 1
                    q[12] = 0x20
                    // Nome "*" codificato (primo livello): '*' = 0x2A -> 'C','K'; padding 0x00 -> 'A','A'
                    q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte()
                    for (i in 15 until 45) q[i] = 'A'.code.toByte()
                    q[45] = 0
                    q[46] = 0; q[47] = 0x21 // NBSTAT
                    q[48] = 0; q[49] = 1 // IN
                    sock.send(DatagramPacket(q, q.size, InetAddress.getByName(host), 137))
                    val buf = ByteArray(1024)
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    parseNbstat(buf, p.length)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun parseNbstat(b: ByteArray, len: Int): String? {
            if (len < 57) return null
            var off = 12
            // nome della risposta: 0x20 + 32 byte + 0x00 (oppure puntatore compresso 0xC0xx)
            off += if ((b[off].toInt() and 0xC0) == 0xC0) 2 else (b[off].toInt() and 0xFF) + 2
            off += 2 + 2 + 4 + 2 // TYPE, CLASS, TTL, RDLENGTH
            if (off >= len) return null
            val num = b[off].toInt() and 0xFF
            off += 1
            for (i in 0 until num) {
                if (off + 18 > len) break
                val name = String(b, off, 15, Charsets.US_ASCII).trimEnd(' ', '\u0000')
                val suffix = b[off + 15].toInt() and 0xFF
                val flags = ((b[off + 16].toInt() and 0xFF) shl 8) or (b[off + 17].toInt() and 0xFF)
                val group = (flags and 0x8000) != 0
                if (suffix == 0 && !group && name.isNotBlank()) return name
                off += 18
            }
            return null
        }
    }
}
