# ============================================================
#  TrafficSnapshot.ps1
#
#  FONTOS, OSZINTE KORLATOZAS, MIELOTT HASZNALNAD:
#  Ez a script csak azt a forgalmat latja, ami EZEN a gepen
#  (a halozati kartyajan) atmegy. NEM latja, hogy pl. a miner
#  es a router kozott mennyi forgalom zajlik, ha az a forgalom
#  sose erinti ezt a gepet - ahhoz vagy:
#    a) a router sajat admin-feluleten kell megnezni a
#       forgalom-statisztikat / csatlakoztatott eszkozok listajat
#       (ez altalaban a legegyszerubb es legpontosabb), VAGY
#    b) egy MANAGED switch-en port-tukrozest (port mirroring /
#       SPAN port) kell beallitani, es EZT a gepet arra a
#       tukrozott portra kotni, VAGY
#    c) a routeren NetFlow/sFlow exportot kell bekapcsolni (ha
#       a router tamogatja), es azt egy gyujtoszerverrel elemezni.
#
#  Amit EZ a script ad: a sajat gep halozati forgalmat bontja le
#  cel-IP szerint egy rovid ideig tarto (alapbol 20 masodperces)
#  mintavetellel, a Windows beepitett pktmon eszkozevel (NINCS
#  installalas, minden tamogatott Windows 10/11-en mar rajta van).
#  Hasznos, ha PONT ERROL a geprol gyanus/nagy forgalmat latsz,
#  vagy ha ezt a gepet a router SPAN/mirror portjara kotod -
#  akkor mar a TELJES LAN forgalmat latja ez a script is.
#
#  Hasznalat:
#      .\TrafficSnapshot.ps1
#      .\TrafficSnapshot.ps1 -DurationSec 60
#
#  Admin jogosultsag KELL hozza (a pktmon rendszerszintu capture-t
#  inditasahoz) - ha nincs, a script jelzi es leall.
# ============================================================

param(
    [int]$DurationSec = 20,
    [string]$LogDir = "$PSScriptRoot\LOG"
)

function Test-Admin {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

Write-Host "=== TrafficSnapshot.ps1 - helyi forgalom pillanatkep (pktmon) ===" -ForegroundColor Cyan
Write-Host "FONTOS: ez CSAK az ezen a gepen atmeno forgalmat latja - lasd a fejlec megjegyzeset a fajlban." -ForegroundColor Yellow
Write-Host ""

if (-not (Test-Admin)) {
    Write-Host "Adminisztratori jog szukseges a pktmon inditasahoz. Inditsd ujra a scriptet adminisztratorkent." -ForegroundColor Red
    exit 1
}

if (-not (Get-Command pktmon -ErrorAction SilentlyContinue)) {
    Write-Host "A pktmon parancs nem talalhato ezen a rendszeren (regebbi Windows verzio lehet)." -ForegroundColor Red
    Write-Host "Alternativa: 'netstat -ano' idobeli osszehasonlitasa, vagy Wireshark/Npcap telepitese." -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$EtlPath = Join-Path $env:TEMP "traffic_$Timestamp.etl"
$TxtOut = Join-Path $env:TEMP "traffic_$Timestamp.txt"
$OutFile = Join-Path $LogDir "TrafficSnapshot_$Timestamp.txt"

Write-Host "Rogzites inditasa $DurationSec masodpercre..." -ForegroundColor Yellow
try {
    pktmon stop 2>&1 | Out-Null   # biztos, ami biztos, ha korabban futva maradt volna
    pktmon start --etw -f $EtlPath 2>&1 | Out-Null
    Start-Sleep -Seconds $DurationSec
    pktmon stop 2>&1 | Out-Null
} catch {
    Write-Host "pktmon hiba: $_" -ForegroundColor Red
    exit 1
}

Write-Host "Rogzites kesz, feldolgozas..." -ForegroundColor Yellow
try {
    pktmon format $EtlPath -o $TxtOut --dump 2>&1 | Out-Null
} catch {
    Write-Host "pktmon format hiba: $_" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $TxtOut)) {
    Write-Host "Nem keszult el a pktmon szoveges kimenet." -ForegroundColor Red
    exit 1
}

# --- A pktmon dump soraibol IP-parok (forras -> cel) kiszedese, egyszeru szamlalassal ---
# Ez egy egyszeru, "legjobb probalkozas" feldolgozas - a pktmon szoveges formatuma
# verziofuggo lehet, ezert altalanos IP-par mintaillesztest hasznalunk teljes csomag-
# elemzes helyett (ami kulon csomagot igenyelne).
$ipPairRegex = '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}).{1,40}?(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})'
$pairCounts = @{}
$rawLines = Get-Content -Path $TxtOut -ErrorAction SilentlyContinue
foreach ($line in $rawLines) {
    if ($line -match $ipPairRegex) {
        $pair = "$($Matches[1]) -> $($Matches[2])"
        if ($pairCounts.ContainsKey($pair)) { $pairCounts[$pair]++ } else { $pairCounts[$pair] = 1 }
    }
}

$Log = [System.Collections.Generic.List[string]]::new()
$Log.Add("=== TRAFFIC SNAPSHOT (pktmon, $DurationSec mp) ===")
$Log.Add("Ido: $(Get-Date)")
$Log.Add("FONTOS: csak az EZEN a gepen atmeno forgalom latszik (lasd fejlec magyarazat).")
$Log.Add("")
$Log.Add("Legaktivabb IP-parok (csomagszam szerint, TOP 40):")
$Log.Add("")

$sorted = $pairCounts.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 40
if ($sorted.Count -eq 0) {
    $Log.Add("Nem sikerult IP-parokat kinyerni a pktmon kimenetbol (lehet, hogy nem volt forgalom, vagy a formatum eltero).")
} else {
    foreach ($item in $sorted) {
        $Log.Add("  $($item.Value) csomag | $($item.Key)")
    }
}

$Log.Add("")
$Log.Add("Ha egy IP (pl. egy gyanus miner cime) itt egyaltalan nem szerepel, az azt jelentheti,")
$Log.Add("hogy annak a forgalma nem ezen a gepen megy at - a router sajat statisztikaja vagy")
$Log.Add("egy switch port-tukrozes ad csak teljes kepet a teljes LAN-rol.")

$Log | Out-File -FilePath $OutFile -Encoding UTF8
Remove-Item $EtlPath, $TxtOut -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Kesz! Log: $OutFile" -ForegroundColor Green
$openAnswer = Read-Host "Megnyissam a logot bongeszoben, olvashato formaban? (I/n - alapertelmezett: Igen)"
if ([string]::IsNullOrWhiteSpace($openAnswer) -or $openAnswer -match "^(i|ig|igen|y|yes)$") {
    $logToIndex = Join-Path $PSScriptRoot "LOGtoINDEX.ps1"
    if (Test-Path $logToIndex) {
        try { & $logToIndex -LogFile $OutFile -LogDir $LogDir } catch { Write-Host "LOGtoINDEX hiba: $_" -ForegroundColor Yellow }
    }
}
