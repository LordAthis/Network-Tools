<!-- Verzio: v0.1.0 - 2026-09-28 -->
# Linkelés - Network-Tools példányok összekapcsolása (terv + v1 előkészítés)

**Cél:** a Network-Tools példányok (Android app, Windowsos változat, később Linux) felismerik egymást, és
egymáshoz képest mérnek - a helyi hálózaton ÉS távoli gépek között is. Mindkét irány működjön:
a telefon küld és a Windows fogad, vagy fordítva.

Miért jó ez?

- **Pontosabb kábel- és stabilitásteszt:** nem egy DNS-szerver vagy router válaszára kell hagyatkozni, hanem egy
  ismert, a mérésre felkészített társra (UDP-visszhang, pontos csomagméret, sorszám, időbélyeg).
- **Valódi LAN-sávszélesség két pont között** (telefon ↔ PC, PC ↔ PC), több switch/router láncon át - itt látszik
  meg igazán, melyik szakasz lassú (pl. egy 100 Mbps-os switch a láncban).
- **Távoli felügyelet:** egy távoli telephelyen futó Windowsos példány "jelen van-e", milyen a kapcsolat minősége
  hozzá (RTT, veszteség), később távoli lekérdezések (miner-állapot, LOG-ok).

## v1 - ami MOST készült el (csak Android)

| Elem | Leírás |
|---|---|
| Port | **UDP 47800** (bemutatkozás + visszhang); **TCP 47801** fenntartva (sávszélesség, v2) |
| Üzenet | egy UDP-csomag = egy JSON-objektum, `"nt":"NetworkTools-Link"`, `"v":1` |
| Típusok | `HELLO`, `HELLO_REPLY`, `PING`, `PONG`, `BYE` |
| Felfedezés | `HELLO` broadcast (255.255.255.255 + az alháló broadcast-címe) és közvetlenül a megadott távoli címekre |
| Válaszadó | "Látható" módban a telefon a 47800-as porton figyel: `HELLO` → `HELLO_REPLY`, `PING` → `PONG` |
| Mérés | `PING`/`PONG` sorszámmal, opcionális kitöltéssel (`pad`) - RTT, veszteség |
| UI | Beállítások > **LINKELÉS (ELŐKÉSZÍTÉS)**: név, azonosító, Látható kapcsoló, távoli címek, KERESÉS, PING |

Két telefonon, ugyanazon a WiFi-n már most kipróbálható: az egyiken "Látható" BE, a másikon KERESÉS.

### Üzenet-formátum (v1)

```json
{
  "nt": "NetworkTools-Link",
  "v": 1,
  "type": "HELLO",
  "id": "7f3c9a2e-...",          // a példány állandó azonosítója (UUID)
  "name": "Ulefone Armor 14 Pro", // megjeleníthető név
  "platform": "android",         // android | windows | linux
  "app": "0.1.12",               // az app verziója
  "caps": ["hello", "echo"],     // képességek: echo, speed-server, miner, log ...
  "seq": 1,
  "ts": 1790000000000,           // küldési idő (ms) - csak tájékoztató, a RTT-t a KÜLDŐ méri
  "replyTo": "…",                // HELLO_REPLY / PONG: a kérdező azonosítója
  "pad": "xxxx…"                 // PING/PONG: kitöltés a csomagméret-szimulációhoz
}
```

A válaszadó a `PONG`-ban VISSZAKÜLDI a `seq`-et és a `pad`-et (azonos méret mindkét irányban).

## v2 - Windowsos oldal (következő kör)

- `win/LinkAgent.ps1` (Launcher új menüpont): ugyanez a protokoll PowerShellben (UdpClient), háttérben futtatható
  - válaszol a `HELLO`/`PING`-re, és maga is tud keresni/pingelni (fordított irány: a PC méri a telefont).
- A meglévő `SpeedServer.ps1` bekerül a képességek közé (`"caps": ["echo","speed-server"]`, a port a HELLO-ban),
  így a telefon **magától** megtalálja a LAN-os sebességmérő szervert - nem kell kézzel beírni a címét.
- TCP 47801: kétirányú sávszélesség-mérés két példány között (a SpeedServer protokolljával kompatibilisen).

## Távoli gépek (nem ugyanazon a helyi hálózaton)

A broadcast csak a helyi hálózaton működik. Távoli géphez három út van - ajánlott sorrendben:

1. **VPN / overlay hálózat (ajánlott):** pl. Tailscale, ZeroTier, WireGuard. A két gép egy "virtuális LAN"-ba
   kerül, a távoli gép saját címén (pl. 100.x.y.z) közvetlenül elérhető - a "Távoli címek" mezőbe ezt kell beírni.
   Nincs port-nyitás, titkosított, CGNAT mögött is működik (a mobilnet szinte mindig CGNAT - lásd a CGNAT-teszt).
2. **Port-továbbítás:** a távoli routeren az UDP 47800 továbbítása a Windowsos gépre + DDNS-név. Egyszerű, de
   CGNAT mögött (és sok mobil/optikai szolgáltatónál) NEM működik, és a portot az internet felé nyitja.
3. **Közvetítő (relay/rendezvous) szerver:** mindkét fél KIFELÉ kapcsolódik egy szerverhez (MQTT, WebSocket), és
   ott "találkoznak" a párosítókulcsból képzett csatornán. Mindig működik, de kell hozzá egy szerver (saját VPS
   vagy megbízható nyilvános szolgáltatás). v3 anyag.

## Biztonság - párosítás (v2, még NINCS kész)

v1-ben a láthatóság csak a nevet, platformot, verziót árulja el; adatot nem ad ki, parancsot nem fogad. Mielőtt
a link adatot/parancsot is szállítana (LOG-ok, miner-állapot, távoli mérés indítása), kötelező a párosítás:

- **Párosítókulcs:** az egyik példány 6 jegyű kódot / QR-kódot mutat, a másik beírja/beolvassa → közös titok.
- **HMAC-SHA256** minden üzenetre (`"sig"` mező) a közös titokkal + időbélyeg-ablak (visszajátszás ellen).
- Nem párosított társ: csak `HELLO`/`PING` (mint most), minden más üzenet eldobva.
- A párosított társak listája a profilokhoz hasonlóan titkosítva, a `profiles/` mappában.

## Kábelteszt linkkel (v2)

A mostani M2/M3 a routertől vagy egy DNS-szervertől kapott válaszra épül. Link esetén:
- **M2 "Linkelt társ" cél:** `PING`/`PONG` a preset szerinti rátával és mérettel (VoIP 50/s ~172 B stb.) -
  a veszteség így a két pont közötti teljes útvonalé, nem egy DNS-továbbítóé.
- **M3 társ felé:** a kábel mindkét végén lehet mérni (telefon ↔ PC), és a PC oldali mérés a telefonén kívüli
  szakaszokat is kiméri.
- **Egyirányú veszteség:** a sorszámokból megállapítható, hogy oda vagy vissza irányban veszett-e el a csomag
  (ehhez a PONG-ban a társ a saját fogadási számlálóját is visszaküldi - v2 bővítés).
