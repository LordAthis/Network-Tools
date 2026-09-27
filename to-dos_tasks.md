# Fejlesztési Specifikáció és Tennivalók: Android Kábeltesztelő Funkció (Claude-hoz)

Ez a dokumentum az Android alkalmazásba beépítendő **vezetékes hálózati kábeltesztelő funkció** (Ethernet/LAN diagnosztika) részletes fejlesztési specifikációja. Tartalmazza az Ookla Speedtest logikáját, a [Packet Loss Test](https://packetlosstest.com/ "Packet Loss Test") WebRTC-alapú működésének integrációját, valamint a natív ICMP pingelési folyamatokat.

---

## 1. Funkcionális Követelmények és Architektúra

Az alkalmazásnak három fő diagnosztikai modult kell tartalmaznia a fizikai Ethernet kábel és a hálózati stabilitás tesztelésére:

```
[Android App Hálózati Diagnosztika]
   ├── 1. Sávszélesség Modul (Speedtest) -> Fast Ethernet (100 Mbps) korlát detektálás
   ├── 2. Stabilitás Modul (WebRTC / UDP) -> Csomagveszteség, Jitter, Latency mérés
   └── 3. Fizikai Kontaktus Modul (ICMP)  -> Végtelenített háttér-ping valós idejű grafikonnal
```

---

## 2. Részletes Technikai Specifikáció és Logika

### M1: Sávszélesség és Sebességkorlát Teszt (Ookla Alapok)
*   **Cél:** A kábel fizikai érpár-hibáinak (szakadás, rossz crimpelés) felderítése a sávszélesség korlátai alapján.
*   **Technikai Logika:** Az alkalmazás töltsön le és fel adatokat dedikált szerverekről többszálú TCP kapcsolatokon keresztül (hasonlóan az [Ookla Speedtest](https://www.speedtest.net/ "Ookla Speedtest") CLI-hez).
*   **Kábelhiba Detektálási Küszöbérték:**
    *   Ha a mért letöltési/feltöltési sebesség **szigorúan 90–95 Mbps között** maximalizálódik, miközben az előfizetett csomag nagyobb (pl. 500 vagy 1000 Mbps), az alkalmazásnak **"Fizikai Kábelkorlát (Fast Ethernet)"** figyelmeztetést kell dobnia.
    *   *Magyarázat:* Az 1 Gbps-os (Gigabit) kapcsolathoz az RJ45-ös kábel mind a 8 rézszálára szükség van. Ha ebből akár csak egy is megszakad vagy rosszul érintkezik, a hálózati kártyák automatikusan visszaesnek 100 Mbps-os (Fast Ethernet) módba, ami a gyakorlatban ~94 Mbps nettó átvitelt jelent.

### M2: Stabilitás és Csomagveszteség Modul ([Packet Loss Test](https://packetlosstest.com/ "Packet Loss Test") Integráció)
*   **Cél:** A valós idejű, késleltetés-érzékeny alkalmazások (WebRTC, VoIP, Gaming) stream-minőségének szimulációja.
*   **Működési Elv (A packetlosstest.com alapján):**
    *   A hagyományos TCP-alapú sebességtesztek elrejtik a csomagveszteséget az automatikus újraküldés (TCP retransmission) miatt.
    *   Az alkalmazásban egy **WebRTC DataChannel**-t kell felépíteni, amelyet kötelezően `ordered: false` és `maxRetransmits: 0` paraméterekkel kell konfigurálni. Ez kikényszeríti a nyers, megbízhatatlan és nem szekvenciális **UDP/SCTP** datagram-kezelést (pufferelés és újraküldés nélkül).
*   **Mérési Paraméterek (Presetek):**
    *   **Általános kábelteszt:** 30–60 másodperces időtartam.
    *   **VoIP / WebRTC szimuláció:** Kis méretű csomagok (kb. 160-200 byte), magas frekvencia (pl. 50 csomag/másodperc).
    *   **Gaming szimuláció (Apex/Fortnite):** Közepes csomagméret, változó küldési ráta.
*   **Kinyerendő és Megjelenítendő Metrikák:**
    1.  **Packet Loss (Csomagveszteség %):** `(Elveszett csomagok / Összes elküldött csomag) * 100`.
        *   *Értékelés:* **0.0%** = Kiváló kábel; **0.1% – 0.5%** = Elfogadható; **> 1.0%** = Romló VoIP/Játék élmény; **> 5.0%** = Súlyos kábel- vagy hardverhiba.
    2.  **Latency / RTT (Késleltetés):** A kiküldött sorszámozott csomag és a szerverről visszaérkező echo közötti idő (milliszekundumban).
    3.  **Latency Jitter (Késleltetés-ingadozás):** Az egymást követő csomagok érkezési időkülönbségének varianciája (pl. RFC 3550 szerint számítva). A magas jitter instabil fizikai kapcsolatot jelez.

### M3: Haladó Kábelteszt (Folyamatos ICMP Ping)
*   **Cél:** A kábel mozgatásakor, hajlításakor jelentkező pillanatnyi kontaktushibák (szakaszos törések) valós idejű monitorozása.
*   **Android Implementáció:** Mivel a `Runtime.getRuntime().exec("ping -t 8.8.8.8")` nem minden Android verzión működik megbízhatóan és nehezen megszakítható, egy natív Java/Kotlin `InetAddress.isReachable()` alapú ciklust vagy egy háttérszálon futó ICMP socket-et kell megvalósítani.
*   **UX/UI Elvárás:**
    *   A teszt addig fusson, amíg a felhasználó le nem állítja (legyen egy "Stop" gomb).
    *   A kapott ping értékeket egy valós idejű vonaldiagramon (Line Chart) kell kirajzolni.
    *   Ha egy pingre nem érkezik válasz (időtúllépés / Request timed out), a grafikonon egy piros függőleges sáv vagy törés jelenjen meg, és az app adjon ki egy rövid hangjelzést (Audio feedback).
    *   *Felhasználói utasítás az appban:* "Indítsa el a tesztet, majd óvatosan mozgassa, hajlítsa meg a kábelt a csatlakozók közelében! Ha szakadást tapasztal, a kábel cserére szorul."

---

## 3. Implementációs Tennivalók Listája (Android Fejlesztői Taskok)

### ⬜ UI/UX Tervezés
*   [x] Egybefüggő Dashboard tervezése, ahol mindhárom teszt indítható. *(Sebességteszt képernyő, v0.1.10)*
*   [x] Valós idejű kördiagram a csomagveszteség ábrázolására (zöld/sárga/piros zónákkal).
*   [x] Valós idejű Line Chart a folyamatos ping modulhoz.
*   [x] Figyelmeztető ~~modális ablakok~~ kiértékelő sorok a hibákhoz (pl. "Fast Ethernet limit"). *Pop-up helyett a mérés alatt, színezve (zöld/kék/sárga/piros) - így futás közben nem takar el semmit; a Gyorsjelentésbe [HIBA]/[FIGYELEM] jelzéssel kerül.*

### ⬜ Network Réteg és Protokollok
*   [x] **TCP Engine:** Többszálú le- ÉS feltöltés (1/4/8 szál), felfutás-levágással, terhelés alatti késleltetéssel. *(speed/BandwidthTester.kt)*
*   [~] **WebRTC DataChannel Engine:** natív WebRTC-hez saját szerver-párt (signaling + peer) kellene üzemeltetni, ezért: (1) **natív UDP-motor** ugyanazzal a viselkedéssel (újraküldés/sorrendezés nélkül, DNS-visszhanggal) *(speed/UdpStabilityTester.kt)*, (2) az eredeti **packetlosstest.com WebRTC-teszt a beépített böngészőben** egy gombbal.
*   [x] **ICMP / Ping Worker:** Coroutine a teszt-motor scope-jában, 250/500/1000 ms-onként, átjáró / 8.8.8.8 / egyedi cél felé; ICMP híján TCP-tartalék. *(speed/ContinuousPinger.kt, IcmpPing.kt)*

### ⬜ Kiértékelő és Diagnosztikai Logika (Üzleti Logika)
*   [x] **Sebesség ellenőrző algoritmus:** *(speed/SpeedAnalyzer.kt - a p90 "plafon" 86-97 Mbps sávban; az előfizetésen túl a link-sebességet is figyeli, és ha nincs előfizetés megadva, akkor is figyelmeztet)*
    ```kotlin
    if (downloadSpeed in 90.0..96.0 && userContractSpeed > 100) {
        triggerWarning(WarningType.FAST_ETHERNET_LIMIT)
    }
    ```
*   [x] **Csomagveszteség kalkulátor:** Jitter (RFC 3550) és Packet Loss valós idejű aggregálása 1 másodperces ablakokban.
*   [x] **Statisztika generálás:** Összesített riport (Min/Max/Avg, Jitter, veszteség, diagnózis) - átirat a `log/tests/` alá, összefoglaló a Gyorsjelentésbe, előzmény hálózatonként (`profiles/speedtests.json`).

### ⬜ Hardver- és Rendszer-hozzáférések
*   [x] `android.permission.INTERNET` és `android.permission.ACCESS_NETWORK_STATE` engedélyek bekötése. *(már korábban megvolt)*
*   [x] Ethernet kapcsolat típus detektálása (`NetworkCapabilities.TRANSPORT_ETHERNET`) - figyelmeztetés, ha WiFi-n próbál kábelt tesztelni.

---
*Megjegyzés Claude számára: A kód megírásakor ügyelj a hálózati hívások aszinkron kezelésére (Kotlin Coroutines / Dispatchers.IO), hogy a UI ne fagyjon le a folyamatos ping vagy a WebRTC adatfolyam alatt.*

---

## 4. Megvalósítás v0.1.10 - a specifikáción TÚL (a felhasználói kérés alapján)

*   [x] **Következetes kiértékelés a már ismert adatokhoz képest:** előfizetett sebesség (hálózatonként megadható), WiFi PHY le/fel sebesség + sáv + jelerősség, vezetékes link-sebesség (ha olvasható), a saját korábbi mérések mediánja (azonos hálózat + kapcsolat-típus + szerver).
*   [x] **Bufferbloat** (terhelés alatti késleltetés-növekedés, A+..F osztályzat).
*   [x] **LAN-mérés külön kapcsolóval:** átjáró + DNS + a korábbi felderítő tesztek által látott eszközök; kis/nagy ping, veszteség, becsült útvonal-sebesség (kísérleti), kilógó eszközök jelölése.
*   [x] **Gyártó-specifikus port-sebesség lista** (`Android/app/src/main/assets/device_speed_catalog.json`), bővíthető: saját bejegyzés a "LISTÁBA" gombbal, a saját mérések automatikusan (`profiles/device_speeds_user.json`).
*   [x] **Windows `SpeedServer.ps1`** (Launcher 15.): valódi LAN-áteresztés mérése telefon ↔ PC között.

## 5. Ajánlott további bővítések (még NINCS kész)

*   [ ] **iperf3-kliens** az appban - ha a hálózaton van iperf3-szerver (NAS, router, PC), szabványos LAN-mérés.
*   [ ] **MTU / fragmentáció teszt** (ping "don't fragment" + csökkenő méret) - PPPoE/VPN MTU-hibák kimutatására.
*   [ ] **Időzített sebességteszt** (pl. óránként M1 + M2) és napszak szerinti grafikon - a szolgáltatói "esti lassulás" dokumentálására.
*   [ ] **Router-lekérdezés (SNMP ifSpeed / UPnP)**: a router WAN- és LAN-portjainak kapcsolt sebessége közvetlenül (ahol a router engedi).
*   [ ] **Traceroute-szerű ugrás-elemzés** (TTL-lel növelt ping, ahol a rendszer-ping támogatja) - hol nő meg a késleltetés az útvonalon.
*   [ ] **A saját bejegyzések visszavezetése** a repó listájába (export gomb a `device_speeds_user.json`-ból), és a Windows-oldal (NetworkDiag/MinerStatus) is használja ugyanezt a listát.
