// Verzio: v0.5.0 - 2026-09-22
package hu.lordathis.networktools.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.lordathis.networktools.crypto.CryptoState
import hu.lordathis.networktools.crypto.KeyPolicy
import hu.lordathis.networktools.engine.LogFileInfo
import hu.lordathis.networktools.engine.ExportKind
import hu.lordathis.networktools.storage.BackupStatus

// ---------------------------------------------------------------------------
// BEÁLLÍTÁSOK panel (görgethető, keretes szakaszokkal), fentről lefelé:
//   Általános (skin) · Háttérben futás · Értesítési hangok · Mentési (napló fájlba / e-mailben + fájllista) · Adatmentés ·
//   Hálózati (üres) · E-mail · Titkosítás
// ---------------------------------------------------------------------------

/** A Beállítások panel megjelenítendő állapota. */
internal data class SettingsUiState(
    val skin: Skin,
    val backgroundRun: Boolean,
    val notifSoundEnabled: Boolean,
    val notifSoundTitle: String,
    val notifPermission: Boolean,
    val logFiles: List<LogFileInfo>,
    val emailRecipients: List<String>,
    val emailCurrent: String,
    val emailInput: String,
    val emailInputValid: Boolean,
    val emailValid: Boolean,
    val emailConfirmed: Boolean,
    val crypto: CryptoState,
    val keyInput: String,
    val keyVisible: Boolean,
    val backup: BackupStatus,
    val backupSdkOk: Boolean,
    // --- Hálózati beállítások ---
    val wifiForced: Boolean,
    val mobileForced: Boolean,
    val bluetoothEnabled: Boolean,
    val portScanMode: String,
    val customPortList: String,
    val scanConcurrency: Int,
    val extraSubnets: String,
    val sshLoginEnabled: Boolean,
    // --- Külső szolgáltatók (AI API / MCP) ---
    val externalApiKey: String,
    val externalMcpServer: String,
)

/** A Beállítások panel eseményei. */
internal data class SettingsActions(
    val onSkinChange: (Skin) -> Unit,
    val onBackgroundRunChange: (Boolean) -> Unit,
    val onOpenBatterySettings: () -> Unit,
    val onNotifSoundEnabledChange: (Boolean) -> Unit,
    val onPickNotifSound: () -> Unit,
    val onNotifTest: () -> Unit,
    val onExport: (ExportKind) -> Unit,
    val onEmailFile: (ExportKind) -> Unit,
    val onLogFileDelete: (String) -> Unit,
    val onEmailSelect: (String) -> Unit,
    val onEmailInputChange: (String) -> Unit,
    val onEmailAdd: () -> Unit,
    val onEmailDelete: (String) -> Unit,
    val onEmailTest: () -> Unit,
    val onEmailConfirm: () -> Unit,
    val onKeyInputChange: (String) -> Unit,
    val onKeyVisibleChange: (Boolean) -> Unit,
    val onKeyAdd: () -> Unit,
    val onKeyGenerate: () -> Unit,
    val onKeyCopy: () -> Unit,
    val onKeyDelete: () -> Unit,
    val onBackupGrant: () -> Unit,
    val onBackupNow: () -> Unit,
    // --- Hálózati beállítások ---
    val onWifiForcedChange: (Boolean) -> Unit,
    val onMobileForcedChange: (Boolean) -> Unit,
    val onBluetoothEnabledChange: (Boolean) -> Unit,
    val onPortScanModeChange: (String) -> Unit,
    val onCustomPortListChange: (String) -> Unit,
    val onScanConcurrencyChange: (Int) -> Unit,
    val onExtraSubnetsChange: (String) -> Unit,
    // onSshLoginEnabledChange NINCS - a csúszka szándékosan inaktív, amíg a funkció el nem készül.
    // --- Külső szolgáltatók ---
    val onExternalApiKeyChange: (String) -> Unit,
    val onExternalMcpServerChange: (String) -> Unit,
)

/** Keretes beállítás-szakasz: felirat + tartalom, belső kerettel. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Accent.copy(alpha = 0.45f), shape)
            .padding(12.dp)
    ) {
        Text(title, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextDim),
            modifier = Modifier.padding(end = 10.dp, top = 6.dp, bottom = 6.dp)
        )
        Text(label, color = if (enabled) TextMain else TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun SaveFileButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, Accent.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

/** Mentés-gomb + mellette a levél-ikon (e-mailben küldés a beállított címzettnek). */
@Composable
private fun SaveRow(label: String, onSave: () -> Unit, onEmail: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SaveFileButton(label, modifier = Modifier.weight(1f), onClick = onSave)
        val shape = RoundedCornerShape(10.dp)
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(shape)
                .border(1.dp, Accent.copy(alpha = 0.6f), shape)
                .clickable(onClick = onEmail),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Email, contentDescription = "Küldés e-mailben", tint = Accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun StatusChip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/** Pipálható sor (be/ki beállításokhoz). */
@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextDim, checkmarkColor = StripeTextColor)
        )
        Text(label, color = TextMain, fontSize = 12.sp)
    }
}

@Composable
internal fun SettingsStripedPanel(modifier: Modifier, ui: SettingsUiState, act: SettingsActions) {
    StripedPanel(
        modifier = modifier,
        topStripe = StripeSpec("BEÁLLÍTÁSOK", enabled = false, onClick = null)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------------------------------------------------------- Általános
            SettingsSection("ÁLTALÁNOS BEÁLLÍTÁSOK") {
                Text("Megjelenés (skin) - azonnal érvényes és megmarad", color = TextDim, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                ChoiceRow("Alapértelmezett (a rendszer színe)", ui.skin == Skin.SYSTEM) { act.onSkinChange(Skin.SYSTEM) }
                ChoiceRow("Sötét", ui.skin == Skin.DARK) { act.onSkinChange(Skin.DARK) }
                ChoiceRow("Világos", ui.skin == Skin.LIGHT) { act.onSkinChange(Skin.LIGHT) }
            }

            // ---------------------------------------------------------------- Háttérben futás
            SettingsSection("HÁTTÉRBEN FUTÁS") {
                CheckRow("Futás a háttérben (állandó értesítéssel)", ui.backgroundRun, act.onBackgroundRunChange)
                Text(
                    "Bekapcsolva az app előtér-szolgáltatásként fut, így az Android nem állítja le, amikor " +
                        "kilépsz belőle. Az állandó értesítés ehhez kell, hang nélkül jelenik meg. Egyes telefonokon " +
                        "az akkumulátor-optimalizálást is ki kell kapcsolni az apphoz.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                SaveFileButton("AKKUMULÁTOR-BEÁLLÍTÁS MEGNYITÁSA", Modifier.fillMaxWidth(), act.onOpenBatterySettings)
            }

            // ---------------------------------------------------------------- Értesítési hangok
            SettingsSection("ÉRTESÍTÉSI HANGOK") {
                CheckRow("Hang az értesítéseknél", ui.notifSoundEnabled, act.onNotifSoundEnabledChange)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Választott hang:", color = TextDim, fontSize = 10.sp)
                    Text(ui.notifSoundTitle, color = TextMain, fontSize = 11.sp, maxLines = 1)
                }
                if (!ui.notifPermission) {
                    Text(
                        "Az értesítésekhez engedély kell (Android 13+): a PRÓBA gomb megkéri.",
                        color = WarnColor,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaveFileButton("HANG KIVÁLASZTÁSA", Modifier.weight(1f), act.onPickNotifSound)
                    SaveFileButton("PRÓBA", Modifier.weight(1f), act.onNotifTest)
                }
            }

            // ---------------------------------------------------------------- Mentési
            SettingsSection("MENTÉSI BEÁLLÍTÁSOK") {
                Text(
                    "A fájlok az app védett mappájában vannak. A mentés gombbal a választott helyre másolhatók " +
                        "(alapból a Letöltések mappa), a levél ikonnal e-mailben küldhetők a beállított címzettnek.",
                    color = TextDim,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaveRow("NAPLÓ MENTÉSE", { act.onExport(ExportKind.LOG) }, { act.onEmailFile(ExportKind.LOG) })
                }
                Text(
                    "A NAPLÓ MENTÉSE a rendszer fájlválasztóját nyitja: ott bármelyik helyet kiválaszthatod, akár a Google " +
                        "felhő-mappát is (a választott mappába kerül, ott látod és törölheted).",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(Modifier.height(12.dp))
                Text("NAPLÓFÁJLOK A TELEFONON", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                if (ui.logFiles.isEmpty()) {
                    Text("Nincs naplófájl.", color = TextDim, fontSize = 11.sp)
                }
                ui.logFiles.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = TextMain, fontSize = 11.sp, maxLines = 1)
                            Text(
                                "${(file.sizeBytes + 512) / 1024} KB  ·  ${formatDateTime(file.modifiedMs)}",
                                color = TextDim,
                                fontSize = 9.sp
                            )
                        }
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(shape)
                                .border(1.dp, DangerColor.copy(alpha = 0.6f), shape)
                                .clickable { act.onLogFileDelete(file.name) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Naplófájl törlése", tint = DangerColor, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ---------------------------------------------------------------- Adatmentés
            SettingsSection("ADATMENTÉS (TÚLÉLI AZ ELTÁVOLÍTÁST)") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.backup.available) StatusChip("BE", Accent) else StatusChip("KI", DangerColor)
                    val last = ui.backup.lastMs
                    Text(
                        if (ui.backup.available && last != null) "Utolsó mentés: ${formatDateTime(last)} (${ui.backup.lastCopied} fájl frissült)"
                        else if (!ui.backupSdkOk) "Android 11 vagy újabb szükséges"
                        else "Nincs engedély a mappa-hozzáféréshez",
                        color = TextDim,
                        fontSize = 10.sp
                    )
                }
                Text(
                    "A naplók másolata a telefon közös Dokumentumok/NetworkTools " +
                        "mappájába kerül (a Fájlok appban látod). Az app eltávolítása és újratelepítése nem törli. " +
                        "Indításkor, 30 percenként és háttérbe lépéskor frissül; a mentésből soha nem töröl. " +
                        "Újratelepítés után elég újra megadni az engedélyt: az app visszaállítja az adatokat.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "Figyelem: a másolat TITKOSÍTATLAN, és a Dokumentumok mappát a teljes fájlhozzáféréssel rendelkező " +
                        "appok is olvashatják. A beállítások és a titkosítási kulcs nem kerülnek bele.",
                    color = WarnColor,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (ui.backup.message.isNotBlank() && ui.backup.available) {
                    Text(ui.backup.message, color = DangerColor, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ui.backup.available) {
                        SaveFileButton("ENGEDÉLY MEGADÁSA", Modifier.weight(1f), act.onBackupGrant)
                    }
                    if (ui.backup.available) {
                        SaveFileButton("MENTÉS / VISSZAÁLLÍTÁS MOST", Modifier.weight(1f), act.onBackupNow)
                    }
                }
            }

            // ---------------------------------------------------------------- Hálózati
            SettingsSection("HÁLÓZATI BEÁLLÍTÁSOK") {
                Text(
                    "A WiFi/mobilnet kapcsoló ugyanaz, mint a főképernyő házikója melletti ikonok - itt is " +
                        "elérhető, hogy egy teszt csak egy hálózaton fusson.",
                    color = TextDim,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.height(6.dp))
                CheckRow("Forgalom kényszerítése WiFi-re", ui.wifiForced, act.onWifiForcedChange)
                CheckRow("Forgalom kényszerítése mobilnetre", ui.mobileForced, act.onMobileForcedChange)
                CheckRow("Bluetooth", ui.bluetoothEnabled, act.onBluetoothEnabledChange)
                Text(
                    "A Bluetooth-kapcsoló egyelőre csak jelzi az állapotot - a hozzá tartozó önálló panel " +
                        "egy következő körben készül.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(10.dp))
                Text("PORT-SCAN", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                ChoiceRow("Csak a listán szereplő portok", ui.portScanMode == "LIST") { act.onPortScanModeChange("LIST") }
                ChoiceRow("Összes port (1-65535, lassabb)", ui.portScanMode == "ALL") { act.onPortScanModeChange("ALL") }
                if (ui.portScanMode == "LIST") {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = ui.customPortList,
                        onValueChange = act.onCustomPortListChange,
                        label = { Text("Egyedi portlista (vesszővel, üres = alapértelmezett)") },
                        singleLine = true,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("VIZSGÁLAT PARAMÉTEREI", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = ui.scanConcurrency.toString(),
                    onValueChange = { text -> text.toIntOrNull()?.let(act.onScanConcurrencyChange) },
                    label = { Text("Egyidejű vizsgálatok száma (throttle)") },
                    singleLine = true,
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ui.extraSubnets,
                    onValueChange = act.onExtraSubnetsChange,
                    label = { Text("Extra alhálók (pl. 192.168.5.0/24), soronként") },
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Text("TÁVOLI BEJELENTKEZÉS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ui.sshLoginEnabled, onCheckedChange = null, enabled = false)
                    Text("SSH-bejelentkezés engedélyezése", color = TextDim, fontSize = 12.sp)
                }
                Text(
                    "Egyelőre inaktív: a mostani tesztek csak SSH-portot/bannert olvasnak (nem jelentkeznek be). " +
                        "A tényleges bejelentkezés a hitelesítő-trezorral (mentett jelszavak) együtt készül el.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ---------------------------------------------------------------- Külső szolgáltatók
            SettingsSection("KÜLSŐ SZOLGÁLTATÁSOK (AI API / MCP)") {
                Text(
                    "Egyelőre csak a beállítás helye - a funkció (AI-alapú felismerés, MCP-kapcsolat) egy " +
                        "következő körben készül el. A jobb oldali fiók \"Külső szolgáltatások\" gombja egy " +
                        "\"kidolgozás alatt\" panelt nyit.",
                    color = TextDim,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ui.externalApiKey,
                    onValueChange = act.onExternalApiKeyChange,
                    label = { Text("API-kulcs") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ui.externalMcpServer,
                    onValueChange = act.onExternalMcpServerChange,
                    label = { Text("MCP-szerver címe") },
                    singleLine = true,
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------------------------------------------------------------- E-mail
            SettingsSection("E-MAIL BEÁLLÍTÁSOK") {
                Text(
                    "Címzett a levél ikonos küldésekhez. A feladó a telefon levelezőjének elsődleges fiókja " +
                        "(az Android ezt programból nem engedi megadni; a levélírón belül átváltható).",
                    color = TextDim,
                    fontSize = 10.sp
                )
                Text(
                    "A lista minta címekkel indul (example.com): töröld őket, és vedd fel a sajátodat.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(6.dp))
                if (ui.emailRecipients.isEmpty()) {
                    Text("Nincs címzett - adj hozzá egyet.", color = WarnColor, fontSize = 11.sp)
                }
                ui.emailRecipients.forEach { address ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ChoiceRow(address, ui.emailCurrent == address) { act.onEmailSelect(address) }
                        }
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(shape)
                                .border(1.dp, DangerColor.copy(alpha = 0.6f), shape)
                                .clickable { act.onEmailDelete(address) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Címzett törlése", tint = DangerColor, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.emailInput,
                    onValueChange = act.onEmailInputChange,
                    label = { Text("Új címzett (e-mail cím)") },
                    singleLine = true,
                    isError = ui.emailInput.isNotBlank() && !ui.emailInputValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (ui.emailInput.isNotBlank() && !ui.emailInputValid) {
                    Text("Ez nem érvényes e-mail cím (vagy már szerepel a listában).", color = DangerColor, fontSize = 10.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    PillButton("HOZZÁADÁS", enabled = ui.emailInputValid, filled = true, onClick = act.onEmailAdd)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.emailConfirmed) StatusChip("✓ MEGERŐSÍTVE", Accent) else StatusChip("MÉG NEM ERŐSÍTETT", WarnColor)
                    Text(ui.emailCurrent.ifBlank { "(nincs kiválasztott címzett)" }, color = TextDim, fontSize = 10.sp, maxLines = 1)
                }
                Text(
                    "Az app egy cím létezését nem tudja ellenőrizni: küldj tesztlevelet, és ha megérkezett, jelöld meg.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("TESZTLEVÉL", enabled = ui.emailValid, filled = true, onClick = act.onEmailTest)
                    if (!ui.emailConfirmed) {
                        PillButton("MEGÉRKEZETT", enabled = ui.emailValid, filled = false, onClick = act.onEmailConfirm)
                    }
                }
            }

            // ---------------------------------------------------------------- Titkosítás
            SettingsSection("TITKOSÍTÁS (KULCS)") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.crypto.ready) StatusChip("BE", Accent) else StatusChip("KI", DangerColor)
                    Text(
                        if (ui.crypto.ready) "Kulcs azonosító: ${ui.crypto.kid}" else if (ui.crypto.busy) "Kulcs betöltése..." else "Nincs kulcs - nincs titkosítás",
                        color = TextDim,
                        fontSize = 10.sp
                    )
                }
                Text(
                    "AES-256-GCM. A kulcs a telefon rendszer-kulcstárával védve tárolódik.",
                    color = TextDim,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.keyInput,
                    onValueChange = act.onKeyInputChange,
                    label = { Text("Kulcs (begépelés / beillesztés)") },
                    singleLine = true,
                    visualTransformation = if (ui.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { act.onKeyVisibleChange(!ui.keyVisible) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = ui.keyVisible,
                        onCheckedChange = act.onKeyVisibleChange,
                        colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextDim, checkmarkColor = StripeTextColor)
                    )
                    Text("Kulcs megjelenítése", color = TextMain, fontSize = 11.sp)
                }
                val problems = if (ui.keyInput.isBlank()) emptyList() else KeyPolicy.validate(ui.keyInput)
                if (ui.keyInput.isBlank()) {
                    Text(
                        "Követelmény: min. ${KeyPolicy.MIN_LENGTH} karakter, legalább 1 kisbetű, 1 nagybetű, 1 szám és 1 speciális karakter.",
                        color = TextDim,
                        fontSize = 9.sp
                    )
                } else if (problems.isNotEmpty()) {
                    Text("Hiányzik: " + problems.joinToString("; "), color = WarnColor, fontSize = 9.sp)
                } else {
                    Text("A kulcs megfelel a követelményeknek.", color = Accent, fontSize = 9.sp)
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaveFileButton("KULCS HOZZÁADÁSA", Modifier.weight(1f), act.onKeyAdd)
                        SaveFileButton("KULCS GENERÁLÁSA", Modifier.weight(1f), act.onKeyGenerate)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaveFileButton("KULCS MÁSOLÁSA", Modifier.weight(1f), act.onKeyCopy)
                        SaveFileButton("KULCS TÖRLÉSE", Modifier.weight(1f), act.onKeyDelete)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
