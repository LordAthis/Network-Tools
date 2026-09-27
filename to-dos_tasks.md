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
*   [ ] Egybefüggő Dashboard tervezése, ahol mindhárom teszt indítható.
*   [ ] Valós idejű kördiagram a csomagveszteség ábrázolására (zöld/sárga/piros zónákkal).
*   [ ] Valós idejű Line Chart a folyamatos ping modulhoz.
*   [ ] Figyelmeztető modális ablakok (Pop-up) a hibák kiértékeléséhez (pl. "Fast Ethernet limit észlelve").

### ⬜ Network Réteg és Protokollok
*   [ ] **TCP Engine:** Többszálú letöltési logikát megírni a sávszélesség teszthez.
*   [ ] **WebRTC DataChannel Engine:** Új, testreszabható WebRTC kliens modul létrehozása. Beállítani az `ordered = false` és `maxRetransmits = 0` értékeket az igazi UDP szimulációhoz.
*   [ ] **ICMP / Ping Worker:** Egy `Coroutine` vagy `Background Service` létrehozása, ami 500ms-onként küld ICMP csomagot a Google DNS (`8.8.8.8`) vagy a helyi átjáró (Gateway) felé.

### ⬜ Kiértékelő és Diagnosztikai Logika (Üzleti Logika)
*   [ ] **Sebesség ellenőrző algoritmus:**
    ```kotlin
    if (downloadSpeed in 90.0..96.0 && userContractSpeed > 100) {
        triggerWarning(WarningType.FAST_ETHERNET_LIMIT)
    }
    ```
*   [ ] **Csomagveszteség kalkulátor:** Jitter és Packet Loss adatok valós idejű aggregálása 1 másodperces ablakokban.
*   [ ] **Statisztika generálás:** A teszt végén összesített riport készítése (Min/Max/Avg Ping, Jitter, Összes veszteség, Diagnózis).

### ⬜ Hardver- és Rendszer-hozzáférések
*   [ ] `android.permission.INTERNET` és `android.permission.ACCESS_NETWORK_STATE` engedélyek bekötése.
*   [ ] Ethernet kapcsolat típus detektálása (`NetworkCapabilities.TRANSPORT_ETHERNET`), hogy figyelmeztesse a felhasználót, ha Wi-Fi-n próbál kábelt tesztelni.

---
*Megjegyzés Claude számára: A kód megírásakor ügyelj a hálózati hívások aszinkron kezelésére (Kotlin Coroutines / Dispatchers.IO), hogy a UI ne fagyjon le a folyamatos ping vagy a WebRTC adatfolyam alatt.*
