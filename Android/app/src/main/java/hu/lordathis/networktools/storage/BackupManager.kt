// Verzio: v0.2.0 - 2026-09-21
package hu.lordathis.networktools.storage

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A mentés állapota a UI-nak. [available]: van-e mappa-hozzáférés; [lastMs]: az utolsó sikeres kör ideje. */
data class BackupStatus(val available: Boolean, val lastMs: Long?, val lastCopied: Int, val message: String)

/** Egy mentési/visszaállítási kör eredménye. */
data class BackupResult(val copied: Int, val restored: Int, val merged: Int, val errors: List<String>) {
    val changedLocal: Boolean get() = restored + merged > 0
}

/**
 * Az app adatainak tükrözése egy MÁSIK helyre (a telefon közös Dokumentumok mappájába), amely az
 * app eltávolítását is túléli - és a visszaállítás onnan.
 *
 *  - [mirror]: a helyi fájlokat a mentési mappába másolja, ha hiányoznak vagy változtak. A mentésből
 *    SOHA nem töröl (a régi naplók is megmaradnak).
 *  - [restore]: a mentésben lévő fájlokat visszahozza: ami helyben hiányzik, azt átmásolja; ami mindkét
 *    helyen megvan, azt EGYESÍTI (napló: sorok uniója), így semmi nem vész el, akkor sem, ha az
 *    újratelepítés után már íródott új adat.
 * Sorrend: mindig előbb [restore], utána [mirror] - így a tükrözés nem írhatja felül a mentést egy üres, friss telepítéssel.
 * Tiszta JVM (nincs Android-függés).
 */
class BackupManager(private val localRoot: File, private val backupRoot: File) {

    /** A mappa-név szerinti zárolást a hívó adja (pl. az adott tár objektuma), hogy közben ne írjanak bele. */
    fun mirror(dirs: List<String>, lockFor: (String) -> Any): BackupResult {
        var copied = 0
        val errors = ArrayList<String>()
        for (dir in dirs) {
            val src = File(localRoot, dir)
            if (!src.isDirectory) continue
            synchronized(lockFor(dir)) {
                for (file in src.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }) {
                    try {
                        val dest = File(backupRoot, file.relativeTo(localRoot).path)
                        if (!dest.exists() || dest.length() != file.length() || dest.lastModified() != file.lastModified()) {
                            writeAtomically(dest, file.readBytes(), file.lastModified())
                            copied++
                        }
                    } catch (e: Exception) {
                        errors.add("${file.name}: ${e.message}")
                    }
                }
            }
        }
        return BackupResult(copied, 0, 0, errors)
    }

    fun restore(dirs: List<String>, lockFor: (String) -> Any): BackupResult {
        var restored = 0
        var merged = 0
        val errors = ArrayList<String>()
        for (dir in dirs) {
            val src = File(backupRoot, dir)
            if (!src.isDirectory) continue
            synchronized(lockFor(dir)) {
                for (backupFile in src.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }) {
                    try {
                        val local = File(localRoot, backupFile.relativeTo(backupRoot).path)
                        if (!local.exists() || local.length() == 0L) {
                            writeAtomically(local, backupFile.readBytes(), backupFile.lastModified())
                            restored++
                        } else {
                            val mergedBytes = mergeFile(dir, local.name, local.readBytes(), backupFile.readBytes())
                            if (mergedBytes != null && !mergedBytes.contentEquals(local.readBytes())) {
                                writeAtomically(local, mergedBytes, System.currentTimeMillis())
                                merged++
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("${backupFile.name}: ${e.message}")
                    }
                }
            }
        }
        return BackupResult(0, restored, merged, errors)
    }

    private fun mergeFile(dir: String, name: String, local: ByteArray, backup: ByteArray): ByteArray? {
        val localText = String(local, Charsets.UTF_8)
        val backupText = String(backup, Charsets.UTF_8)
        return when {
            name.endsWith(".log") -> LogMerge.mergeLines(localText, backupText)
            else -> null // ismeretlen fájl: helyi változat marad
        }?.toByteArray(Charsets.UTF_8)
    }

    private fun writeAtomically(dest: File, bytes: ByteArray, lastModified: Long) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.setLastModified(lastModified)
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        dest.setLastModified(lastModified)
    }
}

/** Egyesítő-függvények a helyi és a mentett változat összefésüléséhez (tiszta logika). */
object LogMerge {

    /** Napló: a két oldal egyedi sorai, rendezve (a sor elején időbélyeg van, így a szöveges rendezés időrend). */
    fun mergeLines(localText: String, backupText: String): String {
        val lines = LinkedHashSet<String>()
        (localText.lines() + backupText.lines()).filter { it.isNotEmpty() }.forEach { lines.add(it) }
        return lines.sorted().joinToString("\n") + "\n"
    }
}
