<!-- Verzio: v0.5.0 - 2026-09-22 -->
# Network Tool's - Android

A Network-Tools projekt Android változata (Kotlin + Jetpack Compose, minSdk 26). Ebben a körben az UI-váz mellé bekerült egy
valódi hálózati teszt-motor is (lásd lent) - korábban csak a kinézet és a keretrendszer volt kész.

## Képernyő és menü

- **Fejléc**: "Network Tool's"; alatta státuszsor (verzió + háttérben futás BE/KI); csillagos háttér.
- **Kezdőlap**: felül a **GYORS ELLENŐRZÉS** kártya (állandó, amíg a hálózat/kapcsolat él - milyen kapcsolat aktív, ismert-e a
  hálózat, gyors eszközszám, internet-elérhetőség), alatta a **GYORSJELENTÉS** kártya (a legutóbbi tesztek összefoglalója,
  a JELENLEGI hálózatra szűrve), alul a **VISSZAJELZÉSEK** terület: 2/3 az éppen futó teszt(ek) élő, terminál-szerű kimenete
  (lapfüllel, ha egyszerre több fut), 1/3 az állandó, korábbi indításokat is mutató LOG.
- **Két, szél-húzással nyitható fiók** - a funkciók gombjai KIZÁRÓLAG itt vannak:
  - **Bal**: F1 · F2 · F3 - a "teljesen natívan megvalósítható" hálózati tesztek, három témába csoportosítva.
  - **Jobb**: Jegyzet · Gyorsjelentés (teljes nézet) · Napló · Mentés · Sebességteszt · Külső szolgáltatások · Webolvasó ·
    Beállítások · Névjegy.
- **Alsó ikonsor - MINDEN képernyőn**: [mobilnet + fogaskerék] — [közép] — [WiFi + fogaskerék]. A mobilnet/WiFi ikonra
  koppintva az app a SAJÁT forgalmát arra a hálózatra kényszeríti (lásd lent, "Hálózat-kényszerítés"); a fogaskerék a
  rendszer WiFi/mobiladat beállítását nyitja. A közép ikon a Kezdőlapon lefelé mutató **nyíl** (csak eredménnyel aktív →
  Eredmények képernyő), minden más képernyőn **házikó** (vissza a Kezdőlapra).
- Vissza gomb: nyitott fiók zárása → panelről a Kezdőlapra → kilépés.

## Hálózati tesztek (ÚJ ebben a körben)

Az `F1`/`F2`/`F3` gombok a `Network-Tools_feladatlista_es_halozati_tesztek.md` dokumentum "✅ Teljesen, natívan
megvalósítható" csoportjait valósítják meg, témánként (a lista `assets/tests_catalog.json`-ban van, névvel + rövidkóddal):

- **F1 - Hálózat és eszközök**: adapter/IP-infó, átjáró+DNS, publikus IP, CGNAT-teszt, IPv6-állapot, eszköz/rendszerinfó,
  ping-sweep, hostname-feloldás, SNMP-alapú eszközfelismerés, vezetékes/mobil összehasonlítás.
- **F2 - Portok és szolgáltatások**: port-scan (lista vagy "összes", lásd Hálózati beállítások), HTTP web-UI cím, SSH-banner,
  miner API-próba (cgminer/bmminer JSON).
- **F3 - Útvonal és kapcsolat**: gateway/WAN-elérhetőség + csomagvesztés, DNS-feloldási sebesség.

Minden teszt a **saját, hosszú életű scope-jában fut** (nem a képernyőn) - a panel bezárása, a fiókok nyitása/zárása nem
szakítja meg. Fut közben a terminál-sáv élőben mutatja; a végén egy teljes átirat kerül a `log/tests/` mappába
(`<HálózatNév>_ÉÉÉÉMMDD_HHmmss.log`), és egy rövid összefoglaló a Gyorsjelentésbe.

**ARP-spike**: induláskor EGYETLEN alkalommal lefut egy próba (`/proc/net/arp` olvasható-e) - az eredmény a LOG-ban van, és
onnantól nem fut le újra. Ez dönti majd el, hogy a MAC-cím-alapú funkciók (gyártó-felismerés, "néma eszköz") megvalósíthatók-e
root nélkül - lásd a feladatlista B1 pontját.

**Ismert korlát (Android-platform, nem hiba)**: más appok TCP-kapcsolatai, a rendszer ARP-táblája, a teljes route-tábla és a
traceroute root nélkül nem (vagy csak részlegesen) érhető el egy telepített appból - ezt a feladatlista részletesen indokolja.

## Hálózat-kényszerítés és hálózati profilok (ÚJ)

- **Hálózat-kényszerítés**: Android 10+ óta egy app nem tudja ki/bekapcsolni a WiFi/mobilnet rádiót - az alsó ikonok és a
  Hálózati beállítások ehelyett az app SAJÁT forgalmát kényszerítik egy kiválasztott hálózatra (`bindProcessToNetwork`),
  hogy egy teszt ne keveredjen a másik kapcsolattal.
- **Profilok**: minden WiFi-hálózat SSID + BSSID (az AP saját MAC-je - nem a LAN-átjáró ARP-ből olvasott MAC-je, ami
  valószínűleg nem elérhető) alapján azonosítva; azonos SSID, eltérő BSSID = KÜLÖN profil (ütközés-figyelés). Ismeretlen
  hálózat neve "Ismeretlen-\<BSSID 6 hexe\>", hogy több névtelen hálózat is megkülönböztethető maradjon. Minden profilhoz
  eszközlista tartozik (MAC → név/Nick/kategória/fiók/jelszó - a jelszó/fiók saját Keystore-kulccsal titkosítva, függetlenül
  a Beállítások > Titkosítás opcionális kulcsától). **A profil-kezelő felület (mentés/törlés/betöltés/inaktiválás, a
  "Floppy" gomb) még NEM készült el** - a profilok most automatikusan jönnek létre és mentődnek, kezelő UI nélkül.

## Funkciók

| Menüpont | Állapot |
|---|---|
| Kezdőlap | működik: Gyors ellenőrzés + Gyorsjelentés + Visszajelzések (élő teszt-terminál + állandó LOG) |
| F1 / F2 / F3 | működik: valódi hálózati tesztek (lásd fent) |
| Jegyzet | működik: "+ ÚJ JEGYZET" → Cím + Tartalom + MENTÉS; lista alul, MÓDOSÍTÁS/TÖRLÉS. Egy jegyzet = egy `.md` fájl. |
| Gyorsjelentés (jobb fiók) | működik: a Kezdőlap két kártyája teljes képernyőn |
| Napló | működik (napi naplófájlok, mentés fájlba) |
| Mentés | működik: log-doboz + korábbi mentések listája (CSV-ben) + naplófájl-törlés egy helyen |
| Sebességteszt | "kidolgozás alatt" - csak a gomb és a panel váza kész |
| Külső szolgáltatások | "kidolgozás alatt" - a Beállításokban már van API-kulcs/MCP-cím mező hozzá |
| Webolvasó | működik: egyszerűsített beépített böngésző (WebView), csak http(s) |
| Beállítások | lásd lent |
| Névjegy | az app leírása, verzió, adatmappa |
| Eredmények (alsó nyíl) | működik: az összes lezárt teszt átirata egy görgethető listában |

**Beállítások**: Általános (skin) · Háttérben futás · Értesítési hangok · Mentési beállítások (napló fájlba/e-mailben) ·
Adatmentés (Dokumentumok/NetworkTools) · **Hálózati beállítások** (WiFi/mobilnet/Bluetooth kapcsoló, port-scan mód+lista,
throttle, extra alhálók, inaktív SSH-bejelentkezés csúszka) · **Külső szolgáltatások** (API-kulcs, MCP-cím - csak mezők) ·
E-mail beállítások · Titkosítás (kulcs).

## Háttérfeladatok - szabályok

- A feladat a `TestEngine`/`AppHub`-ban (saját, hosszú életű `CoroutineScope`), nem a képernyőn fut: a képernyőváltás, a
  fiókok és panelek nyitása/zárása **nem állítja le és nem szakítja meg**.
- A részeredményeket a `TestEngine.jobs` StateFlow és a `hub.postFeed(...)` közli - a Kezdőlap folyamatosan mutatja.
- Hosszan futó feladatnál a `BackgroundService` (Beállítások > Háttérben futás) tartja életben a folyamatot. Automatikus
  indítás újraindításkor NINCS (és nem is tervezett).

## Mentések - csak látható, törölhető mentés van

Az elv: az app nem csinál rejtett, fájlonként nem látható/törölhető mentést (a Google Auto Backup ki van kapcsolva:
`allowBackup="false"`). Ami van:

- **Adatmentés**: a napló, a jegyzetek és a profilok másolata a telefon **Dokumentumok/NetworkTools** mappájába kerül; az
  app eltávolítását túléli. Kell hozzá a "Minden fájl kezelése" engedély (Beállítások > Adatmentés).
- **Mentés (kézi)**: a jobb fiók "Mentés" gombja, vagy Beállítások > Mentési beállítások > NAPLÓ MENTÉSE - a rendszer
  fájlválasztóját nyitja: bármelyik helyet kiválaszthatod, akár a Google Drive-ot is. Minden mentés bekerül egy CSV-be
  vezetett előzménylistába (`export_history.csv`), ezt mutatja a Mentés panel.
- **Naplófájlok a telefonon**: a Beállításokban / a Mentés panelen minden fájl mellett kuka - törli a telefonról.
- A Google Drive automatikus (OAuth-os, kétirányú) szinkronja NINCS az appban - ez a végleges döntés volt (lásd korábbi
  egyeztetés); a kísérleti változat külön archívumban megvan, ha valaha mégis kell.

## Amit ez a kör KIHAGYOTT (nyíltan jelezve, nem lett elfelejtve)

- **Floppy (profil-kezelő UI)**, **Import/Export** (ajtó-nyíl ikon), **SSH/FTP-webolvasó bővítés**, **Bluetooth-panel**,
  **Miner's** (ventilátor ikon, hitelesítő-trezor, AI-fotó-felismerés) - ezek a KÖVETKEZŐ kör anyaga.
- **A MAC-cím → név "interjú" folyamat** (teszt után végigkérdezi a talált eszközöket): az ARP-spike eredményétől függ,
  ami csak ebben a körben derül ki - utána tervezhető.
- **Saját (importált) értesítési hangfájl**: a rendszer hangkiválasztó megvan, a saját fájl importálása még nem készült el
  ebben a körben (kimaradt, pótlandó).

## Szerkezet

```
Android/app/src/main/java/hu/lordathis/networktools/
  MainActivity.kt        képernyők, fiókok, engedélyek, e-mail, mentés, alsó ikonsor
  engine/                AppHub (ViewModel), TestEngine + TestCatalog + TestModels (hálózati tesztek),
                         QuickCheck (induláskori/hálózatváltási gyorsellenőrzés), QuickReportStore, Defaults
  network/                a valódi hálózati próbák: NetworkIdentity, PingTools, PortScanner, SnmpProbe,
                         GatewayTest, MinerApiProbe, HttpTitleProbe, SshBannerProbe, ArpProbe, NetworkForcer
  profiles/               NetworkProfile/ProfileDevice, ProfileStore (SSID+BSSID egyeztetés), ProfileSecretCrypto
  notes/                 NoteStore (egy jegyzet = egy .md fájl)
  ui/                    Theme, DrawerSystem, Panels (Kezdőlap+Gyorsjelentés+Visszajelzések), TestPanels (F1-F3),
                         SyncPanel, BottomBar, SettingsPanel, NotesPanel, Dialogs, NetworkIcon, Background
  storage/ crypto/ settings/   tárolás, titkosítás, beállítás-tár, ExportHistoryStore
  notify/ service/       értesítési csatornák + hang; háttérben futó előtér-szolgáltatás
Android/app/src/main/assets/tests_catalog.json   a tesztek neve + rövidkódja (a lapfülekhez)
Android/icon/            az app-ikon forrása (SVG) és 512 px előnézet
```

## Build

- **GitHub Actions**: `.github/workflows/android-build.yml` (Android/ változásra, bármelyik ágon, vagy kézzel) - az APK
  `NetworkTools.apk` néven az *Artifacts* között van. Gradle wrapper nincs a repóban, a workflow telepíti a Gradle-t.
- **Helyben**: Android Studio megnyitja az `Android/` mappát, vagy `gradle assembleDebug` (Gradle 8.7, JDK 17).
- Plugin-verziók (AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.00): ismert, egymással kompatibilis kombináció.
- Az `app/debug.keystore` stabil DEBUG aláíró kulcs (nem titok): enélkül a CI minden futáson új kulccsal írna alá, és a
  telefonon a frissítés aláírás-ütközésen elbukna.
- **Új engedélyek ebben a körben**: `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION` (a jelenlegi WiFi SSID/BSSID lekérdezéséhez
  - Android ezt megköveteli, helyadatot az app nem gyűjt/küld). Telepítéskor ezekre is rá fog kérdezni a rendszer.

## Állapot - őszintén

A kód ebben a környezetben **nem lett lefordítva** (nincs Android SDK / Gradle-classpath): a Kotlin-forrás szintaktikailag,
kereszthivatkozásokra (a saját composable-ök és az AppHub/AppPreferences tagjai paraméterről paraméterre) és az XML/JSON/YAML
fájlok formailag ellenőrizve; az első valódi build a CI-n fut. A legkockázatosabb, most először bevezetett részek: a valódi
socket/UDP-alapú hálózati kód (SNMP-csomag kézi BER-kódolása, miner-API JSON-protokoll), a `NetworkCallback`-alapú
hálózatfigyelés, és a `bindProcessToNetwork` hálózat-kényszerítés - ezeket célszerű elsőként kipróbálni a telefonon.
