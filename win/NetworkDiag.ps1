#Requires -Version 5.1
# Network Diagnostic Script - Thorough LAN Scan + Error Collection
# Output: C:\lan\NetworkDiag_YYYYMMDD_HHMMSS.txt

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
$OutFile = Join-Path $OutDir "NetworkDiag_$Timestamp.txt"
$Log = [System.Collections.Generic.List[string]]::new()

function Write-Log {
    param([string]$Message, [string]$Color = "White")
    \( line = " \)(Get-Date -Format 'HH:mm:ss') | $Message"
    $Log.Add($line)
    Write-Host $line -ForegroundColor $Color
}

Write-Log "=== HÁLÓZATI DIAGNOSZTIKA INDUL ===" "Cyan"
Write-Log "Gép: $env:COMPUTERNAME | Felhasználó: $env:USERNAME | Idő: $(Get-Date)"
Write-Log "Kimeneti fájl: $OutFile"
Write-Log ""

# ==================== 1. ALAP RENDSZER / ADAPTER INFO ====================
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

# ==================== 2. ARP TÁBLA (létező eszközök) ====================
Write-Log ""
Write-Log "=== 2. ARP TÁBLA (Get-NetNeighbor + arp -a) ===" "Cyan"

try {
    $neighbors = Get-NetNeighbor -AddressFamily IPv4 | Where-Object { $_.State -ne "Unreachable" } | Sort-Object IPAddress
    Write-Log "Get-NetNeighbor találatok: $($neighbors.Count)"
    $neighbors | Format-Table IPAddress, LinkLayerAddress, State, InterfaceAlias -AutoSize | Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "Get-NetNeighbor hiba: $_" "Red"
}

Write-Log "--- arp -a ---"
$arp = arp -a 2>&1 | Out-String
$Log.Add($arp)
Write-Host $arp

# ==================== 3. PING-SWEEP a leggyakoribb alhálózatokon ====================
Write-Log ""
Write-Log "=== 3. PING-SWEEP (több alhálózat) ===" "Cyan"

$Subnets = @(
    "192.168.0",
    "192.168.1",
    "192.168.2",
    "163.138.8"     # szolgáltatói router tartomány (feltételezett /24)
)

$AliveHosts = [System.Collections.Generic.List[string]]::new()
$PingResults = [System.Collections.Generic.List[object]]::new()

foreach ($subnet in $Subnets) {
    Write-Log "Sweep indul: $subnet.0/24 ..." "Yellow"
    
    $jobs = 1..254 | ForEach-Object {
        $ip = "$subnet.$_"
        Start-Job -ScriptBlock {
            param($target)
            $result = Test-Connection -ComputerName $target -Count 1 -Quiet -TimeoutSeconds 1 -ErrorAction SilentlyContinue
            [PSCustomObject]@{
                IP      = $target
                Alive   = [bool]$result
            }
        } -ArgumentList $ip
    }

    # Várakozás + eredmények
    $jobs | Wait-Job -Timeout 90 | Out-Null
    foreach ($job in $jobs) {
        $res = Receive-Job $job -ErrorAction SilentlyContinue
        if ($res -and $res.Alive) {
            $AliveHosts.Add($res.IP)
            $PingResults.Add($res)
            Write-Log "  ÉLŐ: $($res.IP)" "Green"
        }
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

Write-Log ""
Write-Log "Összes élő host a sweep-ből: $($AliveHosts.Count)" "Green"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }

# ==================== 4. TOVÁBBI INFORMÁCIÓK AZ ÉLŐ HOSTOKRÓL ====================
Write-Log ""
Write-Log "=== 4. RÉSZLETES HOST INFO (élő címek) ===" "Cyan"

foreach ($ip in ($AliveHosts | Sort-Object)) {
    Write-Log "--- $ip ---" "Yellow"
    
    # Hostname feloldás
    try {
        $dns = [System.Net.Dns]::GetHostEntry($ip)
        Write-Log "  Hostname: $($dns.HostName)"
    } catch {
        Write-Log "  Hostname: (nem oldható fel)"
    }

    # ARP MAC (ha van)
    try {
        $mac = (Get-NetNeighbor -IPAddress $ip -ErrorAction SilentlyContinue).LinkLayerAddress
        if ($mac) { Write-Log "  MAC: $mac" }
    } catch {}

    # Gyors port ellenőrzés (gyakori eszköz portok)
    $commonPorts = @(80, 443, 22, 23, 8080, 8443, 4028, 3333, 25, 3389)
    $openPorts = @()
    foreach ($port in $commonPorts) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $iar = $tcp.BeginConnect($ip, $port, $null, $null)
            $success = $iar.AsyncWaitHandle.WaitOne(400, $false)
            if ($success -and $tcp.Connected) {
                $openPorts += $port
            }
            $tcp.Close()
        } catch {}
    }
    if ($openPorts.Count -gt 0) {
        Write-Log "  Nyitott portok: $($openPorts -join ', ')" "Green"
    } else {
        Write-Log "  Nyitott gyakori port: nincs (vagy tűzfal)"
    }
}

# ==================== 5. ÚTVONAL TÁBLA + DNS ====================
Write-Log ""
Write-Log "=== 5. ÚTVONAL TÁBLA ÉS DNS ===" "Cyan"

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

# ==================== 6. AKTÍV TCP KAPCSOLATOK ====================
Write-Log ""
Write-Log "=== 6. AKTÍV TCP KAPCSOLATOK (Get-NetTCPConnection) ===" "Cyan"
try {
    Get-NetTCPConnection -State Established, Listen -ErrorAction SilentlyContinue | 
        Select-Object LocalAddress, LocalPort, RemoteAddress, RemotePort, State, OwningProcess |
        Sort-Object LocalPort | Format-Table -AutoSize | Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "TCP kapcsolatok hiba: $_" "Red"
}

# ==================== 7. HÁLÓZATI HIBÁK / STATISZTIKÁK ====================
Write-Log ""
Write-Log "=== 7. ADAPTER HIBASTATISZTIKÁK (Get-NetAdapterStatistics) ===" "Cyan"
try {
    Get-NetAdapterStatistics | Format-Table Name, ReceivedBytes, SentBytes, ReceivedUnicastPackets, SentUnicastPackets, 
        ReceivedDiscardedPackets, OutboundDiscardedPackets, ReceivedPacketErrors, OutboundPacketErrors -AutoSize | 
        Out-String | ForEach-Object { $Log.Add($_); Write-Host $_ }
} catch {
    Write-Log "Statisztika hiba: $_" "Red"
}

Write-Log ""
Write-Log "=== 8. RENDSZERNAPLÓ - HÁLÓZATI HIBÁK (utolsó 48 óra) ===" "Cyan"

$EventLogs = @(
    @{ Log = "System"; Provider = "Tcpip"; Level = 2,3 },          # Error + Warning
    @{ Log = "System"; Provider = "e1dexpress"; Level = 2,3 },     # Intel adapter
    @{ Log = "System"; Provider = "Netwtw*"; Level = 2,3 },        # Intel WiFi
    @{ Log = "System"; Provider = "ndis"; Level = 2,3 },
    @{ Log = "System"; Provider = "Dhcp*"; Level = 2,3 },
    @{ Log = "Microsoft-Windows-DNS-Client/Operational"; Provider = $null; Level = 2,3 }
)

$since = (Get-Date).AddHours(-48)

foreach ($el in $EventLogs) {
    try {
        $filter = @{
            LogName   = $el.Log
            StartTime = $since
            Level     = $el.Level
        }
        if ($el.Provider) { $filter.ProviderName = $el.Provider }

        $events = Get-WinEvent -FilterHashtable $filter -MaxEvents 30 -ErrorAction SilentlyContinue
        if ($events) {
            Write-Log "--- $($el.Log) / $($el.Provider) --- találat: $($events.Count)" "Yellow"
            foreach ($e in $events) {
                $msg = $e.Message -replace "`r`n", " " -replace "\s+", " "
                if ($msg.Length -gt 180) { $msg = $msg.Substring(0, 180) + "..." }
                Write-Log "  [$(\( e.TimeCreated.ToString('yyyy-MM-dd HH:mm'))] ID: \)($e.Id) $msg"
            }
        }
    } catch {
        # sok log nem létezik minden gépen – csendben ugorjuk
    }
}

# ==================== 9. EGYÉB HASZNOS PARANCSOK ====================
Write-Log ""
Write-Log "=== 9. EGYÉB INFORMÁCIÓK ===" "Cyan"

Write-Log "--- netstat -ano (összefoglaló) ---"
$netstat = netstat -ano 2>&1 | Select-String "LISTENING|ESTABLISHED" | Select-Object -First 40 | Out-String
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

# ==================== 10. ÖSSZEFOGLALÓ ====================
Write-Log ""
Write-Log "=== ÖSSZEFOGLALÓ ===" "Cyan"
Write-Log "Élő hostok száma (ping): $($AliveHosts.Count)"
Write-Log "Élő IP-k:"
$AliveHosts | Sort-Object | ForEach-Object { Write-Log "  $_" }
Write-Log ""
Write-Log "Ha több router/switch van a hálózaton, a fenti élő címek közül a gateway-ek (általában .1 vagy .254) és a gyakori portokkal rendelkező eszközök a legfontosabbak."
Write-Log "Miner-ek gyakran 3333, 4028 vagy egyedi portokon hallgatnak."
Write-Log ""
Write-Log "=== DIAGNOSZTIKA VÉGE ===" "Cyan"
Write-Log "Fájl mentve: $OutFile"

# Fájlba írás
$Log | Out-File -FilePath $OutFile -Encoding UTF8

Write-Host ""
Write-Host "Kész! A teljes log itt van: $OutFile" -ForegroundColor Green
Write-Host "Küldd el ezt a fájlt, és együtt kiértékeljük." -ForegroundColor Green

# Opcionális: automatikus megnyitás
try { Invoke-Item $OutFile } catch {}