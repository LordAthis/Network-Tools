<!-- Verzio: v0.4.0 - 2026-09-21 -->
# Network Tool's - Android

A Network-Tools projekt Android változata (Kotlin + Jetpack Compose, minSdk 26). Jelenleg az app KINÉZETE és váza készült - a
hálózati tesztek működése a következő kör.

## Képernyő és menü

- **Fejléc**: "Network Tool's"; alatta státuszsor (verzió + háttérben futás BE/KI); csillagos háttér.
- **Kezdőlap**: a *VISSZAJELZÉSEK* panel (csak olvasható): az app eseményei (adatmentés, hibák...), és a
  későbbi körben a háttérben futó tesztek eredményei / részeredményei. Funkció-gomb nincs rajta. (Chat nincs.)
- **Két, szél-húzással nyitható fiók** (vagy a fogantyúra koppintással) - a funkciók gombjai KIZÁRÓLAG itt vannak:
  - **Bal**: Jegyzet · F1 · F2 · F3 (az F-gombok panelén egyelőre csak a gomb neve áll)
  - **Jobb**: Gyorsjelentés (villám; csak a név) · Napló · Webolvasó (földgömb) · Beállítások · Névjegy (i)
- **Házikó-gomb**: amíg nem a Kezdőlapon vagy, lent középen egy házikó, benne a hálózat-ikon - ez visz a Kezdőlapra.
- Vissza gomb: nyitott fiók zárása → panelről a Kezdőlapra → kilépés.

## Funkciók

| Menüpont | Állapot |
|---|---|
| Kezdőlap (visszajelzések) | működik: az app eseményei; a háttértesztek eredményeinek helye |
| Jegyzet | működik: "+ ÚJ JEGYZET" → Cím + Tartalom + MENTÉS; alul a mentett jegyzetek listája, mindegyik mellett MÓDOSÍTÁS / TÖRLÉS. Egy jegyzet = egy `.md` fájl (cím, dátum, tartalom) a `notes/` mappában, a Dokumentumok/NetworkTools mentés is másolja. |
| F1-F3, Gyorsjelentés | csak név |
| Napló | működik (napi naplófájlok, mentés fájlba) |
| Webolvasó | működik: egyszerűsített beépített böngésző (WebView), csak http(s) |
| Beállítások | lásd lent |
| Névjegy | az app leírása, verzió, adatmappa |

**Beállítások**: Általános (skin) · Háttérben futás · Értesítési hangok · Mentési beállítások (napló fájlba/e-mailben + naplófájlok listája, törlés) ·
Adatmentés (Dokumentumok/NetworkTools) · Hálózati beállítások (üres doboz) · E-mail beállítások
(felvehető/törölhető címzett-lista, minta címekkel indul) · Titkosítás (kulcs).

## Háttérfeladatok (a következő körben jövő tesztek) - szabályok

- A feladat az `AppHub`-ban (ViewModel-scope), nem a képernyőn fut: a képernyőváltás, a fiókok és panelek nyitása/zárása
  **nem állítja le és nem szakítja meg**.
- A részeredményeket a `hub.postFeed(forrás, szöveg, szint)` közli - a Kezdőlap folyamatosan mutatja, futás közben is.
- Hosszan futó feladatnál a `BackgroundService` (Beállítások > Háttérben futás) tartja életben a folyamatot. Automatikus
  indítás újraindításkor NINCS (és nem is tervezett).

## Mentések - csak látható, törölhető mentés van

Az elv: az app nem csinál rejtett, fájlonként nem látható/törölhető mentést. (A Google automatikus app-mentése, az Auto Backup
ki van kapcsolva: `allowBackup="false"`.) Ami van:

- **Adatmentés**: a napló és a jegyzetek másolata a telefon **Dokumentumok/NetworkTools** mappájába kerül (a Fájlok appban látod és
  törölheted); az app eltávolítását túléli, újratelepítés után visszaállítja az adatokat. Kell hozzá a "Minden fájl kezelése"
  engedély (Beállítások > Adatmentés). A mentésből az app soha nem töröl - a naplófájl/jegyzet törlésekor viszont a mentésbeli
  másolatát is törli, különben visszahozná.
- **Napló mentése (kézi)**: Beállítások > Mentési beállítások > NAPLÓ MENTÉSE - a rendszer fájlválasztóját nyitja: ott bármelyik
  helyet kiválaszthatod, akár a 
  ikon: e-mailben küldi a beállított címzettnek.
- **Naplófájlok a telefonon**: a Beállításokban a lista minden fájlja mellett kuka - törli a telefonról (a már máshová elmentett
  példányokat nem érinti).
- **Jegyzetek**: minden jegyzet egy `.md` fájl a `notes/` mappában (és a Dokumentumok/NetworkTools mentésben).

A Google Drive automatikus (háttér) szinkronja egyelőre nincs benne. Az OAuth-os (Cloud Console-t igénylő) változat külön
archívumban megvan, ha később mégis kell.

## Szerkezet

```
Android/app/src/main/java/hu/lordathis/networktools/
  MainActivity.kt        képernyők, fiókok, engedélyek, e-mail, mentés, jegyzetek
  engine/                AppHub (ViewModel: napló, jegyzetek, visszajelzések, mentések), Defaults, modellek
  notes/                 NoteStore (egy jegyzet = egy .md fájl)
  ui/                    Theme (skin-paletta), DrawerSystem, Panels (+Visszajelzések, Webolvasó),
                         SettingsPanel, NotesPanel, Dialogs, NetworkIcon (ikon + házikó-gomb), Background
  storage/ crypto/ settings/   tárolás, titkosítás, beállítás-tár
  notify/ service/       értesítési csatornák + hang; háttérben futó előtér-szolgáltatás
Android/icon/            az app-ikon forrása (SVG) és 512 px előnézet
```

## Build

- **GitHub Actions**: `.github/workflows/android-build.yml` (Android/ változásra, bármelyik ágon, vagy kézzel) - az APK
  `NetworkTools.apk` néven az *Artifacts* között van. Gradle wrapper nincs a repóban, a workflow telepíti a Gradle-t.
- **Helyben**: Android Studio megnyitja az `Android/` mappát, vagy `gradle assembleDebug` (Gradle 8.7, JDK 17).
- Plugin-verziók (AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.00): ismert, egymással kompatibilis kombináció.
- Az `app/debug.keystore` stabil DEBUG aláíró kulcs (nem titok): enélkül a CI minden futáson új kulccsal írna alá, és a telefonon a frissítés
  aláírás-ütközésen elbukna. A kulcs cseréje után a régi verziót el kell távolítani a telefonról.

## Állapot - őszintén

A kód **még nem lett lefordítva** (a fejlesztői környezetben nincs Android SDK / Maven-hozzáférés): a Kotlin-forrás
szintaktikailag, az XML-ek és a workflow formailag ellenőrizve; az első valódi build a CI-n fut. A legvalószínűbb hibaforrások
az új részek: Webolvasó, háttér-szolgáltatás, értesítési hangok, házikó-ikon, 
az engedély-kérés API-ja).
