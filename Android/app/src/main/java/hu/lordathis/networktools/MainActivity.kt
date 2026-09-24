// Verzio: v0.6.1 - 2026-09-24
package hu.lordathis.networktools

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import hu.lordathis.networktools.engine.AppHub
import hu.lordathis.networktools.engine.ExportKind
import hu.lordathis.networktools.engine.JobStatus
import hu.lordathis.networktools.engine.TestCatalog
import hu.lordathis.networktools.network.ConnectionKind
import hu.lordathis.networktools.notify.NotificationHelper
import hu.lordathis.networktools.service.BackgroundService
import hu.lordathis.networktools.ui.AboutStripedPanel
import hu.lordathis.networktools.ui.AppHeader
import hu.lordathis.networktools.ui.BgBottom
import hu.lordathis.networktools.ui.BgMid
import hu.lordathis.networktools.ui.BgTop
import hu.lordathis.networktools.ui.BottomBar
import hu.lordathis.networktools.ui.ConfirmDialog
import hu.lordathis.networktools.ui.DRAWER_PANEL_DP
import hu.lordathis.networktools.notes.NoteItem
import hu.lordathis.networktools.ui.DrawerScrim
import hu.lordathis.networktools.ui.EdgeDrawer
import hu.lordathis.networktools.ui.EdgeDrawerState
import hu.lordathis.networktools.ui.HomeStripedPanel
import hu.lordathis.networktools.ui.LEFT_DRAWER_PANEL_DP
import hu.lordathis.networktools.ui.LeftDrawerPanel
import hu.lordathis.networktools.ui.LogStripedPanel
import hu.lordathis.networktools.ui.NetworkToolsTheme
import hu.lordathis.networktools.ui.NotesStripedPanel
import hu.lordathis.networktools.ui.QuickReportFullStripedPanel
import hu.lordathis.networktools.ui.RightDrawerPanel
import hu.lordathis.networktools.ui.SettingsActions
import hu.lordathis.networktools.ui.SettingsStripedPanel
import hu.lordathis.networktools.ui.SettingsUiState
import hu.lordathis.networktools.ui.Skin
import hu.lordathis.networktools.ui.StarField
import hu.lordathis.networktools.ui.SyncStripedPanel
import hu.lordathis.networktools.ui.TextDim
import hu.lordathis.networktools.ui.WebReaderStripedPanel
import hu.lordathis.networktools.ui.WorkInProgressStripedPanel
import hu.lordathis.networktools.ui.drawerGestures
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Network Tool's - natív Android / Jetpack Compose kliens.
//
//   - Kezdőlap (HOME): fejléc + státusz + GYORS ELLENŐRZÉS kártya (állandó, hálózat-szűrt) +
//     GYORSJELENTÉS kártya (a legutóbbi tesztek összefoglalója, szintén hálózat-szűrt) + a
//     VISSZAJELZÉSEK terület (2/3 élő teszt-terminál lapfüllel, 1/3 állandó LOG).
//   - Két, szél-húzással (vagy a fogantyúra koppintással) nyitható fiók - a funkciók gombjai
//     KIZÁRÓLAG itt vannak:
//       Bal:  F1 · F2 · F3 (a "teljesen natívan megvalósítható" hálózati tesztek, csoportosítva)
//       Jobb: Jegyzet · Gyorsjelentés · Napló · Mentés · Sebességteszt · Külső szolgáltatások ·
//             Webolvasó · Beállítások · Névjegy
//   - Alul MINDIG egy 3-ikonos sor: [mobilnet+fogaskerék] [közép: nyíl/házikó] [WiFi+fogaskerék].
//     A közép ikon a Kezdőlapon lefelé mutató NYÍL (csak eredménnyel aktív -> Eredmények képernyő),
//     minden más képernyőn HÁZIKÓ (vissza a Kezdőlapra).
// ---------------------------------------------------------------------------

private enum class Screen {
    HOME, SETTINGS, LOG, ABOUT, NOTES, WEB_READER,
    QUICK_REPORT, SYNC, SPEED_TEST, EXTERNAL_SERVICES, RESULTS,
}

// A "Napló mentése" fájlválasztó alapértelmezett helye: a Letöltések mappa.
private const val DOWNLOADS_URI = "content://com.android.externalstorage.documents/document/primary%3ADownload"

class MainActivity : ComponentActivity() {

    private val hub: AppHub by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ha a háttérben futás be volt kapcsolva, az app indulásakor a szolgáltatás újraindul.
        if (hub.prefs.backgroundRun) {
            try {
                BackgroundService.start(this)
            } catch (e: Exception) {
                hub.log("A háttér-szolgáltatás indítása sikertelen: ${e.message}")
            }
        }
        setContent { NetworkToolsApp(hub = hub) }
    }

    override fun onResume() {
        super.onResume()
        hub.onAppResumed()
    }

    override fun onStop() {
        super.onStop()
        hub.onAppStopped()
    }
}

@Composable
private fun NetworkToolsApp(hub: AppHub) {
    val context = LocalContext.current
    val prefs = hub.prefs

    // A hub állapotai - a UI csak megfigyel.
    val logLines by hub.logLines.collectAsState()
    val logFiles by hub.logFiles.collectAsState()
    val notes by hub.notes.collectAsState()
    val quickCheck by hub.quickCheck.collectAsState()
    val quickReport by hub.quickReportForCurrentNetwork.collectAsState()
    val testJobs by hub.testJobs.collectAsState()
    val forcedTransport by hub.forcedTransport.collectAsState()
    val exportHistory by hub.exportHistory.collectAsState()
    val testGroups = remember { TestCatalog.groups(context) }

    // Fiókok: a nyitottság a panel szélességéhez viszonyított, szinkron állapot.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val panelWidthPx = with(density) { DRAWER_PANEL_DP.dp.toPx() }
    val leftDrawer = remember(panelWidthPx) { EdgeDrawerState(scope, panelWidthPx) }
    val rightDrawer = remember(panelWidthPx) { EdgeDrawerState(scope, panelWidthPx) }
    val closeDrawers: () -> Unit = {
        leftDrawer.close()
        rightDrawer.close()
    }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var skin by remember { mutableStateOf(runCatching { Skin.valueOf(prefs.skin) }.getOrDefault(Skin.SYSTEM)) }
    var webReaderUrl by rememberSaveable { mutableStateOf("") }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    var pendingDeleteNote by remember { mutableStateOf<NoteItem?>(null) }
    var pendingDeleteLog by remember { mutableStateOf<String?>(null) }

    // A Beállítások megnyitásakor a telefonon lévő naplófájlok listája frissül.
    LaunchedEffect(screen) {
        if (screen == Screen.SETTINGS || screen == Screen.SYNC) hub.refreshLogFiles()
    }

    // ------------------------------------------------------------------ Fájl mentése (napló / jegyzet)
    var pendingExport by rememberSaveable { mutableStateOf<ExportKind?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val kind = pendingExport
        val uri = result.data?.data
        pendingExport = null
        if (kind != null && result.resultCode == Activity.RESULT_OK && uri != null) {
            scope.launch {
                val message = hub.export(kind, uri).fold(
                    onSuccess = { count -> "${kind.label} elmentve ($count bejegyzés)." },
                    onFailure = { e -> "A mentés sikertelen: ${e.message}" }
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val startExport: (ExportKind) -> Unit = { kind ->
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = kind.mimeType
            putExtra(Intent.EXTRA_TITLE, "${kind.fileStem}-$stamp.${kind.extension}")
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DOWNLOADS_URI))
        }
        pendingExport = kind
        exportLauncher.launch(intent)
    }

    // ------------------------------------------------------------------ E-mail
    var emailRecipients by remember { mutableStateOf(prefs.emailRecipients) }
    var emailSelected by remember { mutableStateOf(prefs.emailSelected) }
    var emailInput by remember { mutableStateOf("") }
    var emailConfirmedSet by remember { mutableStateOf(prefs.emailConfirmed) }
    val emailCurrent = if (emailSelected in emailRecipients) emailSelected else emailRecipients.firstOrNull().orEmpty()
    val emailValid = emailCurrent.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(emailCurrent).matches()
    val emailInputTrim = emailInput.trim()
    val emailInputValid = emailInputTrim.isNotEmpty() &&
        Patterns.EMAIL_ADDRESS.matcher(emailInputTrim).matches() &&
        emailRecipients.none { it.equals(emailInputTrim, ignoreCase = true) }

    val composeEmail: (String, String, File?) -> Unit = { subject, body, attachment ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailCurrent))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (attachment != null) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", attachment)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(attachment.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            context.startActivity(Intent.createChooser(intent, "E-mail küldése"))
        } catch (e: ActivityNotFoundException) {
            toast("Nincs telepített levelező alkalmazás.")
        }
    }
    val sendFileByEmail: suspend (ExportKind) -> Unit = { kind ->
        hub.prepareShareFile(kind).fold(
            onSuccess = { file -> composeEmail("Network Tool's - ${kind.label}", "A Network Tool's alkalmazásból küldve.", file) },
            onFailure = { e -> toast("A küldés nem indítható: ${e.message}") }
        )
    }
    val startEmail: (ExportKind) -> Unit = { kind ->
        if (!emailValid) {
            toast("Előbb adj meg érvényes címzettet (Beállítások > E-mail beállítások).")
        } else {
            scope.launch { sendFileByEmail(kind) }
        }
    }

    // ------------------------------------------------------------------ Titkosítás (kulcs)
    val backupStatus by hub.backupStatus.collectAsState()
    val cryptoState by hub.crypto.state.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var confirmKeyDelete by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------ Háttérben futás, értesítések
    var backgroundRun by remember { mutableStateOf(prefs.backgroundRun) }
    var notifSoundEnabled by remember { mutableStateOf(prefs.notificationSoundEnabled) }
    var notifSoundTitle by remember { mutableStateOf(NotificationHelper.soundTitle(context, prefs)) }
    var notifPermission by remember { mutableStateOf(NotificationHelper.hasPermission(context)) }
    var afterNotifPermission by remember { mutableStateOf<String?>(null) }

    fun applyBackgroundRun(enabled: Boolean) {
        backgroundRun = enabled
        prefs.backgroundRun = enabled
        try {
            if (enabled) BackgroundService.start(context) else BackgroundService.stop(context)
            hub.log("Háttérben futás: " + if (enabled) "BE" else "KI")
        } catch (e: Exception) {
            hub.log("A háttér-szolgáltatás módosítása sikertelen: ${e.message}")
            toast("A háttérben futás nem indítható: ${e.message}")
        }
    }

    fun runNotificationTest() {
        if (NotificationHelper.postTest(context, prefs)) {
            hub.log("Próba-értesítés elküldve.")
        } else {
            toast("Az értesítésekhez engedély kell.")
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermission = granted
        hub.log(if (granted) "Értesítési engedély megadva." else "Értesítési engedély megtagadva.")
        val next = afterNotifPermission
        afterNotifPermission = null
        if (granted) {
            if (next == "BACKGROUND") applyBackgroundRun(true)
            if (next == "TEST") runNotificationTest()
        } else if (next == "BACKGROUND") {
            applyBackgroundRun(true)
        }
    }
    fun requestNotifPermissionThen(next: String) {
        afterNotifPermission = next
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val soundPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.notificationSoundUri = picked?.toString() ?: ""
            NotificationHelper.rebuildAlertChannel(context, prefs)
            notifSoundTitle = NotificationHelper.soundTitle(context, prefs)
            hub.log("Értesítési hang kiválasztva: $notifSoundTitle")
        }
    }

    // ------------------------------------------------------------------ Hálózati beállítások (helyi tükrözés)
    var bluetoothEnabled by remember { mutableStateOf(prefs.bluetoothEnabled) }
    var portScanMode by remember { mutableStateOf(prefs.portScanMode) }
    var customPortList by remember { mutableStateOf(prefs.customPortList) }
    var scanConcurrency by remember { mutableIntStateOf(prefs.scanConcurrency) }
    var extraSubnets by remember { mutableStateOf(prefs.extraSubnets) }
    var externalApiKey by remember { mutableStateOf(prefs.externalApiKey) }
    var externalMcpServer by remember { mutableStateOf(prefs.externalMcpServer) }
    var autoTestsEnabled by remember { mutableStateOf(prefs.autoTestsEnabled) }
    var autoTestsIntervalMinutes by remember { mutableIntStateOf(prefs.autoTestsIntervalMinutes) }

    val hasResults = testJobs.any { it.status != JobStatus.RUNNING } || quickReport.isNotEmpty()

    // Vissza gomb: nyitott fiók bezárása, majd vissza a Kezdőlapra.
    BackHandler(enabled = leftDrawer.isOpen || rightDrawer.isOpen) { closeDrawers() }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    val statusText = "Háttérben futás: " + if (backgroundRun) "BE" else "KI"

    NetworkToolsTheme(skin) {
        Surface(color = BgBottom, modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))
                    .drawerGestures(leftDrawer, rightDrawer)
            ) {
                StarField(modifier = Modifier.fillMaxSize())

                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppHeader()
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "v${BuildConfig.VERSION_NAME}  |  $statusText",
                            color = TextDim,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                        )
                        Spacer(Modifier.height(6.dp))

                        when (screen) {
                            Screen.HOME -> {
                                HomeStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    quickCheck = quickCheck,
                                    quickReport = quickReport,
                                    logLines = logLines,
                                    testJobs = testJobs,
                                )
                            }

                            Screen.SETTINGS -> {
                                SettingsStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    ui = SettingsUiState(
                                        skin = skin,
                                        backgroundRun = backgroundRun,
                                        notifSoundEnabled = notifSoundEnabled,
                                        notifSoundTitle = notifSoundTitle,
                                        notifPermission = notifPermission,
                                        logFiles = logFiles,
                                        emailRecipients = emailRecipients,
                                        emailCurrent = emailCurrent,
                                        emailInput = emailInput,
                                        emailInputValid = emailInputValid,
                                        emailValid = emailValid,
                                        emailConfirmed = emailCurrent in emailConfirmedSet,
                                        crypto = cryptoState,
                                        keyInput = keyInput,
                                        keyVisible = keyVisible,
                                        backup = backupStatus,
                                        backupSdkOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                                        wifiForced = forcedTransport == ConnectionKind.WIFI,
                                        mobileForced = forcedTransport == ConnectionKind.CELLULAR,
                                        bluetoothEnabled = bluetoothEnabled,
                                        portScanMode = portScanMode,
                                        customPortList = customPortList,
                                        scanConcurrency = scanConcurrency,
                                        extraSubnets = extraSubnets,
                                        sshLoginEnabled = prefs.sshLoginEnabled,
                                        autoTestsEnabled = autoTestsEnabled,
                                        autoTestsIntervalMinutes = autoTestsIntervalMinutes,
                                        externalApiKey = externalApiKey,
                                        externalMcpServer = externalMcpServer,
                                    ),
                                    act = SettingsActions(
                                        onSkinChange = {
                                            skin = it
                                            prefs.skin = it.name
                                        },
                                        onBackgroundRunChange = { enabled ->
                                            if (enabled && !NotificationHelper.hasPermission(context)) {
                                                requestNotifPermissionThen("BACKGROUND")
                                            } else {
                                                applyBackgroundRun(enabled)
                                            }
                                        },
                                        onOpenBatterySettings = {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            } catch (e: Exception) {
                                                toast("A beállítás-oldal nem nyitható meg: ${e.message}")
                                            }
                                        },
                                        onNotifSoundEnabledChange = { enabled ->
                                            notifSoundEnabled = enabled
                                            prefs.notificationSoundEnabled = enabled
                                            NotificationHelper.rebuildAlertChannel(context, prefs)
                                            hub.log("Értesítési hang: " + if (enabled) "BE" else "KI")
                                        },
                                        onPickNotifSound = {
                                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Értesítési hang")
                                                putExtra(
                                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                                    NotificationHelper.soundUri(prefs)
                                                )
                                            }
                                            soundPickerLauncher.launch(intent)
                                        },
                                        onNotifTest = {
                                            if (NotificationHelper.hasPermission(context)) {
                                                runNotificationTest()
                                            } else {
                                                requestNotifPermissionThen("TEST")
                                            }
                                        },
                                        onExport = startExport,
                                        onEmailFile = startEmail,
                                        onLogFileDelete = { pendingDeleteLog = it },
                                        onEmailSelect = {
                                            emailSelected = it
                                            prefs.emailSelected = it
                                        },
                                        onEmailInputChange = { emailInput = it },
                                        onEmailAdd = {
                                            val list = emailRecipients + emailInputTrim
                                            emailRecipients = list
                                            prefs.emailRecipients = list
                                            if (emailSelected !in list || emailSelected.isEmpty()) {
                                                emailSelected = emailInputTrim
                                                prefs.emailSelected = emailInputTrim
                                            }
                                            emailInput = ""
                                        },
                                        onEmailDelete = { address ->
                                            val list = emailRecipients.filterNot { it == address }
                                            emailRecipients = list
                                            prefs.emailRecipients = list
                                            if (emailSelected == address) {
                                                emailSelected = list.firstOrNull().orEmpty()
                                                prefs.emailSelected = emailSelected
                                            }
                                            emailConfirmedSet = emailConfirmedSet - address
                                            prefs.emailConfirmed = emailConfirmedSet
                                        },
                                        onEmailTest = {
                                            composeEmail(
                                                "Network Tool's tesztlevél",
                                                "Ez egy tesztlevél a Network Tool's alkalmazásból. Ha megkaptad, az alkalmazásban " +
                                                    "(Beállítások > E-mail beállítások) jelöld meg: MEGÉRKEZETT.",
                                                null
                                            )
                                        },
                                        onEmailConfirm = {
                                            emailConfirmedSet = emailConfirmedSet + emailCurrent
                                            prefs.emailConfirmed = emailConfirmedSet
                                        },
                                        onKeyInputChange = { keyInput = it },
                                        onKeyVisibleChange = { keyVisible = it },
                                        onKeyAdd = {
                                            scope.launch {
                                                hub.crypto.setPassphrase(keyInput.trim()).fold(
                                                    onSuccess = { kid ->
                                                        toast("Kulcs beállítva (azonosító: $kid).")
                                                        keyInput = ""
                                                    },
                                                    onFailure = { e -> toast(e.message ?: "A kulcs beállítása sikertelen.") }
                                                )
                                            }
                                        },
                                        onKeyGenerate = {
                                            keyInput = hub.crypto.generatePassphrase()
                                            keyVisible = true
                                            toast("Kulcs generálva. Másold el, majd nyomd meg: KULCS HOZZÁADÁSA.")
                                        },
                                        onKeyCopy = {
                                            scope.launch {
                                                val key = keyInput.ifBlank { hub.crypto.revealPassphrase().orEmpty() }
                                                if (key.isBlank()) {
                                                    toast("Nincs kulcs, amit másolni lehetne.")
                                                } else {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Network Tool's kulcs", key))
                                                    toast("A kulcs a vágólapra másolva - használat után töröld a vágólapot.")
                                                }
                                            }
                                        },
                                        onKeyDelete = { confirmKeyDelete = true },
                                        onBackupGrant = {
                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                                toast("Az adatmentéshez Android 11 vagy újabb kell.")
                                            } else {
                                                try {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                            Uri.parse("package:" + context.packageName)
                                                        )
                                                    )
                                                } catch (e: Exception) {
                                                    try {
                                                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                                    } catch (e2: Exception) {
                                                        toast("A beállítás-oldal nem nyitható meg: ${e2.message}")
                                                    }
                                                }
                                            }
                                        },
                                        onBackupNow = {
                                            scope.launch {
                                                val status = hub.backupNow()
                                                toast(
                                                    if (status.available) "Mentés kész (${status.lastCopied} fájl frissült)."
                                                    else status.message
                                                )
                                            }
                                        },
                                        onWifiForcedChange = { forced ->
                                            hub.setForcedTransport(if (forced) ConnectionKind.WIFI else null)
                                        },
                                        onMobileForcedChange = { forced ->
                                            hub.setForcedTransport(if (forced) ConnectionKind.CELLULAR else null)
                                        },
                                        onBluetoothEnabledChange = {
                                            bluetoothEnabled = it
                                            prefs.bluetoothEnabled = it
                                        },
                                        onPortScanModeChange = {
                                            portScanMode = it
                                            prefs.portScanMode = it
                                        },
                                        onCustomPortListChange = {
                                            customPortList = it
                                            prefs.customPortList = it
                                        },
                                        onScanConcurrencyChange = {
                                            scanConcurrency = it
                                            prefs.scanConcurrency = it
                                        },
                                        onExtraSubnetsChange = {
                                            extraSubnets = it
                                            prefs.extraSubnets = it
                                        },
                                        onAutoTestsEnabledChange = {
                                            autoTestsEnabled = it
                                            hub.setAutoTestsEnabled(it)
                                        },
                                        onAutoTestsIntervalChange = {
                                            autoTestsIntervalMinutes = hub.setAutoTestsInterval(it)
                                        },
                                        onExternalApiKeyChange = {
                                            externalApiKey = it
                                            prefs.externalApiKey = it
                                        },
                                        onExternalMcpServerChange = {
                                            externalMcpServer = it
                                            prefs.externalMcpServer = it
                                        },
                                    )
                                )
                            }

                            Screen.NOTES -> {
                                NotesStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    notes = notes,
                                    notesFolder = hub.storage.notesDir.absolutePath,
                                    onSave = { id, title, content ->
                                        scope.launch {
                                            hub.saveNote(id, title, content)
                                            toast("Jegyzet mentve.")
                                        }
                                    },
                                    onDelete = { pendingDeleteNote = it }
                                )
                            }

                            Screen.LOG -> {
                                LogStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    logLines = logLines,
                                    onSave = { startExport(ExportKind.LOG) }
                                )
                            }

                            Screen.ABOUT -> {
                                AboutStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    versionName = BuildConfig.VERSION_NAME,
                                    dataFolder = hub.storage.root.absolutePath
                                )
                            }

                            Screen.QUICK_REPORT -> {
                                QuickReportFullStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    quickCheck = quickCheck,
                                    quickReport = quickReport,
                                )
                            }

                            Screen.SYNC -> {
                                SyncStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    recentLog = logLines.filter {
                                        it.contains("mentve", true) || it.contains("Mentés", true) ||
                                            it.contains("Adatmentés", true) || it.contains("napló", true)
                                    }.takeLast(40),
                                    history = exportHistory,
                                    logFiles = logFiles,
                                    onExportNow = { startExport(ExportKind.LOG) },
                                    onDeleteLogFile = { pendingDeleteLog = it },
                                )
                            }

                            Screen.SPEED_TEST -> {
                                WorkInProgressStripedPanel(modifier = Modifier.fillMaxWidth().weight(1f), title = "SEBESSÉGTESZT")
                            }

                            Screen.EXTERNAL_SERVICES -> {
                                WorkInProgressStripedPanel(modifier = Modifier.fillMaxWidth().weight(1f), title = "KÜLSŐ SZOLGÁLTATÁSOK")
                            }

                            Screen.RESULTS -> {
                                LogStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    logLines = testJobs.filter { it.status != JobStatus.RUNNING }
                                        .sortedByDescending { it.finishedMs ?: it.startedMs }
                                        .flatMap { listOf("=== ${it.label} (${it.shortCode}) ===") + it.lines },
                                    onSave = { startExport(ExportKind.LOG) }
                                )
                            }

                            Screen.WEB_READER -> {
                                WebReaderStripedPanel(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    startUrl = webReaderUrl,
                                    onUrlChange = { webReaderUrl = it },
                                    onLog = { hub.log(it) }
                                )
                            }
                        }
                    }

                    BottomBar(
                        isHome = screen == Screen.HOME,
                        hasResults = hasResults,
                        forcedTransport = forcedTransport,
                        wifiActive = quickCheck.connections.any { it.kind == ConnectionKind.WIFI },
                        mobileActive = quickCheck.connections.any { it.kind == ConnectionKind.CELLULAR },
                        onOpenWifiSettings = { hub.openSystemNetworkToggle(ConnectionKind.WIFI) },
                        onOpenMobileSettings = { hub.openSystemNetworkToggle(ConnectionKind.CELLULAR) },
                        onCenterClick = {
                            screen = if (screen == Screen.HOME) {
                                if (hasResults) Screen.RESULTS else Screen.HOME
                            } else {
                                Screen.HOME
                            }
                        },
                    )
                }

                // Sötétítő: nyitott fióknál a fiókon kívülre koppintva bezár.
                DrawerScrim(left = leftDrawer, right = rightDrawer, onDismiss = closeDrawers)

                EdgeDrawer(
                    alignEnd = false,
                    state = leftDrawer,
                    onHandleTap = {
                        rightDrawer.close()
                        leftDrawer.toggle()
                    },
                    panelWidth = LEFT_DRAWER_PANEL_DP.dp,
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    LeftDrawerPanel(
                        groups = testGroups,
                        jobs = testJobs,
                        onRun = { test -> hub.startTest(test.id) },
                    )
                }

                EdgeDrawer(
                    alignEnd = true,
                    state = rightDrawer,
                    onHandleTap = {
                        leftDrawer.close()
                        rightDrawer.toggle()
                    },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    RightDrawerPanel(
                        onNotes = {
                            screen = Screen.NOTES
                            rightDrawer.close()
                        },
                        onQuickAccess = {
                            screen = Screen.QUICK_REPORT
                            rightDrawer.close()
                        },
                        onLogOpen = {
                            screen = Screen.LOG
                            rightDrawer.close()
                        },
                        onSync = {
                            screen = Screen.SYNC
                            rightDrawer.close()
                        },
                        onSpeedTest = {
                            screen = Screen.SPEED_TEST
                            rightDrawer.close()
                        },
                        onExternalServices = {
                            screen = Screen.EXTERNAL_SERVICES
                            rightDrawer.close()
                        },
                        onWebReader = {
                            screen = Screen.WEB_READER
                            rightDrawer.close()
                        },
                        onSettings = {
                            screen = Screen.SETTINGS
                            rightDrawer.close()
                        },
                        onAbout = {
                            screen = Screen.ABOUT
                            rightDrawer.close()
                        }
                    )
                }

                // ------------------------------------------------------------ Párbeszédablakok
                pendingDeleteNote?.let { note ->
                    ConfirmDialog(
                        title = "Jegyzet törlése",
                        text = "Törlöd ezt: \"${note.title}\"? A .md fájl is törlődik, a törlés nem vonható vissza.",
                        confirmLabel = "TÖRLÉS",
                        onConfirm = {
                            pendingDeleteNote = null
                            scope.launch {
                                hub.deleteNote(note.id)
                                toast("Törölve.")
                            }
                        },
                        onDismiss = { pendingDeleteNote = null }
                    )
                }
                pendingDeleteLog?.let { name ->
                    ConfirmDialog(
                        title = "Naplófájl törlése",
                        text = "Törlöd a telefonról: $name? A Dokumentumok/NetworkTools mentésből is törlődik. " +
                            "A már máshová (fájlba, e-mailbe) elmentett példányok megmaradnak. A törlés nem vonható vissza.",
                        confirmLabel = "TÖRLÉS",
                        onConfirm = {
                            pendingDeleteLog = null
                            hub.deleteLogFile(name)
                        },
                        onDismiss = { pendingDeleteLog = null }
                    )
                }
                if (confirmKeyDelete) {
                    ConfirmDialog(
                        title = "Kulcs törlése",
                        text = "Ha törlöd a kulcsot, a titkosítás kikapcsol. Biztosan törlöd?",
                        confirmLabel = "TÖRLÉS",
                        onConfirm = {
                            confirmKeyDelete = false
                            hub.crypto.clearKey()
                            toast("Kulcs törölve - a titkosítás kikapcsolva.")
                        },
                        onDismiss = { confirmKeyDelete = false }
                    )
                }
            }
        }
    }
}
