// Verzio: v0.3.0 - 2026-09-21
package hu.lordathis.networktools.notes

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random

/** Egy jegyzet. [id] = a fájl neve kiterjesztés nélkül; [createdMs] a létrehozás ideje. */
data class NoteItem(val id: String, val title: String, val createdMs: Long, val content: String)

/**
 * A jegyzetek: minden jegyzet egy külön MARKDOWN (.md) fájl a `notes/` mappában.
 *
 *     # Cím
 *
 *     _2026-09-21T20:31:00+02:00_
 *
 *     a jegyzet tartalma...
 *
 * (Cím, dátum, tartalom.) A fájlok sima szövegek: a telefon bármelyik szövegszerkesztőjével/markdown-nézegetőjével
 * olvashatók, és a Mentés (Dokumentumok/NetworkTools) is másolja őket.
 */
class NoteStore(private val dir: File) {

    @Synchronized
    fun loadAll(): List<NoteItem> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                decode(f.readText(Charsets.UTF_8), f.name.removeSuffix(".md"), f.lastModified())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.createdMs }
    }

    /** Új vagy meglévő jegyzet mentése (biztonságosan: ideiglenes fájl + átnevezés). */
    @Synchronized
    fun save(note: NoteItem) {
        dir.mkdirs()
        val file = File(dir, note.id + ".md")
        val tmp = File(dir, note.id + ".md.tmp")
        tmp.writeBytes(encode(note).toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        return File(dir, "$id.md").delete()
    }

    companion object {
        private val ID_PATTERN = Regex("""[A-Za-z0-9_-]+""")
        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val random = Random()

        /** Új azonosító a létrehozás idejéből: jegyzet-ÉÉÉÉHHNN-ÓÓPPMM-xxxx (a fájlnév rendezhető és egyedi). */
        fun newId(nowMs: Long): String {
            val stamp = ID_TIME.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
            return "jegyzet-$stamp-" + (1000 + random.nextInt(9000))
        }

        fun encode(note: NoteItem): String {
            val title = note.title.replace('\n', ' ').replace('\r', ' ').trim()
            val iso = OffsetDateTime.ofInstant(Instant.ofEpochMilli(note.createdMs), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            return "# $title\n\n_${iso}_\n\n${note.content.replace("\r\n", "\n")}\n"
        }

        /** A fájl szövegéből jegyzet; ha a szerkezet nem a megszokott (kézzel szerkesztett fájl), a fájlnév/módosítás-idő szolgál tartalékként. */
        fun decode(text: String, id: String, lastModifiedMs: Long): NoteItem {
            val lines = text.replace("\r\n", "\n").split("\n")
            if (lines.size >= 3 && lines[0].startsWith("# ")) {
                val title = lines[0].removePrefix("# ").trim()
                val dateLine = lines[2].trim()
                val created = if (dateLine.length > 2 && dateLine.startsWith("_") && dateLine.endsWith("_")) {
                    try {
                        OffsetDateTime.parse(dateLine.substring(1, dateLine.length - 1)).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                val contentStart = if (created != null) 4 else 2
                val content = lines.drop(contentStart).joinToString("\n").trimEnd('\n')
                return NoteItem(id, title, created ?: lastModifiedMs, content)
            }
            return NoteItem(id, id, lastModifiedMs, text.trim())
        }
    }
}
