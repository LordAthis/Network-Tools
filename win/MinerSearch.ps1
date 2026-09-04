# ============================================================
#  MinerSearch.ps1
#  Miner felfedezes a LOG-okbol + sajat LOG iras
#  Nem tolt be feleslegesen minden gyarto fajlt.
#  Mas script hivja; a vegen visszater (nincs blokkolo pause).
# ============================================================

$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "MinerSearch"

# --- Utak ---
$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = (Get-Location).Path }

$DataDir = Join-Path $ScriptRoot "datas"
$LogDir  = Join-Path $ScriptRoot "LOG"

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$LogFile   = Join-Path $LogDir "MinerSearch_$timestamp.txt"

function Write-Log {
    param([string]$Message, [string]$Color = "White")
    $line = "{0} | {1}" -f (Get-Date -Format "HH:mm:ss"), $Message
    Write-Host $line -ForegroundColor $Color
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

Write-Log "=== MinerSearch INDUL ===" "Cyan"
Write-Log "Gep: $env:COMPUTERNAME | Felhasznalo: $env:USERNAME"
Write-Log "LOG fajl: $LogFile"
Write-Log ""

function Load-JsonFile {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path $Path)) {
        Write-Log "HIANYZIK: $Name" "Red"
        return $null
    }
    try {
        return (Get-Content -Path $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch {
        Write-Log "JSON hiba ($Name): $($_.Exception.Message)" "Red"
        return $null
    }
}

# Csak a szukseges kis fajlok
$simpleKeywords = Load-JsonFile -Path (Join-Path $DataDir "minerpoollist.json") -Name "minerpoollist.json"
$portsCommon    = Load-JsonFile -Path (Join-Path $DataDir "common\ports.json") -Name "ports.json"

if ($simpleKeywords) { Write-Log "minerpoollist.json : $($simpleKeywords.Count) kulcsszo" "Green" }
if ($portsCommon)    { Write-Log "ports.json betoltve" "Green" }
Write-Log ""

# --- LOG fajlok listazasa menuben ---
Write-Log "=== LOG FAJLOK ===" "Yellow"

$allLogs = @()
if (Test-Path $LogDir) {
    $allLogs = @(Get-ChildItem -Path $LogDir -Filter "*.txt" -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending)
}

if ($allLogs.Count -eq 0) {
    Write-Log "Nincs LOG fajl: $LogDir" "Red"
    Write-Log "=== MinerSearch VEGE (nincs bemenet) ===" "Cyan"
    return
}

$today = Get-Date -Format "yyyyMMdd"
$menuItems = @()
$i = 0

Write-Host ""
Write-Host "Elerheto LOG fajlok:" -ForegroundColor Cyan
foreach ($l in $allLogs) {
    $i++
    $mark = ""
    if ($l.Name -match $today -or $l.LastWriteTime.Date -eq (Get-Date).Date) { $mark = " [MA]" }
    Write-Host ("  {0,2}. {1}  ({2}){3}" -f $i, $l.Name, $l.LastWriteTime.ToString("yyyy-MM-dd HH:mm"), $mark)
    $menuItems += $l
}

Write-Host ""
Write-Host "  A  = osszes mai LOG" -ForegroundColor DarkCyan
Write-Host "  L  = legutolso LOG csak" -ForegroundColor DarkCyan
Write-Host "  S  = sorszam (pl. 1 vagy 1,3)" -ForegroundColor DarkCyan
Write-Host "  Q  = kilepes" -ForegroundColor DarkCyan
Write-Host ""
$choice = Read-Host "Valasztas"

$selectedLogs = @()
switch -Regex ($choice.Trim().ToUpper()) {
    '^Q$' {
        Write-Log "Felhasznalo kilepett." "Yellow"
        return
    }
    '^A$' {
        $selectedLogs = @($allLogs | Where-Object {
            $_.Name -match $today -or $_.LastWriteTime.Date -eq (Get-Date).Date
        })
        if ($selectedLogs.Count -eq 0) {
            Write-Log "Nincs mai LOG. Legutolso lesz hasznalva." "Yellow"
            $selectedLogs = @($allLogs[0])
        }
    }
    '^L$' {
        $selectedLogs = @($allLogs[0])
    }
    default {
        $nums = $choice -split '[,;\s]+' | Where-Object { $_ -match '^\d+$' }
        foreach ($n in $nums) {
            $idx = [int]$n - 1
            if ($idx -ge 0 -and $idx -lt $menuItems.Count) {
                $selectedLogs += $menuItems[$idx]
            }
        }
        if ($selectedLogs.Count -eq 0) {
            Write-Log "Ervenytelen valasztas -> legutolso LOG." "Yellow"
            $selectedLogs = @($allLogs[0])
        }
    }
}

Write-Log "Kivalasztott LOG-ok: $($selectedLogs.Count) db"
foreach ($s in $selectedLogs) { Write-Log "  -> $($s.Name)" "DarkGray" }
Write-Log ""

# --- Elemzes ---
Write-Log "=== ELEMZES ===" "Yellow"

$foundKeywords = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$foundPorts    = [System.Collections.Generic.HashSet[int]]::new()
$foundIps      = [System.Collections.Generic.HashSet[string]]::new()

$checkPorts = @(22, 80, 443, 4028, 4433, 50051, 8080, 9999, 3333, 3357, 1010, 8888)
if ($portsCommon -and $portsCommon.priority_scan) {
    $checkPorts = @($portsCommon.priority_scan) + @($portsCommon.stratum_common) | Select-Object -Unique
}

foreach ($log in $selectedLogs) {
    Write-Log "Feldolgozas: $($log.Name)" "Cyan"
    $content = Get-Content -Path $log.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $content) {
        Write-Log "  Ures vagy olvashatatlan." "Red"
        continue
    }

    if ($simpleKeywords) {
        foreach ($kw in $simpleKeywords) {
            if ($content -match [regex]::Escape([string]$kw)) {
                [void]$foundKeywords.Add([string]$kw)
            }
        }
    }

    foreach ($p in $checkPorts) {
        if ($content -match ":$p\b") { [void]$foundPorts.Add([int]$p) }
    }

    $ipMatches = [regex]::Matches($content, '\b(?:192\.168|10\.|172\.(?:1[6-9]|2[0-9]|3[01]))\.\d{1,3}\.\d{1,3}\b')
    foreach ($m in $ipMatches) { [void]$foundIps.Add($m.Value) }
}

Write-Log ""
if ($foundKeywords.Count -gt 0) {
    Write-Log "Talalt kulcsszavak:" "Green"
    $foundKeywords | Sort-Object | ForEach-Object { Write-Log "  - $_" "Green" }
} else {
    Write-Log "Nincs ismert pool kulcsszo." "DarkGray"
}

if ($foundPorts.Count -gt 0) {
    Write-Log "Relevans portok: $($foundPorts -join ', ')" "Yellow"
}

if ($foundIps.Count -gt 0) {
    Write-Log "Kinyert belso IP-k ($($foundIps.Count)):" "Yellow"
    $foundIps | Sort-Object | ForEach-Object { Write-Log "  $_" "DarkGray" }
} else {
    Write-Log "Nem talalhato belso IP a LOG-okban." "DarkGray"
}

# IP lista mentese kulon, hogy a MinerStatus konnyen olvassa
$ipListFile = Join-Path $LogDir "MinerSearch_IPs_$timestamp.txt"
$foundIps | Sort-Object | Set-Content -Path $ipListFile -Encoding UTF8
Write-Log "IP lista mentve: $ipListFile" "Green"

Write-Log ""
Write-Log ""
Write-Log "=== MinerSearch VEGE ===" "Cyan"
Write-Log "LOG mentve: $LogFile" "Green"

# Opcionalis LOG megnyitas (onallo futtataskor hasznalhato)
$openLog = Read-Host "Megnyissuk a LOG fajlt bongeszoben, olvashato formaban? (I/N)"
if ($openLog -match '^[IiYy]') {
    $logToIndex = Join-Path $ScriptRoot "LOGtoINDEX.ps1"
    if (Test-Path $logToIndex) {
        try { & $logToIndex -LogFile $LogFile -LogDir $LogDir } catch { Write-Log "LOGtoINDEX inditas sikertelen: $($_.Exception.Message)" "Yellow" }
    } else {
        try { Start-Process notepad.exe -ArgumentList $LogFile } catch { Write-Log "Notepad inditas sikertelen" "Yellow" }
    }
}
# Visszater a hivohoz (ha volt hivo)
