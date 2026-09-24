// Verzio: v0.6.2 - 2026-09-24
package hu.lordathis.networktools.engine

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import hu.lordathis.networktools.BuildConfig
import hu.lordathis.networktools.crypto.CryptoService
import hu.lordathis.networktools.network.ConnectionKind
import hu.lordathis.networktools.network.NetworkForcer
import hu.lordathis.networktools.network.NetworkIdentity
import hu.lordathis.networktools.profiles.NetworkProfile
import hu.lordathis.networktools.profiles.ProfileDevice
import hu.lordathis.networktools.profiles.ProfileMatch
import hu.lordathis.networktools.profiles.ProfileStore
import hu.lordathis.networktools.settings.AppPreferences
import hu.lordathis.networktools.storage.AppStorage
import hu.lordathis.networktools.storage.BackupManager
import hu.lordathis.networktools.storage.BackupStatus
import hu.lordathis.networktools.storage.ExportHistoryEntry
import hu.lordathis.networktools.storage.ExportHistoryStore
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.withTimeoutOrNull
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
 *   Profilok         ProfileStore   hálózatonkénti (SSID+BSSID) eszköz/jelszó-regiszter (profiles/)
 *   Gyors ellenőrzés QuickCheck     induláskor / hálózatváltáskor - lásd [quickCheck]
 *   Teszt-motor      TestEngine     a mély hálózati tesztek (F1/F2/F3) - lásd [testJobs], [startTest]
 *   Visszajelzések   feed           a Kezdőlap sávja: app-események és a tesztek eredményei
 *   Menü             a UI-ban: a fiók-állapot szinkron
 *
 * HÁTTÉRFELADATOK (a tesztek) SZABÁLYA: a feladat ITT, a hub SAJÁT, hosszú életű scope-jában fut (nem az
 * Activity-ben, nem a képernyő kompozíciójában), ezért a képernyőváltás, a fiókok, a panelek megnyitása/
 * zárása NEM állítja le és NEM szakítja meg. A részeredményeket a [postFeed]-del és a [TestEngine.jobs]
 * StateFlow-val közli: a Kezdőlap/VISSZAJELZÉSEK panel folyamatosan mutatja, amíg fut is.
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

    // --- Hálózati profilok (SSID+BSSID -> eszközlista, jelszavak) ---------------------------------------
    private val profileStore = ProfileStore(storage.profilesDir)
    private val profileMutex = Mutex()
    private val profilesState = MutableStateFlow<List<NetworkProfile>>(emptyList())
    val profiles: StateFlow<List<NetworkProfile>> = profilesState.asStateFlow()

    // --- Gyors ellenőrzés (induláskor / hálózatváltáskor) + a mély teszt-motor ------------------------
    private val networkIdentity = NetworkIdentity(appContext)
    private val quickCheckRunner = QuickCheckRunner(networkIdentity, profileStore)
    private val quickCheckState = MutableStateFlow(QuickCheckState())
    val quickCheck: StateFlow<QuickCheckState> = quickCheckState.asStateFlow()

    private val quickReportStore = QuickReportStore(storage.profilesDir)
    private val quickReportState = MutableStateFlow<List<TestRunSummary>>(emptyList())
    /** A Gyorsjelentés alsó kerete: csak a JELENLEGI hálózathoz tartozó teszt-összefoglalók. */
    val quickReportForCurrentNetwork: StateFlow<List<TestRunSummary>> = quickReportState.asStateFlow()

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val testEngine = TestEngine(
        testLogDir = storage.testLogDir,
        quickReportStore = quickReportStore,
        identity = networkIdentity,
        prefs = prefs,
        scope = testScope,
        currentNetworkKey = { quickCheckState.value.networkKey },
        currentNetworkLogName = { quickCheckState.value.networkLogName },
        onAppLog = { log(it) },
    )
    val testJobs: StateFlow<List<TestJob>> = testEngine.jobs

    private val lastAutoRunState = MutableStateFlow(prefs.lastAutoTestsRunMs)
    /** Az automatikus tesztek legutóbbi lefutása - a jobb fiók Gyorsjelentés ikonjának "friss" jelzéséhez. */
    val lastAutoRunMs: StateFlow<Long> = lastAutoRunState.asStateFlow()

    /** Az auto-teszt időzítő felébresztése (beállítás-változáskor). CONFLATED: csak a legutolsó jelzés számít. */
    private enum class AutoWake { RUN_NOW, RESCHEDULE }
    private val autoTestsWake = Channel<AutoWake>(Channel.CONFLATED)
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // --- Mentés-előzmény (a Sync/Mentés panel alsó listája) ---------------------------------------------
    private val exportHistoryStore = ExportHistoryStore(File(storage.root, "export_history.csv"))
    private val exportHistoryState = MutableStateFlow<List<ExportHistoryEntry>>(emptyList())
    val exportHistory: StateFlow<List<ExportHistoryEntry>> = exportHistoryState.asStateFlow()

    // --- WiFi/mobilnet "erre kényszerítés" (a házikó melletti ikonok) -----------------------------------
    private val forcedTransportState = MutableStateFlow(
        runCatching { ConnectionKind.valueOf(prefs.forcedTransport) }.getOrNull()
    )
    val forcedTransport: StateFlow<ConnectionKind?> = forcedTransportState.asStateFlow()

    fun setForcedTransport(kind: ConnectionKind?) {
        forcedTransportState.value = kind
        prefs.forcedTransport = kind?.name ?: "NONE"
        NetworkForcer.apply(appContext, kind)
        postFeed(
            "Hálózat",
            if (kind == null) "Hálózat-kényszerítés kikapcsolva." else "Forgalom erre kényszerítve: $kind",
            FeedLevel.INFO,
        )
    }

    fun openSystemNetworkToggle(kind: ConnectionKind) {
        NetworkForcer.openSystemToggle(appContext, kind)
    }

    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                // A VISSZAJELZÉSEK panel állandó LOG-ja a korábbi (akár tegnapi) indítások sorait is
                // mutassa, nem csak a mai napét - ezért itt readAll() (minden megmaradt napi fájl), nem
                // csak a mai nap loadTail()-je.
                val tail = logStore.readAll().lines().filter { it.isNotBlank() }.takeLast(MAX_LOG_LINES)
                logState.update { (tail + it).takeLast(MAX_LOG_LINES) }
                noteState.value = noteStore.loadAll()
                profilesState.value = profileStore.loadAll()
                exportHistoryState.value = exportHistoryStore.loadAll()
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

        // Induláskori GYORS ellenőrzés: milyen hálózat(ok) érhetők el, ismerős-e, hány élő eszköz -
        // azonnal a Kezdőlapra és a Gyorsjelentésbe. Utána a hálózatváltásokat egy NetworkCallback figyeli.
        testScope.launch {
            delay(800)
            runQuickCheck()
            registerNetworkWatcher()
            runArpSpikeIfNeeded()
            runAutoTestsLoop()
        }
    }

    /**
     * Az egyszerű, gyors tesztek (lásd [TestCatalog.autoTests]) induláskori + időzített futtatása.
     * A köz a beállított érték (5-120 perc), de ha az összes auto-teszt együttes ideje ennél tovább
     * tartana, a köz automatikusan az összidő + 5 percre nő (biztonsági ráhagyás, hogy ne fusson
     * egymásba két kör). A jelenlegi auto-tesztek együttes ideje a gyakorlatban pár másodperc, ezért
     * ez a korlát a mostani készlettel nem szokott érvénybe lépni - de jövőbeli, lassabb auto-tesztnél igen.
     */
    private suspend fun runAutoTestsLoop() {
        // Az elso kor induláskor mindig lefut (ha be van kapcsolva).
        var runNow = true
        while (currentCoroutineContext().isActive) {
            val enabled = prefs.autoTestsEnabled
            val intervalMs = effectiveAutoIntervalMinutes() * 60_000L
            val now = System.currentTimeMillis()
            val last = prefs.lastAutoTestsRunMs
            val due = runNow || last <= 0L || now - last >= intervalMs
            runNow = false
            if (enabled && due) {
                val tests = TestCatalog.autoTests(appContext)
                prefs.lastAutoTestsRunMs = System.currentTimeMillis()
                lastAutoRunState.value = prefs.lastAutoTestsRunMs
                val next = LocalDateTime.now().plusSeconds(intervalMs / 1000).format(HHMM)
                log("Automatikus tesztkör indul (${tests.size} teszt, köz: ${effectiveAutoIntervalMinutes()} perc, következő: $next).")
                for (def in tests) {
                    testEngine.start(def.id, def.name, def.shortCode)
                    delay(250) // enyhe ütemezés, hogy ne induljon mind egyszerre
                }
            }
            // Várakozás a következő esedékességig - DE a beállítás változása (BE/KI, új köz) azonnal felébreszti,
            // igy nem kell a régi (pl. 15 perces) várakozás végét kivárni.
            val signal = if (!prefs.autoTestsEnabled) {
                autoTestsWake.receive() // kikapcsolva: csak beállítás-változásra ébred
            } else {
                val remaining = prefs.lastAutoTestsRunMs + intervalMs - System.currentTimeMillis()
                withTimeoutOrNull(remaining.coerceAtLeast(1_000L)) { autoTestsWake.receive() }
            }
            if (signal == AutoWake.RUN_NOW) runNow = true
        }
    }

    private fun effectiveAutoIntervalMinutes(): Int {
        val estimatedTotalSeconds = TestCatalog.autoTests(appContext).size * 3 // óvatos, felülbecsült egyedi idő
        val safetyMinutes = (estimatedTotalSeconds / 60) + 5
        return maxOf(prefs.autoTestsIntervalMinutes, safetyMinutes)
    }

    /** Automatikus tesztek BE/KI - a UI hívja. Bekapcsoláskor azonnal lefut egy kör. */
    fun setAutoTestsEnabled(enabled: Boolean) {
        prefs.autoTestsEnabled = enabled
        log("Automatikus tesztek: " + if (enabled) "BE (egy kör most indul)" else "KI")
        autoTestsWake.trySend(if (enabled) AutoWake.RUN_NOW else AutoWake.RESCHEDULE)
    }

    /** Új ismétlési köz mentése - a UI MENTÉS gombja hívja. Az időzítő azonnal az új közzel számol. */
    fun setAutoTestsInterval(minutes: Int): Int {
        val value = minutes.coerceIn(5, 120)
        prefs.autoTestsIntervalMinutes = value
        val effective = effectiveAutoIntervalMinutes()
        val extra = if (effective != value) " (a tesztek hossza miatt ténylegesen $effective perc)" else ""
        log("Automatikus tesztek ismétlési köze mentve: $value perc$extra.")
        autoTestsWake.trySend(AutoWake.RESCHEDULE)
        return value
    }

    /** Egy NetworkCallback, ami a kapcsolat VÁLTOZÁSAKOR (nem időzítve!) újrafuttatja a gyors ellenőrzést. */
    private fun registerNetworkWatcher() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                postFeed("Hálózat", "Új kapcsolat elérhető.", FeedLevel.INFO)
                testScope.launch { runQuickCheck() }
            }

            override fun onLost(network: Network) {
                postFeed("Hálózat", "Egy kapcsolat megszűnt.", FeedLevel.WARN)
                testScope.launch { runQuickCheck() }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                testScope.launch { runQuickCheck(deepSweep = false) }
            }
        }
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            log("Hálózat-figyelő regisztrálása sikertelen: ${e.message}")
        }
    }

    /**
     * A gyors ellenőrzés futtatása/frissítése. Ismeretlen hálózatnál (vagy azonos SSID-jű, de eltérő
     * BSSID-jű "ütközésnél") automatikusan létrejön egy profil-csonk - lásd [ProfileStore.match].
     * [deepSweep]: fusson-e a gyors élő-host-számlálás is (kapcsolat-JELLEMZŐ változásnál nem kell újra).
     */
    private suspend fun runQuickCheck(deepSweep: Boolean = true) {
        quickCheckState.update { it.copy(running = true) }
        val existing = profilesState.value
        val result = quickCheckRunner.run(existing, deepSweep = deepSweep)
        if (result.match is ProfileMatch.SsidCollision) {
            postFeed(
                "Hálózat",
                "Azonos nevű (\"${result.wifiIdentity?.ssid}\"), de MÁS hálózat, mint egy elmentett profilod - új, önálló profil jön létre.",
                FeedLevel.WARN,
            )
        }
        val finalResult = if (result.match !is ProfileMatch.Exact && result.wifiIdentity != null) {
            profileMutex.withLock {
                val now = System.currentTimeMillis()
                val created = profileStore.newProfile(result.wifiIdentity.ssid, result.wifiIdentity.bssid, now)
                val updated = profilesState.value + created
                profilesState.value = updated
                withContext(storageDispatcher) { profileStore.saveAll(updated) }
                log("Új hálózati profil létrehozva: ${created.shortLabel()}")
                result.copy(activeProfile = created)
            }
        } else {
            result
        }
        quickCheckState.value = finalResult
        quickReportState.value = quickReportStore.forNetwork(finalResult.networkKey)
        if (finalResult.activeProfile != null && result.match is ProfileMatch.Exact) {
            postFeed("Hálózat", "Ismert hálózat: ${finalResult.activeProfile.displayName}", FeedLevel.OK)
        }
    }

    /** EGYSZERI próba: olvasható-e a /proc/net/arp - a következő indításkor már nem fut le újra. */
    private suspend fun runArpSpikeIfNeeded() {
        if (prefs.arpSpikeRan) return
        log("ARP-tábla olvashatósági próba indul (egyszeri teszt)...")
        val def = TestCatalog.arpSpike(appContext)
        testEngine.start(def.id, def.name, def.shortCode)
        prefs.arpSpikeRan = true
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
        }.onSuccess {
            log("${kind.label} elmentve ($it bejegyzés).")
            exportHistoryStore.append(kind.label, "Fájl (választott hely)", "$it bejegyzés")
            exportHistoryState.value = exportHistoryStore.loadAll()
        }
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

    private fun backupLock(dir: String): Any = when (dir) {
        "notes" -> noteStore
        "profiles" -> profileStore
        else -> logStore
    }

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
                val dirs = listOf("log", "notes", "profiles")
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
    // Hálózati profilok (SSID+BSSID -> eszközlista) - a Floppy-panel (mentés/törlés/betöltés/
    // inaktiválás UI) egy következő körben készül, de az alap-műveletek innen már elérhetők.
    // ==================================================================================

    fun renameProfile(id: String, nick: String) {
        testScope.launch {
            profileMutex.withLock {
                val updated = profilesState.value.map {
                    if (it.id == id) it.copy(nick = nick.trim(), updatedMs = System.currentTimeMillis()) else it
                }
                profilesState.value = updated
                withContext(storageDispatcher) { profileStore.saveAll(updated) }
                log("Profil átnevezve: \"$nick\"")
            }
        }
    }

    fun setProfileActive(id: String, active: Boolean) {
        testScope.launch {
            profileMutex.withLock {
                val updated = profilesState.value.map {
                    if (it.id == id) it.copy(active = active, updatedMs = System.currentTimeMillis()) else it
                }
                profilesState.value = updated
                withContext(storageDispatcher) { profileStore.saveAll(updated) }
            }
        }
    }

    /** Eszköz felvétele/frissítése egy profilban. A [password]/[account] itt még NYERS szöveg - itt titkosítjuk. */
    fun upsertProfileDevice(
        profileId: String,
        mac: String,
        deviceName: String,
        nick: String,
        category: String,
        account: String,
        password: String,
        notes: String,
    ) {
        testScope.launch {
            profileMutex.withLock {
                val now = System.currentTimeMillis()
                val accountEnc = if (account.isEmpty()) "" else profileStore.encryptSecret(account)
                val passwordEnc = if (password.isEmpty()) "" else profileStore.encryptSecret(password)
                val updated = profilesState.value.map { profile ->
                    if (profile.id != profileId) return@map profile
                    val existing = profile.devices.firstOrNull { it.mac == mac }
                    val device = ProfileDevice(
                        mac = mac,
                        deviceName = deviceName,
                        nick = nick,
                        category = category,
                        accountEnc = accountEnc,
                        passwordEnc = passwordEnc,
                        notes = notes,
                        testRefs = existing?.testRefs ?: emptyList(),
                        firstSeenMs = existing?.firstSeenMs ?: now,
                        lastSeenMs = now,
                    )
                    profile.copy(
                        devices = profile.devices.filterNot { it.mac == mac } + device,
                        updatedMs = now,
                    )
                }
                profilesState.value = updated
                withContext(storageDispatcher) { profileStore.saveAll(updated) }
                log("Eszköz mentve a profilban: $deviceName ($mac)")
            }
        }
    }

    /** Egy eszköz jelszó/fiók mezőjének VISSZAFEJTETT értéke - csak megjelenítéshez, óvatosan hívandó. */
    fun revealDeviceSecret(encrypted: String): String = profileStore.decryptSecret(encrypted)

    fun deleteProfileDevice(profileId: String, mac: String) {
        testScope.launch {
            profileMutex.withLock {
                val updated = profilesState.value.map { profile ->
                    if (profile.id == profileId) profile.copy(devices = profile.devices.filterNot { it.mac == mac }) else profile
                }
                profilesState.value = updated
                withContext(storageDispatcher) { profileStore.saveAll(updated) }
            }
        }
    }

    // ==================================================================================
    // Hálózati tesztek (F1/F2/F3) - lásd [TestCatalog], [TestEngine]
    // ==================================================================================

    fun startTest(testId: String) {
        val def = TestCatalog.find(appContext, testId) ?: run {
            log("Ismeretlen teszt-azonosító: $testId")
            return
        }
        testEngine.start(def.id, def.name, def.shortCode)
    }

    fun isTestRunning(testId: String): Boolean = testEngine.isRunning(testId)

    fun clearFinishedTests() = testEngine.clearFinished()

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
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        testScope.cancel()
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
