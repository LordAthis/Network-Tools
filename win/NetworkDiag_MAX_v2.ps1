#Requires -Version 5.2
<#
    NetworkDiag MAX v2 - Maximalisan agressziv LAN + mobilnet diagnosztika
    Cel: semmi se maradjon rejtve - ASIC minerek, "nema" switchek, portolt/tiltott
         eszkozok, lassu valaszok, ismert mining pool-okra meno kapcsolatok, mobilnet.

    Hasznalat:
        - Kuldd el ezt a fajlt (es melle a datas\ mappat) a felhasznalonak
        - Jobb klikk -> "Futtatas PowerShell-lel" (vagy dupla katt, ha .ps1 tarsitva van)
        - A script magatol ker admin jogot
        - A futas vegen a script melletti LOG mappaban keletkezo .txt fajlt kell visszakuldeni
        - A script a vegen rakerdez, hogy megnyissa-e a LOG mappat (alapbol Igen -> csak Entert kell nyomni)

    Kimenet: <script mappaja>\LOG\NetworkDiag_MAX_YYYYMMDD_HHMMSS.txt

    Konfiguracios fajlok (nem kotelezoek - ha hianyoznak, a script automatikusan
    letrehozza oket alapertelmezett tartalommal az elso futaskor), a script melletti
    datas\ mappaban:
        datas\iplist.json          - vizsgalando fix alhalok, JSON tombkent
                                      pl.: ["192.168.0.0/24","192.168.8.0/24","10.0.5.0/24"]
        datas\minerpoollist.json   - ismert mining pool kulcsszavak (hostname/PTR egyezeshez)
                                      pl.: ["pool","stratum","nanopool","antpool"]
        datas\knownservices.json   - ismert/legitim kulso szolgaltatasok (hostname/PTR egyezeshez),
                                      pl. tavsegitseg (TeamViewer) es felho szinkron (OneDrive) -
                                      ezek kulon, "nem gyanus" kategoriaban jelennek meg a logban
                                      pl.: ["teamviewer","onedrive","googledrive","dropbox"]
        datas\disableips.json      - kizarando IP-k/mintak - hamis pozitivok elkerulesehez
                                      (a broadcast/multicast cimeket a script MINDIG automatikusan
                                      kiszuri, ez a lista tovabbi, kezzel felvett kivetelekre valo)
                                      pl.: ["10.0.0.99","192.168.1.254"]
        datas\smartlist.json       - TV/okoseszkoz/halozati eszkoz felismero kulcsszavak
                                      (hostname/NetBIOS nev alapjan, v1: csak nev-egyezes)
                                      pl.: ["smarttv","chromecast","mikrotik","hikvision"]
        datas\macouilist.json      - MAC-cim (OUI/gyarto-elotag) -> gyarto/kategoria lista.
                                      A legrobusztusabb felismeres, mert MAC MINDIG lathato
                                      ARP-bol, meg akkor is, ha az eszkoz semmi masra nem
                                      valaszol (pl. hibas ASIC miner web klienssel).
                                      Bovitesehez hasznald a Build-OuiList.ps1 scriptet.
        datas\Routers.json         - ismert routerek listaja (topologia terkepezeshez)
        datas\Switchs.json         - ismert switchek listaja (topologia terkepezeshez,
                                      a 6/C SNMP-felderites innen tudja, mi mar ismert)
    Ezek szerkesztesevel a keresesi tartomany es a felismeres bovitheto
    a script kodjanak modositasa nelkul.

    Megjegyzes: a LOG\ mappat erdemes .gitignore-ba tenni (szemelyes halozati adatokat,
    IP-ket, MAC cimeket tartalmaz) - a datas\ mappa viszont mehet verziokezelobe.
#>

param(
    [switch]$NoElevation,
    [int]$ThrottleLimit = 64,       # parhuzamos ping-sweep szalak
    [int]$PortThrottleLimit = 120,  # parhuzamos portscan szalak
    [string[]]$ExtraSubnets = @(),  # pl. -ExtraSubnets "10.0.0.0/24","172.16.5.0/24"
    [string]$IpListPath = "$PSScriptRoot\datas\iplist.json",           # vizsgalando alhalok listaja (JSON tomb) - a script melletti datas\ mappaban
    [string]$MinerPoolListPath = "$PSScriptRoot\datas\minerpoollist.json",  # ismert mining pool kulcsszavak (JSON tomb) - a script melletti datas\ mappaban
    [string]$KnownServicesListPath = "$PSScriptRoot\datas\knownservices.json",  # ismert/legitim kulso szolgaltatasok (TeamViewer, OneDrive stb.) - JSON tomb
    [string]$DisableIpsListPath = "$PSScriptRoot\datas\disableips.json",  # kizarando IP-k/mintak (hamis pozitivok: broadcast, multicast, stb.) - JSON tomb
    [string]$SmartListPath = "$PSScriptRoot\datas\smartlist.json",  # okoseszkoz/TV/halozati eszkoz felismero kulcsszavak - JSON tomb
    [string]$MacOuiListPath = "$PSScriptRoot\datas\macouilist.json",  # MAC-cim (OUI) -> gyarto/kategoria lista - JSON objektum
    [string]$RoutersListPath = "$PSScriptRoot\datas\Routers.json",  # ismert routerek listaja - topologia terkepezeshez
    [string]$SwitchesListPath = "$PSScriptRoot\datas\Switchs.json",  # ismert switchek listaja - topologia terkepezeshez
    [string]$LogDir = "$PSScriptRoot\LOG"  # a kimeneti log fajlok mappaja - a script melletti LOG\ mappa
)

# ==================== 0. SCRIPT ELERESI UT (irm | iex vedelem) ====================
$ScriptPath = $MyInvocation.MyCommand.Path

# ==================== JOGOSULTSAG EMELES ====================
function Test-Admin {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $NoElevation -and -not (Test-Admin)) {
    if ($ScriptPath) {
        Write-Host "Jogosultsag emeles szukseges. Ujrainditas adminisztratorkent..." -ForegroundColor Yellow
        Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`" -NoElevation -ThrottleLimit $ThrottleLimit -PortThrottleLimit $PortThrottleLimit -IpListPath `"$IpListPath`" -MinerPoolListPath `"$MinerPoolListPath`" -KnownServicesListPath `"$KnownServicesListPath`" -DisableIpsListPath `"$DisableIpsListPath`" -SmartListPath `"$SmartListPath`" -MacOuiListPath `"$MacOuiListPath`" -RoutersListPath `"$RoutersListPath`" -SwitchesListPath `"$SwitchesListPath`" -LogDir `"$LogDir`""
        exit
    } else {
        Write-Host "FIGYELEM: A script nem .ps1 fajlbol fut (pl. irm | iex), ezert automatikus admin-emeles nem lehetseges." -ForegroundColor Red
        Write-Host "Mentsd el .ps1 fajlba es ugy inditsd 'Futtatas PowerShell-lel adminisztratorkent' -val, kulonben egyes tesztek hianyosak lesznek (esemenynaplo, adapter statisztika)." -ForegroundColor Yellow
    }
}

# ==================== KIMENETI MAPPA ====================
$OutDir = $LogDir
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$OutFile = Join-Path $OutDir "NetworkDiag_MAX_$Timestamp.txt"
$Log = [System.Collections.Generic.List[string]]::new()

function Write-Log {
    param([string]$Message, [string]$Color = "White")
    $line = "$(Get-Date -Format 'HH:mm:ss') | $Message"
    $Log.Add($line)
    Write-Host $line -ForegroundColor $Color
}

function Write-Block {
    param([string]$Text)
    $Log.Add($Text)
    Write-Host $Text
}

Write-Log "=== MAXIMALIS HALOZATI DIAGNOSZTIKA v2 INDUL ===" "Cyan"
Write-Log "Gep: $env:COMPUTERNAME | Felhasznalo: $env:USERNAME | Ido: $(Get-Date)"
Write-Log "Kimeneti fajl: $OutFile"
Write-Log "IP/subnet lista fajl: $IpListPath"
Write-Log "Mining pool kulcsszo lista fajl: $MinerPoolListPath"
Write-Log "Ismert kulso szolgaltatas lista fajl: $KnownServicesListPath"
Write-Log "Cel: semmi se maradjon rejtve (ASIC miner, nema switch, lassu/portolt eszkoz, mobilnet, mining pool kapcsolatok)"
Write-Log ""

# ==================== 0/B. POWERSHELL VERZIO ES KEPESSEGEK ====================
Write-Log "=== 0. POWERSHELL VERZIO ES KORNYEZET ===" "Cyan"
$PSVer = $PSVersionTable.PSVersion
$IsPS6Plus = $PSVer.Major -ge 6
Write-Log "PowerShell verzio: $($PSVer.ToString()) | Edition: $($PSVersionTable.PSEdition)"
if ($IsPS6Plus) {
    Write-Log "OS: $($PSVersionTable.OS)"
    if (-not $IsWindows) {
        Write-Log "Ez a script csak Windows rendszeren futtathato (Windows-specifikus cmdlet-eket hasznal). Kilepes." "Red"
        $Log | Out-File -FilePath $OutFile -Encoding UTF8
        exit
    }
}
Write-Log "Admin jogosultsag: $(Test-Admin)"

# Kepesseg-detektalas - hogy tudjuk, mit tudunk kihasznalni
$Cap = [ordered]@{
    NetAdapter        = [bool](Get-Command Get-NetAdapter -ErrorAction SilentlyContinue)
    NetIPAddress      = [bool](Get-Command Get-NetIPAddress -ErrorAction SilentlyContinue)
    NetNeighbor       = [bool](Get-Command Get-NetNeighbor -ErrorAction SilentlyContinue)
    NetTCPConnection  = [bool](Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)
    NetAdapterStats   = [bool](Get-Command Get-NetAdapterStatistics -ErrorAction SilentlyContinue)
    ResolveDnsName    = [bool](Get-Command Resolve-DnsName -ErrorAction SilentlyContinue)
    WinEvent          = [bool](Get-Command Get-WinEvent -ErrorAction SilentlyContinue)
}
foreach ($k in $Cap.Keys) {
    Write-Log "  Elerheto: $k -> $($Cap[$k])"
}
Write-Log ""

# ==================== SEGEDFUGGVENYEK ====================

# --- .NET alapu ping teszt: verzio-fuggetlen, nem hasznalja a Test-Connection
#     -TimeoutSeconds parameteret (az csak PS 6+ alatt letezik, 5.1-nel hibat dob!)
function Test-PingHost {
    param(
        [string]$TargetIP,
        [int]$Attempts = 3,
        [int]$TimeoutMs = 800
    )
    $successCount = 0
    $totalTime = 0
    $details = New-Object System.Collections.Generic.List[string]
    try {
        $pinger = New-Object System.Net.NetworkInformation.Ping
    } catch {
        return [PSCustomObject]@{ IP=$TargetIP; SuccessCount=0; AvgMs=$null; Details="Ping objektum hiba"; Alive=$false; Slow=$false }
    }
    for ($i = 1; $i -le $Attempts; $i++) {
        try {
            $reply = $pinger.Send($TargetIP, $TimeoutMs)
            if ($reply -and $reply.Status -eq [System.Net.NetworkInformation.IPStatus]::Success) {
                $successCount++
                $totalTime += $reply.RoundtripTime
                $details.Add("OK $($reply.RoundtripTime)ms")
            } else {
                $st = if ($reply) { $reply.Status } else { "FAIL" }
                $details.Add("FAIL($st)")
            }
        } catch {
            $details.Add("EXC")
        }
        Start-Sleep -Milliseconds 100
    }
    $pinger.Dispose()
    $avg = if ($successCount -gt 0) { [math]::Round($totalTime / $successCount, 0) } else { $null }
    [PSCustomObject]@{
        IP           = $TargetIP
        SuccessCount = $successCount
        AvgMs        = $avg
        Details      = ($details -join " | ")
        Alive        = ($successCount -gt 0)
        Slow         = ($successCount -gt 0 -and $avg -gt 80)
    }
}

# --- Konnyusulyu, szal (runspace) alapu parhuzamositas Start-Job helyett.
#     Sokkal kevesebb eroforrast hasznal, mint egy kulon process/job elem.
function Invoke-Parallel {
    param(
        [Parameter(Mandatory)][array]$InputItems,
        [Parameter(Mandatory)][scriptblock]$ScriptBlock,
        [int]$Throttle = 64
    )
    $results = [System.Collections.Generic.List[object]]::new()
    if ($InputItems.Count -eq 0) { return $results }

    $pool = [runspacefactory]::CreateRunspacePool(1, [Math]::Max(1,$Throttle))
    $pool.Open()
    $tasks = New-Object System.Collections.Generic.List[object]

    foreach ($item in $InputItems) {
        $ps = [powershell]::Create()
        $ps.RunspacePool = $pool
        [void]$ps.AddScript($ScriptBlock).AddArgument($item)
        $handle = $ps.BeginInvoke()
        $tasks.Add([PSCustomObject]@{ PS = $ps; Handle = $handle })
    }

    foreach ($t in $tasks) {
        try {
            $out = $t.PS.EndInvoke($t.Handle)
            foreach ($o in $out) { $results.Add($o) }
        } catch {
        } finally {
            $t.PS.Dispose()
        }
    }

    $pool.Close()
    $pool.Dispose()
    return $results
}

# --- IP + prefix -> halozati CIDR
function Get-NetworkCidr {
    param([string]$IPAddress, [int]$PrefixLength)
    try {
        $ipBytes = ([System.Net.IPAddress]$IPAddress).GetAddressBytes()
        [Array]::Reverse($ipBytes)
        $ipInt = [BitConverter]::ToUInt32($ipBytes, 0)
        $hostBits = 32 - $PrefixLength
        $mask = if ($hostBits -ge 32) { [uint32]0 } else { [uint32]::MaxValue -shl $hostBits }
        $networkInt = $ipInt -band $mask
        $netBytes = [BitConverter]::GetBytes([uint32]$networkInt)
        [Array]::Reverse($netBytes)
        $networkIP = ([System.Net.IPAddress]$netBytes).ToString()
        return "$networkIP/$PrefixLength"
    } catch { return $null }
}

# --- CIDR -> az adott alhalo broadcast cime (pl. "192.168.1.0/24" -> "192.168.1.255")
#     Ez kell ahhoz, hogy a broadcast cimet a "nema eszkoz" felismeresbol kizarjuk -
#     a broadcast cim SOHA nem valodi eszkoz, hanem protokollszintu cim.
function Get-BroadcastAddress {
    param([string]$NetworkCidr)
    try {
        $parts = $NetworkCidr -split '/'
        $ipBytes = ([System.Net.IPAddress]$parts[0]).GetAddressBytes()
        [Array]::Reverse($ipBytes)
        $networkInt = [BitConverter]::ToUInt32($ipBytes, 0)
        $prefix = [int]$parts[1]
        $hostBits = 32 - $prefix
        $hostMask = if ($hostBits -ge 32) { [uint32]::MaxValue } else { ([uint32]1 -shl $hostBits) - [uint32]1 }
        $broadcastInt = $networkInt -bor $hostMask
        $bytes = [BitConverter]::GetBytes([uint32]$broadcastInt)
        [Array]::Reverse($bytes)
        return ([System.Net.IPAddress]$bytes).ToString()
    } catch { return $null }
}

# --- CIDR -> host IP lista (max ~1022 host, biztonsagi korlat)
function Get-SubnetHosts {
    param([string]$NetworkCidr)
    try {
        $parts = $NetworkCidr -split '/'
        $ipBytes = ([System.Net.IPAddress]$parts[0]).GetAddressBytes()
        [Array]::Reverse($ipBytes)
        $networkInt = [BitConverter]::ToUInt32($ipBytes, 0)
        $prefix = [int]$parts[1]
        $hostBits = 32 - $prefix
        $numHosts = [math]::Pow(2, $hostBits) - 2
        if ($numHosts -lt 1) { $numHosts = 0 }
        if ($numHosts -gt 1022) { $numHosts = 1022 }  # biztonsagi korlat (kb. egy /22-nyi)
        $ips = New-Object System.Collections.Generic.List[string]
        for ($i = 1; $i -le $numHosts; $i++) {
            $hostInt = $networkInt + $i
            $bytes = [BitConverter]::GetBytes([uint32]$hostInt)
            [Array]::Reverse($bytes)
            $ips.Add(([System.Net.IPAddress]$bytes).ToString())
        }
        return $ips
    } catch { return @() }
}

# --- Kulso JSON fajlbol listat betolto segedfuggveny.
#     Ha a fajl nem letezik, letrehozza az alapertelmezett tartalommal (elso futaskor sem hal el),
#     ha letezik de hibas/ures, az alapertelmezett listaval fut tovabb.
function Get-JsonList {
    param(
        [string]$Path,
        [array]$DefaultValue,
        [string]$Label
    )
    if (Test-Path $Path) {
        try {
            $raw = Get-Content -Path $Path -Raw -Encoding UTF8
            $data = $raw | ConvertFrom-Json -ErrorAction Stop
            $list = @($data) | Where-Object { $_ -and $_.ToString().Trim() -ne "" }
            if ($list.Count -gt 0) {
                Write-Log "$Label betoltve fajlbol: $Path ($($list.Count) elem)" "Green"
                return $list
            } else {
                Write-Log "$Label fajl ures vagy ervenytelen ($Path) - alapertelmezett lista hasznalva." "Yellow"
                return $DefaultValue
            }
        } catch {
            Write-Log "$Label fajl beolvasasi hiba ($Path): $_ - alapertelmezett lista hasznalva." "Red"
            return $DefaultValue
        }
    } else {
        Write-Log "$Label fajl nem talalhato ($Path) - letrehozom alapertelmezett tartalommal." "Yellow"
        try {
            $dir = Split-Path $Path -Parent
            if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
            $DefaultValue | ConvertTo-Json | Out-File -FilePath $Path -Encoding UTF8
            Write-Log "Alapertelmezett $Label fajl letrehozva: $Path - ezt szerkesztve bovitheted legkozelebb, kod modositasa nelkul."
        } catch {
            Write-Log "Nem sikerult letrehozni a(z) $Label fajlt ($Path): $_ - csak memoriaban, alapertelmezett listaval fut tovabb." "Red"
        }
        return $DefaultValue
    }
}

# --- Ismert mining pool kulcsszavak (hostname / PTR alapu felismereshez)
#     Fajlbol toltve: /datas/minerpoollist.json (JSON tomb, pl. ["pool","stratum","nanopool", ...])
#     Ha nincs ilyen fajl, a script letrehozza az alabbi alapertelmezett tartalommal.
$DefaultMiningPoolKeywords = @(
    "pool","stratum","nanopool","ethermine","antpool","f2pool","viabtc","poolin",
    "2miners","herominers","unmineable","hiveon","luxor","slushpool","emcd",
    "btc.com","foundryusa","binance","ocean.xyz","kryptex","mining"
)
$MiningPoolKeywords = Get-JsonList -Path $MinerPoolListPath -DefaultValue $DefaultMiningPoolKeywords -Label "Mining pool kulcsszo lista"

function Test-MiningIndicator {
    param([string]$HostnameOrText)
    if ([string]::IsNullOrWhiteSpace($HostnameOrText)) { return $false }
    foreach ($kw in $MiningPoolKeywords) {
        if ($HostnameOrText -match [regex]::Escape($kw)) { return $true }
    }
    return $false
}

# --- Ismert / legitim kulso szolgaltatasok (tavoli eleres, felho tarhely, stb.)
#     Kulon lista a mining pool listatol - ez NEM gyanus mintazat, hanem tudatos
#     feherlista, hogy pl. a TeamViewer-en keresztuli tavsegitseg forgalma vagy a
#     felho szinkron szolgaltatasok (OneDrive, Google Drive) egyertelmuen "ismert,
#     rendben levo" kapcsolatkent jelenjenek meg a logban, ne keveredjenek a
#     gyanus talalatok koze es ne okozzanak felreertest.
#     Fajlbol toltve: /datas/knownservices.json (JSON tomb, pl. ["teamviewer","onedrive", ...])
#     Ha nincs ilyen fajl, a script letrehozza az alabbi alapertelmezett tartalommal.
$DefaultKnownServiceKeywords = @(
    "teamviewer","anydesk","logmein","chrome-remote-desktop","remotedesktop",
    "skype","teams","zoom","discord","whatsapp",
    "onedrive","live.com","1drv","office365","office.com","microsoft","windowsupdate","msftconnecttest",
    "googledrive","googleusercontent","google.com","gstatic",
    "dropbox","icloud","apple.com",
    "akamai","cloudflare","amazonaws","azureedge","azure","cloudfront",
    "steam","steampowered","valve",
    "github","githubusercontent"
)
$KnownServiceKeywords = Get-JsonList -Path $KnownServicesListPath -DefaultValue $DefaultKnownServiceKeywords -Label "Ismert kulso szolgaltatas lista"

function Test-KnownServiceIndicator {
    param([string]$HostnameOrText)
    if ([string]::IsNullOrWhiteSpace($HostnameOrText)) { return $null }
    foreach ($kw in $KnownServiceKeywords) {
        if ($HostnameOrText -match [regex]::Escape($kw)) { return $kw }
    }
    return $null
}

# --- Kizarando IP-k / mintak listaja (hamis pozitivok: broadcast, multicast, protokoll-cimek, stb.)
#     Ezek NEM valodi eszkozok, hanem protokollszintu cimek, amik az ARP tablaban
#     "Permanent" bejegyzeskent szoktak megjelenni annak ellenere, hogy senki nem valaszol
#     rajuk pingre - a script korabban tevesen "nema" eszkoznek vette oket.
#     A multicast (224.0.0.0/4) es a 255.255.255.255 tartomanyt a script MINDIG,
#     programozottan is kiszuri (lasd Test-IsNoiseAddress), ez a lista csak a
#     TOVABBI, kezzel felvett kivetelekre valo (pl. egy adott nyomtato/eszkoz IP-je,
#     ami tudottan false-positive-ot ad).
#     Fajlbol toltve: /datas/disableips.json (JSON tomb, pl. ["192.168.1.255","224.0.0.251", "10.0.0.99"])
$DefaultDisableIps = @(
    "224.0.0.22", "224.0.0.251", "224.0.0.252", "239.255.255.250", "255.255.255.255"
)
$DisableIps = Get-JsonList -Path $DisableIpsListPath -DefaultValue $DefaultDisableIps -Label "Kizarando IP lista"

function Test-IsNoiseAddress {
    param(
        [string]$IP,
        [string[]]$BroadcastAddresses = @(),
        [string[]]$DisabledIps = @()
    )
    if ([string]::IsNullOrWhiteSpace($IP)) { return $true }
    if ($IP -eq "255.255.255.255" -or $IP -eq "0.0.0.0") { return $true }
    $octets = $IP -split '\.'
    if ($octets.Count -eq 4) {
        try {
            $first = [int]$octets[0]
            if ($first -ge 224 -and $first -le 239) { return $true }  # multicast (224.0.0.0/4, ide esik az SSDP 239.x is)
        } catch { }
    }
    if ($BroadcastAddresses -contains $IP) { return $true }  # a vizsgalt subnetek sajat broadcast cime (pl. .255)
    if ($DisabledIps -contains $IP) { return $true }
    foreach ($pattern in $DisabledIps) {
        if ($pattern -match '[\*\?]' -and $IP -like $pattern) { return $true }  # wildcard tamogatas, pl. "224.*"
    }
    return $false
}

# --- Okoseszkoz / TV / halozati eszkoz felismero kulcsszavak (hostname / NetBIOS alapjan)
#     KEZDETI (v1) valtozat: csak nev alapu egyezes. Egy kesobbi bovitesben erdemes lenne
#     MAC-cim (OUI - gyarto-prefix) alapu felismerest is hozzatenni, mert sok okoseszkoz
#     (pl. IoT szenzor) nem ad ki ertelmes hostnevet, csak a MAC-cim arulja el a gyartot.
#     Fajlbol toltve: /datas/smartlist.json (JSON tomb, pl. ["smarttv","chromecast","hue", ...])
$DefaultSmartDeviceKeywords = @(
    "smarttv","android-tv","androidtv","tizen","webos","bravia","aquos",
    "samsung","lg-","sony","philips","hisense","tcl-","panasonic","vizio",
    "roku","chromecast","google-home","googlehome","nest-","firetv","fire-tv","appletv","apple-tv",
    "xbox","playstation","ps4","ps5","nintendo","switch-",
    "sonos","hue-bridge","shelly","tasmota","esp32","esp8266","sonoff","tuya","tplink","tp-link","xiaomi","mi-router",
    "printer","hp-print","canon","epson","brother",
    "hikvision","dahua","reolink","foscam","axis-",
    "mikrotik","ubnt","unifi","tenda","dlink","d-link","netgear","asus-router","zyxel","huawei-",
    "raspberrypi","raspberry","esphome"
)
$SmartDeviceKeywords = Get-JsonList -Path $SmartListPath -DefaultValue $DefaultSmartDeviceKeywords -Label "Okoseszkoz felismero lista"

function Test-SmartDeviceIndicator {
    param([string]$HostnameOrText)
    if ([string]::IsNullOrWhiteSpace($HostnameOrText)) { return $null }
    foreach ($kw in $SmartDeviceKeywords) {
        if ($HostnameOrText -match [regex]::Escape($kw)) { return $kw }
    }
    return $null
}

# ==================== 1. ALAP ADAPTER + IP ====================
Write-Log "=== 1. HALOZATI ADAPTEREK ES IP KONFIGURACIO ===" "Cyan"
try {
    $adapters = Get-NetAdapter | Sort-Object Name
    foreach ($a in $adapters) {
        Write-Log "Adapter: $($a.Name) | Status: $($a.Status) | MAC: $($a.MacAddress) | LinkSpeed: $($a.LinkSpeed) | MediaType: $($a.MediaType)"
    }
} catch {
    Write-Log "Get-NetAdapter hiba: $_" "Red"
}
Write-Log ""
Write-Log "--- ipconfig /all ---"
Write-Block (ipconfig /all 2>&1 | Out-String)

Write-Log ""
Write-Log "--- Get-NetIPConfiguration (gateway, DNS, interfeszenkent) ---"
try {
    Get-NetIPConfiguration | Format-List InterfaceAlias, IPv4Address, IPv4DefaultGateway, DNSServer |
        Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Get-NetIPConfiguration hiba: $_" "Red"
}

Write-Log ""
Write-Log "--- Get-NetIPAddress ---"
try {
    Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" } |
        Format-Table InterfaceAlias, IPAddress, PrefixLength, AddressState -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Get-NetIPAddress hiba: $_" "Red"
}

# ==================== 2. MOBILNET / WWAN / CELLULAR ====================
Write-Log ""
Write-Log "=== 2. MOBILNET (SIM / WWAN / CELLULAR) INFORMACIOK ===" "Cyan"
Write-Log "A router SIM-kartyas mobilinternetet hasznal. Az alabbiak a Windows oldali Mobile Broadband adatokat mutatjak."
try {
    $wwanAdapters = Get-NetAdapter | Where-Object {
        $_.InterfaceDescription -match "Mobile|WWAN|Cellular|LTE|5G|Broadband|Sierra|Quectel|Huawei|Fibocom|SIM" -or
        $_.MediaType -match "Wireless WAN|WWAN"
    }
    if ($wwanAdapters) {
        Write-Log "Talalt WWAN / Mobile Broadband adapter(ek):" "Green"
        foreach ($w in $wwanAdapters) {
            Write-Log "  Nev: $($w.Name) | Status: $($w.Status) | MAC: $($w.MacAddress) | Desc: $($w.InterfaceDescription) | Speed: $($w.LinkSpeed)"
        }
    } else {
        Write-Log "Nem talalhato klasszikus WWAN adapter ezen a gepen (a mobilnet a routeren van, nem a PC-n)." "Yellow"
        Write-Log "Ez normalis, ha a SIM a routerben van."
    }
} catch {
    Write-Log "WWAN adapter kereses hiba: $_" "Red"
}
Write-Log ""
foreach ($cmd in @(
    @{ Title = "netsh mbn show interfaces";               Args = "mbn show interfaces" },
    @{ Title = "netsh mbn show connection interface=*";   Args = "mbn show connection interface=*" },
    @{ Title = "netsh mbn show profiles";                 Args = "mbn show profiles" },
    @{ Title = "netsh mbn show readyinfo interface=*";    Args = "mbn show readyinfo interface=*" },
    @{ Title = "netsh mbn show signal interface=*";       Args = "mbn show signal interface=*" }
)) {
    Write-Log "--- $($cmd.Title) ---"
    try {
        $out = & netsh $cmd.Args.Split(" ") 2>&1 | Out-String
        Write-Block $out
    } catch { }
}
Write-Log ""
Write-Log "MOBILNET MEGJEGYZESEK:" "Yellow"
Write-Log "- Ha a SIM a routerben van, a fenti netsh mbn parancsok gyakran uresek vagy hibat adnak (ez normalis)."
Write-Log "- A router WAN oldala CGNAT-on lehet -> befele (port forward) altalaban nem mukodik."
Write-Log "- A belso halozati problemak (miner-ek, switch-ek, lassu eszkozok) fuggetlenek a mobilnet minosegetol."

# ==================== 3. ARP / IPv4+IPv6 NEIGHBOR ====================
Write-Log ""
Write-Log "=== 3. ARP TABLA + Get-NetNeighbor (IPv4 es IPv6, minden allapot) ===" "Cyan"
$Neighbors4 = @()
try {
    $Neighbors4 = Get-NetNeighbor -AddressFamily IPv4 | Sort-Object IPAddress
    Write-Log "Get-NetNeighbor (IPv4) osszes bejegyzes: $($Neighbors4.Count)"
    $Neighbors4 | Format-Table IPAddress, LinkLayerAddress, State, InterfaceAlias -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Get-NetNeighbor (IPv4) hiba: $_" "Red"
}
Write-Log "--- arp -a ---"
Write-Block (arp -a 2>&1 | Out-String)

Write-Log ""
Write-Log "--- Get-NetNeighbor (IPv6) - csak informativ, aktiv IPv6 sweep nem lehetseges (/64 tul nagy) ---"
try {
    Get-NetNeighbor -AddressFamily IPv6 -ErrorAction SilentlyContinue |
        Where-Object { $_.State -in @("Reachable","Stale","Permanent") -and $_.IPAddress -notlike "fe80*" } |
        Format-Table IPAddress, LinkLayerAddress, State, InterfaceAlias -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch { }

# ==================== 4. ALHALOK MEGHATAROZASA (fix + automatikus) ====================
Write-Log ""
Write-Log "=== 4. VIZSGALANDO ALHALOK MEGHATAROZASA ===" "Cyan"

# Ismert, gyari/tipikus tartomanyok ennel a halozatnal (mobilnet router: 192.168.8.x gyari)
# Fajlbol toltve: /datas/iplist.json (JSON tomb, pl. ["192.168.0.0/24","192.168.8.0/24", ...])
# Ha nincs ilyen fajl, a script letrehozza az alabbi alapertelmezett tartalommal - utana
# mar csak ezt a fajlt kell boviteni, ha ujabb router/switch tartomanyt kell felvenni.
$DefaultKnownSubnets = @(
    "192.168.0.0/24",
    "192.168.1.0/24",
    "192.168.2.0/24",
    "192.168.8.0/24",
    "163.138.8.0/24"
)
$KnownSubnets = Get-JsonList -Path $IpListPath -DefaultValue $DefaultKnownSubnets -Label "IP/subnet lista"

# Automatikus felismeres a gepen konfiguralt IP-k alapjan (mas/eltero tartomanyok elkapasara)
$AutoSubnets = New-Object System.Collections.Generic.List[string]
try {
    $localIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" }
    foreach ($ip in $localIps) {
        if ($ip.PrefixLength -ge 22 -and $ip.PrefixLength -le 30) {
            $cidr = Get-NetworkCidr -IPAddress $ip.IPAddress -PrefixLength $ip.PrefixLength
            if ($cidr) { $AutoSubnets.Add($cidr) }
        } else {
            Write-Log "Kihagyva automatikus sweep-bol (tul nagy/kicsi tartomany): $($ip.IPAddress)/$($ip.PrefixLength)" "Yellow"
        }
    }
} catch {
    Write-Log "Automatikus alhalo-detektalas hiba: $_" "Red"
}

$AllSubnets = @($KnownSubnets + $AutoSubnets + $ExtraSubnets) | Select-Object -Unique
Write-Log "Vizsgalando alhalok (fix + automatikusan felismert + extra):"
$AllSubnets | ForEach-Object { Write-Log "  $_" }

# A vizsgalt alhalok sajat broadcast cimeinek kiszamitasa (pl. .255 egy /24-nel) -
# ezeket sose vegyuk valodi eszkoznek, meg akkor sem, ha "Permanent" ARP bejegyzeskent latszanak.
$BroadcastAddresses = New-Object System.Collections.Generic.List[string]
foreach ($s in $AllSubnets) {
    $b = Get-BroadcastAddress -NetworkCidr $s
    if ($b) { $BroadcastAddresses.Add($b) }
}
Write-Log "Kiszamitott broadcast cimek (ezek automatikusan kizarva a nema-eszkoz listabol): $($BroadcastAddresses -join ', ')"

# ==================== 5. AGGRESSZIV, PARHUZAMOSITOTT PING-SWEEP ====================
Write-Log ""
Write-Log "=== 5. AGGRESSZIV PING-SWEEP (runspace pool, 3 probalkozas/host, throttle=$ThrottleLimit) ===" "Cyan"

$AliveHosts = [System.Collections.Generic.List[string]]::new()
$SlowHosts  = [System.Collections.Generic.List[string]]::new()
$AllTested  = [System.Collections.Generic.List[object]]::new()

$PingScriptBlock = {
    param($ip)
    $successCount = 0
    $totalTime = 0
    $details = New-Object System.Collections.Generic.List[string]
    try {
        $pinger = New-Object System.Net.NetworkInformation.Ping
        for ($i = 1; $i -le 3; $i++) {
            try {
                $reply = $pinger.Send($ip, 800)
                if ($reply -and $reply.Status -eq [System.Net.NetworkInformation.IPStatus]::Success) {
                    $successCount++
                    $totalTime += $reply.RoundtripTime
                    $details.Add("OK $($reply.RoundtripTime)ms")
                } else {
                    $st = if ($reply) { $reply.Status } else { "FAIL" }
                    $details.Add("FAIL($st)")
                }
            } catch { $details.Add("EXC") }
            Start-Sleep -Milliseconds 100
        }
        $pinger.Dispose()
    } catch { }
    $avg = if ($successCount -gt 0) { [math]::Round($totalTime / $successCount, 0) } else { $null }
    [PSCustomObject]@{
        IP = $ip; SuccessCount = $successCount; AvgMs = $avg
        Details = ($details -join " | "); Alive = ($successCount -gt 0)
        Slow = ($successCount -gt 0 -and $avg -gt 80)
    }
}

foreach ($subnet in $AllSubnets) {
    $targets = Get-SubnetHosts -NetworkCidr $subnet
    if ($targets.Count -eq 0) { continue }
    Write-Log "Sweep indul: $subnet ($($targets.Count) cim) ..." "Yellow"
    $results = Invoke-Parallel -InputItems $targets -ScriptBlock $PingScriptBlock -Throttle $ThrottleLimit
    foreach ($res in $results) {
        $AllTested.Add($res)
        if ($res.Alive) {
            $AliveHosts.Add($res.IP)
            if ($res.Slow) {
                $SlowHosts.Add($res.IP)
                Write-Log "  LASSU VALASZ: $($res.IP) | Atlag: $($res.AvgMs) ms | $($res.Details)" "Magenta"
            } else {
                Write-Log "  ELO: $($res.IP) | Atlag: $($res.AvgMs) ms | $($res.Details)" "Green"
            }
        }
    }
}

Write-Log ""
Write-Log "Osszes tesztelt cim: $($AllTested.Count)"
Write-Log "Osszes elo host: $($AliveHosts.Count)" "Green"
Write-Log "Lassu valaszu hostok: $($SlowHosts.Count)" "Magenta"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }

# --- ARP-ban latszo, de pingre NEM valaszolo "nema" eszkozok (tuzfal / ICMP tiltas mogotti miner/switch gyanus)
Write-Log ""
Write-Log "=== 5/B. CSAK ARP-BAN LATHATO, PINGRE NEM VALASZOLO (NEMA) ESZKOZOK ===" "Cyan"
$ArpOnlyHosts = [System.Collections.Generic.List[string]]::new()
$FilteredNoiseCount = 0
foreach ($n in $Neighbors4) {
    if ($n.State -in @("Reachable","Stale","Permanent") -and $n.IPAddress -and ($AliveHosts -notcontains $n.IPAddress)) {
        # FONTOS JAVITAS: a broadcast (pl. x.x.x.255) es multicast (224.0.0.0/4, pl. mDNS/SSDP)
        # cimek rendszeresen "Permanent" ARP bejegyzeskent latszanak, de NEM valodi eszkozok -
        # ezeket itt kiszurjuk, kulonben hamis "nema eszkoz" talalatok keletkeznek.
        if (Test-IsNoiseAddress -IP $n.IPAddress -BroadcastAddresses $BroadcastAddresses -DisabledIps $DisableIps) {
            $FilteredNoiseCount++
            continue
        }
        $ArpOnlyHosts.Add($n.IPAddress)
        Write-Log "  NEMA (ARP-ban van, pingre nem valaszol): $($n.IPAddress) | MAC: $($n.LinkLayerAddress)" "Magenta"
    }
}
Write-Log "Kiszurt protokollszintu (broadcast/multicast) ARP bejegyzes: $FilteredNoiseCount (ezek nem valodi eszkozok)"
if ($ArpOnlyHosts.Count -eq 0) {
    Write-Log "Nincs valodi nema eszkoz - minden tenyleges ARP-bejegyzes valaszolt pingre is."
} else {
    Write-Log "Ezek az eszkozok ICMP-t (pinget) blokkolnak, de fizikailag jelen vannak a halozaton - gyanusak lehetnek (miner, rejtett switch, tuzfalazott gep)." "Yellow"
}

# Vizsgalati celpontok: elo hostok + nema (ARP-only) hostok egyutt
$InvestigateTargets = @($AliveHosts + $ArpOnlyHosts) | Select-Object -Unique | Sort-Object

# ==================== 6. RESZLETES HOST VIZSGALAT (hostname, MAC, NetBIOS, portok) ====================
Write-Log ""
Write-Log "=== 6. RESZLETES HOST VIZSGALAT ($($InvestigateTargets.Count) cel: elo + nema) ===" "Cyan"

$CommonPorts = @(21, 22, 23, 25, 80, 443, 445, 8080, 8443, 3389, 5900)
$MinerPorts  = @(1800, 3333, 3334, 3335, 3336, 3357, 4028, 4444, 5555, 6666, 7777,
                  8332, 8333, 9332, 9333, 9999, 14433, 14444, 45700, 8888, 9998)
$AllPorts = ($CommonPorts + $MinerPorts) | Select-Object -Unique

# 6/A - gyors, host-szintu metaadatok (hostname, MAC, NetBIOS, okoseszkoz-tipus)
$HostMeta = @{}
$SmartDeviceHits = [System.Collections.Generic.List[string]]::new()
foreach ($ip in $InvestigateTargets) {
    Write-Log "--- $ip ---" "Yellow"
    $meta = [ordered]@{ Hostname = $null; MAC = $null; NetBIOS = $null; DeviceType = $null }

    try {
        $dns = [System.Net.Dns]::GetHostEntry($ip)
        $meta.Hostname = $dns.HostName
        Write-Log "  Hostname (DNS): $($dns.HostName)"
    } catch {
        if ($Cap.ResolveDnsName) {
            try {
                $ptr = Resolve-DnsName -Name $ip -Type PTR -ErrorAction Stop
                $meta.Hostname = $ptr.NameHost
                Write-Log "  Hostname (PTR): $($ptr.NameHost)"
            } catch { Write-Log "  Hostname: (nem oldhato fel)" }
        } else {
            Write-Log "  Hostname: (nem oldhato fel)"
        }
    }

    try {
        $mac = ($Neighbors4 | Where-Object { $_.IPAddress -eq $ip } | Select-Object -First 1).LinkLayerAddress
        if ($mac) { $meta.MAC = $mac; Write-Log "  MAC: $mac" } else { Write-Log "  MAC: (nincs ARP bejegyzes)" }
    } catch {
        Write-Log "  MAC: hiba"
    }

    try {
        $nbt = nbtstat -A $ip 2>&1 | Out-String
        if ($nbt -and $nbt -notmatch "Host not found" -and $nbt -notmatch "not found") {
            $meta.NetBIOS = $nbt.Trim()
            Write-Log "  NetBIOS (nbtstat -A):"
            Write-Block $nbt
        }
    } catch { }

    # Okoseszkoz/TV/halozati eszkoz felismeres a hostname + NetBIOS szoveg alapjan (v1: csak nev-egyezes,
    # MAC-OUI/gyarto alapu felismeres kesobbi bovites lehet).
    $deviceTextToCheck = "$($meta.Hostname) $($meta.NetBIOS)"
    $deviceMatch = Test-SmartDeviceIndicator -HostnameOrText $deviceTextToCheck
    if ($deviceMatch) {
        $meta.DeviceType = $deviceMatch
        $line = "$ip -> valoszinu eszkoztipus: $deviceMatch (nev alapjan: '$($meta.Hostname)')"
        $SmartDeviceHits.Add($line)
        Write-Log "  ESZKOZTIPUS (valoszinu, nev alapjan): $deviceMatch" "Green"
    }

    $HostMeta[$ip] = $meta
}

if ($SmartDeviceHits.Count -gt 0) {
    Write-Log ""
    Write-Log "Felismert okoseszkozok/TV-k/halozati eszkozok szama: $($SmartDeviceHits.Count)" "Green"
} else {
    Write-Log ""
    Write-Log "Nev alapjan nem sikerult okoseszkozt/TV-t/halozati eszkozt beazonositani (sok IoT eszkoz nem ad ki ertelmes hostnevet - ez normalis)." "Yellow"
}

# 6/B - parhuzamositott portscan minden celhoston (host x port kombinaciok egyszerre)
Write-Log ""
Write-Log "--- Parhuzamos portscan indul ($($InvestigateTargets.Count) host x $($AllPorts.Count) port, throttle=$PortThrottleLimit) ---" "Yellow"

$PortTargets = New-Object System.Collections.Generic.List[object]
foreach ($ip in $InvestigateTargets) {
    foreach ($port in $AllPorts) {
        $PortTargets.Add([PSCustomObject]@{ IP = $ip; Port = $port })
    }
}

$PortScriptBlock = {
    param($target)
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $iar = $tcp.BeginConnect($target.IP, $target.Port, $null, $null)
        $success = $iar.AsyncWaitHandle.WaitOne(900, $false)
        $sw.Stop()
        $open = $false; $ms = $null
        if ($success -and $tcp.Connected) { $open = $true; $ms = $sw.ElapsedMilliseconds }
        $tcp.Close()
        [PSCustomObject]@{ IP = $target.IP; Port = $target.Port; Open = $open; Ms = $ms }
    } catch {
        [PSCustomObject]@{ IP = $target.IP; Port = $target.Port; Open = $false; Ms = $null }
    }
}

$PortResults = Invoke-Parallel -InputItems $PortTargets -ScriptBlock $PortScriptBlock -Throttle $PortThrottleLimit
$PortResultsByIp = $PortResults | Group-Object IP

$SuspiciousMinerHosts = [System.Collections.Generic.List[string]]::new()

foreach ($grp in $PortResultsByIp) {
    $ip = $grp.Name
    $openPorts = $grp.Group | Where-Object { $_.Open -and $_.Ms -le 300 } | Select-Object -ExpandProperty Port
    $slowPorts = $grp.Group | Where-Object { $_.Open -and $_.Ms -gt 300 } | ForEach-Object { "$($_.Port)($($_.Ms)ms)" }
    $minerOpen = $openPorts | Where-Object { $MinerPorts -contains $_ }

    Write-Log "--- $ip port eredmenyek ---" "Yellow"
    if ($openPorts.Count -gt 0) {
        Write-Log "  Nyitott portok: $($openPorts -join ', ')" "Green"
    }
    if ($slowPorts.Count -gt 0) {
        Write-Log "  LASSU port valaszok: $($slowPorts -join ', ')" "Magenta"
    }
    if ($openPorts.Count -eq 0 -and $slowPorts.Count -eq 0) {
        Write-Log "  Nyitott port: nincs (vagy tuzfal / tul lassu)"
    }
    if ($minerOpen.Count -gt 0) {
        $SuspiciousMinerHosts.Add($ip)
        Write-Log "  *** GYANUS: ismert miner/stratum port nyitva ezen az eszkozon: $($minerOpen -join ', ') ***" "Red"
    }
}

# ==================== 6/B. MAC-CIM (OUI) ALAPU GYARTO/ESZKOZ FELISMERES ====================
# Ez a legrobusztusabb felismeres, mert a MAC-cim az ARP tablabol MINDIG lathato,
# fuggetlenul attol, hogy az eszkoz valaszol-e pingre vagy van-e nyitott portja.
# Ez oldja meg a "hibasan mukodo miner, aminek nem tolt be a webes kliense" esetet is:
# a MAC-cim gyarto-elotagja (OUI) akkor is elarulja, mi az eszkoz, ha minden mas nema.
Write-Log ""
Write-Log "=== 6/B. MAC-CIM (OUI) ALAPU GYARTO/ESZKOZ FELISMERES ===" "Cyan"
$MacOuiData = $null
if (Test-Path $MacOuiListPath) {
    try {
        $MacOuiData = Get-Content -Path $MacOuiListPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
        Write-Log "MAC OUI lista betoltve: $MacOuiListPath ($($MacOuiData.entries.Count) bejegyzes)" "Green"
    } catch {
        Write-Log "MAC OUI lista beolvasasi hiba ($MacOuiListPath): $_" "Red"
    }
} else {
    Write-Log "MAC OUI lista nem talalhato ($MacOuiListPath) - ez a felismeres kimarad. Futtasd a Build-OuiList.ps1-et a bovitesehez." "Yellow"
}

$MacVendorHits = [System.Collections.Generic.List[string]]::new()
if ($MacOuiData -and $MacOuiData.entries) {
    foreach ($ip in $InvestigateTargets) {
        $mac = $HostMeta[$ip].MAC
        if (-not $mac) { continue }
        $ouiNormalized = ($mac -replace '[^0-9A-Fa-f]', '').ToUpper()
        if ($ouiNormalized.Length -lt 6) { continue }
        $ouiNormalized = $ouiNormalized.Substring(0, 6)
        $match = $MacOuiData.entries | Where-Object { $_.oui -eq $ouiNormalized } | Select-Object -First 1
        if ($match) {
            $line = "$ip (MAC: $mac) -> gyarto: $($match.vendor) | kategoria: $($match.category)"
            $MacVendorHits.Add($line)
            if ($match.category -eq "miner") {
                Write-Log "  *** MAC ALAPJAN VALOSZINU ASIC MINER (fuggetlenul a ping/port valasztol): $line ***" "Red"
            } else {
                Write-Log "  $line" "Cyan"
            }
        }
    }
}
if ($MacVendorHits.Count -eq 0) {
    Write-Log "Nincs MAC-OUI egyezes a jelenlegi listaval (ez nem jelenti azt, hogy nincs ismeretlen eszkoz - a lista meg bovitheto, ld. Build-OuiList.ps1)."
} else {
    Write-Log "MAC-OUI alapjan felismert eszkozok szama: $($MacVendorHits.Count)" "Cyan"
}

# ==================== 6/C. SNMP ALAPU SWITCH/ROUTER FELDERITES (KISERLETI) ====================
# A LEGTOBB unmanaged switch soha nem fog latszani (nincs sajat IP-je/protokollja).
# A MANAGED switchek/routerek viszont sokszor valaszolnak SNMP-re (UDP 161, "public" community),
# meg akkor is, ha pingre/webre nem. Ez KISERLETI funkcio: sajat kezzel irt, minimalis SNMPv1
# GET csomagot kuld (nincs kulso fuggoseg/modul), es a valaszbol kiolvassa a sysDescr/sysName-t.
# Ha hibas/nem vart valaszt kapsz, jelezd - ez a resz meg nem volt eles eszkozon tesztelve.
Write-Log ""
Write-Log "=== 6/C. SNMP ALAPU SWITCH/ROUTER FELDERITES (KISERLETI, UDP 161, community=public) ===" "Cyan"

$SnmpScriptBlock = {
    param($ip)
    function Get-LastOctetString {
        param([byte[]]$Bytes)
        $i = 0; $lastValue = $null
        while ($i -lt $Bytes.Length) {
            $tag = $Bytes[$i]; $i++
            if ($i -ge $Bytes.Length) { break }
            $lenByte = $Bytes[$i]; $i++
            $len = 0
            if ($lenByte -lt 0x80) {
                $len = $lenByte
            } else {
                $numBytes = $lenByte -band 0x7F
                for ($k = 0; $k -lt $numBytes; $k++) {
                    if ($i -ge $Bytes.Length) { break }
                    $len = ($len -shl 8) -bor $Bytes[$i]; $i++
                }
            }
            if ($tag -eq 0x04 -and ($i + $len) -le $Bytes.Length) {
                $lastValue = [System.Text.Encoding]::ASCII.GetString($Bytes, $i, $len)
            }
            if (($tag -band 0x20) -ne 0) {
                continue
            } else {
                $i += $len
            }
        }
        return $lastValue
    }

    # SNMPv1 GetRequest, community "public", OID sysDescr.0 (1.3.6.1.2.1.1.1.0)
    $sysDescrPacket = [byte[]]@(0x30,0x27,0x02,0x01,0x00,0x04,0x06,0x70,0x75,0x62,0x6C,0x69,0x63,0xA0,0x1A,0x02,0x01,0x01,0x02,0x01,0x00,0x02,0x01,0x00,0x30,0x0F,0x30,0x0D,0x06,0x09,0x2B,0x06,0x01,0x02,0x01,0x01,0x01,0x00,0x05,0x00)
    # SNMPv1 GetRequest, community "public", OID sysName.0 (1.3.6.1.2.1.1.5.0)
    $sysNamePacket  = [byte[]]@(0x30,0x27,0x02,0x01,0x00,0x04,0x06,0x70,0x75,0x62,0x6C,0x69,0x63,0xA0,0x1A,0x02,0x01,0x01,0x02,0x01,0x00,0x02,0x01,0x00,0x30,0x0F,0x30,0x0D,0x06,0x09,0x2B,0x06,0x01,0x02,0x01,0x01,0x05,0x00,0x05,0x00)

    $result = [PSCustomObject]@{ IP = $ip; SysDescr = $null; SysName = $null; Responded = $false }
    try {
        $udp = New-Object System.Net.Sockets.UdpClient
        $udp.Client.ReceiveTimeout = 700
        $endpoint = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($ip), 161)

        try {
            [void]$udp.Send($sysDescrPacket, $sysDescrPacket.Length, $endpoint)
            $remoteEp = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $resp = $udp.Receive([ref]$remoteEp)
            $result.SysDescr = Get-LastOctetString -Bytes $resp
            $result.Responded = $true
        } catch { }

        try {
            [void]$udp.Send($sysNamePacket, $sysNamePacket.Length, $endpoint)
            $remoteEp2 = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $resp2 = $udp.Receive([ref]$remoteEp2)
            $result.SysName = Get-LastOctetString -Bytes $resp2
            $result.Responded = $true
        } catch { }

        $udp.Close()
    } catch { }
    return $result
}

$SnmpResults = Invoke-Parallel -InputItems $InvestigateTargets -ScriptBlock $SnmpScriptBlock -Throttle 40
$SnmpHits = $SnmpResults | Where-Object { $_.Responded }

# Ismert routerek/switchek betoltese (ha vannak) - hogy megkulonboztessuk az UJ, meg
# nem dokumentalt SNMP-eszkozoket a mar ismertektol.
$KnownRouterIps = @()
$KnownSwitchIps = @()
if (Test-Path $RoutersListPath) {
    try {
        $rdata = Get-Content -Path $RoutersListPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
        $KnownRouterIps = $rdata.routers | Where-Object { $_.ip } | Select-Object -ExpandProperty ip
    } catch { }
}
if (Test-Path $SwitchesListPath) {
    try {
        $sdata = Get-Content -Path $SwitchesListPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
        $KnownSwitchIps = $sdata.switches | Where-Object { $_.ip } | Select-Object -ExpandProperty ip
    } catch { }
}

if ($SnmpHits.Count -eq 0) {
    Write-Log "Nincs SNMP-valasz egyik vizsgalt eszkoztol sem (ez normalis, ha nincs managed switch/router, vagy az SNMP le van tiltva - ami biztonsagi szempontbol egyebkent ajanlott is)."
} else {
    foreach ($hit in $SnmpHits) {
        $knownTag = ""
        if ($KnownRouterIps -contains $hit.IP) { $knownTag = " [MAR ISMERT - Routers.json]" }
        elseif ($KnownSwitchIps -contains $hit.IP) { $knownTag = " [MAR ISMERT - Switchs.json]" }
        else { $knownTag = " [UJ - erdemes felvenni a Routers.json vagy Switchs.json fajlba]" }
        Write-Log "  SNMP VALASZ (valoszinu switch/router): $($hit.IP)$knownTag" "Green"
        if ($hit.SysDescr) { Write-Log "    sysDescr: $($hit.SysDescr)" }
        if ($hit.SysName)  { Write-Log "    sysName : $($hit.SysName)" }
    }
    Write-Log "SNMP-re valaszolo eszkozok szama: $($SnmpHits.Count) - ezek valoszinuleg managed switchek/routerek, amik eddig 'nema' eszkozkent latszottak." "Cyan"
}

# ==================== 7. PATHPING / TRACEROUTE + GATEWAY/WAN TESZT ====================
Write-Log ""
Write-Log "=== 7. PATHPING / TRACEROUTE (lassu + nema + elo hostok) ===" "Cyan"
$TargetsForPath = @($SlowHosts + $ArpOnlyHosts + $AliveHosts) | Select-Object -Unique | Select-Object -First 15
foreach ($ip in $TargetsForPath) {
    Write-Log "--- pathping $ip (roviditett) ---" "Yellow"
    try {
        Write-Block (pathping -n -q 3 -p 100 -w 800 $ip 2>&1 | Out-String)
    } catch {
        Write-Log "  pathping hiba: $_" "Red"
    }
}

Write-Log ""
Write-Log "=== 7/B. EXPLICIT GATEWAY ES WAN (INTERNET) TESZT ===" "Cyan"
$Gateways = @()
try {
    $Gateways = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } |
        Select-Object -ExpandProperty IPv4DefaultGateway).NextHop | Select-Object -Unique
} catch { }

foreach ($gw in $Gateways) {
    $r = Test-PingHost -TargetIP $gw -Attempts 5 -TimeoutMs 800
    $lossPct = [math]::Round((1 - ($r.SuccessCount / 5)) * 100, 0)
    Write-Log "Gateway $gw : $($r.SuccessCount)/5 valasz | Atlag: $($r.AvgMs) ms | Csomagvesztes: $lossPct% | $($r.Details)" $(if ($lossPct -gt 0) { "Magenta" } else { "Green" })
}

foreach ($wanTarget in @("1.1.1.1", "8.8.8.8")) {
    $r = Test-PingHost -TargetIP $wanTarget -Attempts 5 -TimeoutMs 1000
    $lossPct = [math]::Round((1 - ($r.SuccessCount / 5)) * 100, 0)
    Write-Log "WAN teszt $wanTarget : $($r.SuccessCount)/5 valasz | Atlag: $($r.AvgMs) ms | Csomagvesztes: $lossPct% | $($r.Details)" $(if ($lossPct -gt 0) { "Magenta" } else { "Green" })
}

Write-Log ""
Write-Log "--- DNS feloldas teszt (google.com) ---"
try {
    $swDns = [System.Diagnostics.Stopwatch]::StartNew()
    if ($Cap.ResolveDnsName) {
        $dnsTest = Resolve-DnsName -Name "google.com" -ErrorAction Stop
    } else {
        $dnsTest = [System.Net.Dns]::GetHostEntry("google.com")
    }
    $swDns.Stop()
    Write-Log "DNS feloldas SIKERES ($($swDns.ElapsedMilliseconds) ms alatt)" "Green"
} catch {
    Write-Log "DNS feloldas SIKERTELEN: $_" "Red"
}

# ==================== 8. UTVONAL + DNS KONFIG ====================
Write-Log ""
Write-Log "=== 8. UTVONAL TABLA ES DNS KONFIG ===" "Cyan"
Write-Log "--- route print ---"
Write-Block (route print 2>&1 | Out-String)

Write-Log "--- Get-DnsClientServerAddress ---"
try {
    Get-DnsClientServerAddress -AddressFamily IPv4 |
        Format-Table InterfaceAlias, ServerAddresses -AutoSize | Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "DNS info hiba: $_" "Red"
}

# ==================== 9. AKTIV TCP KAPCSOLATOK + MINING POOL KORRELACIO ====================
Write-Log ""
Write-Log "=== 9. AKTIV TCP KAPCSOLATOK ===" "Cyan"
$TcpConns = @()
try {
    $TcpConns = Get-NetTCPConnection -State Established, Listen -ErrorAction SilentlyContinue
    $TcpConns | Select-Object LocalAddress, LocalPort, RemoteAddress, RemotePort, State, OwningProcess |
        Sort-Object LocalPort | Format-Table -AutoSize | Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "TCP kapcsolatok hiba: $_" "Red"
}

Write-Log ""
Write-Log "=== 9/B. ISMERT/LEGITIM KULSO SZOLGALTATASOK ES MINING POOL / GYANUS PORT KORRELACIO ===" "Cyan"
Write-Log "(Elobb az ismert szolgaltatas-listaval vetjuk ossze - pl. TeamViewer, OneDrive - hogy ezek egyertelmuen ne keveredjenek a gyanus talalatok koze.)"
$MiningHits = [System.Collections.Generic.List[string]]::new()
$KnownServiceHits = [System.Collections.Generic.List[string]]::new()
$established = $TcpConns | Where-Object { $_.State -eq "Established" -and $_.RemoteAddress -notmatch "^(127\.|0\.0\.0\.0|::)" }
foreach ($c in $established) {
    $ptrName = $null
    if ($Cap.ResolveDnsName -and $c.RemoteAddress -notmatch "^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)") {
        try {
            $ptr = Resolve-DnsName -Name $c.RemoteAddress -Type PTR -ErrorAction Stop
            $ptrName = $ptr.NameHost
        } catch { }
    }

    $procName = "?"
    try {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) { $procName = $proc.ProcessName }
    } catch { }

    # 1) Eloszor ismert/legitim szolgaltatas-e (TeamViewer, OneDrive, Google, stb.)
    $knownMatch = Test-KnownServiceIndicator -HostnameOrText $ptrName
    if ($knownMatch) {
        $line = "ISMERT SZOLGALTATAS: $($c.LocalAddress):$($c.LocalPort) <-> $($c.RemoteAddress):$($c.RemotePort) ($ptrName) | Folyamat: $procName (PID $($c.OwningProcess)) | Egyezes: $knownMatch"
        $KnownServiceHits.Add($line)
        Write-Log "  $line" "Cyan"
        continue  # ismert, legitim kapcsolat - nem kell tovabb (mining) vizsgalni
    }

    # 2) Csak ha NEM ismert szolgaltatas, akkor nezzuk a mining pool / gyanus port mintazatokat
    $isMinerPort = ($MinerPorts -contains $c.RemotePort) -or ($MinerPorts -contains $c.LocalPort)
    $keywordHit = Test-MiningIndicator -HostnameOrText $ptrName

    if ($isMinerPort -or $keywordHit) {
        $reason = @()
        if ($isMinerPort) { $reason += "ismert stratum/miner port" }
        if ($keywordHit)  { $reason += "PTR kulcsszo egyezes ($ptrName)" }
        $line = "GYANUS KAPCSOLAT: $($c.LocalAddress):$($c.LocalPort) <-> $($c.RemoteAddress):$($c.RemotePort) | Folyamat: $procName (PID $($c.OwningProcess)) | Ok: $($reason -join '; ')"
        $MiningHits.Add($line)
        Write-Log "  $line" "Red"
    }
}

Write-Log ""
if ($KnownServiceHits.Count -gt 0) {
    Write-Log "Ismert/legitim kulso szolgaltatas kapcsolatok szama: $($KnownServiceHits.Count) (pl. TeamViewer, OneDrive, Google stb. - ezek NEM gyanusak, csak informaciok)" "Cyan"
} else {
    Write-Log "Nem talalhato ismert szolgaltatas (TeamViewer/OneDrive/stb.) mintazatu aktiv kapcsolat ezen a gepen."
}
if ($MiningHits.Count -eq 0) {
    Write-Log "Nem talalhato ismert mining pool port/domain mintazatu aktiv TCP kapcsolat ezen a gepen."
} else {
    Write-Log "FIGYELEM: $($MiningHits.Count) gyanus kapcsolat talalhato - ez ezen a GEPEN futo folyamatra utal, nem feltetlenul a halozat mas eszkozere!" "Red"
}

# ==================== 10. ADAPTER HIBASTATISZTIKAK (DELTA MERES) ====================
Write-Log ""
Write-Log "=== 10. ADAPTER HIBASTATISZTIKAK ES FORGALOM (5 mp delta meres) ===" "Cyan"
try {
    $statsBefore = Get-NetAdapterStatistics
    Write-Log "Meres indul, 5 masodperc varakozas..."
    Start-Sleep -Seconds 5
    $statsAfter = Get-NetAdapterStatistics

    foreach ($after in $statsAfter) {
        $before = $statsBefore | Where-Object { $_.Name -eq $after.Name }
        if ($before) {
            $rxDelta = $after.ReceivedBytes - $before.ReceivedBytes
            $txDelta = $after.SentBytes - $before.SentBytes
            $rxKBs = [math]::Round($rxDelta / 5 / 1024, 1)
            $txKBs = [math]::Round($txDelta / 5 / 1024, 1)
            Write-Log "  $($after.Name): RX $rxKBs KB/s | TX $txKBs KB/s | Hibas fogadott csomag: $($after.ReceivedPacketErrors) | Hibas kuldott: $($after.OutboundPacketErrors) | Eldobott (RX/TX): $($after.ReceivedDiscardedPackets)/$($after.OutboundDiscardedPackets)"
            if ($rxKBs -gt 500 -or $txKBs -gt 500) {
                Write-Log "    -> Folyamatosan magas forgalom, erdemes megnezni mi generalja (lehet hatterben futo torrent/backup/miner is)." "Yellow"
            }
        }
    }
} catch {
    Write-Log "Adapter statisztika hiba: $_" "Red"
}

# ==================== 11. HELYI FOLYAMATOK (CPU / HALOZAT) ====================
Write-Log ""
Write-Log "=== 11. HELYI GEP - LEGTOBB CPU-T HASZNALO FOLYAMATOK (helyi miner kizarasahoz) ===" "Cyan"
try {
    Get-Process | Sort-Object CPU -Descending | Select-Object -First 15 Name, Id,
        @{N='CPU(s)';E={[math]::Round($_.CPU,1)}}, @{N='WS(MB)';E={[math]::Round($_.WorkingSet64/1MB,1)}} |
        Format-Table -AutoSize | Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Folyamatlista hiba: $_" "Red"
}

# ==================== 12. RENDSZERNAPLO ====================
Write-Log ""
Write-Log "=== 12. RENDSZERNAPLO - HALOZATI HIBAK (utolso 72 ora) ===" "Cyan"
# FONTOS JAVITAS: a Get-WinEvent FilterHashtable ProviderName mezoje NEM tamogat wildcard-ot,
# ezert elobb feloldjuk a pontos provider neveket Get-WinEvent -ListProvider segitsegevel.
$ProviderPatterns = @("Tcpip", "e1dexpress", "Netwtw*", "ndis", "Dhcp*")
$ResolvedProviders = New-Object System.Collections.Generic.List[string]
foreach ($pat in $ProviderPatterns) {
    try {
        $found = Get-WinEvent -ListProvider $pat -ErrorAction SilentlyContinue
        foreach ($f in $found) { if (-not $ResolvedProviders.Contains($f.Name)) { $ResolvedProviders.Add($f.Name) } }
    } catch { }
}
Write-Log "Feloldott provider-ek: $($ResolvedProviders -join ', ')"

$since = (Get-Date).AddHours(-72)
foreach ($providerName in $ResolvedProviders) {
    try {
        $events = Get-WinEvent -FilterHashtable @{ LogName = "System"; ProviderName = $providerName; StartTime = $since; Level = 2,3 } -MaxEvents 40 -ErrorAction SilentlyContinue
        if ($events) {
            Write-Log "--- System / $providerName --- talalat: $($events.Count)" "Yellow"
            foreach ($e in $events) {
                $msg = $e.Message -replace "`r`n", " " -replace "\s+", " "
                if ($msg.Length -gt 200) { $msg = $msg.Substring(0, 200) + "..." }
                Write-Log "  [$($e.TimeCreated.ToString('yyyy-MM-dd HH:mm'))] ID: $($e.Id) $msg"
            }
        }
    } catch { }
}

# DNS-Client operational log - elobb ellenorizzuk, hogy engedelyezve van-e
try {
    $dnsLog = Get-WinEvent -ListLog "Microsoft-Windows-DNS-Client/Operational" -ErrorAction Stop
    if ($dnsLog.IsEnabled) {
        $dnsEvents = Get-WinEvent -FilterHashtable @{ LogName = "Microsoft-Windows-DNS-Client/Operational"; StartTime = $since; Level = 2,3 } -MaxEvents 40 -ErrorAction SilentlyContinue
        if ($dnsEvents) {
            Write-Log "--- DNS-Client/Operational --- talalat: $($dnsEvents.Count)" "Yellow"
            foreach ($e in $dnsEvents) {
                $msg = $e.Message -replace "`r`n", " " -replace "\s+", " "
                if ($msg.Length -gt 200) { $msg = $msg.Substring(0, 200) + "..." }
                Write-Log "  [$($e.TimeCreated.ToString('yyyy-MM-dd HH:mm'))] ID: $($e.Id) $msg"
            }
        }
    } else {
        Write-Log "A Microsoft-Windows-DNS-Client/Operational naplo nincs engedelyezve ezen a gepen - kihagyva." "Yellow"
    }
} catch {
    Write-Log "DNS-Client/Operational naplo nem erheto el ezen a gepen - kihagyva." "Yellow"
}

# ==================== 13. EGYEB ====================
Write-Log ""
Write-Log "=== 13. EGYEB INFORMACIOK ===" "Cyan"
Write-Log "--- netstat -ano (LISTENING + ESTABLISHED, elso 50) ---"
Write-Block ((netstat -ano 2>&1 | Select-String "LISTENING|ESTABLISHED" | Select-Object -First 50) -join "`n")

Write-Log "--- Get-NetRoute (IPv4) ---"
try {
    Get-NetRoute -AddressFamily IPv4 | Where-Object { $_.DestinationPrefix -ne "255.255.255.255/32" } |
        Sort-Object DestinationPrefix | Format-Table DestinationPrefix, NextHop, InterfaceAlias, RouteMetric -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Get-NetRoute hiba: $_" "Red"
}

# ==================== 14. OSSZEFOGLALO ====================
Write-Log ""
Write-Log "=== OSSZEFOGLALO ===" "Cyan"
Write-Log "PowerShell verzio: $($PSVer.ToString())"
Write-Log "Vizsgalt alhalok: $($AllSubnets -join ', ')"
Write-Log "Osszes tesztelt IP: $($AllTested.Count)"
Write-Log "Elo hostok szama: $($AliveHosts.Count)"
Write-Log "Lassu valaszu hostok: $($SlowHosts.Count)"
Write-Log "Nema (csak ARP-ban latszo, valodi) hostok: $($ArpOnlyHosts.Count)"
Write-Log "Kiszurt protokollszintu (broadcast/multicast) hamis talalat: $FilteredNoiseCount"
Write-Log "Gyanus (miner port nyitva) hostok: $($SuspiciousMinerHosts.Count)"
Write-Log "Gyanus helyi mining pool kapcsolatok: $($MiningHits.Count)"
Write-Log "Ismert/legitim kulso szolgaltatas kapcsolatok (TeamViewer, OneDrive, stb.): $($KnownServiceHits.Count)"
Write-Log "Felismert okoseszkozok/TV-k/halozati eszkozok (nev alapjan): $($SmartDeviceHits.Count)"
Write-Log "Felismert eszkozok MAC-OUI (gyarto) alapjan: $($MacVendorHits.Count)"
Write-Log "SNMP-re valaszolo (valoszinu managed switch/router) eszkozok: $($SnmpHits.Count)"
Write-Log ""
Write-Log "Elo IP-k:"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }
Write-Log ""
Write-Log "Nema (ARP-only) IP-k - erdemes fizikailag megkeresni, mi ez:"
$ArpOnlyHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Magenta" }
Write-Log ""
Write-Log "Lassu IP-k (gyanus):"
$SlowHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Magenta" }
Write-Log ""
Write-Log "Gyanus IP-k (nyitott miner/stratum port):"
$SuspiciousMinerHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Red" }
Write-Log ""
Write-Log "Felismert eszkoztipusok (TV/okoseszkoz/halozati eszkoz, nev alapjan):"
if ($SmartDeviceHits.Count -eq 0) {
    Write-Log "  (nincs ilyen talalat)"
} else {
    $SmartDeviceHits | ForEach-Object { Write-Log "  $_" "Green" }
}
Write-Log ""
Write-Log "MEGJEGYZES A SWITCH-EKROL:"
Write-Log "A managed/unmanaged switch-ek tobbsege NEM valaszol pingre, NEM jelenik meg ARP-ban (ha nincs management IP),"
Write-Log "es NEM nyit portokat. Csak akkor latszanak, ha van management interfeszuk (altalaban .1 / .254 / kulon VLAN)."
Write-Log "A 'lathatatlan' switch-ek hibajat leginkabb a mogottuk levo eszkozok santitasabol, packet error-okbol"
Write-Log "es a pathping vesztesegebol lehet kovetkeztetni."
Write-Log ""
Write-Log "MEGJEGYZES AZ ASIC MINEREKROL:"
Write-Log "Ha egy ASIC miner rendesen termel, de nem erheto el a belso halozaton, az egyik leggyakoribb ok, hogy"
Write-Log "kulon VLAN-on/subneten van, vagy a webes admin felulete (altalaban 80/8080-as porton) tuzfalazva van a helyi geprol."
Write-Log "A fenti 'nema' es 'gyanus port' listak pont ezeket probaljak kiszurni."
Write-Log ""
Write-Log "MOBILNET EMLEKEZTETO:"
Write-Log "A SIM a routerben van -> a PC-n futo netsh mbn parancsok gyakran uresek. Ez normalis."
Write-Log "A belso halozati hibak (miner, switch, lassu eszkoz) fuggetlenek a mobilnet minosegetol."
Write-Log ""
Write-Log "=== DIAGNOSZTIKA VEGE ===" "Cyan"
Write-Log "Fajl mentve: $OutFile"

# Fajlba iras
$Log | Out-File -FilePath $OutFile -Encoding UTF8

Write-Host ""
Write-Host "Kesz! A teljes, maximalis log itt van: $OutFile" -ForegroundColor Green
Write-Host "Kuldd el ezt a fajlt, es egyutt kiertekeljuk." -ForegroundColor Green
Write-Host ""
$openAnswer = Read-Host "Megnyissam a logot bongeszoben, olvashato formaban? (I/n - alapertelmezett: Igen, csak nyomj Entert)"
if ([string]::IsNullOrWhiteSpace($openAnswer) -or $openAnswer -match "^(i|ig|igen|y|yes)$") {
    $logToIndex = Join-Path $PSScriptRoot "LOGtoINDEX.ps1"
    if (Test-Path $logToIndex) {
        try {
            & $logToIndex -LogFile $OutFile -LogDir $OutDir
        } catch {
            Write-Host "LOGtoINDEX.ps1 futtatasa nem sikerult: $_" -ForegroundColor Red
            Write-Host "Kezzel itt talalod a logot: $OutFile" -ForegroundColor Yellow
        }
    } else {
        Write-Host "LOGtoINDEX.ps1 nem talalhato a script mellett - nyisd meg kezzel: $OutFile" -ForegroundColor Yellow
    }
} else {
    Write-Host "Rendben, semmi nem nyilik meg. Eleresi ut: $OutFile" -ForegroundColor Yellow
}
