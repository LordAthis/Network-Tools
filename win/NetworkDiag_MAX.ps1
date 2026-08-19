#Requires -Version 5.1
# NetworkDiag MAX - Maximálisan agresszív LAN + mobilnet diagnosztika
# Cél: semmi se maradjon rejtve (miner-ek, sántító eszközök, lassú válaszok, switch-ek, mobilnet)
# Kimenet: C:\lan\NetworkDiag_MAX_YYYYMMDD_HHMMSS.txt

param(
    [switch]$NoElevation
)

# ==================== JOGOSULTSÁG EMELÉS ====================
function Test-Admin {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $NoElevation -and -not (Test-Admin)) {
    Write-Host "Jogosultság emelés szükséges. Újraindítás adminisztrátorként..." -ForegroundColor Yellow
    $scriptPath = $MyInvocation.MyCommand.Path
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -NoElevation"
    exit
}

# ==================== KIMENETI MAPP A ====================
$OutDir = "C:\lan"
if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$OutFile = Join-Path $OutDir "NetworkDiag_MAX_$Timestamp.txt"
$Log = [System.Collections.Generic.List[string]]::new()

function Write-Log {
    param([string]$Message, [string]$Color = "White")
    \( line = " \)(Get-Date -Format 'HH:mm:ss') | $Message"
    $Log.Add($line)
    Write-Host $line -ForegroundColor $Color
}

Write-Log "=== MAXIMÁLIS HÁLÓZATI DIAGNOSZTIKA INDUL ===" "Cyan"
Write-Log "Gép: $env:COMPUTERNAME | Felhasználó: $env:USERNAME | Idő: $(Get-Date)"
Write-Log "Kimeneti fájl: $OutFile"
Write-Log "Cél: semmi se maradjon rejtve (miner, sántító eszköz, lassú válasz, switch, mobilnet)"
Write-Log ""

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
$ipconfig = ipconfig /all 2>&1 | Out-String
$Log.Add($ipconfig)
Write-Host $ipconfig

Write-Log ""
Write-Log "--- Get-NetIPAddress ---"
try {
    Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" } | 
        Format-Table InterfaceAlias, IPAddress, PrefixLength, AddressState -AutoSize | Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
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
Write-Log "--- netsh mbn show interfaces ---"
try {
    $mbnIf = netsh mbn show interfaces 2>&1 | Out-String
    $Log.Add($mbnIf)
    Write-Host $mbnIf
} catch {
    Write-Log "netsh mbn show interfaces hiba: $_" "Red"
}

Write-Log "--- netsh mbn show connection interface=* ---"
try {
    $mbnConn = netsh mbn show connection interface=* 2>&1 | Out-String
    $Log.Add($mbnConn)
    Write-Host $mbnConn
} catch {
    Write-Log "netsh mbn show connection hiba: $_" "Red"
}

Write-Log "--- netsh mbn show profiles ---"
try {
    $mbnProf = netsh mbn show profiles 2>&1 | Out-String
    $Log.Add($mbnProf)
    Write-Host $mbnProf
} catch {
    Write-Log "netsh mbn show profiles hiba: $_" "Red"
}

Write-Log "--- netsh mbn show readyinfo interface=* ---"
try {
    $mbnReady = netsh mbn show readyinfo interface=* 2>&1 | Out-String
    $Log.Add($mbnReady)
    Write-Host $mbnReady
} catch {}

Write-Log "--- netsh mbn show signal interface=* ---"
try {
    $mbnSignal = netsh mbn show signal interface=* 2>&1 | Out-String
    $Log.Add($mbnSignal)
    Write-Host $mbnSignal
} catch {}

Write-Log ""
Write-Log "MOBILNET MEGJEGYZÉSEK:" "Yellow"
Write-Log "- Ha a SIM a routerben van, a fenti netsh mbn parancsok gyakran üresek vagy hibát adnak (ez normális)."
Write-Log "- A router WAN oldala CGNAT-on lehet → befelé (port forward) általában nem működik."
Write-Log "- A belső hálózati problémák (miner-ek, switch-ek, lassú eszközök) függetlenek a mobilnet minőségétől."
Write-Log "- A 163.138.8.0/24 tartomány valószínűleg a router LAN oldala vagy a szolgáltató által kiosztott tartomány."

# ==================== 3. ARP / NEIGHBOR ====================
Write-Log ""
Write-Log "=== 3. ARP TÁBLA + Get-NetNeighbor (minden állapot) ===" "Cyan"

try {
    $neighbors = Get-NetNeighbor -AddressFamily IPv4 | Sort-Object IPAddress
    Write-Log "Get-NetNeighbor összes bejegyzés: $($neighbors.Count)"
    $neighbors | Format-Table IPAddress, LinkLayerAddress, State, InterfaceAlias -AutoSize | Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "Get-NetNeighbor hiba: $_" "Red"
}

Write-Log "--- arp -a ---"
$arp = arp -a 2>&1 | Out-String
$Log.Add($arp)
Write-Host $arp

# ==================== 4. AGGRESSZÍV PING-SWEEP ====================
Write-Log ""
Write-Log "=== 4. AGGRESSZÍV PING-SWEEP (3 próbálkozás, 2 mp timeout) ===" "Cyan"

$Subnets = @(
    "192.168.0",
    "192.168.1",
    "192.168.2",
    "163.138.8"
)

$AliveHosts = [System.Collections.Generic.List[string]]::new()
$SlowHosts  = [System.Collections.Generic.List[string]]::new()
$AllTested  = [System.Collections.Generic.List[object]]::new()

foreach ($subnet in $Subnets) {
    Write-Log "Sweep indul: $subnet.0/24 ..." "Yellow"
    
    $jobs = 1..254 | ForEach-Object {
        $ip = "$subnet.$_"
        Start-Job -ScriptBlock {
            param($target)
            $successCount = 0
            $totalTime = 0
            $results = @()
            
            for ($i = 1; $i -le 3; $i++) {
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $ping = Test-Connection -ComputerName $target -Count 1 -TimeoutSeconds 2 -ErrorAction SilentlyContinue
                $sw.Stop()
                $ms = $sw.ElapsedMilliseconds
                
                if ($ping) {
                    $successCount++
                    $totalTime += $ms
                    $results += "OK ${ms}ms"
                } else {
                    $results += "TIMEOUT/FAIL"
                }
                Start-Sleep -Milliseconds 150
            }
            
            [PSCustomObject]@{
                IP            = $target
                SuccessCount  = $successCount
                AvgMs         = if ($successCount -gt 0) { [math]::Round($totalTime / $successCount, 0) } else { $null }
                Details       = ($results -join " | ")
                Alive         = ($successCount -gt 0)
                Slow          = ($successCount -gt 0 -and ($totalTime / $successCount) -gt 80)
            }
        } -ArgumentList $ip
    }

    $jobs | Wait-Job -Timeout 180 | Out-Null
    
    foreach ($job in $jobs) {
        $res = Receive-Job $job -ErrorAction SilentlyContinue
        if ($res) {
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
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

Write-Log ""
Write-Log "Összes élő host: $($AliveHosts.Count)" "Green"
Write-Log "Lassú válaszú hostok: $($SlowHosts.Count)" "Magenta"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }

# ==================== 5. RÉSZLETES HOST VIZSGÁLAT (portok + stratum) ====================
Write-Log ""
Write-Log "=== 5. RÉSZLETES HOST VIZSGÁLAT (hostname, MAC, portok, stratum) ===" "Cyan"

$CommonPorts = @(21, 22, 23, 25, 80, 443, 445, 8080, 8443, 3389, 5900)
$MinerPorts   = @(3333, 3334, 3335, 3336, 4028, 5555, 7777, 9999, 14433, 14444, 45700, 8888, 9998)

$AllPorts = ($CommonPorts + $MinerPorts) | Select-Object -Unique

foreach ($ip in ($AliveHosts | Sort-Object)) {
    Write-Log "--- $ip ---" "Yellow"
    
    # Hostname
    try {
        $dns = [System.Net.Dns]::GetHostEntry($ip)
        Write-Log "  Hostname: $($dns.HostName)"
    } catch {
        Write-Log "  Hostname: (nem oldható fel)"
    }

    # MAC
    try {
        $mac = (Get-NetNeighbor -IPAddress $ip -ErrorAction SilentlyContinue).LinkLayerAddress
        if ($mac) { Write-Log "  MAC: $mac" } else { Write-Log "  MAC: (nincs ARP bejegyzés)" }
    } catch {
        Write-Log "  MAC: hiba"
    }

    # Portscan
    $openPorts = @()
    $slowPorts = @()
    
    foreach ($port in $AllPorts) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $iar = $tcp.BeginConnect($ip, $port, $null, $null)
            $success = $iar.AsyncWaitHandle.WaitOne(900, $false)
            $sw.Stop()
            
            if ($success -and $tcp.Connected) {
                $ms = $sw.ElapsedMilliseconds
                if ($ms -gt 300) {
                    $slowPorts += "\( port( \){ms}ms)"
                } else {
                    $openPorts += $port
                }
            }
            $tcp.Close()
        } catch {}
    }
    
    if ($openPorts.Count -gt 0) {
        Write-Log "  Nyitott portok: $($openPorts -join ', ')" "Green"
    }
    if ($slowPorts.Count -gt 0) {
        Write-Log "  LASSÚ port válaszok: $($slowPorts -join ', ')" "Magenta"
    }
    if ($openPorts.Count -eq 0 -and $slowPorts.Count -eq 0) {
        Write-Log "  Nyitott port: nincs (vagy tűzfal / túl lassú)"
    }
}

# ==================== 6. PATHPING / TRACEROUTE ====================
Write-Log ""
Write-Log "=== 6. PATHPING / TRACEROUTE (lassú + élő hostok) ===" "Cyan"

$TargetsForPath = ($SlowHosts + $AliveHosts | Select-Object -First 12) | Select-Object -Unique

foreach ($ip in $TargetsForPath) {
    Write-Log "--- pathping $ip (rövidített) ---" "Yellow"
    try {
        $pp = pathping -n -q 3 -p 100 -w 800 $ip 2>&1 | Out-String
        $Log.Add($pp)
        Write-Host $pp
    } catch {
        Write-Log "  pathping hiba: $_" "Red"
    }
}

# ==================== 7. ÚTVONAL + DNS ====================
Write-Log ""
Write-Log "=== 7. ÚTVONAL TÁBLA ÉS DNS ===" "Cyan"

Write-Log "--- route print ---"
$route = route print 2>&1 | Out-String
$Log.Add($route)
Write-Host $route

Write-Log "--- Get-DnsClientServerAddress ---"
try {
    Get-DnsClientServerAddress -AddressFamily IPv4 | 
        Format-Table InterfaceAlias, ServerAddresses -AutoSize | Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "DNS info hiba: $_" "Red"
}

# ==================== 8. TCP KAPCSOLATOK ====================
Write-Log ""
Write-Log "=== 8. AKTÍV TCP KAPCSOLATOK ===" "Cyan"
try {
    Get-NetTCPConnection -State Established, Listen -ErrorAction SilentlyContinue | 
        Select-Object LocalAddress, LocalPort, RemoteAddress, RemotePort, State, OwningProcess |
        Sort-Object LocalPort | Format-Table -AutoSize | Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "TCP kapcsolatok hiba: $_" "Red"
}

# ==================== 9. ADAPTER HIBASTATISZTIKÁK ====================
Write-Log ""
Write-Log "=== 9. ADAPTER HIBASTATISZTIKÁK ===" "Cyan"
try {
    Get-NetAdapterStatistics | Format-Table Name, ReceivedBytes, SentBytes, ReceivedUnicastPackets, SentUnicastPackets, 
        ReceivedDiscardedPackets, OutboundDiscardedPackets, ReceivedPacketErrors, OutboundPacketErrors -AutoSize | 
        Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "Statisztika hiba: $_" "Red"
}

# ==================== 10. RENDSZERNAPLÓ ====================
Write-Log ""
Write-Log "=== 10. RENDSZERNAPLÓ - HÁLÓZATI HIBÁK (utolsó 72 óra) ===" "Cyan"

$EventLogs = @(
    @{ Log = "System"; Provider = "Tcpip"; Level = 2,3 },
    @{ Log = "System"; Provider = "e1dexpress"; Level = 2,3 },
    @{ Log = "System"; Provider = "Netwtw*"; Level = 2,3 },
    @{ Log = "System"; Provider = "ndis"; Level = 2,3 },
    @{ Log = "System"; Provider = "Dhcp*"; Level = 2,3 },
    @{ Log = "Microsoft-Windows-DNS-Client/Operational"; Provider = $null; Level = 2,3 }
)

$since = (Get-Date).AddHours(-72)

foreach ($el in $EventLogs) {
    try {
        $filter = @{
            LogName   = $el.Log
            StartTime = $since
            Level     = $el.Level
        }
        if ($el.Provider) { $filter.ProviderName = $el.Provider }

        $events = Get-WinEvent -FilterHashtable $filter -MaxEvents 40 -ErrorAction SilentlyContinue
        if ($events) {
            Write-Log "--- $($el.Log) / $($el.Provider) --- találat: $($events.Count)" "Yellow"
            foreach ($e in $events) {
                $msg = $e.Message -replace "`r`n", " " -replace "\s+", " "
                if ($msg.Length -gt 200) { $msg = $msg.Substring(0, 200) + "..." }
                Write-Log "  [$(\( e.TimeCreated.ToString('yyyy-MM-dd HH:mm'))] ID: \)($e.Id) $msg"
            }
        }
    } catch {}
}

# ==================== 11. EGYÉB ====================
Write-Log ""
Write-Log "=== 11. EGYÉB INFORMÁCIÓK ===" "Cyan"

Write-Log "--- netstat -ano (LISTENING + ESTABLISHED) ---"
$netstat = netstat -ano 2>&1 | Select-String "LISTENING|ESTABLISHED" | Select-Object -First 50 | Out-String
$Log.Add($netstat)
Write-Host $netstat

Write-Log "--- Get-NetRoute (IPv4) ---"
try {
    Get-NetRoute -AddressFamily IPv4 | Where-Object { $_.DestinationPrefix -ne "255.255.255.255/32" } |
        Sort-Object DestinationPrefix | Format-Table DestinationPrefix, NextHop, InterfaceAlias, RouteMetric -AutoSize |
        Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "Get-NetRoute hiba: $_" "Red"
}

# ==================== 12. ÖSSZEFOGLALÓ ====================
Write-Log ""
Write-Log "=== ÖSSZEFOGLALÓ ===" "Cyan"
Write-Log "Élő hostok száma: $($AliveHosts.Count)"
Write-Log "Lassú válaszú hostok: $($SlowHosts.Count)"
Write-Log ""
Write-Log "Élő IP-k:"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }
Write-Log ""
Write-Log "Lassú IP-k (gyanús):"
$SlowHosts | Sort-Object | ForEach-Object { Write-Log "  $_" "Magenta" }
Write-Log ""
Write-Log "MEGJEGYZÉS A SWITCH-EKRŐL:"
Write-Log "A managed/unmanaged switch-ek többsége NEM válaszol pingre, NEM jelenik meg ARP-ban (ha nincs management IP),"
Write-Log "és NEM nyit portokat. Csak akkor látszanak, ha van management interfészük (általában .1 / .254 / külön VLAN)."
Write-Log "A 'láthatatlan' switch-ek hibáját leginkább a mögöttük lévő eszközök sántításából, packet error-okból"
Write-Log "és a pathping veszteségéből lehet következtetni."
Write-Log ""
Write-Log "MOBILNET EMLÉKEZTETŐ:"
Write-Log "A SIM a routerben van → a PC-n futó netsh mbn parancsok gyakran üresek. Ez normális."
Write-Log "A belső hálózati hibák (miner, switch, lassú eszköz) függetlenek a mobilnet minőségétől."
Write-Log ""
Write-Log "=== DIAGNOSZTIKA VÉGE ===" "Cyan"
Write-Log "Fájl mentve: $OutFile"

# Fájlba írás
$Log | Out-File -FilePath $OutFile -Encoding UTF8

Write-Host ""
Write-Host "Kész! A teljes, maximális log itt van: $OutFile" -ForegroundColor Green
Write-Host "Küldd el ezt a fájlt, és együtt kiértékeljük." -ForegroundColor Green

try { Invoke-Item $OutFile } catch {}