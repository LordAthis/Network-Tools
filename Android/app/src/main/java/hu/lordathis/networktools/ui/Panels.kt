// Verzio: v0.4.1 - 2026-09-21
package hu.lordathis.networktools.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import hu.lordathis.networktools.engine.JobStatus
import hu.lordathis.networktools.engine.QuickCheckState
import hu.lordathis.networktools.engine.TestJob
import hu.lordathis.networktools.engine.TestRunSummary
import hu.lordathis.networktools.network.ConnectionKind
import hu.lordathis.networktools.profiles.ProfileMatch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// "Stripe" panel (közös panel-minta): a Kezdőlap doboza és a jobb fiók
// valamennyi almenüje (Beállítások/Napló/Névjegy/Gyorsjelentés/Webolvasó), valamint a bal fiók
// gombjainak panelei ugyanezt a vizuális/interakciós mintát használják. A panel felső (és
// opcionálisan alsó) szélén egy KICSI, középre igazított, a feliratához igazodó méretű, pirula
// alakú gomb van (Megnyitás / Mentés / Mégsem).
// ---------------------------------------------------------------------------

internal data class StripeSpec(
    val label: String,
    val enabled: Boolean,
    val onClick: (() -> Unit)?,
)

/** Kicsi, a feliratához igazodó méretű, pirula alakú gomb (a panelek "stripe"-ja és a Mentés/Mégsem gombok). */
@Composable
internal fun PillButton(label: String, enabled: Boolean, filled: Boolean = true, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(50)
    val base = Modifier.clip(shape)
    val styled = if (filled) {
        base.background(if (enabled) Accent.copy(alpha = 0.9f) else Accent.copy(alpha = 0.28f))
    } else {
        base.border(1.5.dp, Accent.copy(alpha = if (enabled) 0.8f else 0.3f), shape)
    }
    Box(
        modifier = styled
            .then(if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (filled) StripeTextColor else Accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun Stripe(spec: StripeSpec) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        PillButton(spec.label, spec.enabled, filled = true, onClick = spec.onClick)
    }
}

@Composable
internal fun StripedPanel(
    modifier: Modifier = Modifier,
    topStripe: StripeSpec? = null,
    bottomStripe: StripeSpec? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelBg)
            .border(2.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (topStripe != null) {
                Stripe(topStripe)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(10.dp),
                content = content
            )
            if (bottomStripe != null) {
                Stripe(bottomStripe)
            }
        }
    }
}

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm")

/** Idő (ms) olvasható dátum+óra formában (a helyi időzónában). */
internal fun formatDateTime(ms: Long): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * GYORS ELLENŐRZÉS kártya: induláskor / hálózatváltáskor frissül, és amíg a hálózat/kapcsolat él,
 * VÁLTOZATLANUL a paneljén marad - mély kutatási adat ide sosem kerül (ld. a felhasználói kérés:
 * "a gyors ellenőrzés marad a paneljén, oda mély kutatási információk nem kerülnek").
 */
@Composable
internal fun QuickCheckCard(state: QuickCheckState) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg, shape)
            .border(1.dp, Accent.copy(alpha = 0.4f), shape)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GYORS ELLENŐRZÉS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (state.running) Text("frissítés...", color = TextDim, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (state.connections.isEmpty()) {
            Text("Nincs aktív hálózati kapcsolat.", color = WarnColor, fontSize = 11.sp)
        } else {
            val kinds = state.connections.map { it.kind }.distinct()
            val typeText = when {
                kinds.size > 1 -> "vegyes (" + kinds.joinToString(" + ") { transportLabel(it) } + ")"
                else -> transportLabel(kinds.first())
            }
            Text("Kapcsolat: $typeText", color = TextMain, fontSize = 12.sp)
            val networkLabel = when {
                state.match is ProfileMatch.Exact -> "Ismert hálózat: ${state.activeProfile?.displayName}"
                state.wifiIdentity != null -> "Új/ismeretlen hálózat: ${state.wifiIdentity.ssid ?: state.networkLogName}"
                else -> null
            }
            if (networkLabel != null) Text(networkLabel, color = AccentBlue, fontSize = 11.sp)
            val count = state.quickDeviceCount
            if (count != null) Text("Gyors eszközszám: $count élő gép a hálózaton", color = TextMain, fontSize = 11.sp)
            Text(
                if (state.internetReachable == true) "Internet: elérhető" else "Internet: nem elérhető",
                color = if (state.internetReachable == true) Accent else DangerColor,
                fontSize = 11.sp,
            )
        }
    }
}

private fun transportLabel(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.WIFI -> "WiFi"
    ConnectionKind.CELLULAR -> "mobilnet"
    ConnectionKind.ETHERNET -> "vezetékes"
    ConnectionKind.VPN -> "VPN"
    ConnectionKind.OTHER -> "egyéb"
}

/** GYORSJELENTÉS keret: a legutóbbi tesztek összefoglalója, a JELENLEGI hálózatra szűrve. */
@Composable
internal fun QuickReportCard(summaries: List<TestRunSummary>) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg, shape)
            .border(1.dp, AccentBlue.copy(alpha = 0.4f), shape)
            .padding(10.dp),
    ) {
        Text("GYORSJELENTÉS", color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        if (summaries.isEmpty()) {
            Text("Ezen a hálózaton még nem futott teszt.", color = TextDim, fontSize = 11.sp)
        } else {
            summaries.sortedByDescending { it.finishedMs }.take(6).forEach { s ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.shortCode, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.headline, color = TextMain, fontSize = 11.sp)
                        Text(formatDateTime(s.finishedMs), color = TextDim, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

/** A tesztek terminál-szerű élő kimenete - lapfüllel, ha egyszerre több fut. */
@Composable
private fun TestTerminalArea(jobs: List<TestJob>) {
    val running = jobs.filter { it.status == JobStatus.RUNNING }
    var selected by remember { mutableStateOf<String?>(null) }
    val active = running.firstOrNull { it.jobId == selected } ?: running.firstOrNull()

    Column(modifier = Modifier.fillMaxSize()) {
        if (running.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nincs jelenleg futó teszt.", color = TextDim, fontSize = 11.sp)
            }
            return
        }
        if (running.size > 1) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                for (job in running) {
                    val isSelected = job.jobId == active?.jobId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) Accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { selected = job.jobId }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(job.shortCode, color = if (isSelected) Accent else TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (active != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(active.lines) { line -> Text(line, color = Accent, fontSize = 9.sp) }
            }
        }
    }
}

/**
 * Kezdőlap: fent a GYORS ELLENŐRZÉS és a GYORSJELENTÉS kártya (mindkettő állandó, hálózat-szűrt),
 * alatta a VISSZAJELZÉSEK terület, két részre osztva: felül (2/3) az éppen futó teszt(ek) élő,
 * terminál-szerű kimenete lapfüllel, alul (1/3) az állandó, görgethető LOG - mindkettő önállóan görgethető.
 */
@Composable
internal fun HomeStripedPanel(
    modifier: Modifier,
    quickCheck: QuickCheckState,
    quickReport: List<TestRunSummary>,
    logLines: List<String>,
    testJobs: List<TestJob>,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.scrollToItem(logLines.size - 1)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickCheckCard(quickCheck)
        QuickReportCard(quickReport)
        StripedPanel(
            modifier = Modifier.fillMaxWidth().weight(1f),
            topStripe = StripeSpec("VISSZAJELZÉSEK", enabled = false, onClick = null),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NetworkIcon(modifier = Modifier.align(Alignment.Center).size(160.dp).alpha(0.06f))
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(2f).fillMaxWidth()) { TestTerminalArea(testJobs) }
                    androidx.compose.material3.HorizontalDivider(color = Accent.copy(alpha = 0.25f))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                        if (logLines.isEmpty()) {
                            Text("Itt jelenik meg az állandó napló (indítás, mentés, tesztek stb.).", color = TextDim, fontSize = 10.sp)
                        } else {
                            LazyColumn(state = listState) {
                                items(logLines) { line -> Text(line, color = TextDim, fontSize = 9.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A jobb fiók "Gyorsjelentés" gombja: ugyanaz a két kártya, teljes képernyőn (a VISSZAJELZÉSEK terminál nélkül). */
@Composable
internal fun QuickReportFullStripedPanel(modifier: Modifier, quickCheck: QuickCheckState, quickReport: List<TestRunSummary>) {
    StripedPanel(modifier = modifier, topStripe = StripeSpec("GYORSJELENTÉS", enabled = false, onClick = null)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickCheckCard(quickCheck)
            QuickReportCard(quickReport)
        }
    }
}

/**
 * Funkció nélküli panel: csak az ELNEVEZÉS látszik (a bal fiók F1-F3 gombjai és a Gyorsjelentés).
 * Ha egy gomb funkciót kap, ezt a panelt kell lecserélni.
 */
@Composable
internal fun NameStripedPanel(modifier: Modifier, label: String) {
    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(label, enabled = false, onClick = null)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
internal fun LogStripedPanel(modifier: Modifier, logLines: List<String>, onSave: () -> Unit) {
    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec("NAPLÓ MENTÉSE", enabled = true, onClick = onSave)
    ) {
        if (logLines.isEmpty()) {
            Text("Még nincs naplóbejegyzés.", color = TextDim, fontSize = 11.sp)
        } else {
            LazyColumn {
                items(logLines) { line -> Text(line, color = Accent, fontSize = 9.sp) }
            }
        }
    }
}

@Composable
internal fun AboutStripedPanel(modifier: Modifier, versionName: String, dataFolder: String) {
    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec("NÉVJEGY", enabled = false, onClick = null)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("LordAthis / Network Tool's", color = AccentBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "A Network-Tools projekt Android változata: hálózati segédeszközök, a Windowsos és " +
                    "Linuxos scriptek mellé.",
                color = TextMain,
                fontSize = 11.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(
                    "Visszajelzések a Kezdőlapon (háttérfeladatok eredményei - fejlesztés alatt)",
                    "Webolvasó (egyszerűsített, beépített böngésző)",
                    "Napló, mentések, e-mailes küldés",
                    "Titkosítás (kulcs), adatmentés",
                    "Háttérben futás, értesítési hangok",
                ).forEach { Text("•  $it", color = TextMain, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Network Tool's v$versionName\n\n" +
                    "Adatmappa (napló):\n$dataFolder\n\n" +
                    "Android 11 fölött a fájlkezelők az Android/data mappát általában nem mutatják - " +
                    "a fájlokat a Beállítások > Mentési beállítások alatt el tudod menteni (pl. a Letöltések mappába).\n\n" +
                    "Adatmentés: az adatok másolata a telefon Dokumentumok/NetworkTools mappájában is megvan, ami az app " +
                    "eltávolítását túléli (Beállítások > Adatmentés).",
                color = TextDim,
                fontSize = 11.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Webolvasó: egyszerűsített, beépített böngésző (WebView): címsor, megnyitás, vissza / előre /
// újratöltés. Nincs lapfül, könyvjelző, letöltés-kezelés; csak http(s) oldalak nyílnak meg.
// ---------------------------------------------------------------------------

/** A beírt szövegből megnyitható cím: "https://" nélküli tartomány -> https, szóközös szöveg -> keresés. */
internal fun normalizeWebInput(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return t
    val looksLikeAddress = !t.contains(' ') && t.contains('.')
    return if (looksLikeAddress) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
}

@Composable
internal fun WebReaderStripedPanel(
    modifier: Modifier,
    startUrl: String,
    onUrlChange: (String) -> Unit,
    onLog: (String) -> Unit,
) {
    var input by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val open: () -> Unit = {
        val url = normalizeWebInput(input)
        if (url.isNotEmpty()) {
            input = url
            webView?.loadUrl(url)
            onUrlChange(url)
            onLog("Webolvasó: megnyitás: $url")
        }
    }

    // Vissza gomb: előbb az oldal előzményében lép vissza; ha nincs hova, a fő kezelő a Kezdőlapra visz.
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    // A panel elhagyásakor a WebView felszabadul (az utolsó cím megmarad, a következő megnyitáskor újratöltődik).
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec(
            label = "MEGNYITÁS",
            enabled = input.isNotBlank(),
            onClick = open
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("WEBOLVASÓ", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("https://...", fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { open() }),
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton("VISSZA", enabled = canGoBack, filled = false) { webView?.goBack() }
                Spacer(Modifier.size(8.dp))
                PillButton("ELŐRE", enabled = canGoForward, filled = false) { webView?.goForward() }
                Spacer(Modifier.size(8.dp))
                PillButton("ÚJRA", enabled = true, filled = false) { webView?.reload() }
            }
            Spacer(Modifier.height(6.dp))
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    color = Accent,
                    trackColor = Accent.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                if (url != null) {
                                    input = url
                                    onUrlChange(url)
                                }
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        if (startUrl.isNotBlank()) loadUrl(startUrl)
                    }
                },
                update = { view -> if (webView !== view) webView = view },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun appFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Accent.copy(alpha = 0.5f),
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextDim,
    cursorColor = Accent,
)

/** A fejléc: az app neve. */
@Composable
internal fun AppHeader() {
    Text(
        "Network Tool's",
        color = Accent,
        fontSize = 30.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        maxLines = 1,
        softWrap = false
    )
}
