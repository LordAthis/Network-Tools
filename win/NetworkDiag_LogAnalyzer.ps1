#Requires -Version 5.1
<#
    NetworkDiag LogAnalyzer v1 (kezdeti valtozat - meg bovitheto)

    Cel: egy korabbi NetworkDiag_MAX_v2.ps1 futasabol szarmazo LOG fajlt dolgoz fel,
         kigyujti belole a "lassu", "nema" (ARP-only) es "gyanus" (miner-port) IP-ket,
         majd EZEKEN, csak ezeken vegez egy sokkal alaposabb, celzott utovizsgalatot:
         - tobb ping-probalkozas, csomagvesztes es jitter (szoras) szamitassal
         - szelesebb portscan (nem csak nehany port, hanem 1-1024 + ismert miner portok)
         - pathping minden celponthoz

    Ez EGY KEZDETI (v1) VALTOZAT. A cel egy teljes log-feldolgozo eszkoz felepitese,
    ami kesobb tovabb bovul - pl.:
        - tobb korabbi log osszehasonlitasa idoben (trend: ugyanaz az IP hetek ota lassu-e)
        - automatikus riport / osszefoglalo tobb futasrol
        - MAC-cim (OUI/gyarto) alapu eszkoz-azonositas

    Hasznalat:
        .\NetworkDiag_LogAnalyzer.ps1
            -> a legujabb NetworkDiag_MAX_*.txt fajlt hasznalja a LOG\ mappabol

        .\NetworkDiag_LogAnalyzer.ps1 -LogFile "C:\...\LOG\NetworkDiag_MAX_20260824_120000.txt"
            -> egy konkret log fajlt dolgoz fel

    Kimenet: <script mappaja>\LOG\NetworkDiag_FOLLOWUP_YYYYMMDD_HHMMSS.txt

    Konfiguracios fajl (megosztott a fo scripttel):
        datas\disableips.json - kizarando IP-k/mintak (hamis pozitivok, ld. fo script)
#>

param(
    [string]$LogFile,
    [string]$LogDir = "$PSScriptRoot\LOG",
    [string]$DisableIpsListPath = "$PSScriptRoot\datas\disableips.json",
    [int]$PortThrottleLimit = 120,
    [int]$PingAttempts = 10,
    [int]$PingTimeoutMs = 1000
)

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$OutFile = Join-Path $LogDir "NetworkDiag_FOLLOWUP_$Timestamp.txt"
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

Write-Log "=== NETWORKDIAG LOG-ELEMZO ES UTOVIZSGALAT (v1) ===" "Cyan"
Write-Log "Gep: $env:COMPUTERNAME | Ido: $(Get-Date)"
Write-Log ""

# ==================== SEGEDFUGGVENYEK (a fo scripttel azonos logikaval) ====================

function Get-JsonList {
    param([string]$Path, [array]$DefaultValue, [string]$Label)
    if (Test-Path $Path) {
        try {
            $raw = Get-Content -Path $Path -Raw -Encoding UTF8
            $data = $raw | ConvertFrom-Json -ErrorAction Stop
            $list = @($data) | Where-Object { $_ -and $_.ToString().Trim() -ne "" }
            if ($list.Count -gt 0) {
                Write-Log "$Label betoltve fajlbol: $Path ($($list.Count) elem)" "Green"
                return $list
            } else {
                Write-Log "$Label fajl ures - alapertelmezett lista hasznalva." "Yellow"
                return $DefaultValue
            }
        } catch {
            Write-Log "$Label fajl beolvasasi hiba ($Path): $_ - alapertelmezett lista hasznalva." "Red"
            return $DefaultValue
        }
    } else {
        Write-Log "$Label fajl nem talalhato ($Path) - alapertelmezett lista hasznalva (a fo script letrehozza, ha azt is futtatod)." "Yellow"
        return $DefaultValue
    }
}

$DefaultDisableIps = @("224.0.0.22", "224.0.0.251", "224.0.0.252", "239.255.255.250", "255.255.255.255")
$DisableIps = Get-JsonList -Path $DisableIpsListPath -DefaultValue $DefaultDisableIps -Label "Kizarando IP lista"

function Test-IsNoiseAddress {
    param([string]$IP, [string[]]$DisabledIps = @())
    if ([string]::IsNullOrWhiteSpace($IP)) { return $true }
    if ($IP -eq "255.255.255.255" -or $IP -eq "0.0.0.0") { return $true }
    $octets = $IP -split '\.'
    if ($octets.Count -eq 4) {
        try {
            $first = [int]$octets[0]
            if ($first -ge 224 -and $first -le 239) { return $true }
        } catch { }
    }
    if ($DisabledIps -contains $IP) { return $true }
    foreach ($pattern in $DisabledIps) {
        if ($pattern -match '[\*\?]' -and $IP -like $pattern) { return $true }
    }
    return $false
}

function Invoke-Parallel {
    param([array]$InputItems, [scriptblock]$ScriptBlock, [int]$Throttle = 64)
    $results = [System.Collections.Generic.List[object]]::new()
    if ($InputItems.Count -eq 0) { return $results }
    $pool = [runspacefactory]::CreateRunspacePool(1, [Math]::Max(1, $Throttle))
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

# ==================== 1. LOG FAJL MEGKERESESE ====================
Write-Log "=== 1. FELDOLGOZANDO LOG FAJL ===" "Cyan"
if (-not $LogFile) {
    $latest = Get-ChildItem -Path $LogDir -Filter "NetworkDiag_MAX_*.txt" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) {
        Write-Log "Nem talalhato NetworkDiag_MAX_*.txt fajl a $LogDir mappaban." "Red"
        Write-Log "Eloszor futtasd a NetworkDiag_MAX_v2.ps1 -t, vagy add meg -LogFile parameterrel egy konkret fajlt." "Yellow"
        $Log | Out-File -FilePath $OutFile -Encoding UTF8
        exit
    }
    $LogFile = $latest.FullName
    Write-Log "Nincs -LogFile megadva - a legujabb log hasznalva: $LogFile"
} else {
    if (-not (Test-Path $LogFile)) {
        Write-Log "A megadott log fajl nem talalhato: $LogFile" "Red"
        $Log | Out-File -FilePath $OutFile -Encoding UTF8
        exit
    }
    Write-Log "Feldolgozott log: $LogFile"
}

$RawLines = Get-Content -Path $LogFile -Encoding UTF8

# ==================== 2. LOG SZOVEG FELDOLGOZASA ====================
Write-Log ""
Write-Log "=== 2. LOG TARTALOM FELDOLGOZASA ===" "Cyan"

function Get-LogMessages {
    param([string[]]$Lines)
    $messages = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Lines) {
        if ($line -match '^\d{2}:\d{2}:\d{2} \| (.*)$') {
            $messages.Add($Matches[1])
        }
    }
    return $messages
}

function Get-IpsAfterHeader {
    param([string[]]$Messages, [string]$HeaderSubstring)
    $result = New-Object System.Collections.Generic.List[string]
    $capture = $false
    foreach ($msg in $Messages) {
        if (-not $capture) {
            if ($msg -like "*$HeaderSubstring*") { $capture = $true }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($msg)) { break }
        $trimmed = $msg.Trim()
        if ($trimmed -match '^(\d{1,3}\.){3}\d{1,3}$') {
            $result.Add($trimmed)
        } else {
            break
        }
    }
    return $result
}

$Messages = Get-LogMessages -Lines $RawLines

$SlowIpsRaw = Get-IpsAfterHeader -Messages $Messages -HeaderSubstring "Lassu IP-k"
$ArpOnlyIpsRaw = Get-IpsAfterHeader -Messages $Messages -HeaderSubstring "Nema (ARP-only) IP-k"
$SuspiciousIpsRaw = Get-IpsAfterHeader -Messages $Messages -HeaderSubstring "Gyanus IP-k"

Write-Log "Logban talalt lassu IP: $($SlowIpsRaw.Count)"
Write-Log "Logban talalt nema (ARP-only) IP: $($ArpOnlyIpsRaw.Count)"
Write-Log "Logban talalt gyanus (miner-port) IP: $($SuspiciousIpsRaw.Count)"

# Zaj (broadcast/multicast/kizart) cimek kiszurese - biztos, ami biztos, akkor is,
# ha egy regebbi, meg a bugfix elotti logot dolgozunk fel.
$AllRawTargets = @($SlowIpsRaw + $ArpOnlyIpsRaw + $SuspiciousIpsRaw) | Select-Object -Unique
$Targets = New-Object System.Collections.Generic.List[string]
$FilteredCount = 0
foreach ($ip in $AllRawTargets) {
    if (Test-IsNoiseAddress -IP $ip -DisabledIps $DisableIps) {
        $FilteredCount++
        continue
    }
    $Targets.Add($ip)
}
Write-Log "Kiszurt protokollszintu/zaj cim: $FilteredCount"
Write-Log "Tovabb vizsgalando (egyedi) cel-IP-k szama: $($Targets.Count)"

if ($Targets.Count -eq 0) {
    Write-Log ""
    Write-Log "Nincs tovabb vizsgalando IP a logban - a korabbi futas nem talalt valodi lassu/nema/gyanus eszkozt." "Green"
    Write-Log "Nincs teendo, az utovizsgalat itt befejezodik."
    $Log | Out-File -FilePath $OutFile -Encoding UTF8
    Write-Host ""
    Write-Host "Kesz! Log: $OutFile" -ForegroundColor Green
    exit
}
$Targets | ForEach-Object { Write-Log "  $_" "Yellow" }

# ==================== 3. MELY PING TESZT (jitter, csomagvesztes) ====================
Write-Log ""
Write-Log "=== 3. MELY PING TESZT ($PingAttempts probalkozas / IP, timeout $PingTimeoutMs ms) ===" "Cyan"

$PingScriptBlock = {
    param($ctx)
    $ip = $ctx.IP
    $attempts = $ctx.Attempts
    $timeoutMs = $ctx.TimeoutMs
    $successCount = 0
    $times = New-Object System.Collections.Generic.List[double]
    try {
        $pinger = New-Object System.Net.NetworkInformation.Ping
        for ($i = 1; $i -le $attempts; $i++) {
            try {
                $reply = $pinger.Send($ip, $timeoutMs)
                if ($reply -and $reply.Status -eq [System.Net.NetworkInformation.IPStatus]::Success) {
                    $successCount++
                    $times.Add([double]$reply.RoundtripTime)
                }
            } catch { }
            Start-Sleep -Milliseconds 100
        }
        $pinger.Dispose()
    } catch { }
    $avg = 0; $stdev = 0; $min = 0; $max = 0
    if ($times.Count -gt 0) {
        $avg = ($times | Measure-Object -Average).Average
        $min = ($times | Measure-Object -Minimum).Minimum
        $max = ($times | Measure-Object -Maximum).Maximum
        if ($times.Count -gt 1) {
            $variance = ($times | ForEach-Object { [math]::Pow($_ - $avg, 2) } | Measure-Object -Sum).Sum / $times.Count
            $stdev = [math]::Sqrt($variance)
        }
    }
    [PSCustomObject]@{
        IP = $ip; Attempts = $attempts; Success = $successCount
        LossPct = [math]::Round((1 - ($successCount / $attempts)) * 100, 0)
        AvgMs = [math]::Round($avg, 1); MinMs = [math]::Round($min, 1); MaxMs = [math]::Round($max, 1)
        JitterMs = [math]::Round($stdev, 1)
    }
}

$PingContexts = $Targets | ForEach-Object { [PSCustomObject]@{ IP = $_; Attempts = $PingAttempts; TimeoutMs = $PingTimeoutMs } }
$PingResults = Invoke-Parallel -InputItems $PingContexts -ScriptBlock $PingScriptBlock -Throttle 32

foreach ($r in ($PingResults | Sort-Object IP)) {
    $color = if ($r.LossPct -gt 0 -or $r.JitterMs -gt 30) { "Magenta" } else { "Green" }
    Write-Log "  $($r.IP): $($r.Success)/$($r.Attempts) valasz | Csomagvesztes: $($r.LossPct)% | Atlag: $($r.AvgMs) ms | Min/Max: $($r.MinMs)/$($r.MaxMs) ms | Jitter: $($r.JitterMs) ms" $color
}

# ==================== 4. SZELES PORTSCAN (1-1024 + ismert miner portok) ====================
Write-Log ""
Write-Log "=== 4. SZELES PORTSCAN (1-1024 + ismert miner/stratum portok, throttle=$PortThrottleLimit) ===" "Cyan"

$MinerPorts = @(1800, 3333, 3334, 3335, 3336, 3357, 4028, 4444, 5555, 6666, 7777,
                8332, 8333, 9332, 9333, 9999, 14433, 14444, 45700, 8888, 9998)
$WidePortRange = 1..1024
$AllPorts = @($WidePortRange + $MinerPorts) | Select-Object -Unique

$PortTargets = New-Object System.Collections.Generic.List[object]
foreach ($ip in $Targets) {
    foreach ($port in $AllPorts) {
        $PortTargets.Add([PSCustomObject]@{ IP = $ip; Port = $port })
    }
}
Write-Log "Osszes host x port kombinacio: $($PortTargets.Count) - ez eltarthat egy ideig..." "Yellow"

$PortScriptBlock = {
    param($target)
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $iar = $tcp.BeginConnect($target.IP, $target.Port, $null, $null)
        $success = $iar.AsyncWaitHandle.WaitOne(700, $false)
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

foreach ($grp in $PortResultsByIp) {
    $ip = $grp.Name
    $openPorts = $grp.Group | Where-Object { $_.Open } | Select-Object -ExpandProperty Port | Sort-Object
    $minerOpen = $openPorts | Where-Object { $MinerPorts -contains $_ }
    Write-Log "--- $ip ---" "Yellow"
    if ($openPorts.Count -gt 0) {
        Write-Log "  Nyitott portok: $($openPorts -join ', ')" "Green"
    } else {
        Write-Log "  Nincs nyitott port (1-1024 + miner portok kozott) - tuzfalazott vagy nagyon szigoruan zart eszkoz."
    }
    if ($minerOpen.Count -gt 0) {
        Write-Log "  *** GYANUS: ismert miner/stratum port nyitva: $($minerOpen -join ', ') ***" "Red"
    }
}

# ==================== 5. PATHPING MINDEN CELPONTHOZ ====================
Write-Log ""
Write-Log "=== 5. PATHPING MINDEN CELPONTHOZ ===" "Cyan"
foreach ($ip in $Targets) {
    Write-Log "--- pathping $ip ---" "Yellow"
    try {
        Write-Block (pathping -n -q 4 -p 100 -w 800 $ip 2>&1 | Out-String)
    } catch {
        Write-Log "  pathping hiba: $_" "Red"
    }
}

# ==================== OSSZEFOGLALO ====================
Write-Log ""
Write-Log "=== OSSZEFOGLALO ===" "Cyan"
Write-Log "Feldolgozott log: $LogFile"
Write-Log "Utovizsgalt IP-k szama: $($Targets.Count)"
Write-Log ""
Write-Log "Ez egy v1 (kezdeti) utovizsgalat volt. Erdemes a jovoben ezt kiegesziteni:" "Yellow"
Write-Log "  - tobb futas / tobb log osszehasonlitasa idoben (allando vs idoszakos hiba elkulonitese)"
Write-Log "  - MAC-cim (OUI/gyarto) alapu eszkoz-azonositas a hostname-fuggetlen felismereshez"
Write-Log "  - automatikus riasztas, ha ugyanaz az IP tobb egymas utani futasban is gyanus marad"
Write-Log ""
Write-Log "=== UTOVIZSGALAT VEGE ===" "Cyan"
Write-Log "Fajl mentve: $OutFile"

$Log | Out-File -FilePath $OutFile -Encoding UTF8

Write-Host ""
Write-Host "Kesz! Az utovizsgalat logja itt van: $OutFile" -ForegroundColor Green
Write-Host ""
$openAnswer = Read-Host "Megnyissam a logot bongeszoben, olvashato formaban? (I/n - alapertelmezett: Igen, csak nyomj Entert)"
if ([string]::IsNullOrWhiteSpace($openAnswer) -or $openAnswer -match "^(i|ig|igen|y|yes)$") {
    $logToIndex = Join-Path $PSScriptRoot "LOGtoINDEX.ps1"
    if (Test-Path $logToIndex) {
        try {
            & $logToIndex -LogFile $OutFile -LogDir $LogDir
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
