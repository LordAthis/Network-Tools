// Verzio: v0.1.0 - 2026-09-21
package hu.lordathis.networktools.storage

import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Napi naplófájlok a `log/` mappában: networktools-ÉÉÉÉ-HH-NN.log, soronként
 * "ÉÉÉÉ-HH-NN óó:pp:mm.ezr üzenet". A [keepDays]-nál régebbieket törli.
 */
class LogStore(private val dir: File, private val keepDays: Int = 14) {

    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val filePattern = Regex("""networktools-(\d{4}-\d{2}-\d{2})\.log""")

    private fun fileFor(date: LocalDate) = File(dir, "networktools-$date.log")

    @Synchronized
    fun append(line: String, now: LocalDateTime = LocalDateTime.now()) {
        dir.mkdirs()
        val clean = line.replace('\n', ' ').replace('\r', ' ')
        val text = now.format(stampFormat) + " " + clean + "\n"
        FileOutputStream(fileFor(now.toLocalDate()), true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized
    fun loadTail(maxLines: Int, today: LocalDate = LocalDate.now()): List<String> {
        val f = fileFor(today)
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).takeLast(maxLines)
    }

    /** Az összes (még meglévő) napi naplófájl egymás után, időrendben - a "Napló mentése" exporthoz. */
    @Synchronized
    fun readAll(): String {
        val files = dir.listFiles { f -> filePattern.matches(f.name) }?.sortedBy { it.name } ?: return ""
        return files.joinToString("") { it.readText(Charsets.UTF_8) }
    }

    @Synchronized
    fun purgeOld(today: LocalDate = LocalDate.now()): Int {
        val files = dir.listFiles() ?: return 0
        val limit = today.minusDays(keepDays.toLong())
        var deleted = 0
        for (f in files) {
            val m = filePattern.matchEntire(f.name) ?: continue
            val date = try {
                LocalDate.parse(m.groupValues[1])
            } catch (e: Exception) {
                continue
            }
            if (date.isBefore(limit) && f.delete()) deleted++
        }
        return deleted
    }
}
