// Verzio: v0.3.0 - 2026-09-21
package hu.lordathis.networktools.storage

import android.content.Context
import java.io.File

/**
 * Az app saját mappaszerkezete. Az első indításkor (és minden későbbi indításkor, idempotensen)
 * az app maga hozza létre - nincs külön telepítő-lépés.
 *
 *   <root>/log/    napi naplófájlok (networktools-ÉÉÉÉ-HH-NN.log)
 *   <root>/notes/  jegyzetek: egy jegyzet = egy .md fájl (cím, dátum, tartalom)
 *
 * A gyökér az app-specifikus külső tár (Android/data/hu.lordathis.networktools/files), ami engedély
 * nélkül írható, és FRISSÍTÉSKOR (azonos csomagnév + aláírás, magasabb versionCode) érintetlen marad.
 * Eltávolításkor törlődik (ellene véd az Adatmentés a Dokumentumok mappába).
 */
class AppStorage(val root: File) {
    val logDir = File(root, "log")
    val notesDir = File(root, "notes")

    /** Létrehozza a hiányzó mappákat; a most létrehozottak nevét adja vissza. */
    fun ensureLayout(): List<String> {
        val created = ArrayList<String>()
        for (dir in listOf(logDir, notesDir)) {
            if (!dir.exists() && dir.mkdirs()) created.add(dir.name)
        }
        return created
    }

    companion object {
        /** Az app-specifikus külső tár; ha az nem elérhető, a belső (privát) tár. */
        fun resolveRoot(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir
    }
}
