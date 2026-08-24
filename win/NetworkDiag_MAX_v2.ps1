#Requires -Version 5.1
<#
    NetworkDiag MAX v2 - Maximálisan agresszív LAN + mobilnet diagnosztika
    Cél: semmi se maradjon rejtve - ASIC minerek, "néma" switchek, portolt/tiltott
         eszközök, lassú válaszok, ismert mining pool-okra menő kapcsolatok, mobilnet.

    Használat:
        - Küldd el ezt a fájlt a felhasználónak
        - Jobb klikk -> "Futtatás PowerShell-lel" (vagy dupla katt, ha .ps1 társítva van)
        - A script magától kér admin jogot
        - A futás végén a C:\lan mappában keletkező .txt fájlt kell visszaküldeni

    Kimenet: C:\lan\NetworkDiag_MAX_YYYYMMDD_HHMMSS.txt

    Konfigurációs fájlok (nem kötelezőek - ha hiányoznak, a script automatikusan
    létrehozza őket alapértelmezett tartalommal az első futáskor):
        /datas/iplist.json         - vizsgálandó fix alhálók, JSON tömbként
                                      pl.: ["192.168.0.0/24","192.168.8.0/24","10.0.5.0/24"]
        /datas/minerpoollist.json  - ismert mining pool kulcsszavak (hostname/PTR egyezéshez)
                                      pl.: ["pool","stratum","nanopool","antpool"]
    Ezek szerkesztésével a keresési tartomány és a pool-felismerés bővíthető
    a script kódjának módosítása nélkül.
#>

param(
    [switch]$NoElevation,
    [int]$ThrottleLimit = 64,       # párhuzamos ping-sweep szálak
    [int]$PortThrottleLimit = 120,  # párhuzamos portscan szálak
    [string[]]$ExtraSubnets = @(),  # pl. -ExtraSubnets "10.0.0.0/24","172.16.5.0/24"
    [string]$IpListPath = "/datas/iplist.json",           # vizsgálandó alhálók listája (JSON tömb)
    [string]$MinerPoolListPath = "/datas/minerpoollist.json"  # ismert mining pool kulcsszavak (JSON tömb)
)

# ==================== 0. SCRIPT ELÉRÉSI ÚT (irm | iex védelem) ====================
$ScriptPath = $MyInvocation.MyCommand.Path

# ==================== JOGOSULTSÁG EMELÉS ====================
function Test-Admin {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $NoElevation -and -not (Test-Admin)) {
    if ($ScriptPath) {
        Write-Host "Jogosultság emelés szükséges. Újraindítás adminisztrátorként..." -ForegroundColor Yellow
        Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`" -NoElevation -ThrottleLimit $ThrottleLimit -PortThrottleLimit $PortThrottleLimit -IpListPath `"$IpListPath`" -MinerPoolListPath `"$MinerPoolListPath`""
        exit
    } else {
        Write-Host "FIGYELEM: A script nem .ps1 fájlból fut (pl. irm | iex), ezért automatikus admin-emelés nem lehetséges." -ForegroundColor Red
        Write-Host "Mentsd el .ps1 fájlba és úgy indítsd 'Futtatás PowerShell-lel adminisztrátorként' -val, különben egyes tesztek hiányosak lesznek (eseménynapló, adapter statisztika)." -ForegroundColor Yellow
    }
}

# ==================== KIMENETI MAPPA ====================
$OutDir = "C:\lan"
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

Write-Log "=== MAXIMÁLIS HÁLÓZATI DIAGNOSZTIKA v2 INDUL ===" "Cyan"
Write-Log "Gép: $env:COMPUTERNAME | Felhasználó: $env:USERNAME | Idő: $(Get-Date)"
Write-Log "Kimeneti fájl: $OutFile"
Write-Log "IP/subnet lista fájl: $IpListPath"
Write-Log "Mining pool kulcsszó lista fájl: $MinerPoolListPath"
Write-Log "Cél: semmi se maradjon rejtve (ASIC miner, néma switch, lassú/portolt eszköz, mobilnet, mining pool kapcsolatok)"
Write-Log ""

# ==================== 0/B. POWERSHELL VERZIÓ ÉS KÉPESSÉGEK ====================
Write-Log "=== 0. POWERSHELL VERZIÓ ÉS KÖRNYEZET ===" "Cyan"
$PSVer = $PSVersionTable.PSVersion
$IsPS6Plus = $PSVer.Major -ge 6
Write-Log "PowerShell verzió: $($PSVer.ToString()) | Edition: $($PSVersionTable.PSEdition)"
if ($IsPS6Plus) {
    Write-Log "OS: $($PSVersionTable.OS)"
    if (-not $IsWindows) {
        Write-Log "Ez a script csak Windows rendszeren futtatható (Windows-specifikus cmdlet-eket használ). Kilépés." "Red"
        $Log | Out-File -FilePath $OutFile -Encoding UTF8
        exit
    }
}
Write-Log "Admin jogosultság: $(Test-Admin)"

# Képesség-detektálás - hogy tudjuk, mit tudunk kihasználni
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
    Write-Log "  Elérhető: $k -> $($Cap[$k])"
}
Write-Log ""

# ==================== SEGÉDFÜGGVÉNYEK ====================

# --- .NET alapú ping teszt: verzió-független, nem használja a Test-Connection
#     -TimeoutSeconds paraméterét (az csak PS 6+ alatt létezik, 5.1-nél hibát dob!)
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

# --- Könnyűsúlyú, szál (runspace) alapú párhuzamosítás Start-Job helyett.
#     Sokkal kevesebb erőforrást használ, mint egy külön process/job elem.
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

# --- IP + prefix -> hálózati CIDR
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

# --- CIDR -> host IP lista (max ~1022 host, biztonsági korlát)
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
        if ($numHosts -gt 1022) { $numHosts = 1022 }  # biztonsági korlát (kb. egy /22-nyi)
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

# --- Külső JSON fájlból listát betöltő segédfüggvény.
#     Ha a fájl nem létezik, létrehozza az alapértelmezett tartalommal (első futáskor sem hal el),
#     ha létezik de hibás/üres, az alapértelmezett listával fut tovább.
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
                Write-Log "$Label betöltve fájlból: $Path ($($list.Count) elem)" "Green"
                return $list
            } else {
                Write-Log "$Label fájl üres vagy érvénytelen ($Path) - alapértelmezett lista használva." "Yellow"
                return $DefaultValue
            }
        } catch {
            Write-Log "$Label fájl beolvasási hiba ($Path): $_ - alapértelmezett lista használva." "Red"
            return $DefaultValue
        }
    } else {
        Write-Log "$Label fájl nem található ($Path) - létrehozom alapértelmezett tartalommal." "Yellow"
        try {
            $dir = Split-Path $Path -Parent
            if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
            $DefaultValue | ConvertTo-Json | Out-File -FilePath $Path -Encoding UTF8
            Write-Log "Alapértelmezett $Label fájl létrehozva: $Path - ezt szerkesztve bővítheted legközelebb, kód módosítása nélkül."
        } catch {
            Write-Log "Nem sikerült létrehozni a(z) $Label fájlt ($Path): $_ - csak memóriában, alapértelmezett listával fut tovább." "Red"
        }
        return $DefaultValue
    }
}

# --- Ismert mining pool kulcsszavak (hostname / PTR alapú felismeréshez)
#     Fájlból töltve: /datas/minerpoollist.json (JSON tömb, pl. ["pool","stratum","nanopool", ...])
#     Ha nincs ilyen fájl, a script létrehozza az alábbi alapértelmezett tartalommal.
$DefaultMiningPoolKeywords = @(
    "pool","stratum","nanopool","ethermine","antpool","f2pool","viabtc","poolin",
    "2miners","herominers","unmineable","hiveon","luxor","slushpool","emcd",
    "btc.com","foundryusa","binance","ocean.xyz","kryptex","mining"
)
$MiningPoolKeywords = Get-JsonList -Path $MinerPoolListPath -DefaultValue $DefaultMiningPoolKeywords -Label "Mining pool kulcsszó lista"

function Test-MiningIndicator {
    param([string]$HostnameOrText)
    if ([string]::IsNullOrWhiteSpace($HostnameOrText)) { return $false }
    foreach ($kw in $MiningPoolKeywords) {
        if ($HostnameOrText -match [regex]::Escape($kw)) { return $true }
    }
    return $false
}

# ==================== 1. ALAP ADAPTER + IP ====================
Write-Log "=== 1. HÁLÓZATI ADAPTEREK ÉS IP KONFIGURÁCIÓ ===" "Cyan"
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
Write-Log "--- Get-NetIPConfiguration (gateway, DNS, interfészenként) ---"
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
Write-Log "=== 2. MOBILNET (SIM / WWAN / CELLULAR) INFORMÁCIÓK ===" "Cyan"
Write-Log "A router SIM-kártyás mobilinternetet használ. Az alábbiak a Windows oldali Mobile Broadband adatokat mutatják."
try {
    $wwanAdapters = Get-NetAdapter | Where-Object {
        $_.InterfaceDescription -match "Mobile|WWAN|Cellular|LTE|5G|Broadband|Sierra|Quectel|Huawei|Fibocom|SIM" -or
        $_.MediaType -match "Wireless WAN|WWAN"
    }
    if ($wwanAdapters) {
        Write-Log "Talált WWAN / Mobile Broadband adapter(ek):" "Green"
        foreach ($w in $wwanAdapters) {
            Write-Log "  Név: $($w.Name) | Status: $($w.Status) | MAC: $($w.MacAddress) | Desc: $($w.InterfaceDescription) | Speed: $($w.LinkSpeed)"
        }
    } else {
        Write-Log "Nem található klasszikus WWAN adapter ezen a gépen (a mobilnet a routeren van, nem a PC-n)." "Yellow"
        Write-Log "Ez normális, ha a SIM a routerben van."
    }
} catch {
    Write-Log "WWAN adapter keresés hiba: $_" "Red"
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
Write-Log "MOBILNET MEGJEGYZÉSEK:" "Yellow"
Write-Log "- Ha a SIM a routerben van, a fenti netsh mbn parancsok gyakran üresek vagy hibát adnak (ez normális)."
Write-Log "- A router WAN oldala CGNAT-on lehet -> befelé (port forward) általában nem működik."
Write-Log "- A belső hálózati problémák (miner-ek, switch-ek, lassú eszközök) függetlenek a mobilnet minőségétől."

# ==================== 3. ARP / IPv4+IPv6 NEIGHBOR ====================
Write-Log ""
Write-Log "=== 3. ARP TÁBLA + Get-NetNeighbor (IPv4 és IPv6, minden állapot) ===" "Cyan"
$Neighbors4 = @()
try {
    $Neighbors4 = Get-NetNeighbor -AddressFamily IPv4 | Sort-Object IPAddress
    Write-Log "Get-NetNeighbor (IPv4) összes bejegyzés: $($Neighbors4.Count)"
    $Neighbors4 | Format-Table IPAddress, LinkLayerAddress, State, InterfaceAlias -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Get-NetNeighbor (IPv4) hiba: $_" "Red"
}
Write-Log "--- arp -a ---"
Write-Block (arp -a 2>&1 | Out-String)

Write-Log ""
Write-Log "--- Get-NetNeighbor (IPv6) - csak informatív, aktív IPv6 sweep nem lehetséges (/64 túl nagy) ---"
try {
    Get-NetNeighbor -AddressFamily IPv6 -ErrorAction SilentlyContinue |
        Where-Object { $_.State -in @("Reachable","Stale","Permanent") -and $_.IPAddress -notlike "fe80*" } |
        Format-Table IPAddress, LinkLayerAddress, State, InterfaceAlias -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch { }

# ==================== 4. ALHÁLÓK MEGHATÁROZÁSA (fix + automatikus) ====================
Write-Log ""
Write-Log "=== 4. VIZSGÁLANDÓ ALHÁLÓK MEGHATÁROZÁSA ===" "Cyan"

# Ismert, gyári/tipikus tartományok ennél a hálózatnál (mobilnet router: 192.168.8.x gyári)
# Fájlból töltve: /datas/iplist.json (JSON tömb, pl. ["192.168.0.0/24","192.168.8.0/24", ...])
# Ha nincs ilyen fájl, a script létrehozza az alábbi alapértelmezett tartalommal - utána
# már csak ezt a fájlt kell bővíteni, ha újabb router/switch tartományt kell felvenni.
$DefaultKnownSubnets = @(
    "192.168.0.0/24",
    "192.168.1.0/24",
    "192.168.2.0/24",
    "192.168.8.0/24",
    "163.138.8.0/24"
)
$KnownSubnets = Get-JsonList -Path $IpListPath -DefaultValue $DefaultKnownSubnets -Label "IP/subnet lista"

# Automatikus felismerés a gépen konfigurált IP-k alapján (más/eltérő tartományok elkapására)
$AutoSubnets = New-Object System.Collections.Generic.List[string]
try {
    $localIps = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" }
    foreach ($ip in $localIps) {
        if ($ip.PrefixLength -ge 22 -and $ip.PrefixLength -le 30) {
            $cidr = Get-NetworkCidr -IPAddress $ip.IPAddress -PrefixLength $ip.PrefixLength
            if ($cidr) { $AutoSubnets.Add($cidr) }
        } else {
            Write-Log "Kihagyva automatikus sweep-ből (túl nagy/kicsi tartomány): $($ip.IPAddress)/$($ip.PrefixLength)" "Yellow"
        }
    }
} catch {
    Write-Log "Automatikus alháló-detektálás hiba: $_" "Red"
}

$AllSubnets = @($KnownSubnets + $AutoSubnets + $ExtraSubnets) | Select-Object -Unique
Write-Log "Vizsgálandó alhálók (fix + automatikusan felismert + extra):"
$AllSubnets | ForEach-Object { Write-Log "  $_" }

# ==================== 5. AGGRESSZÍV, PÁRHUZAMOSÍTOTT PING-SWEEP ====================
Write-Log ""
Write-Log "=== 5. AGGRESSZÍV PING-SWEEP (runspace pool, 3 próbálkozás/host, throttle=$ThrottleLimit) ===" "Cyan"

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
    Write-Log "Sweep indul: $subnet ($($targets.Count) cím) ..." "Yellow"
    $results = Invoke-Parallel -InputItems $targets -ScriptBlock $PingScriptBlock -Throttle $ThrottleLimit
    foreach ($res in $results) {
        $AllTested.Add($res)
        if ($res.Alive) {
            $AliveHosts.Add($res.IP)
            if ($res.Slow) {
                $SlowHosts.Add($res.IP)
                Write-Log "  LASSÚ VÁLASZ: $($res.IP) | Átlag: $($res.AvgMs) ms | $($res.Details)" "Magenta"
            } else {
                Write-Log "  ÉLŐ: $($res.IP) | Átlag: $($res.AvgMs) ms | $($res.Details)" "Green"
            }
        }
    }
}

Write-Log ""
Write-Log "Összes tesztelt cím: $($AllTested.Count)"
Write-Log "Összes élő host: $($AliveHosts.Count)" "Green"
Write-Log "Lassú válaszú hostok: $($SlowHosts.Count)" "Magenta"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }

# --- ARP-ban látszó, de pingre NEM válaszoló "néma" eszközök (tűzfal / ICMP tiltás mögötti miner/switch gyanús)
Write-Log ""
Write-Log "=== 5/B. CSAK ARP-BAN LÁTHATÓ, PINGRE NEM VÁLASZOLÓ (NÉMA) ESZKÖZÖK ===" "Cyan"
$ArpOnlyHosts = [System.Collections.Generic.List[string]]::new()
foreach ($n in $Neighbors4) {
    if ($n.State -in @("Reachable","Stale","Permanent") -and $n.IPAddress -and ($AliveHosts -notcontains $n.IPAddress)) {
        $ArpOnlyHosts.Add($n.IPAddress)
        Write-Log "  NÉMA (ARP-ban van, pingre nem válaszol): $($n.IPAddress) | MAC: $($n.LinkLayerAddress)" "Magenta"
    }
}
if ($ArpOnlyHosts.Count -eq 0) {
    Write-Log "Nincs ilyen eszköz - minden ARP-bejegyzés válaszolt pingre is."
} else {
    Write-Log "Ezek az eszközök ICMP-t (pinget) blokkolnak, de fizikailag jelen vannak a hálózaton - gyanúsak lehetnek (miner, rejtett switch, tűzfalazott gép)." "Yellow"
}

# Vizsgálati célpontok: élő hostok + néma (ARP-only) hostok együtt
$InvestigateTargets = @($AliveHosts + $ArpOnlyHosts) | Select-Object -Unique | Sort-Object

# ==================== 6. RÉSZLETES HOST VIZSGÁLAT (hostname, MAC, NetBIOS, portok) ====================
Write-Log ""
Write-Log "=== 6. RÉSZLETES HOST VIZSGÁLAT ($($InvestigateTargets.Count) cél: élő + néma) ===" "Cyan"

$CommonPorts = @(21, 22, 23, 25, 80, 443, 445, 8080, 8443, 3389, 5900)
$MinerPorts  = @(1800, 3333, 3334, 3335, 3336, 3357, 4028, 4444, 5555, 6666, 7777,
                  8332, 8333, 9332, 9333, 9999, 14433, 14444, 45700, 8888, 9998)
$AllPorts = ($CommonPorts + $MinerPorts) | Select-Object -Unique

# 6/A - gyors, host-szintű metaadatok (hostname, MAC, NetBIOS)
$HostMeta = @{}
foreach ($ip in $InvestigateTargets) {
    Write-Log "--- $ip ---" "Yellow"
    $meta = [ordered]@{ Hostname = $null; MAC = $null; NetBIOS = $null }

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
            } catch { Write-Log "  Hostname: (nem oldható fel)" }
        } else {
            Write-Log "  Hostname: (nem oldható fel)"
        }
    }

    try {
        $mac = ($Neighbors4 | Where-Object { $_.IPAddress -eq $ip } | Select-Object -First 1).LinkLayerAddress
        if ($mac) { $meta.MAC = $mac; Write-Log "  MAC: $mac" } else { Write-Log "  MAC: (nincs ARP bejegyzés)" }
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

    $HostMeta[$ip] = $meta
}

# 6/B - párhuzamosított portscan minden célhoston (host x port kombinációk egyszerre)
Write-Log ""
Write-Log "--- Párhuzamos portscan indul ($($InvestigateTargets.Count) host x $($AllPorts.Count) port, throttle=$PortThrottleLimit) ---" "Yellow"

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

    Write-Log "--- $ip port eredmények ---" "Yellow"
    if ($openPorts.Count -gt 0) {
        Write-Log "  Nyitott portok: $($openPorts -join ', ')" "Green"
    }
    if ($slowPorts.Count -gt 0) {
        Write-Log "  LASSÚ port válaszok: $($slowPorts -join ', ')" "Magenta"
    }
    if ($openPorts.Count -eq 0 -and $slowPorts.Count -eq 0) {
        Write-Log "  Nyitott port: nincs (vagy tűzfal / túl lassú)"
    }
    if ($minerOpen.Count -gt 0) {
        $SuspiciousMinerHosts.Add($ip)
        Write-Log "  *** GYANÚS: ismert miner/stratum port nyitva ezen az eszközön: $($minerOpen -join ', ') ***" "Red"
    }
}

# ==================== 7. PATHPING / TRACEROUTE + GATEWAY/WAN TESZT ====================
Write-Log ""
Write-Log "=== 7. PATHPING / TRACEROUTE (lassú + néma + élő hostok) ===" "Cyan"
$TargetsForPath = @($SlowHosts + $ArpOnlyHosts + $AliveHosts) | Select-Object -Unique | Select-Object -First 15
foreach ($ip in $TargetsForPath) {
    Write-Log "--- pathping $ip (rövidített) ---" "Yellow"
    try {
        Write-Block (pathping -n -q 3 -p 100 -w 800 $ip 2>&1 | Out-String)
    } catch {
        Write-Log "  pathping hiba: $_" "Red"
    }
}

Write-Log ""
Write-Log "=== 7/B. EXPLICIT GATEWAY ÉS WAN (INTERNET) TESZT ===" "Cyan"
$Gateways = @()
try {
    $Gateways = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } |
        Select-Object -ExpandProperty IPv4DefaultGateway).NextHop | Select-Object -Unique
} catch { }

foreach ($gw in $Gateways) {
    $r = Test-PingHost -TargetIP $gw -Attempts 5 -TimeoutMs 800
    $lossPct = [math]::Round((1 - ($r.SuccessCount / 5)) * 100, 0)
    Write-Log "Gateway $gw : $($r.SuccessCount)/5 válasz | Átlag: $($r.AvgMs) ms | Csomagvesztés: $lossPct% | $($r.Details)" $(if ($lossPct -gt 0) { "Magenta" } else { "Green" })
}

foreach ($wanTarget in @("1.1.1.1", "8.8.8.8")) {
    $r = Test-PingHost -TargetIP $wanTarget -Attempts 5 -TimeoutMs 1000
    $lossPct = [math]::Round((1 - ($r.SuccessCount / 5)) * 100, 0)
    Write-Log "WAN teszt $wanTarget : $($r.SuccessCount)/5 válasz | Átlag: $($r.AvgMs) ms | Csomagvesztés: $lossPct% | $($r.Details)" $(if ($lossPct -gt 0) { "Magenta" } else { "Green" })
}

Write-Log ""
Write-Log "--- DNS feloldás teszt (google.com) ---"
try {
    $swDns = [System.Diagnostics.Stopwatch]::StartNew()
    if ($Cap.ResolveDnsName) {
        $dnsTest = Resolve-DnsName -Name "google.com" -ErrorAction Stop
    } else {
        $dnsTest = [System.Net.Dns]::GetHostEntry("google.com")
    }
    $swDns.Stop()
    Write-Log "DNS feloldás SIKERES ($($swDns.ElapsedMilliseconds) ms alatt)" "Green"
} catch {
    Write-Log "DNS feloldás SIKERTELEN: $_" "Red"
}

# ==================== 8. ÚTVONAL + DNS KONFIG ====================
Write-Log ""
Write-Log "=== 8. ÚTVONAL TÁBLA ÉS DNS KONFIG ===" "Cyan"
Write-Log "--- route print ---"
Write-Block (route print 2>&1 | Out-String)

Write-Log "--- Get-DnsClientServerAddress ---"
try {
    Get-DnsClientServerAddress -AddressFamily IPv4 |
        Format-Table InterfaceAlias, ServerAddresses -AutoSize | Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "DNS info hiba: $_" "Red"
}

# ==================== 9. AKTÍV TCP KAPCSOLATOK + MINING POOL KORRELÁCIÓ ====================
Write-Log ""
Write-Log "=== 9. AKTÍV TCP KAPCSOLATOK ===" "Cyan"
$TcpConns = @()
try {
    $TcpConns = Get-NetTCPConnection -State Established, Listen -ErrorAction SilentlyContinue
    $TcpConns | Select-Object LocalAddress, LocalPort, RemoteAddress, RemotePort, State, OwningProcess |
        Sort-Object LocalPort | Format-Table -AutoSize | Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "TCP kapcsolatok hiba: $_" "Red"
}

Write-Log ""
Write-Log "=== 9/B. ISMERT MINING POOL / GYANÚS PORT KORRELÁCIÓ (ki- és bejövő kapcsolatok) ===" "Cyan"
$MiningHits = [System.Collections.Generic.List[string]]::new()
$established = $TcpConns | Where-Object { $_.State -eq "Established" -and $_.RemoteAddress -notmatch "^(127\.|0\.0\.0\.0|::)" }
foreach ($c in $established) {
    $isMinerPort = ($MinerPorts -contains $c.RemotePort) -or ($MinerPorts -contains $c.LocalPort)
    $ptrName = $null
    if ($Cap.ResolveDnsName -and $c.RemoteAddress -notmatch "^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)") {
        try {
            $ptr = Resolve-DnsName -Name $c.RemoteAddress -Type PTR -ErrorAction Stop
            $ptrName = $ptr.NameHost
        } catch { }
    }
    $keywordHit = Test-MiningIndicator -HostnameOrText $ptrName

    if ($isMinerPort -or $keywordHit) {
        $procName = "?"
        try {
            $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
            if ($proc) { $procName = $proc.ProcessName }
        } catch { }
        $reason = @()
        if ($isMinerPort) { $reason += "ismert stratum/miner port" }
        if ($keywordHit)  { $reason += "PTR kulcsszó egyezés ($ptrName)" }
        $line = "GYANÚS KAPCSOLAT: $($c.LocalAddress):$($c.LocalPort) <-> $($c.RemoteAddress):$($c.RemotePort) | Folyamat: $procName (PID $($c.OwningProcess)) | Ok: $($reason -join '; ')"
        $MiningHits.Add($line)
        Write-Log "  $line" "Red"
    }
}
if ($MiningHits.Count -eq 0) {
    Write-Log "Nem található ismert mining pool port/domain mintázatú aktív TCP kapcsolat ezen a gépen."
} else {
    Write-Log "FIGYELEM: $($MiningHits.Count) gyanús kapcsolat található - ez ezen a GÉPEN futó folyamatra utal, nem feltétlenül a hálózat más eszközére!" "Red"
}

# ==================== 10. ADAPTER HIBASTATISZTIKÁK (DELTA MÉRÉS) ====================
Write-Log ""
Write-Log "=== 10. ADAPTER HIBASTATISZTIKÁK ÉS FORGALOM (5 mp delta mérés) ===" "Cyan"
try {
    $statsBefore = Get-NetAdapterStatistics
    Write-Log "Mérés indul, 5 másodperc várakozás..."
    Start-Sleep -Seconds 5
    $statsAfter = Get-NetAdapterStatistics

    foreach ($after in $statsAfter) {
        $before = $statsBefore | Where-Object { $_.Name -eq $after.Name }
        if ($before) {
            $rxDelta = $after.ReceivedBytes - $before.ReceivedBytes
            $txDelta = $after.SentBytes - $before.SentBytes
            $rxKBs = [math]::Round($rxDelta / 5 / 1024, 1)
            $txKBs = [math]::Round($txDelta / 5 / 1024, 1)
            Write-Log "  $($after.Name): RX $rxKBs KB/s | TX $txKBs KB/s | Hibás fogadott csomag: $($after.ReceivedPacketErrors) | Hibás küldött: $($after.OutboundPacketErrors) | Eldobott (RX/TX): $($after.ReceivedDiscardedPackets)/$($after.OutboundDiscardedPackets)"
            if ($rxKBs -gt 500 -or $txKBs -gt 500) {
                Write-Log "    -> Folyamatosan magas forgalom, érdemes megnézni mi generálja (lehet háttérben futó torrent/backup/miner is)." "Yellow"
            }
        }
    }
} catch {
    Write-Log "Adapter statisztika hiba: $_" "Red"
}

# ==================== 11. HELYI FOLYAMATOK (CPU / HÁLÓZAT) ====================
Write-Log ""
Write-Log "=== 11. HELYI GÉP - LEGTÖBB CPU-T HASZNÁLÓ FOLYAMATOK (helyi miner kizárásához) ===" "Cyan"
try {
    Get-Process | Sort-Object CPU -Descending | Select-Object -First 15 Name, Id,
        @{N='CPU(s)';E={[math]::Round($_.CPU,1)}}, @{N='WS(MB)';E={[math]::Round($_.WorkingSet64/1MB,1)}} |
        Format-Table -AutoSize | Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Folyamatlista hiba: $_" "Red"
}

# ==================== 12. RENDSZERNAPLÓ ====================
Write-Log ""
Write-Log "=== 12. RENDSZERNAPLÓ - HÁLÓZATI HIBÁK (utolsó 72 óra) ===" "Cyan"
# FONTOS JAVÍTÁS: a Get-WinEvent FilterHashtable ProviderName mezője NEM támogat wildcard-ot,
# ezért előbb feloldjuk a pontos provider neveket Get-WinEvent -ListProvider segítségével.
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
            Write-Log "--- System / $providerName --- találat: $($events.Count)" "Yellow"
            foreach ($e in $events) {
                $msg = $e.Message -replace "`r`n", " " -replace "\s+", " "
                if ($msg.Length -gt 200) { $msg = $msg.Substring(0, 200) + "..." }
                Write-Log "  [$($e.TimeCreated.ToString('yyyy-MM-dd HH:mm'))] ID: $($e.Id) $msg"
            }
        }
    } catch { }
}

# DNS-Client operational log - előbb ellenőrizzük, hogy engedélyezve van-e
try {
    $dnsLog = Get-WinEvent -ListLog "Microsoft-Windows-DNS-Client/Operational" -ErrorAction Stop
    if ($dnsLog.IsEnabled) {
        $dnsEvents = Get-WinEvent -FilterHashtable @{ LogName = "Microsoft-Windows-DNS-Client/Operational"; StartTime = $since; Level = 2,3 } -MaxEvents 40 -ErrorAction SilentlyContinue
        if ($dnsEvents) {
            Write-Log "--- DNS-Client/Operational --- találat: $($dnsEvents.Count)" "Yellow"
            foreach ($e in $dnsEvents) {
                $msg = $e.Message -replace "`r`n", " " -replace "\s+", " "
                if ($msg.Length -gt 200) { $msg = $msg.Substring(0, 200) + "..." }
                Write-Log "  [$($e.TimeCreated.ToString('yyyy-MM-dd HH:mm'))] ID: $($e.Id) $msg"
            }
        }
    } else {
        Write-Log "A Microsoft-Windows-DNS-Client/Operational napló nincs engedélyezve ezen a gépen - kihagyva." "Yellow"
    }
} catch {
    Write-Log "DNS-Client/Operational napló nem érhető el ezen a gépen - kihagyva." "Yellow"
}

# ==================== 13. EGYÉB ====================
Write-Log ""
Write-Log "=== 13. EGYÉB INFORMÁCIÓK ===" "Cyan"
Write-Log "--- netstat -ano (LISTENING + ESTABLISHED, első 50) ---"
Write-Block ((netstat -ano 2>&1 | Select-String "LISTENING|ESTABLISHED" | Select-Object -First 50) -join "`n")

Write-Log "--- Get-NetRoute (IPv4) ---"
try {
    Get-NetRoute -AddressFamily IPv4 | Where-Object { $_.DestinationPrefix -ne "255.255.255.255/32" } |
        Sort-Object DestinationPrefix | Format-Table DestinationPrefix, NextHop, InterfaceAlias, RouteMetric -AutoSize |
        Out-String | ForEach-Object { Write-Block $_ }
} catch {
    Write-Log "Get-NetRoute hiba: $_" "Red"
}

# ==================== 14. ÖSSZEFOGLALÓ ====================
Write-Log ""
Write-Log "=== ÖSSZEFOGLALÓ ===" "Cyan"
Write-Log "PowerShell verzió: $($PSVer.ToString())"
Write-Log "Vizsgált alhálók: $($AllSubnets -join ', ')"
Write-Log "Összes tesztelt IP: $($AllTested.Count)"
Write-Log "Élő hostok száma: $($AliveHosts.Count)"
Write-Log "Lassú válaszú hostok: $($SlowHosts.Count)"
Write-Log "Néma (csak ARP-ban látszó) hostok: $($ArpOnlyHosts.Count)"
Write-Log "Gyanús (miner port nyitva) hostok: $($SuspiciousMinerHosts.Count)"
Write-Log "Gyanús helyi mining pool kapcsolatok: $($MiningHits.Count)"
Write-Log ""
Write-Log "Élő IP-k:"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }
Write-Log ""
Write-Log "Néma (ARP-only) IP-k - érdemes fizikailag megkeresni, mi ez:"
$ArpOnlyHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Magenta" }
Write-Log ""
Write-Log "Lassú IP-k (gyanús):"
$SlowHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Magenta" }
Write-Log ""
Write-Log "Gyanús IP-k (nyitott miner/stratum port):"
$SuspiciousMinerHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Red" }
Write-Log ""
Write-Log "MEGJEGYZÉS A SWITCH-EKRŐL:"
Write-Log "A managed/unmanaged switch-ek többsége NEM válaszol pingre, NEM jelenik meg ARP-ban (ha nincs management IP),"
Write-Log "és NEM nyit portokat. Csak akkor látszanak, ha van management interfészük (általában .1 / .254 / külön VLAN)."
Write-Log "A 'láthatatlan' switch-ek hibáját leginkább a mögöttük lévő eszközök sántításából, packet error-okból"
Write-Log "és a pathping veszteségéből lehet következtetni."
Write-Log ""
Write-Log "MEGJEGYZÉS AZ ASIC MINEREKRŐL:"
Write-Log "Ha egy ASIC miner rendesen termel, de nem érhető el a belső hálózaton, az egyik leggyakoribb ok, hogy"
Write-Log "külön VLAN-on/subneten van, vagy a webes admin felülete (általában 80/8080-as porton) tűzfalazva van a helyi gépről."
Write-Log "A fenti 'néma' és 'gyanús port' listák pont ezeket próbálják kiszűrni."
Write-Log ""
Write-Log "MOBILNET EMLÉKEZTETŐ:"
Write-Log "A SIM a routerben van -> a PC-n futó netsh mbn parancsok gyakran üresek. Ez normális."
Write-Log "A belső hálózati hibák (miner, switch, lassú eszköz) függetlenek a mobilnet minőségétől."
Write-Log ""
Write-Log "=== DIAGNOSZTIKA VÉGE ===" "Cyan"
Write-Log "Fájl mentve: $OutFile"

# Fájlba írás
$Log | Out-File -FilePath $OutFile -Encoding UTF8

Write-Host ""
Write-Host "Kész! A teljes, maximális log itt van: $OutFile" -ForegroundColor Green
Write-Host "Küldd el ezt a fájlt, és együtt kiértékeljük." -ForegroundColor Green
try { Invoke-Item $OutFile } catch { }
