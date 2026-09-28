<!-- Verzio: v0.2.0 - 2026-09-28 -->
# Későbbi teendők (v0.1.13 után)

**Utolsó kész verzió:** Android app v0.1.13 (`feature/android-app`) – ez a lista a következő körök (v0.1.14+) anyaga.
Forrás: a git log, a kód, az `Android/README.md` „kihagyott” listája, az `Android/LINK_PROTOCOL.md` v2/v3 terve és a
`to-dos_tasks.md` 5. szakasza. A sorrend csoportonként nagyjából fontossági.

## Ágak és kiadás

- [ ] **`feature/android-app` → `main`:** a `main` ágon (`bb0a1f4`) még egyáltalán nincs Android-kód. Dönteni kell,
  mikor kerül be (pl. egy stabil, telefonon kipróbált verziónál), és hogy a CI (`android-build.yml`) a `main`-en is
  kiadjon-e APK-t.
- [ ] **`feature/eszkozlista-es-visszaallitas`** (`ea3d12a`, 2026-09-21): Devices.json + DeviceList.ps1, Bitdeer gyártó,
  CGNAT kétfázisú teszt visszaállítása – nincs beolvasztva sem a `main`-be, sem az Android-ágba. Átnézni, a `main`-be
  olvasztani vagy lezárni.
- [ ] Release-aláírás (a mostani `debug.keystore` csak a folyamatos frissíthetőséget szolgálja) – ha az app valaha
  a telefonon kívül is terjesztésre kerül.

## Tor – folytatás (a v0.1.13 alapjaira)

- [ ] A `tor_lan` teszt találatai kerüljenek a `lan_hosts.json`-ba (új mező, pl. `torPorts`), és a
  `TestDetailPanel.hostValue` mutassa őket – most a Tor-tesztek paneljén a „hálózaton látott adatok” üres.
- [ ] A **Miner's** panelen figyelmeztetés, ha egy minernél Tor-port nyitott (rejtett távoli elérés gyanúja).
- [ ] A `tor_check` tesztben a szolgáltatói blokkolás gyanújakor közvetlen gomb az Orbot híd-beállításaihoz
  (obfs4 / Snowflake) – ha az Orbot erre ad intentet; ha nem, részletes leírás.
- [ ] `orbotInstalledSeen`: ha az Orbotot később eltávolítják, most nincs újraellenőrzés – a Tor-funkciók
  használatkor úgyis ellenőriznek, de az induló napló félrevezető lehet. Egyszerűsítés: induláskor mindig ellenőrizni
  (olcsó hívás).
- [ ] Tesztek SOCKS5-ön (9050) is, ne csak HTTP-proxyn (8118) – az újabb Orbot-verziókban a HTTP-proxy kikapcsolható.
- [ ] `.onion` címek külön csoportban a KÖNYV-ben.
- [ ] Tor-mód bypass-listájába a 100.64.0.0/10 (CGNAT / Tailscale) tartomány – csak `100.64.*` … `100.127.*`
  szabályokkal, NEM `100.*`-gal (az publikus címeket is kivenne a Tor alól).
- [ ] Ha Tor-mód NÉLKÜL nyit meg valaki `.onion` címet: rákérdezés „Tor-mód bekapcsolása?”.
- [ ] Tor-mód jelzése a fejlécben / a Webolvasón kívül is (a proxy-felülírás az egész app WebView-jára vonatkozik).
- [ ] Windows-oldal: a LAN Tor-port keresés (9050/9150/9051/9001/9030) a `MinerSearch.ps1` / `NetworkDiag` mellé.

## SSH és hitelesítés

- [ ] **Hitelesítő-trezor** (mentett SSH/web belépések, Keystore-titkosítással, mint a profil-jelszavak) – ez a
  feltétele minden bejelentkezős funkciónak.
- [ ] **Beépített SSH-bejelentkezés** (a Beállítások inaktív „SSH-bejelentkezés engedélyezése” jelölőnégyzete) –
  pl. csak olvasó parancsok minereken/routereken; könyvtár-választás (pl. sshj) és a méretnövekedés mérlegelése.
- [ ] **`ssh://` átadása külső SSH-kliensnek** (ConnectBot / JuiceSSH / Termux, `ACTION_VIEW`), „nincs kliens”
  esetén CÍM MÁSOLÁSA ablak – v0.1.13-ban a nem-webes címek csak az SSH-nézetet nyitják.
- [ ] Kattintható `ssh://` linkek: a `Links.kt` `LINK_RE` bővítése, és az SSH-nézet sorai `ssh://IP:port` formában
  (most az IP `http://`-re linkelődik, ami SSH-nál félrevezető).
- [ ] A Webolvasó érzékeny belépés/hibakezelés része (LordAthis illeszti be).
- [ ] A Webolvasó **FTP / SFTP** bővítése (README: „SSH/FTP-webolvasó bővítés”).
- [ ] Egyéb sémák kezelése a Webolvasóban: `mailto:`, `tel:`, `rtsp://` (IP-kamerák), `intent:` – átadás a rendszernek,
  ne csendes elnyelés.

## Eszközök panel – bővítési ötletek

- [ ] A gyorsgombok listája beállítható legyen (más alhálók: 10.0.0.1, 172.16.0.1, a tényleges átjáró címe).
- [ ] Tor-gyorsgombok: Orbot indítása/megnyitása, Tor-teszt, Webolvasó Tor-módban – állapotsorral.
- [ ] Hálózati gyorsgombok: WiFi- / mobiladat-beállítások, gyorsellenőrzés újra, automatikus tesztek most.

## Kimaradt UI-elemek (README „kihagyott” lista)

- [ ] **Floppy – profil-kezelő UI:** mentés / törlés / betöltés / inaktiválás; a profilok most csak automatikusan jönnek
  létre.
- [ ] **Import/Export** (ajtó-nyíl ikon): profilok, jegyzetek, beállítások.
- [ ] **Bluetooth-panel.**
- [ ] **Miner's:** hitelesítő-trezor (lásd fent) és AI-fotó-felismerés (típustábla / kijelző fotóból).
- [ ] **Saját értesítési hangfájl importálása** (a rendszer hangkiválasztó már megvan).
- [ ] **Külső szolgáltatások** panel („kidolgozás alatt”): az API-kulcs és MCP-cím mezők mögé tényleges funkció.
- [ ] A bal fiók eredeti kérése szerinti **3 független fiók** – csak ha élő teszttel a gesztuskód biztonságosan átírható;
  a Tor-csoport után (4 csoport, 10 teszt) a magasság újra ellenőrizendő.

## Linkelés (LINK_PROTOCOL.md v2/v3)

- [ ] **v2 Windows:** `win/LinkAgent.ps1` (Launcher új menüpont) – HELLO/PING válasz és keresés PowerShellből.
- [ ] `SpeedServer.ps1` a képességek közé (`"caps": ["echo","speed-server"]`) → a telefon magától találja meg.
- [ ] TCP 47801: kétirányú sávszélesség-mérés két példány között.
- [ ] **Párosítás:** 6 jegyű kód / QR, HMAC-SHA256 aláírás + időablak; párosítás nélkül csak HELLO/PING.
- [ ] Kábelteszt linkkel: M2 „Linkelt társ” cél, M3 a társ felé, egyirányú veszteség a sorszámokból.
- [ ] **v3:** közvetítő (relay/rendezvous) szerver CGNAT mögötti távoli gépekhez.

## Mérés és diagnosztika (to-dos_tasks.md 5. szakasz)

- [ ] iperf3-kliens az appban.
- [ ] MTU / fragmentáció teszt (DF-bit + csökkenő méret) – PPPoE/VPN MTU-hibákhoz.
- [ ] Időzített sebességteszt (óránként M1 + M2) és napszak szerinti grafikon.
- [ ] Router-lekérdezés (SNMP ifSpeed / UPnP) – a portok kapcsolt sebessége.
- [ ] Traceroute-szerű ugrás-elemzés (TTL-lel növelt ping, ahol a rendszer-ping engedi).
- [ ] A `device_speeds_user.json` saját bejegyzéseinek exportja a repó `device_speed_catalog.json`-jába, és a
  Windows-oldal (NetworkDiag / MinerStatus) is ezt a listát használja.

## Platform-korlátok (tudomásul véve, nem tervezett)

- MAC-cím / ARP-tábla root nélkül nem olvasható (Android 12-n megerősítve) → a MAC → név „interjú” csak más forrásból
  (pl. jövőbeli router-bejelentkezés) valósítható meg.
- Automatikus indítás újraindításkor: nem tervezett.
- Google Drive kétirányú szinkron: végleges döntés szerint nincs.
