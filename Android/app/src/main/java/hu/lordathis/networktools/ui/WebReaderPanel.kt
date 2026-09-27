// Verzio: v0.2.0 - 2026-09-28
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import hu.lordathis.networktools.engine.AddressEntry
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Webolvasó: egyszerűsített, beépített böngésző (WebView): címsor, megnyitás, vissza / előre /
// újratöltés; csak http(s) oldalak nyílnak meg.
// v0.1.12: felül, KÖZÉPRE igazítva három gomb:
//   - LISTA: a jelenlegi hálózat eszközei (összesített adatokkal) + a legutóbbi web-címek;
//   - KÖNYV: minden valaha elmentett cím (minden hálózatról) - törlés gombbal, visszakérdezéssel;
//   - SSH: leírás + a hálózatokon látott SSH-szolgáltatások és az SSH-t említő naplósorok.
// A listák a WebView FÖLÉ nyílnak (a betöltött oldal megmarad alattuk).
// ---------------------------------------------------------------------------

/** A beírt szövegből megnyitható cím: "https://" nélküli tartomány -> https, IP -> http, szóközös szöveg -> keresés. */
internal fun normalizeWebInput(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return t
    linkTarget(t)?.let { return it } // puszta IP(:port) -> http://
    val looksLikeAddress = !t.contains(' ') && t.contains('.')
    return if (looksLikeAddress) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
}

private enum class ReaderMode { WEB, LIST, BOOK, SSH }

@Composable
internal fun WebReaderStripedPanel(
    modifier: Modifier,
    startUrl: String,
    onUrlChange: (String) -> Unit,
    onLog: (String) -> Unit,
    onVisited: (url: String, title: String?) -> Unit = { _, _ -> },
    onTitle: (url: String, title: String) -> Unit = { _, _ -> },
    loadAddresses: suspend (currentOnly: Boolean) -> List<AddressEntry> = { emptyList() },
    deleteAddress: suspend (AddressEntry) -> Unit = {},
    loadSshInfo: suspend () -> List<String> = { emptyList() },
) {
    var input by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var mode by remember { mutableStateOf(ReaderMode.WEB) }
    val openUrl: (String) -> Unit = { raw ->
        val url = normalizeWebInput(raw)
        if (url.isNotEmpty()) {
            input = url
            mode = ReaderMode.WEB
            webView?.loadUrl(url)
            onUrlChange(url)
            onLog("Webolvasó: megnyitás: $url")
        }
    }

    // Vissza gomb: előbb a lista-nézet zárul, aztán az oldal előzményében lép vissza.
    BackHandler(enabled = mode != ReaderMode.WEB) { mode = ReaderMode.WEB }
    BackHandler(enabled = mode == ReaderMode.WEB && canGoBack) { webView?.goBack() }

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
        topStripe = StripeSpec(label = "MEGNYITÁS", enabled = input.isNotBlank(), onClick = { openUrl(input) }),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Felső gombsor: középre igazítva ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderModeButton(Icons.AutoMirrored.Filled.FormatListBulleted, null, "Címek (jelenlegi hálózat)", mode == ReaderMode.LIST) {
                    mode = if (mode == ReaderMode.LIST) ReaderMode.WEB else ReaderMode.LIST
                }
                ReaderModeButton(Icons.Filled.Book, null, "Minden elmentett cím", mode == ReaderMode.BOOK) {
                    mode = if (mode == ReaderMode.BOOK) ReaderMode.WEB else ReaderMode.BOOK
                }
                ReaderModeButton(null, "SSH", "SSH", mode == ReaderMode.SSH) {
                    mode = if (mode == ReaderMode.SSH) ReaderMode.WEB else ReaderMode.SSH
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("https://... vagy IP-cím", fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { openUrl(input) }),
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                        onVisited(url, view.title)
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    val u = view.url
                                    if (u != null && !title.isNullOrBlank()) onTitle(u, title)
                                }
                            }
                            if (startUrl.isNotBlank()) loadUrl(normalizeWebInput(startUrl))
                        }
                    },
                    update = { view -> if (webView !== view) webView = view },
                    modifier = Modifier.fillMaxSize(),
                )
                if (mode != ReaderMode.WEB) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PanelBg)
                            // A lista alatti WebView ne kapja meg a koppintásokat.
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        when (mode) {
                            ReaderMode.LIST -> AddressListView(currentOnly = true, loadAddresses, deleteAddress, openUrl)
                            ReaderMode.BOOK -> AddressListView(currentOnly = false, loadAddresses, deleteAddress, openUrl)
                            ReaderMode.SSH -> SshInfoView(loadSshInfo)
                            ReaderMode.WEB -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderModeButton(icon: ImageVector?, text: String?, description: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 36.dp)
            .clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.25f) else ButtonBg)
            .border(1.dp, Accent.copy(alpha = if (selected) 0.9f else 0.45f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = description, tint = Accent, modifier = Modifier.size(20.dp))
        } else {
            Text(text ?: "", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun AddressListView(
    currentOnly: Boolean,
    loadAddresses: suspend (Boolean) -> List<AddressEntry>,
    deleteAddress: suspend (AddressEntry) -> Unit,
    open: (String) -> Unit,
) {
    var entries by remember(currentOnly) { mutableStateOf<List<AddressEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<AddressEntry?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(currentOnly, reload) { entries = loadAddresses(currentOnly) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (currentOnly) "CÍMEK - JELENLEGI HÁLÓZAT + LEGUTÓBBI WEB-CÍMEK" else "MINDEN ELMENTETT CÍM",
            color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        )
        val list = entries
        when {
            list == null -> Text("Betöltés...", color = TextDim, fontSize = 10.sp)
            list.isEmpty() -> Text(
                "Még nincs elmentett cím. A bal fiók tesztjei (pl. Ping-sweep) és a megnyitott oldalak ide kerülnek.",
                color = TextDim, fontSize = 10.sp,
            )
            else -> {
                val groups = list.groupBy { if (it.kind == AddressEntry.Kind.LAN) (it.networkName ?: "LAN") else "Web-címek" }
                for ((groupName, items) in groups) {
                    Text(groupName, color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    items.forEach { e ->
                        AddressRow(e, showDelete = !currentOnly, onOpen = { open(e.openUrl) }, onDelete = { pendingDelete = e })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    pendingDelete?.let { e ->
        ConfirmDialog(
            title = "Cím törlése",
            text = "Biztosan törlöd a listából: ${e.address}" + (e.networkName?.let { " ($it)" } ?: "") +
                "? Az összegyűjtött adatai is törlődnek; a törlés nem vonható vissza.",
            confirmLabel = "TÖRLÉS",
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    deleteAddress(e)
                    reload++
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun AddressRow(e: AddressEntry, showDelete: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.3f), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Maga a cím a link (nincs külön gomb).
            Text(
                e.address,
                color = AccentBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onOpen),
            )
            if (e.title != e.address) Text(e.title, color = TextMain, fontSize = 10.sp, maxLines = 1)
            e.details.forEach { Text(it, color = TextDim, fontSize = 9.sp) }
            if (e.lastMs > 0) Text("utoljára: ${formatDateTime(e.lastMs)}", color = TextDim, fontSize = 8.sp)
        }
        if (showDelete) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, DangerColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Törlés", tint = DangerColor, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SshInfoView(loadSshInfo: suspend () -> List<String>) {
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { lines = loadSshInfo() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("SSH", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            "Az SSH (Secure Shell, 22-es port) titkosított parancssori belépés egy eszközre - routerek, NAS-ok, Linuxos " +
                "gépek, Raspberry Pi-k és a legtöbb ASIC-miner (dropbear/busybox) is kínálja. A beépített SSH-kliens (belépés, " +
                "parancsok, a Miner's hitelesítő-trezorral) egy KÖVETKEZŐ körben készül; addig itt látszik, hol van " +
                "SSH-szolgáltatás a hálózataidon (a Port-scan és az SSH-banner teszt eredményeiből) és mit rögzítettek a naplók.",
            color = TextMain, fontSize = 10.sp,
        )
        Text(
            "Biztonság: gyári jelszavakat (pl. root/root) cserélj le; SSH-t ne engedj ki a routeren át az internetre.",
            color = WarnColor, fontSize = 9.sp,
        )
        Spacer(Modifier.height(4.dp))
        val l = lines
        when {
            l == null -> Text("Betöltés...", color = TextDim, fontSize = 10.sp)
            l.isEmpty() -> Text(
                "Még nincs SSH-adat. Futtasd a bal fiók Szolgáltatások > SSH-banner (vagy Port-scan) tesztjét.",
                color = TextDim, fontSize = 10.sp,
            )
            else -> l.forEach { line ->
                if (line.startsWith("# ")) {
                    Text(line.removePrefix("# "), color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                } else {
                    LinkifiedText(line, color = TextMain, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
