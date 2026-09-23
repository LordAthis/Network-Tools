// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Kézzel épített SNMPv1 GET-kérés UDP 161-en - a NetworkDiag_MAX_v2.ps1 §6/C portja.
 * Managed switch/router felismerésre: sysDescr (1.3.6.1.2.1.1.1.0) és sysName (1.3.6.1.2.1.1.5.0)
 * lekérdezése a "public" közösségi névvel (a leggyakoribb alapértelmezés). Csak OLVASÁS (GET),
 * semmit nem ír a célra.
 */
object SnmpProbe {
    private const val OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0"
    private const val OID_SYS_NAME = "1.3.6.1.2.1.1.5.0"

    data class SnmpResult(val sysDescr: String?, val sysName: String?)

    suspend fun query(host: String, community: String = "public", timeoutMs: Int = 800): SnmpResult? =
        withContext(Dispatchers.IO) {
            val descr = getString(host, community, OID_SYS_DESCR, timeoutMs)
            val name = getString(host, community, OID_SYS_NAME, timeoutMs)
            if (descr == null && name == null) null else SnmpResult(descr, name)
        }

    private fun getString(host: String, community: String, oid: String, timeoutMs: Int): String? {
        return try {
            val request = buildGetRequest(community, oid)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(host)
                socket.send(DatagramPacket(request, request.size, address, 161))
                val buffer = ByteArray(1500)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                parseGetResponseString(response.data, response.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // BER/ASN.1 kódolás - minimális, csak amennyi egy SNMPv1 GetRequest összeállításához kell.
    // ---------------------------------------------------------------------------------------

    private fun buildGetRequest(community: String, oidText: String): ByteArray {
        val oid = encodeOid(oidText)
        val varBind = tlv(0x30, concat(oid, tlv(0x05, ByteArray(0)))) // OID + NULL érték
        val varBindList = tlv(0x30, varBind)
        val requestId = tlv(0x02, byteArrayOf(0x01)) // egyszerű, fix kérés-azonosító
        val errorStatus = tlv(0x02, byteArrayOf(0x00))
        val errorIndex = tlv(0x02, byteArrayOf(0x00))
        val pdu = tlv(0xA0, concat(requestId, errorStatus, errorIndex, varBindList))
        val version = tlv(0x02, byteArrayOf(0x00)) // SNMPv1
        val communityField = tlv(0x04, community.toByteArray(Charsets.US_ASCII))
        return tlv(0x30, concat(version, communityField, pdu))
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray = concat(byteArrayOf(tag.toByte()), encodeLength(content.size), content)

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
    }

    private fun encodeOid(text: String): ByteArray {
        val parts = text.split(".").map { it.toInt() }
        val body = java.io.ByteArrayOutputStream()
        // Az első két szám (pl. 1.3) egyetlen byte-ba tömörödik: 40*első + második.
        body.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var value = parts[i]
            if (value == 0) {
                body.write(0)
                continue
            }
            val chunk = ArrayList<Int>()
            while (value > 0) {
                chunk.add(0, value and 0x7F)
                value = value shr 7
            }
            for (j in chunk.indices) {
                val cont = if (j < chunk.size - 1) 0x80 else 0x00
                body.write(chunk[j] or cont)
            }
        }
        return tlv(0x06, body.toByteArray())
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (a in arrays) out.write(a)
        return out.toByteArray()
    }

    /**
     * A válasz kézzel, a VÁRT alakhoz igazítva (nem általános BER-parser): a konténereken
     * (SEQUENCE 0x30, GetResponse-PDU 0xA2) belelépünk, minden más mezőt átugrunk a hosszával -
     * az utolsó, a varbind-ban talált ÉRTÉK (OCTET STRING/INTEGER/stb.) a válasz.
     */
    private fun parseGetResponseString(data: ByteArray, length: Int): String? {
        val reader = TlvReader(data, length)
        return try {
            reader.lastPrimitiveValue()
        } catch (e: Exception) {
            null
        }
    }

    /** Egyszerű TLV-bejáró: a konténer-tag-eket rekurzívan bejárja, a primitíveket összegyűjti. */
    private class TlvReader(private val data: ByteArray, private val length: Int) {
        private val CONTAINER_TAGS = setOf(0x30, 0xA0, 0xA2, 0xA3)

        fun lastPrimitiveValue(): String? {
            var last: String? = null
            walk(0, length) { tag, valueStart, valueLen ->
                if (tag !in CONTAINER_TAGS) {
                    last = decodePrimitive(tag, valueStart, valueLen)
                }
            }
            return last
        }

        private fun decodePrimitive(tag: Int, start: Int, len: Int): String? = when (tag) {
            0x04 -> String(data, start, len, Charsets.UTF_8) // OCTET STRING
            0x02 -> { // INTEGER
                var value = 0L
                for (i in start until start + len) value = (value shl 8) or (data[i].toLong() and 0xFF)
                value.toString()
            }
            0x06 -> null // OID - nem érdekes válaszként
            0x05 -> null // NULL
            else -> null
        }

        /** [onValue]-t hívja minden TLV-re; konténer tag-eknél a TARTALMÁBA lép be (rekurzívan). */
        private fun walk(from: Int, to: Int, onValue: (tag: Int, valueStart: Int, valueLen: Int) -> Unit) {
            var pos = from
            while (pos < to) {
                val tag = data[pos].toInt() and 0xFF
                pos++
                if (pos >= to) return
                var len = data[pos].toInt() and 0xFF
                pos++
                if (len and 0x80 != 0) {
                    val numBytes = len and 0x7F
                    len = 0
                    repeat(numBytes) {
                        if (pos >= to) return
                        len = (len shl 8) or (data[pos].toInt() and 0xFF)
                        pos++
                    }
                }
                val valueEnd = (pos + len).coerceAtMost(to)
                if (tag in CONTAINER_TAGS) {
                    walk(pos, valueEnd, onValue)
                } else {
                    onValue(tag, pos, valueEnd - pos)
                }
                pos = valueEnd
            }
        }
    }
}
