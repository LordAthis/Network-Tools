# ============================================================
#  MinerSearch.ps1
#  Miner felfedezes + LOG iras
#  Hasznalja: datas/minerpoollist.json, datas/miner_pools.json,
#             datas/manufacturers/*, datas/common/*
# ============================================================

$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "MinerSearch"

# --- Utak ---
$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = (Get-Location).Path }

$DataDir    = Join-Path $ScriptRoot "datas"
$LogDir     = Join-Path $ScriptRoot "LOG"
$ManufDir   = Join-Path $DataDir "manufacturers"
$CommonDir  = Join-Path $DataDir "common"

# LOG mappa biztositasa
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

$timestamp  = Get-Date -Format "yyyyMMdd_HHmmss"
$LogFile    = Join-Path $LogDir "MinerSearch_$timestamp.txt"

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

# --- JSON betoltes seged ---
function Load-JsonFile {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path $Path)) {
        Write-Log "HIANYZIK: $Name -> $Path" "Red"
        return $null
    }
    try {
        $raw = Get-Content -Path $Path -Raw -Encoding UTF8
        return $raw | ConvertFrom-Json
    } catch {
        Write-Log "JSON hiba ($Name): $($_.Exception.Message)" "Red"
        return $null
    }
}

# --- 1. Adatok betoltese ---
Write-Log "=== 1. ADATOK BETOLTESE ===" "Yellow"

$simpleKeywords = Load-JsonFile -Path (Join-Path $DataDir "minerpoollist.json") -Name "minerpoollist.json"
$poolsFull      = Load-JsonFile -Path (Join-Path $DataDir "miner_pools.json") -Name "miner_pools.json"
$portsCommon    = Load-JsonFile -Path (Join-Path $CommonDir "ports.json") -Name "ports.json"
$apiProbes      = Load-JsonFile -Path (Join-Path $CommonDir "api_probes.json") -Name "api_probes.json"
$manufIndex     = Load-JsonFile -Path (Join-Path $ManufDir "_index.json") -Name "_index.json"

if ($simpleKeywords) { Write-Log "minerpoollist.json : $($simpleKeywords.Count) kulcsszo" "Green" }
if ($poolsFull)      { Write-Log "miner_pools.json   : ver $($poolsFull.version)" "Green" }
if ($portsCommon)    { Write-Log "ports.json         : betoltve" "Green" }
if ($apiProbes)      { Write-Log "api_probes.json    : betoltve" "Green" }
if ($manufIndex)     { Write-Log "manufacturers/_index.json : $($manufIndex.load_order.Count) fajl" "Green" }

# Gyarto fajlok betoltese az index szerint
$manufacturers = @()
if ($manufIndex -and $manufIndex.load_order) {
    foreach ($fname in $manufIndex.load_order) {
        $fpath = Join-Path $ManufDir $fname
        $obj = Load-JsonFile -Path $fpath -Name $fname
        if ($obj) {
            $manufacturers += $obj
            Write-Log "  + $fname" "DarkGray"
        }
    }
}
Write-Log "Betoltott gyarto profilok: $($manufacturers.Count)" "Green"
Write-Log ""

# --- 2. Korabbi LOG-ok keresese (opcionalis bemenet) ---
Write-Log "=== 2. KORABBI LOG FAJLOK ===" "Yellow"

$today = Get-Date -Format "yyyyMMdd"
$allLogs = @()
if (Test-Path $LogDir) {
    $allLogs = Get-ChildItem -Path $LogDir -Filter "*.txt" -File -ErrorAction SilentlyContinue |
               Sort-Object LastWriteTime -Descending
}

$todayLogs = $allLogs | Where-Object {
    $_.Name -match $today -or $_.LastWriteTime.Date -eq (Get-Date).Date
}

$selectedLogs = @()
if ($todayLogs) {
    Write-Log "Mai LOG fajlok: $($todayLogs.Count) db" "Green"
    $selectedLogs = @($todayLogs | Select-Object -First 3)
    foreach ($l in $selectedLogs) { Write-Log "  -> $($l.Name)" "DarkGray" }
} else {
    Write-Log "Nincs mai LOG fajl." "Yellow"
    if ($allLogs.Count -gt 0) {
        Write-Log "Legutobbi elerheto fajlok:" "Yellow"
        $allLogs | Select-Object -First 5 | ForEach-Object {
            Write-Log ("  {0}  ({1})" -f $_.Name, $_.LastWriteTime.ToString("yyyy-MM-dd HH:mm")) "DarkGray"
        }
        $answer = Read-Host "Feldolgozzak a legutolso (nem mai) LOG fajlt is? (I/N)"
        if ($answer -match '^[IiYy]') {
            $selectedLogs = @($allLogs | Select-Object -First 1)
            Write-Log "Valasztott: $($selectedLogs[0].Name)" "Cyan"
        } else {
            Write-Log "Regi LOG kihagyva." "Yellow"
        }
    } else {
        Write-Log "Nincs egyaltalan LOG fajl." "Yellow"
    }
}
Write-Log ""

# --- 3. Kulcsszo + port kereses a kivalasztott LOG-okban ---
Write-Log "=== 3. LOG ELEMZES (pool kulcsszavak + portok) ===" "Yellow"

$foundKeywords = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$foundPorts    = [System.Collections.Generic.HashSet[int]]::new()
$foundIps      = [System.Collections.Generic.HashSet[string]]::new()

$checkPorts = @(22, 80, 443, 4028, 4433, 50051, 8080, 9999, 3333, 3357, 1010, 8888)
if ($portsCommon -and $portsCommon.priority_scan) {
    $checkPorts = $portsCommon.priority_scan + $portsCommon.stratum_common | Select-Object -Unique
}

foreach ($log in $selectedLogs) {
    Write-Log "Elemzes: $($log.Name)" "Cyan"
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
        if ($content -match ":$p\b" -or $content -match "\bport\s+$p\b") {
            [void]$foundPorts.Add([int]$p)
        }
    }

    # Egyszeru IP gyujtes (192.168.x.x es hasonlok)
    $ipMatches = [regex]::Matches($content, '\b(?:192\.168|10\.|172\.(?:1[6-9]|2[0-9]|3[01]))\.\d{1,3}\.\d{1,3}\b')
    foreach ($m in $ipMatches) {
        [void]$foundIps.Add($m.Value)
    }
}

if ($foundKeywords.Count -gt 0) {
    Write-Log "Talalt pool/miner kulcsszavak:" "Green"
    $foundKeywords | Sort-Object | ForEach-Object { Write-Log "  - $_" "Green" }
} else {
    Write-Log "Nem talalhato ismert pool kulcsszo a vizsgalt LOG-okban." "DarkGray"
}

if ($foundPorts.Count -gt 0) {
    Write-Log "Emlegetett relevans portok: $($foundPorts -join ', ')" "Yellow"
}

if ($foundIps.Count -gt 0) {
    Write-Log "LOG-bol kinyert belso IP-k ($($foundIps.Count) db):" "Yellow"
    $foundIps | Sort-Object | ForEach-Object { Write-Log "  $_" "DarkGray" }
}
Write-Log ""

# --- 4. Elokeszites a kovetkezo lepeshez (MinerStatus) ---
Write-Log "=== 4. OSSZEFOGLALO / KOVETKEZO LEPES ===" "Yellow"
Write-Log "Betoltott gyarto profilok : $($manufacturers.Count)"
Write-Log "Talalt kulcsszavak        : $($foundKeywords.Count)"
Write-Log "Talalt portok             : $($foundPorts.Count)"
Write-Log "Kinyert IP-k              : $($foundIps.Count)"
Write-Log ""
Write-Log "A reszletes lekerdezest a MinerStatus.ps1 vegzi majd."
Write-Log "  - Alap probe-ok: version / summary / pools (4028)"
Write-Log "  - Web UI check (80/8080)"
Write-Log "  - SSH banner (22)"
Write-Log "  - Gyarto-specifikus bovitett parancsok a manufacturers/*.json-bol"
Write-Log ""
Write-Log "=== MinerSearch VEGE ===" "Cyan"
Write-Log "LOG mentve: $LogFile" "Green"

Write-Host ""
Write-Host "Nyomj Enter-t a kilepeshez..." -ForegroundColor DarkGray
Read-Host | Out-Null
