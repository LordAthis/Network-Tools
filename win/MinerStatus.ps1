# ============================================================
#  MinerStatus.ps1
#  Aktiv lekerdezes a talalt / ismert IP-ken
#  - Alap probe (version/summary/pools, web, SSH banner)
#  - Opcionalisan bovitett, gyarto-specifikus parancsok
#  Mas script hivja; a vegen visszater.
#  Kommentek lehetnek ekezetesek; a kimenet ekezetmentes.
# ============================================================

$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "MinerStatus"

# --- Utak ---
$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = (Get-Location).Path }

$DataDir   = Join-Path $ScriptRoot "datas"
$LogDir    = Join-Path $ScriptRoot "LOG"
$ManufDir  = Join-Path $DataDir "manufacturers"
$CommonDir = Join-Path $DataDir "common"

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$LogFile   = Join-Path $LogDir "MinerStatus_$timestamp.txt"

function Write-Log {
    param([string]$Message, [string]$Color = "White")
    $line = "{0} | {1}" -f (Get-Date -Format "HH:mm:ss"), $Message
    Write-Host $line -ForegroundColor $Color
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Load-JsonFile {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path $Path)) { return $null }
    try {
        return (Get-Content -Path $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch {
        Write-Log "JSON hiba ($Name): $($_.Exception.Message)" "Red"
        return $null
    }
}

function Offer-OpenLog {
    $openLog = Read-Host "Megnyissuk a LOG fajlt bongeszoben, olvashato formaban? (I/N)"
    if ($openLog -match '^[IiYy]') {
        $logToIndex = Join-Path $ScriptRoot "LOGtoINDEX.ps1"
        if (Test-Path $logToIndex) {
            try { & $logToIndex -LogFile $LogFile -LogDir $LogDir } catch { Write-Log "LOGtoINDEX inditas sikertelen: $($_.Exception.Message)" "Yellow" }
        } else {
            try { Start-Process notepad.exe -ArgumentList $LogFile } catch { Write-Log "Notepad inditas sikertelen" "Yellow" }
        }
    }
}

Write-Log "=== MinerStatus INDUL ===" "Cyan"
Write-Log "Gep: $env:COMPUTERNAME | Felhasznalo: $env:USERNAME"
Write-Log "LOG fajl: $LogFile"
Write-Log ""

# --- 1. Cel IP-k ---
Write-Log "=== 1. CEL IP LISTA ===" "Yellow"

Write-Host ""
Write-Host "Honnan vegyuk a cel IP-ket?" -ForegroundColor Cyan
Write-Host "  1. Csak ismert / LOG-bol kinyert IP-k (MinerSearch_IPs_*.txt vagy MinerSearch LOG)" -ForegroundColor DarkCyan
Write-Host "  2. LOG IP-k + aktualis ARP tabla (minden lathato eszkoz)" -ForegroundColor DarkCyan
Write-Host "  3. Kilepes" -ForegroundColor DarkCyan
Write-Host ""
$srcChoice = Read-Host "Valasztas (1/2/3)"

if ($srcChoice -eq "3") {
    Write-Log "Kilepes." "Yellow"
    Offer-OpenLog
    return
}

$targetIps = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)

$ipListFiles = @(Get-ChildItem -Path $LogDir -Filter "MinerSearch_IPs_*.txt" -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending)
if ($ipListFiles.Count -gt 0) {
    $latestIpList = $ipListFiles[0]
    Write-Log "IP lista fajl: $($latestIpList.Name)" "Green"
    Get-Content $latestIpList.FullName | ForEach-Object {
        $ip = $_.Trim()
        if ($ip -match '^\d+\.\d+\.\d+\.\d+$') { [void]$targetIps.Add($ip) }
    }
}

if ($targetIps.Count -eq 0) {
    $msLogs = @(Get-ChildItem -Path $LogDir -Filter "MinerSearch_*.txt" -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending)
    if ($msLogs.Count -gt 0) {
        $content = Get-Content $msLogs[0].FullName -Raw
        $ipMatches = [regex]::Matches($content, '\b(?:192\.168|10\.|172\.(?:1[6-9]|2[0-9]|3[01]))\.\d{1,3}\.\d{1,3}\b')
        foreach ($m in $ipMatches) { [void]$targetIps.Add($m.Value) }
        Write-Log "IP-k a legutobbi MinerSearch LOG-bol: $($msLogs[0].Name)" "Green"
    }
}

if ($srcChoice -eq "2") {
    Write-Log "ARP tabla lekerdezese..." "Yellow"
    try {
        $arp = arp -a 2>$null | Out-String
        $arpIps = [regex]::Matches($arp, '\b(?:192\.168|10\.|172\.(?:1[6-9]|2[0-9]|3[01]))\.\d{1,3}\.\d{1,3}\b')
        foreach ($m in $arpIps) { [void]$targetIps.Add($m.Value) }
        Write-Log "ARP utan IP-k: $($targetIps.Count)" "Green"
    } catch {
        Write-Log "ARP lekerdezes sikertelen: $($_.Exception.Message)" "Red"
    }
}

$cleanIps = @($targetIps | Where-Object {
    $_ -notmatch '\.255$' -and $_ -notmatch '\.0$' -and $_ -notmatch '^224\.' -and $_ -notmatch '^239\.'
} | Sort-Object)

if ($cleanIps.Count -eq 0) {
    Write-Log "Nincs lekerdezheto IP. Futtasd elobb a MinerSearch.ps1-et vagy valassz ARP modot." "Red"
    Write-Log "=== MinerStatus VEGE ===" "Cyan"
    Offer-OpenLog
    return
}

Write-Log "Cel IP-k ($($cleanIps.Count)):" "Yellow"
$idx = 0
foreach ($ip in $cleanIps) {
    $idx++
    Write-Log ("  {0,2}. {1}" -f $idx, $ip) "DarkGray"
}
Write-Log ""

# --- 2. Probe szint ---
Write-Host "Probe szint:" -ForegroundColor Cyan
Write-Host "  1. Csak alap (version / summary / pools + web title + SSH banner)" -ForegroundColor DarkCyan
Write-Host "  2. Alap + bovitett (gyarto JSON extended_commands, ha van)" -ForegroundColor DarkCyan
Write-Host ""
$probeChoice = Read-Host "Valasztas (1/2)"
$doExtended = ($probeChoice -eq "2")
Write-Log "Probe szint: $(if ($doExtended) { 'alap + bovitett' } else { 'csak alap' })"
Write-Log ""

# Gyarto profilok csak bovitett modban
$manufProfiles = @()
if ($doExtended) {
    $manufIndex = Load-JsonFile -Path (Join-Path $ManufDir "_index.json") -Name "_index.json"
    if ($manufIndex -and $manufIndex.load_order) {
        Write-Log "Bovitett mod: gyarto profilok betoltese..." "Yellow"
        foreach ($fname in $manufIndex.load_order) {
            $obj = Load-JsonFile -Path (Join-Path $ManufDir $fname) -Name $fname
            if ($obj) {
                $manufProfiles += $obj
                Write-Log "  + $fname" "DarkGray"
            }
        }
        Write-Log "Betoltott profilok: $($manufProfiles.Count)" "Green"
    }
}
Write-Log ""

# --- Probe segedfuggvenyek ---
function Invoke-TcpJsonProbe {
    param(
        [string]$Ip,
        [int]$Port = 4028,
        [string]$Payload = '{"command":"version"}',
        [int]$TimeoutMs = 2500
    )
    $result = [PSCustomObject]@{ Ok = $false; Raw = $null; Error = $null }
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect($Ip, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($TimeoutMs)
        if (-not $ok) {
            $client.Close()
            $result.Error = "timeout"
            return $result
        }
        $client.EndConnect($iar)
        $stream = $client.GetStream()
        $stream.ReadTimeout = $TimeoutMs
        $stream.WriteTimeout = $TimeoutMs
        $data = [System.Text.Encoding]::ASCII.GetBytes($Payload)
        $stream.Write($data, 0, $data.Length)
        Start-Sleep -Milliseconds 300
        $buffer = New-Object byte[] 8192
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -gt 0) {
            $text = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $read)
            $text = $text -replace '\x00.*$', ''
            $result.Ok = $true
            $result.Raw = $text.Trim()
        } else {
            $result.Error = "empty"
        }
        $stream.Close()
        $client.Close()
    } catch {
        $result.Error = $_.Exception.Message
        try { if ($client) { $client.Close() } } catch {}
    }
    return $result
}

function Get-HttpTitle {
    param([string]$Ip, [int]$Port = 80, [int]$TimeoutSec = 3)
    try {
        $url = "http://${Ip}:${Port}/"
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Timeout = $TimeoutSec * 1000
        $req.ReadWriteTimeout = $TimeoutSec * 1000
        $req.UserAgent = "MinerStatus/1.0"
        $req.Method = "GET"
        $resp = $req.GetResponse()
        $stream = $resp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $body = $reader.ReadToEnd()
        $reader.Close(); $stream.Close(); $resp.Close()
        if ($body -match '<title>(.*?)</title>') { return $Matches[1].Trim() }
        return "HTTP OK"
    } catch { return $null }
}

function Get-SshBanner {
    param([string]$Ip, [int]$Port = 22, [int]$TimeoutMs = 2000)
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect($Ip, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($TimeoutMs)
        if (-not $ok) { $client.Close(); return $null }
        $client.EndConnect($iar)
        $stream = $client.GetStream()
        $stream.ReadTimeout = $TimeoutMs
        Start-Sleep -Milliseconds 200
        $buffer = New-Object byte[] 256
        $read = $stream.Read($buffer, 0, $buffer.Length)
        $client.Close()
        if ($read -gt 0) {
            return ([System.Text.Encoding]::ASCII.GetString($buffer, 0, $read)).Trim()
        }
    } catch { try { if ($client) { $client.Close() } } catch {} }
    return $null
}

# --- 3. Probe futtatas ---
Write-Log "=== 2. PROBE FUTTATAS ===" "Yellow"
$results = @()

foreach ($ip in $cleanIps) {
    Write-Log "--- $ip ---" "Cyan"
    $row = [ordered]@{
        IP          = $ip
        Api4028     = "nem"
        Version     = ""
        SummaryHint = ""
        PoolsHint   = ""
        Web80       = ""
        Ssh22       = ""
        Guess       = ""
        Notes       = ""
    }

    $v = Invoke-TcpJsonProbe -Ip $ip -Port 4028 -Payload '{"command":"version"}'
    if ($v.Ok) {
        $row.Api4028 = "igen"
        $row.Version = ($v.Raw -replace '\s+', ' ').Substring(0, [Math]::Min(120, $v.Raw.Length))
        Write-Log "  4028 version: OK" "Green"
        Write-Log "    $($row.Version)" "DarkGray"

        $s = Invoke-TcpJsonProbe -Ip $ip -Port 4028 -Payload '{"command":"summary"}'
        if ($s.Ok) {
            $row.SummaryHint = ($s.Raw -replace '\s+', ' ').Substring(0, [Math]::Min(100, $s.Raw.Length))
            Write-Log "  4028 summary: OK" "Green"
        }

        $p = Invoke-TcpJsonProbe -Ip $ip -Port 4028 -Payload '{"command":"pools"}'
        if ($p.Ok) {
            $row.PoolsHint = ($p.Raw -replace '\s+', ' ').Substring(0, [Math]::Min(100, $p.Raw.Length))
            Write-Log "  4028 pools: OK" "Green"
        }

        $rawAll = "$($v.Raw) $($s.Raw) $($p.Raw)"
        if ($rawAll -match 'Antminer|Bitmain|bmminer') { $row.Guess = "Bitmain/Antminer" }
        elseif ($rawAll -match 'Avalon|Canaan') { $row.Guess = "Canaan/Avalon" }
        elseif ($rawAll -match 'Whatsminer|MicroBT') { $row.Guess = "MicroBT/Whatsminer" }
        elseif ($rawAll -match 'Braiins|BOSminer') { $row.Guess = "Braiins OS" }
        elseif ($rawAll -match 'Innosilicon') { $row.Guess = "Innosilicon" }
        else { $row.Guess = "cgminer-kompatibilis" }
    } else {
        Write-Log "  4028: $($v.Error)" "DarkGray"
    }

    if ($row.Api4028 -eq "nem") {
        $w = Invoke-TcpJsonProbe -Ip $ip -Port 4433 -Payload '{"cmd":"get_version"}'
        if ($w.Ok) {
            $row.Api4028 = "4433"
            $row.Guess = "MicroBT/Whatsminer"
            $row.Version = ($w.Raw -replace '\s+', ' ').Substring(0, [Math]::Min(100, $w.Raw.Length))
            Write-Log "  4433 Whatsminer: OK" "Green"
        }
    }

    $title = Get-HttpTitle -Ip $ip -Port 80
    if (-not $title) { $title = Get-HttpTitle -Ip $ip -Port 8080 }
    if ($title) {
        $row.Web80 = $title
        Write-Log "  Web UI: $title" "Green"
        if (-not $row.Guess) {
            if ($title -match 'Antminer') { $row.Guess = "Bitmain/Antminer" }
            elseif ($title -match 'Whatsminer') { $row.Guess = "MicroBT/Whatsminer" }
            elseif ($title -match 'Avalon') { $row.Guess = "Canaan/Avalon" }
            elseif ($title -match 'Axe|Bitaxe|Nerd') { $row.Guess = "Bitaxe/AxeOS" }
            elseif ($title -match 'Innosilicon') { $row.Guess = "Innosilicon" }
        }
    } else {
        Write-Log "  Web UI: nincs valasz" "DarkGray"
    }

    $banner = Get-SshBanner -Ip $ip -Port 22
    if ($banner) {
        $row.Ssh22 = ($banner -replace '\s+', ' ').Substring(0, [Math]::Min(80, $banner.Length))
        Write-Log "  SSH22: $($row.Ssh22)" "Green"
    } else {
        Write-Log "  SSH22: nincs" "DarkGray"
    }

    if ($doExtended -and $manufProfiles.Count -gt 0 -and $row.Api4028 -ne "nem") {
        foreach ($prof in $manufProfiles) {
            $match = $false
            if ($row.Guess -and $prof.brand -and ($row.Guess -match [regex]::Escape([string]$prof.brand))) { $match = $true }
            if ($row.Guess -and $prof.manufacturer -and ($row.Guess -match [regex]::Escape([string]$prof.manufacturer))) { $match = $true }
            if (-not $match) { continue }

            if ($prof.extended_commands) {
                foreach ($cmd in $prof.extended_commands) {
                    $payload = $cmd.payload
                    if (-not $payload) { continue }
                    $port = 4028
                    if ($cmd.port) { $port = [int]$cmd.port }
                    $er = Invoke-TcpJsonProbe -Ip $ip -Port $port -Payload $payload
                    if ($er.Ok) {
                        $snippet = ($er.Raw -replace '\s+', ' ').Substring(0, [Math]::Min(80, $er.Raw.Length))
                        Write-Log "  EXT $($cmd.cmd): $snippet" "DarkCyan"
                        $row.Notes += "ext:$($cmd.cmd); "
                    }
                }
            }
            break
        }
    }

    $results += [PSCustomObject]$row
}

# --- 4. Tabla ---
Write-Log ""
Write-Log "=== 3. EREDMENY TABLA ===" "Yellow"
Write-Log ("{0,-15} {1,-8} {2,-22} {3,-20} {4,-12}" -f "IP", "API", "Guess", "Web", "SSH")
Write-Log ("{0,-15} {1,-8} {2,-22} {3,-20} {4,-12}" -f ("-"*15), ("-"*8), ("-"*22), ("-"*20), ("-"*12))

foreach ($r in $results) {
    $webShort = if ($r.Web80) { $r.Web80.Substring(0, [Math]::Min(18, $r.Web80.Length)) } else { "-" }
    $sshShort = if ($r.Ssh22) { "igen" } else { "-" }
    $guessShort = if ($r.Guess) { $r.Guess } else { "-" }
    Write-Log ("{0,-15} {1,-8} {2,-22} {3,-20} {4,-12}" -f $r.IP, $r.Api4028, $guessShort, $webShort, $sshShort)
}

Write-Log ""
Write-Log "=== RESZLETES ===" "Yellow"
foreach ($r in $results) {
    Write-Log "IP: $($r.IP)"
    Write-Log "  API: $($r.Api4028) | Guess: $($r.Guess)"
    if ($r.Version)     { Write-Log "  Version: $($r.Version)" }
    if ($r.SummaryHint) { Write-Log "  Summary: $($r.SummaryHint)" }
    if ($r.PoolsHint)   { Write-Log "  Pools: $($r.PoolsHint)" }
    if ($r.Web80)       { Write-Log "  Web: $($r.Web80)" }
    if ($r.Ssh22)       { Write-Log "  SSH: $($r.Ssh22)" }
    if ($r.Notes)       { Write-Log "  Notes: $($r.Notes)" }
    Write-Log ""
}

Write-Log "=== MinerStatus VEGE ===" "Cyan"
Write-Log "LOG mentve: $LogFile" "Green"
Offer-OpenLog
# Visszater a hivohoz
