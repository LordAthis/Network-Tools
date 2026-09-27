// Verzio: v0.1.0 - 2026-09-27
package hu.lordathis.networktools.speed

import hu.lordathis.networktools.speed.BandwidthTester.Companion.fmt
import hu.lordathis.networktools.speed.BandwidthTester.Companion.median

/**
 * A mérések KIÉRTÉKELÉSE - következetesen a már ismert adatokhoz képest:
 *  - előfizetett csomag (ha megadtad az adott hálózatra),
 *  - a kapcsolat papíron ismert sebessége (WiFi PHY rx/tx, vezetékes link-sebesség),
 *  - a saját korábbi mérések (ugyanazon a hálózaton, ugyanazzal a kapcsolat-típussal),
 *  - LAN-mérésnél a gyártó-specifikus port-sebesség lista és a többi eszköz (egymáshoz képest).
 */
object SpeedAnalyzer {

    /** Fast Ethernet (100 Mbps) nettó plafon: a gyakorlatban ~90-96 Mbps TCP-áteresztés. */
    private val FAST_ETHERNET_BAND = 86.0..97.0

    /** A WiFi valós áteresztőképessége a PHY-sebességhez képest (jellemző érték). */
    private const val WIFI_EFFICIENCY = 0.6

    fun analyzeBandwidth(
        r: BandwidthResult,
        link: LinkSnapshot?,
        contract: ContractSpeed?,
        previous: List<SpeedHistoryEntry>,
        isLanServer: Boolean,
    ): List<Finding> {
        val f = ArrayList<Finding>()
        val down = r.downloadMbps
        val up = r.uploadMbps
        if (down == null && up == null) {
            f += Finding(Severity.BAD, "Nem sikerült adatot átvinni - a szerver nem elérhető vagy a kapcsolat megszakadt.")
            return f
        }

        // --- Fast Ethernet (100 Mbps) plafon - a to-dos M1 fő kábelhiba-jele ---------------------------
        val plateauDown = r.downloadPeakMbps ?: down
        val plateauUp = r.uploadPeakMbps ?: up
        val downInBand = plateauDown != null && plateauDown in FAST_ETHERNET_BAND
        val upInBand = plateauUp != null && plateauUp in FAST_ETHERNET_BAND
        val contractDown = if (isLanServer) null else contract?.downMbps
        val linkClaimsGigabit = (link?.ethernetMbps ?: 0) >= 1000 || (link?.wifiRxMbps ?: link?.wifiLinkMbps ?: 0) >= 300
        if (downInBand || upInBand) {
            val which = listOfNotNull(if (downInBand) "letöltés" else null, if (upInBand) "feltöltés" else null).joinToString(" és ")
            when {
                (contractDown != null && contractDown > 100) || linkClaimsGigabit || isLanServer -> f += Finding(
                    Severity.BAD,
                    "Fizikai kábelkorlát (Fast Ethernet): a $which ~${fmt(if (downInBand) plateauDown else plateauUp)} Mbps-on " +
                        "\"plafonozik\", miközben a csomag/link ennél többet tudna. Egy 1 Gbps-os kapcsolathoz mind a 8 ér kell - " +
                        "ha csak egy is szakadt/rosszul krimpelt, a hálózati kártyák 100 Mbps-ra esnek vissza. Ellenőrizd az útvonal " +
                        "kábeleit (router ↔ switch ↔ eszköz), és hogy nincs-e 10/100-as switch vagy port a láncban."
                )
                else -> f += Finding(
                    Severity.WARN,
                    "Gyanús ~94 Mbps-os plafon ($which). Ha az előfizetésed 100 Mbps fölötti, ez 100 Mbps-os (Fast Ethernet) " +
                        "kapcsolatot jelez valahol az útvonalon - add meg lent az előfizetett sebességet a pontos kiértékeléshez."
                )
            }
        }
        if (link != null && link.isEthernet && link.ethernetMbps == 100) {
            f += Finding(Severity.WARN, "A telefon vezetékes linkje 100 Mbps-on kapcsolt (a rendszer szerint) - kábel/adapter/port korlát.")
        }

        // --- A WiFi mint szűk keresztmetszet --------------------------------------------------------
        val wifiRx = link?.wifiRxMbps ?: link?.wifiLinkMbps
        val wifiTx = link?.wifiTxMbps ?: link?.wifiLinkMbps
        val wifiDownCap = wifiRx?.let { it * WIFI_EFFICIENCY }
        val wifiUpCap = wifiTx?.let { it * WIFI_EFFICIENCY }
        if (link != null && link.isWifi) {
            if (down != null && wifiRx != null && down >= wifiRx * 0.5) {
                f += Finding(Severity.INFO, "A letöltés a WiFi rádiós linkjének (${wifiRx} Mbps PHY) határán van - itt a WiFi a szűk keresztmetszet, nem az internet.")
            }
            val rssi = link.wifiRssi
            if (rssi != null && rssi < -72) {
                f += Finding(Severity.WARN, "Gyenge WiFi-jel ($rssi dBm): menj közelebb az AP-hoz, vagy mérj kábelen a valós internet-sebességhez.")
            }
            if ((link.wifiFreqMhz ?: 0) in 1..2999 && (contractDown ?: 0) > 100) {
                f += Finding(Severity.INFO, "2,4 GHz-es sávon vagy - ez 100 Mbps fölötti előfizetést ritkán tud kihasználni; próbáld 5 GHz-en.")
            }
        }

        // --- Előfizetéshez képest -------------------------------------------------------------------
        if (!isLanServer && contract != null) {
            f += compareToContract("Letöltés", down, contract.downMbps, wifiDownCap)
            f += compareToContract("Feltöltés", up, contract.upMbps, wifiUpCap)
        } else if (!isLanServer && contract == null) {
            f += Finding(Severity.INFO, "Nincs megadva előfizetett sebesség ehhez a hálózathoz - add meg lent, és a mérést ahhoz is viszonyítom.")
        }

        // --- LAN-szerver: a link-sebességhez képest ---------------------------------------------------
        if (isLanServer && down != null) {
            val cap = link?.ethernetMbps?.toDouble() ?: wifiDownCap
            if (cap != null) {
                val ratio = down / cap
                f += if (ratio >= 0.7) Finding(Severity.OK, "A LAN-átvitel a kapcsolat várható felső határának ${pct(ratio)}-a - rendben.")
                else Finding(Severity.WARN, "A LAN-átvitel csak ${pct(ratio)}-a a várható ~${fmt(cap)} Mbps-nak - lassú szakasz/eszköz lehet az útvonalon (vagy a PC maga).")
            }
        }

        // --- Saját korábbi mérésekhez képest ----------------------------------------------------------
        val prevDown = previous.mapNotNull { it.downMbps }
        val med = median(prevDown)
        if (down != null && med != null && prevDown.size >= 3) {
            val ratio = down / med
            f += when {
                ratio < 0.6 -> Finding(Severity.WARN, "Jelentősen lassabb a szokásosnál: ${fmt(down)} vs. ${fmt(med)} Mbps (az eddigi ${prevDown.size} mérés mediánja).")
                ratio > 1.3 -> Finding(Severity.INFO, "Gyorsabb a szokásosnál: ${fmt(down)} vs. ${fmt(med)} Mbps (medián).")
                else -> Finding(Severity.OK, "A szokásos tartományban (eddigi medián: ${fmt(med)} Mbps, ${prevDown.size} mérés).")
            }
        }

        // --- Bufferbloat (terhelés alatti késleltetés-növekedés) --------------------------------------
        val idle = r.idleLatencyMs
        val loaded = r.loadedLatencyMs
        if (idle != null && loaded != null) {
            val delta = (loaded - idle).coerceAtLeast(0.0)
            val grade = when {
                delta < 5 -> "A+"
                delta < 30 -> "A"
                delta < 60 -> "B"
                delta < 200 -> "C"
                delta < 400 -> "D"
                else -> "F"
            }
            val sev = when (grade) {
                "A+", "A" -> Severity.OK
                "B" -> Severity.INFO
                "C" -> Severity.WARN
                else -> Severity.BAD
            }
            f += Finding(
                sev,
                "Bufferbloat: $grade (terhelés alatt +${fmt(delta)} ms: ${fmt(idle)} → ${fmt(loaded)} ms). " +
                    if (sev >= Severity.WARN) "Terhelés alatt a videóhívás/játék akadozni fog - a routeren az SQM/QoS (pl. fq_codel, cake) segít." else ""
            )
        }
        return f
    }

    private fun compareToContract(label: String, measured: Double?, contract: Int?, wifiCap: Double?): List<Finding> {
        if (measured == null || contract == null || contract <= 0) return emptyList()
        val ratio = measured / contract
        if (wifiCap != null && wifiCap < contract * 0.9 && measured >= wifiCap * 0.8) {
            return listOf(
                Finding(
                    Severity.INFO,
                    "$label: ${fmt(measured)} / $contract Mbps (${pct(ratio)}) - a WiFi-link (~${fmt(wifiCap)} Mbps valós) kisebb, " +
                        "mint az előfizetés, így ennél többet ezen a kapcsolaton nem is lehet mérni."
                )
            )
        }
        val sev = when {
            ratio >= 0.9 -> Severity.OK
            ratio >= 0.7 -> Severity.INFO
            ratio >= 0.4 -> Severity.WARN
            else -> Severity.BAD
        }
        return listOf(Finding(sev, "$label: ${fmt(measured)} / $contract Mbps előfizetés = ${pct(ratio)}."))
    }

    fun analyzeStability(r: StabilityResult, link: LinkSnapshot?): List<Finding> {
        val f = ArrayList<Finding>()
        val lossSev = lossSeverity(r.lossPct)
        val lossText = when (lossSev) {
            Severity.OK -> "kiváló kábel/kapcsolat"
            Severity.INFO -> "elfogadható"
            Severity.WARN -> "romló VoIP/játék-élmény"
            Severity.BAD -> "súlyos kábel- vagy hardverhiba gyanú"
        }
        f += Finding(lossSev, "Csomagvesztés: ${"%.2f".format(r.lossPct)}% (${r.sent - r.received}/${r.sent}) - $lossText.")
        if (r.late > 0) f += Finding(Severity.INFO, "${r.late} csomag 1 mp-nél később érkezett (veszteségnek számítva).")
        if (r.worstWindowLossPct >= 20 && r.lossPct < 5) {
            f += Finding(Severity.WARN, "Szakaszos kiesés: a legrosszabb 1 mp-es ablakban ${"%.0f".format(r.worstWindowLossPct)}% veszett el - pillanatnyi kontaktushiba vagy WiFi-zavar.")
        }
        val jitter = r.jitterMs
        if (jitter != null) {
            val limit = if (r.targetIsLocal) 5.0 else 30.0
            f += if (jitter > limit) Finding(Severity.WARN, "Magas jitter: ${"%.1f".format(jitter)} ms (${if (r.targetIsLocal) "LAN-on 5" else "interneten 30"} ms fölött) - instabil kapcsolat.")
            else Finding(Severity.OK, "Jitter: ${"%.1f".format(jitter)} ms - rendben.")
        }
        val avg = r.rttAvgMs
        if (avg != null && r.targetIsLocal) {
            val limit = if (link?.isEthernet == true) 3.0 else 20.0
            if (avg > limit) f += Finding(Severity.WARN, "Magas helyi késleltetés az átjáróig: ${"%.1f".format(avg)} ms (vártnál több) - túlterhelt router/AP vagy gyenge WiFi.")
        }
        if (r.sent > 0 && r.received == 0) {
            f += Finding(Severity.BAD, "Egyetlen válasz sem jött - a cél valószínűleg nem kezel DNS-t. Válassz másik célt (pl. 1.1.1.1).")
        }
        return f
    }

    fun lossSeverity(lossPct: Double): Severity = when {
        lossPct <= 0.0 -> Severity.OK
        lossPct <= 0.5 -> Severity.INFO
        lossPct <= 5.0 -> Severity.WARN
        else -> Severity.BAD
    }

    fun analyzePing(stats: PingStats, link: LinkSnapshot?, targetIsLocal: Boolean): List<Finding> {
        val f = ArrayList<Finding>()
        if (stats.sent == 0) return f
        if (link != null && !link.isEthernet) {
            f += Finding(Severity.INFO, "Nem vezetékes kapcsolaton mértél - az eredmény a WiFi-t is tartalmazza, nem csak a kábelt.")
        }
        when {
            stats.lost == 0 -> f += Finding(Severity.OK, "Nem volt kiesés ${stats.sent} ping alatt - a kábel/kapcsolat stabil.")
            stats.maxLossStreak >= 3 -> f += Finding(
                Severity.BAD,
                "Egymás után ${stats.maxLossStreak} kiesés - valódi megszakadás (kontaktushiba, törött ér, laza csatlakozó). " +
                    "Ha ez a kábel mozgatásakor jelentkezett, a kábel cserére szorul."
            )
            else -> f += Finding(
                lossSeverity(stats.lossPct),
                "${stats.lost} elszórt kiesés (${"%.1f".format(stats.lossPct)}%)" + if (targetIsLocal) " - helyi hálózaton ez már nem normális." else "."
            )
        }
        val max = stats.maxMs
        val avg = stats.avgMs
        if (max != null && avg != null && targetIsLocal && max > avg * 10 && max > 50) {
            f += Finding(Severity.WARN, "Nagy késleltetés-kiugrások (max ${fmt(max)} ms, átlag ${fmt(avg)} ms) - újra-tárgyalás (link renegotiation) vagy WiFi-zavar gyanú.")
        }
        return f
    }

    /**
     * LAN-mérés kiértékelése eszközönként: a többi eszközhöz (medián) és a gyártói listához képest.
     * A "szakasz-becslés" a kis és nagy ping RTT-különbségéből számol (szerializációs késés), ezért
     * csak ICMP-vel és vezetékes/jó WiFi mellett értelmes - kísérleti, tájékoztató jellegű.
     */
    fun analyzeLan(results: List<LanMeasurement>, link: LinkSnapshot?): Pair<List<LanMeasurement>, List<Finding>> {
        val general = ArrayList<Finding>()
        val deltas = results.mapNotNull { m ->
            val s = m.rttSmallMinMs
            val l = m.rttLargeMinMs
            if (s != null && l != null) l - s else null
        }
        val medianDelta = median(deltas)
        val medianRtt = median(results.mapNotNull { it.rttSmallMinMs })
        val annotated = results.map { m ->
            val f = ArrayList<Finding>()
            if (m.sent > 0 && m.lost == m.sent) {
                f += Finding(Severity.WARN, "Nem válaszolt a mérés alatt (lehet, hogy tűzfal tiltja a pinget, vagy közben elment).")
            } else if (m.lost > 0) {
                f += Finding(lossSeverity(m.lossPct), "Csomagvesztés: ${"%.0f".format(m.lossPct)}% (${m.lost}/${m.sent}).")
            }
            val rtt = m.rttSmallMinMs
            if (rtt != null && medianRtt != null && results.size >= 3 && rtt > medianRtt * 3 && rtt - medianRtt > 2) {
                f += Finding(Severity.WARN, "Lassabban válaszol, mint a többi eszköz (${fmt(rtt)} vs. medián ${fmt(medianRtt)} ms) - több switch/WiFi-ugrás vagy túlterhelt eszköz.")
            }
            val s = m.rttSmallMinMs
            val l = m.rttLargeMinMs
            if (s != null && l != null && medianDelta != null && results.size >= 3) {
                val d = l - s
                if (d > medianDelta * 3 && d - medianDelta > 0.15) {
                    f += Finding(
                        Severity.WARN,
                        "A nagy csomagok aránytalanul lassabbak ennél az eszköznél (+${"%.2f".format(d - medianDelta)} ms a mediánhoz képest) - " +
                            "lassú szakasz gyanú (pl. 100 Mbps-os port/switch vagy gyenge WiFi az eszköz felé)."
                    )
                }
            }
            val cat = m.catalog
            if (cat != null) {
                val exp = cat.expectedMbps
                val kind = if (cat.portMbps != null) "${cat.portMbps} Mbps-os vezetékes port" else "csak WiFi (~${cat.wifiMbps} Mbps PHY)"
                f += Finding(
                    if (exp != null && exp <= 100) Severity.INFO else Severity.OK,
                    "Gyártói lista: ${cat.vendor} ${cat.model} - $kind (megbízhatóság: ${cat.confidence})." +
                        if (exp != null && exp <= 100) " Ennél gyorsabb átvitel ettől az eszköztől NEM várható - ez nem hiba." else ""
                )
            }
            m.copy(findings = f)
        }
        if (link != null && link.isWifi) {
            general += Finding(Severity.INFO, "WiFi-n mértél: minden eszköz mérésében benne van a telefon ↔ AP rádiós szakasz is. A pontosabb szakasz-becsléshez vezetékes (USB-Ethernet) kapcsolat ajánlott.")
        }
        val slow = annotated.count { a -> a.findings.any { it.severity >= Severity.WARN } }
        general += if (slow == 0) Finding(Severity.OK, "${annotated.size} eszköz mérve - nem találtam kilógó, lassú szakaszra utaló eszközt.")
        else Finding(Severity.WARN, "${annotated.size} eszközből $slow mutat lassulásra/kiesésre utaló jelet - lásd az eszközöknél.")
        return annotated to general
    }

    private fun pct(ratio: Double): String = "%.0f%%".format(ratio * 100)
}
