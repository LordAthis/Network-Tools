# ============================================================
#  Miner-Search.ps1  -  Miner kereso / elemzo vaz
#  Hasznalja: datas/minerpoollist.json, miner_pools.json,
#             miner_manufacturers.json + LOG mappa legfrissebb fajljai
# ============================================================

$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "Miner-Search"

# --- Utak ---
$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = Get-Location }
$DataDir    = Join-Path $ScriptRoot "datas"
$LogDir     = Join-Path $ScriptRoot "LOG"

# JSON fajlok
$PoolListSimple = Join-Path $DataDir "minerpoollist.json"       # regi kompatibilis (string tomb)
$PoolListFull   = Join-Path $DataDir "miner_pools.json"
$ManufList      = Join-Path $DataDir "miner_manufacturers.json"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  MINER-SEARCH v0.1 (vaz)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# --- 1. JSON-ok betoltese ---
function Load-JsonFile {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path $Path)) {
        Write-Host "  [!] HIANYZIK: $Name -> $Path" -ForegroundColor Red
        return $null
    }
    try {
        $raw = Get-Content -Path $Path -Raw -Encoding UTF8
        return $raw | ConvertFrom-Json
    } catch {
        Write-Host "  [!] JSON hiba ($Name): $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

Write-Host "JSON adatok betoltese..." -ForegroundColor Yellow
$simpleKeywords = Load-JsonFile -Path $PoolListSimple -Name "minerpoollist.json"
$poolsFull      = Load-JsonFile -Path $PoolListFull   -Name "miner_pools.json"
$manufacturers  = Load-JsonFile -Path $ManufList      -Name "miner_manufacturers.json"

if ($simpleKeywords) {
    Write-Host "  minerpoollist.json : $($simpleKeywords.Count) kulcsszo" -ForegroundColor Green
}
if ($poolsFull) {
    Write-Host "  miner_pools.json   : betoltve (ver $($poolsFull.version))" -ForegroundColor Green
}
if ($manufacturers) {
    Write-Host "  miner_manufacturers.json : betoltve (ver $($manufacturers.version))" -ForegroundColor Green
    if ($manufacturers.mac_oui_note) {
        Write-Host ""
        Write-Host "  MAC OUI jegyzet:" -ForegroundColor DarkCyan
        Write-Host "  $($manufacturers.mac_oui_note)" -ForegroundColor DarkGray
    }
}
Write-Host ""

# --- 2. LOG mappa - legfrissebb fajlok keresese ---
function Get-LatestLogFiles {
    param([string]$Dir)

    if (-not (Test-Path $Dir)) {
        Write-Host "  [!] LOG mappa nem talalhato: $Dir" -ForegroundColor Red
        return @()
    }

    $today = Get-Date -Format "yyyyMMdd"
    $todayAlt = Get-Date -Format "yyyy-MM-dd"

    # Minden .txt a LOG-ban
    $allLogs = Get-ChildItem -Path $Dir -Filter "*.txt" -File -ErrorAction SilentlyContinue |
               Sort-Object LastWriteTime -Descending

    if (-not $allLogs) {
        Write-Host "  [!] Nincs .txt fajl a LOG mappaban." -ForegroundColor Red
        return @()
    }

    # Mai fajlok (nevben vagy LastWriteTime alapjan)
    $todayLogs = $allLogs | Where-Object {
        $_.Name -match $today -or
        $_.Name -match $todayAlt -or
        $_.LastWriteTime.Date -eq (Get-Date).Date
    }

    if ($todayLogs) {
        Write-Host "  Mai LOG fajlok talalva: $($todayLogs.Count) db" -ForegroundColor Green
        # Legutolso (legfrissebb) mai
        $latestToday = $todayLogs | Select-Object -First 1
        Write-Host "  Legfrissebb mai: $($latestToday.Name)" -ForegroundColor Green
        return @($latestToday)
    }

    # Nincs mai -> kerdezzunk ra
    Write-Host "  Nincs mai (mai datumu) LOG fajl." -ForegroundColor Yellow
    Write-Host "  Legutobbi elerheto fajlok:" -ForegroundColor Yellow
    $allLogs | Select-Object -First 5 | ForEach-Object {
        Write-Host ("    {0}  ({1})" -f $_.Name, $_.LastWriteTime.ToString("yyyy-MM-dd HH:mm")) -ForegroundColor DarkGray
    }
    Write-Host ""
    $answer = Read-Host "  Feldolgozzak a legutolso (nem mai) LOG fajlt is? (I/N)"
    if ($answer -match '^[IiYy]') {
        $latest = $allLogs | Select-Object -First 1
        Write-Host "  Valasztott: $($latest.Name)" -ForegroundColor Cyan
        return @($latest)
    }

    Write-Host "  Kihagyva a regi LOG feldolgozasa." -ForegroundColor Yellow
    return @()
}

Write-Host "LOG mappa ellenorzese: $LogDir" -ForegroundColor Yellow
$logFiles = Get-LatestLogFiles -Dir $LogDir

# --- 3. Egyszeru elemzes vaz (kesobb bovitheto) ---
function Analyze-LogForMiners {
    param([System.IO.FileInfo]$LogFile, $Keywords, $Ports)

    Write-Host ""
    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
    Write-Host "  Elemzes: $($LogFile.Name)" -ForegroundColor Cyan
    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan

    $content = Get-Content -Path $LogFile.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $content) {
        Write-Host "  Ures vagy olvashatatlan fajl." -ForegroundColor Red
        return
    }

    # Kulcsszavak keresese
    $foundKeywords = @()
    if ($Keywords) {
        foreach ($kw in $Keywords) {
            if ($content -match [regex]::Escape($kw)) {
                $foundKeywords += $kw
            }
        }
    }

    if ($foundKeywords.Count -gt 0) {
        Write-Host "  Talalt pool/miner kulcsszavak:" -ForegroundColor Green
        $foundKeywords | Sort-Object -Unique | ForEach-Object { Write-Host "    - $_" -ForegroundColor Green }
    } else {
        Write-Host "  Nem talalhato ismert pool kulcsszo a LOG-ban." -ForegroundColor DarkGray
    }

    # Gyakori stratum portok emlitese a LOG-ban
    $portHits = @()
    $checkPorts = @(3333, 443, 4028, 4433, 22, 8080, 9999, 3357, 1010, 8888)
    foreach ($p in $checkPorts) {
        if ($content -match ":$p\b" -or $content -match "\b$p\b") {
            $portHits += $p
        }
    }
    if ($portHits.Count -gt 0) {
        Write-Host "  Emlegetett portok (gyanus/szamat): $($portHits -join ', ')" -ForegroundColor Yellow
    }

    # TODO: kesobb
    # - ARP/MAC OUI egyeztetes a manufacturers.mac_oui listaval
    # - 4028-as API probe elo hostokra
    # - netstat ESTABLISHED -> pool domain egyeztetes
    # - SSH banner check ha 22 nyitva
}

if ($logFiles.Count -gt 0) {
    foreach ($lf in $logFiles) {
        Analyze-LogForMiners -LogFile $lf -Keywords $simpleKeywords -Ports $null
    }
} else {
    Write-Host ""
    Write-Host "  Nincs feldolgozando LOG fajl. A script kilep a LOG reszbol." -ForegroundColor Yellow
}

# --- 4. Helyi elokeszites a hallaati scanhez (vaz) ---
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Kovetkezo lepesek (meg nem implementalt - vaz):" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  1. Elo hostok listaja (ping / ARP / korabbi NetworkDiag LOG)"
Write-Host "  2. Prioritas portscan: 22, 80, 4028, 4433, 8080, 50051..."
Write-Host "  3. 4028-as JSON probe (version/summary/pools)"
Write-Host "  4. MAC OUI egyeztetes"
Write-Host "  5. Kimenő kapcsolatok egyeztetese a pool keywords-szel"
Write-Host "  6. Eredmeny mentese a LOG mappaba"
Write-Host ""
Write-Host "Kesz (vaz futas vege)." -ForegroundColor Green
Write-Host "Nyomj Enter-t a kilepeshez..."
Read-Host | Out-Null
