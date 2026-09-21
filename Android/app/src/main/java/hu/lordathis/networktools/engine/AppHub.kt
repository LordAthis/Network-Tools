// Verzio: v0.4.0 - 2026-09-21
package hu.lordathis.networktools.engine

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import hu.lordathis.networktools.BuildConfig
import hu.lordathis.networktools.crypto.CryptoService
import hu.lordathis.networktools.settings.AppPreferences
import hu.lordathis.networktools.storage.AppStorage
import hu.lordathis.networktools.storage.BackupManager
import hu.lordathis.networktools.storage.BackupStatus
import hu.lordathis.networktools.storage.InstallInfo
import hu.lordathis.networktools.notes.NoteItem
import hu.lordathis.networktools.notes.NoteStore
import hu.lordathis.networktools.storage.LogMerge
import hu.lordathis.networktools.storage.LogStore
import hu.lordathis.networktools.storage.Migrations
import hu.lordathis.networktools.storage.StorageWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Az app "agya": az egymástól FÜGGETLEN folyamatok itt kapnak saját scope-ot, és StateFlow-kon
 * keresztül beszélnek egymással - a UI csak megfigyel és eseményt küld. (Az Activity újraindulását,
 * pl. elforgatást, a ViewModel túléli.)
 *
 *   Mentés-folyamat  StorageWriter  saját egyszálas dispatcher: napló lemezre
 *   Titkosítás       CryptoService  önálló, saját szál-készletű szolgáltatás
 *   Adatmentés       backupScope    az adatok tükrözése a közös Dokumentumok/NetworkTools mappába
 *   Jegyzetek        NoteStore      egy jegyzet = egy .md fájl (notes/)
 *   Visszajelzések   feed           a Kezdőlap sávja: app-események és (később) a háttértesztek eredményei
 *   Menü             a UI-ban: a fiók-állapot szinkron
 *
 * HÁTTÉRFELADATOK (a jövőbeli tesztek) SZABÁLYA: a feladat ITT, a hub scope-jában fut (nem az Activity-ben,
 * nem a képernyő kompozíciójában), ezért a képernyőváltás, a fiókok, a panelek megnyitása/zárása NEM állítja
 * le és nem szakítja meg. A részeredményeket a [postFeed]-del kell közölni: a Kezdőlap folyamatosan mutatja.
 * Hosszan futó feladatnál a BackgroundService (Beállítások > Háttérben futás) tartja életben a folyamatot.
 */
class AppHub(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext

    val prefs = AppPreferences(appContext)

    // --- Mappák, verzió, frissítés-felismerés ---------------------------------------
    val storage = AppStorage(AppStorage.resolveRoot(appContext))
    val installInfo: InstallInfo = detectInstall()
    private val createdDirs = storage.ensureLayout()

    // --- Folyamatok (saját scope-ok) -----------------------------------------------
    private val storageExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "networktools-storage").apply { isDaemon = true }
    }
    private val storageDispatcher = storageExecutor.asCoroutineDispatcher()
    private val storageScope = CoroutineScope(SupervisorJob() + storageDispatcher)

    private val logStore = LogStore(storage.logDir)
    private val writer = StorageWriter(
        dispatcher = storageDispatcher,
        logStore = logStore,
        onError = { addLogLine(it) },
    )

    // --- Állapotok (a UI ezeket figyeli) --------------------------------------------
    private val logState = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = logState.asStateFlow()

    private val feedState = MutableStateFlow<List<FeedEntry>>(emptyList())
    val feed: StateFlow<List<FeedEntry>> = feedState.asStateFlow()

    private val logFilesState = MutableStateFlow<List<LogFileInfo>>(emptyList())
    val logFiles: StateFlow<List<LogFileInfo>> = logFilesState.asStateFlow()

    // --- Jegyzetek: egy jegyzet = egy .md fájl -------------------------------------------
    private val noteStore = NoteStore(storage.notesDir)
    private val noteState = MutableStateFlow<List<NoteItem>>(emptyList())
    val notes: StateFlow<List<NoteItem>> = noteState.asStateFlow()
    private val noteMutex = Mutex()

    /** A titkosítás önálló szolgáltatás: bármelyik rész hívhatja. */
    val crypto = CryptoService(appContext) { log(it) }

    // --- Adatmentés: a közös Dokumentumok/NetworkTools mappába, amit az app eltávolítása sem töröl ------
    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupMutex = Mutex()
    private val backupState = MutableStateFlow(BackupStatus(available = false, lastMs = null, lastCopied = 0, message = ""))
    val backupStatus: StateFlow<BackupStatus> = backupState.asStateFlow()
    private var backupWasAvailable = false

    init {
        log("Network Tool's elindult (v${BuildConfig.VERSION_NAME}).")
        postFeed("Alkalmazás", "Elindult (v${BuildConfig.VERSION_NAME}).", FeedLevel.INFO)
        log(installInfo.describe())
        if (createdDirs.isNotEmpty()) log("Mappák létrehozva: ${createdDirs.joinToString(", ")}")
        log("Adatmappa: ${storage.root.absolutePath}")
        log(
            "Eszköz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}"
        )
        val freeMb = storage.root.usableSpace / (1024 * 1024)
        log(
            "Szabad tárhely: $freeMb MB" +
                if (freeMb < 500) " - KEVÉS: az app-frissítés telepítése valószínűleg nem sikerül." else ""
        )
        if (freeMb < 500) postFeed("Alkalmazás", "Kevés a szabad tárhely ($freeMb MB): a frissítés nem biztos, hogy települ.", FeedLevel.WARN)
        if (!crypto.hasStoredKey) {
            log("Titkosítás: nincs kulcs beállítva (Beállítások > Titkosítás).")
        }

        storageScope.launch {
            try {
                logStore.purgeOld()
                Migrations.run(installInfo, storage) { log(it) }
                val tail = logStore.loadTail(MAX_LOG_LINES)
                logState.update { (tail + it).takeLast(MAX_LOG_LINES) }
                noteState.value = noteStore.loadAll()
                refreshLogFiles()
            } catch (e: Exception) {
                log("Adatok betöltése sikertelen: ${e.message}")
            }
        }

        // Adatmentés: indításkor (előbb egyesítés a mentésből, utána tükrözés), majd rendszeresen.
        backupScope.launch {
            delay(4_000)
            runBackupCycle("indulás", quiet = false)
            while (isActive) {
                delay(BACKUP_INTERVAL_MS)
                runBackupCycle("időzített", quiet = true)
            }
        }
    }

    // ==================================================================================
    // Nyilvános műveletek (a UI ezeket hívja)
    // ==================================================================================

    fun log(message: String) {
        addLogLine(message)
        writer.log(message)
    }

    /**
     * Visszajelzés a Kezdőlap sávjára (app-esemény, később: háttérteszt részeredmény/eredmény).
     * Bármelyik szálról hívható.
     */
    fun postFeed(source: String, text: String, level: FeedLevel = FeedLevel.INFO) {
        val entry = FeedEntry(System.currentTimeMillis(), source, text, level)
        feedState.update { (it + entry).takeLast(MAX_FEED_ENTRIES) }
    }

    /** A mentés/küldés tartalma; null, ha még nincs mit menteni. */
    private fun exportBytes(kind: ExportKind): ByteArray? = when (kind) {
        ExportKind.LOG -> logStore.readAll().toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    private fun countEntries(bytes: ByteArray): Int =
        String(bytes, Charsets.UTF_8).lines().count { it.isNotEmpty() }

    /**
     * Fájl mentése a felhasználó által választott helyre (Storage Access Framework URI).
     * Siker esetén a bejegyzések (naplónál: sorok) számát adja.
     */
    suspend fun export(kind: ExportKind, uri: Uri): Result<Int> = withContext(storageDispatcher) {
        runCatching {
            val bytes = exportBytes(kind)
                ?: error("Még nincs mit menteni (${kind.label}): a fájl még nem jött létre.")
            val out = appContext.contentResolver.openOutputStream(uri, "wt")
                ?: error("A célfájl nem nyitható meg.")
            out.use { it.write(bytes) }
            countEntries(bytes)
        }.onSuccess { log("${kind.label} elmentve ($it bejegyzés).") }
            .onFailure { log("${kind.label} mentése sikertelen: ${it.message}") }
    }

    /** E-mailes küldéshez: a tartalom ideiglenes fájlba (cache/share/), amit a FileProvider csatolhatóvá tesz. */
    suspend fun prepareShareFile(kind: ExportKind): Result<File> = withContext(storageDispatcher) {
        runCatching {
            val bytes = exportBytes(kind)
                ?: error("Még nincs mit küldeni (${kind.label}): a fájl még nem jött létre.")
            val dir = File(appContext.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            val file = File(dir, "${kind.fileStem}-$stamp.${kind.extension}")
            file.writeBytes(bytes)
            file
        }.onSuccess { log("${kind.label} előkészítve e-mailhez.") }
            .onFailure { log("${kind.label} előkészítése sikertelen: ${it.message}") }
    }

    // ==================================================================================
    // Adatmentés (túléli az eltávolítást)
    // ==================================================================================

    private fun backupAccessGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun backupFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "NetworkTools")

    private fun backupLock(dir: String): Any = if (dir == "notes") noteStore else logStore

    /**
     * Egy mentési kör: ELŐBB visszaállítás/egyesítés a mentésből (újratelepítés után innen jönnek vissza az adatok),
     * UTÁNA tükrözés a mentésbe - így egy friss, üres telepítés nem írhatja felül a meglévő mentést.
     * [quiet]: csak hiba vagy visszaállítás esetén ír a naplóba (az időzített körök ne szemeteljenek).
     */
    suspend fun runBackupCycle(reason: String, quiet: Boolean): BackupStatus = backupMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!backupAccessGranted()) {
                if (backupWasAvailable) {
                    log("Adatmentés: a mappa-hozzáférés megszűnt.")
                    postFeed("Adatmentés", "A mappa-hozzáférés megszűnt.", FeedLevel.WARN)
                }
                backupWasAvailable = false
                val message = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    "Android 11 vagy újabb szükséges."
                } else {
                    "Nincs engedély: Minden fájl kezelése."
                }
                backupState.update { it.copy(available = false, message = message) }
                return@withContext backupState.value
            }
            try {
                val manager = BackupManager(storage.root, backupFolder())
                val dirs = listOf("log", "notes")
                val restore = manager.restore(dirs, ::backupLock)
                if (restore.changedLocal) {
                    log("Adatmentés: ${restore.restored} fájl visszaállítva, ${restore.merged} egyesítve a mentésből.")
                    postFeed("Adatmentés", "${restore.restored} fájl visszaállítva, ${restore.merged} egyesítve a mentésből.", FeedLevel.OK)
                }
                val mirror = manager.mirror(dirs, ::backupLock)
                val errors = restore.errors + mirror.errors
                backupWasAvailable = true
                backupState.value = BackupStatus(
                    available = true,
                    lastMs = System.currentTimeMillis(),
                    lastCopied = mirror.copied,
                    message = if (errors.isEmpty()) "" else "Hiba: " + errors.first(),
                )
                if (errors.isNotEmpty()) {
                    log("Adatmentés hiba ($reason): ${errors.joinToString("; ")}")
                    postFeed("Adatmentés", "Hiba: ${errors.first()}", FeedLevel.ERROR)
                } else if (!quiet) {
                    log("Adatmentés ($reason): ${mirror.copied} fájl frissítve -> ${backupFolder().absolutePath}")
                    postFeed("Adatmentés", "${mirror.copied} fájl frissítve (Dokumentumok/NetworkTools).", FeedLevel.OK)
                }
            } catch (e: Exception) {
                backupState.update { it.copy(message = "Hiba: ${e.message}") }
                log("Adatmentés sikertelen ($reason): ${e.message}")
                postFeed("Adatmentés", "Sikertelen: ${e.message}", FeedLevel.ERROR)
            }
            backupState.value
        }
    }

    /** Kézi mentés/visszaállítás a Beállításokból. */
    suspend fun backupNow(): BackupStatus = runBackupCycle("kézi", quiet = false)

    /** Az Activity előtérbe kerülésekor: ha közben megadták (vagy visszavonták) a hozzáférést, azonnal reagál. */
    fun onAppResumed() {
        backupScope.launch {
            if (backupAccessGranted() != backupWasAvailable) runBackupCycle("hozzáférés-változás", quiet = false)
        }
    }

    /** Háttérbe kerüléskor: a legfrissebb állapot mentése (Dokumentumok/NetworkTools). */
    fun onAppStopped() {
        backupScope.launch { runBackupCycle("háttérbe kerülés", quiet = true) }
    }

    // ==================================================================================
    // Naplófájlok a telefonon
    // ==================================================================================

    /** A telefonon lévő naplófájlok listájának frissítése (a Beállítások mutatja). */
    fun refreshLogFiles() {
        storageScope.launch {
            val files = storage.logDir.listFiles { f -> f.isFile && LOG_NAME.matches(f.name) }
                ?.map { LogFileInfo(it.name, it.length(), it.lastModified()) }
                ?.sortedByDescending { it.name } ?: emptyList()
            logFilesState.value = files
        }
    }

    /**
     * Naplófájl törlése a telefonról (a Dokumentumok/NetworkTools mentésből is - különben a mentés visszahozná).
     * A már máshová (fájlba, e-mailbe) elmentett példányokat nem érinti.
     */
    fun deleteLogFile(name: String) {
        if (!LOG_NAME.matches(name)) return
        storageScope.launch {
            synchronized(logStore) { File(storage.logDir, name).delete() }
            deleteBackupCopy("log/$name")
            log("Naplófájl törölve: $name")
            postFeed("Napló", "Törölve: $name", FeedLevel.INFO)
            refreshLogFiles()
        }
    }

    /** A Dokumentumok/NetworkTools mentésben lévő másolat törlése (ha van hozzáférés), hogy a visszaállítás ne hozza vissza. */
    private fun deleteBackupCopy(relativePath: String) {
        try {
            if (backupAccessGranted()) File(backupFolder(), relativePath).delete()
        } catch (e: Exception) {
            log("A mentés-másolat törlése sikertelen ($relativePath): ${e.message}")
        }
    }

    // ==================================================================================
    // Jegyzetek (egy jegyzet = egy .md fájl: cím, dátum, tartalom)
    // ==================================================================================

    /** Új jegyzet ([id] == null) vagy a meglévő módosítása (a létrehozás dátuma megmarad). */
    suspend fun saveNote(id: String?, title: String, content: String): NoteItem = noteMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = id?.let { key -> noteState.value.firstOrNull { it.id == key } }
        val note = NoteItem(
            id = existing?.id ?: NoteStore.newId(now),
            title = title.trim(),
            createdMs = existing?.createdMs ?: now,
            content = content.trim(),
        )
        withContext(storageDispatcher) { noteStore.save(note) }
        noteState.value = withContext(storageDispatcher) { noteStore.loadAll() }
        log("Jegyzet mentve: \"${note.title}\" (${note.id}.md)")
        note
    }

    suspend fun deleteNote(id: String) = noteMutex.withLock {
        val note = noteState.value.firstOrNull { it.id == id } ?: return@withLock
        withContext(storageDispatcher) { noteStore.delete(id) }
        deleteBackupCopy("notes/$id.md")
        noteState.value = withContext(storageDispatcher) { noteStore.loadAll() }
        log("Jegyzet törölve: \"${note.title}\"")
    }

    // ==================================================================================
    // Belső lépések
    // ==================================================================================

    private fun addLogLine(message: String) {
        val time = LocalTime.now().format(TIME_FORMAT)
        logState.update { (it + "[$time] $message").takeLast(MAX_LOG_LINES) }
    }

    private fun detectInstall(): InstallInfo {
        val meta = appContext.getSharedPreferences("networktools_meta", Context.MODE_PRIVATE)
        val info = InstallInfo.detect(
            storedVersionCode = meta.getInt("last_version_code", -1),
            storedVersionName = meta.getString("last_version_name", null),
            currentVersionCode = BuildConfig.VERSION_CODE,
            currentVersionName = BuildConfig.VERSION_NAME,
        )
        meta.edit()
            .putInt("last_version_code", BuildConfig.VERSION_CODE)
            .putString("last_version_name", BuildConfig.VERSION_NAME)
            .apply()
        return info
    }

    override fun onCleared() {
        crypto.close()
        backupScope.cancel()
        storageScope.cancel()
        writer.close { storageExecutor.shutdown() }
        super.onCleared()
    }

    private companion object {
        const val MAX_LOG_LINES = 400
        val LOG_NAME = Regex("""networktools-\d{4}-\d{2}-\d{2}\.log""")
        const val MAX_FEED_ENTRIES = 300
        const val BACKUP_INTERVAL_MS = 30L * 60L * 1000L
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
