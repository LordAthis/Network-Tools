// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.storage

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Egy mentési/exportálási esemény (a Mentés-panel alsó listája). */
data class ExportHistoryEntry(val whenMs: Long, val what: String, val destination: String, val detail: String)

/**
 * A korábbi mentések rövid előzménye - CSV-ben (`export_history.csv`), a MENTÉS PANEL alsó listájához.
 * Nem az app fő naplója (az a LogStore dolga) - ez csak a "mikor, mit, hova mentettem" gyors áttekintés.
 * Minimális, önálló CSV-kódolás (RFC 4180 idézőjelezéssel) - nincs külön függősége.
 */
class ExportHistoryStore(private val file: File) {

    @Synchronized
    fun append(what: String, destination: String, detail: String) {
        file.parentFile?.mkdirs()
        val isNew = !file.exists()
        FileOutputStream(file, true).use { out ->
            if (isNew) {
                out.write(("\uFEFF" + encodeRow(listOf("mikor", "mit", "hova", "reszletek")) + "\r\n").toByteArray(Charsets.UTF_8))
            }
            out.write((encodeRow(listOf(nowIso(), what, destination, detail)) + "\r\n").toByteArray(Charsets.UTF_8))
        }
    }

    @Synchronized
    fun loadAll(): List<ExportHistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            decodeRows(file.readText(Charsets.UTF_8)).drop(1).mapNotNull { row ->
                if (row.size < 4) return@mapNotNull null
                val ms = try {
                    OffsetDateTime.parse(row[0]).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                ExportHistoryEntry(ms, row[1], row[2], row[3])
            }.sortedByDescending { it.whenMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun nowIso(): String =
        OffsetDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun encodeRow(fields: List<String>): String = fields.joinToString(",") { field ->
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    private fun decodeRows(text: String): List<List<String>> {
        val clean = text.removePrefix("\uFEFF")
        return clean.split("\r\n", "\n").filter { it.isNotEmpty() }.map { line ->
            val fields = ArrayList<String>()
            val cur = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        cur.append('"'); i++
                    }
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> {
                        fields.add(cur.toString()); cur.clear()
                    }
                    else -> cur.append(ch)
                }
                i++
            }
            fields.add(cur.toString())
            fields
        }
    }
}
