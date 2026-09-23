// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.storage

import android.content.Context
import java.io.File

/**
 * Az app saját mappaszerkezete. Az első indításkor (és minden későbbi indításkor, idempotensen)
 * az app maga hozza létre - nincs külön telepítő-lépés.
 *
 *   <root>/log/          napi naplófájlok (networktools-ÉÉÉÉ-HH-NN.log) - az app saját üzemi naplója
 *   <root>/log/tests/    egy-egy TESZT FUTÁSÁNAK átirata, "<HálózatNév>_ÉÉÉÉMMDD_HHmmss.log" néven
 *   <root>/notes/        jegyzetek: egy jegyzet = egy .md fájl (cím, dátum, tartalom)
 *   <root>/profiles/     hálózati profilok (profiles.json) + a legutóbbi teszt-összefoglalók (quickreport.json)
 *
 * A gyökér az app-specifikus külső tár (Android/data/hu.lordathis.networktools/files), ami engedély
 * nélkül írható, és FRISSÍTÉSKOR (azonos csomagnév + aláírás, magasabb versionCode) érintetlen marad.
 * Eltávolításkor törlődik (ellene véd az Adatmentés a Dokumentumok mappába).
 */
class AppStorage(val root: File) {
    val logDir = File(root, "log")
    val testLogDir = File(logDir, "tests")
    val notesDir = File(root, "notes")
    val profilesDir = File(root, "profiles")

    /** Létrehozza a hiányzó mappákat; a most létrehozottak nevét adja vissza. */
    fun ensureLayout(): List<String> {
        val created = ArrayList<String>()
        for (dir in listOf(logDir, testLogDir, notesDir, profilesDir)) {
            if (!dir.exists() && dir.mkdirs()) created.add(dir.name)
        }
        return created
    }

    companion object {
        /** Az app-specifikus külső tár; ha az nem elérhető, a belső (privát) tár. */
        fun resolveRoot(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir
    }
}
