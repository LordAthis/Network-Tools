# ============================================================
#  Build-OuiList.ps1
#
#  Cel: a macouilist.json (datas mappa) NEM lehet kezzel, fejbol
#  osszeirt teljes OUI adatbazis - ez megbizhatatlan lenne.
#  Ez a script letolti a HIVATALOS IEEE OUI listat (a gepen,
#  ahol ezt futtatod, kell hozza normal internet-eleres), es
#  kiszuri belole azokat a gyartokat, akik erdekesek lehetnek
#  (miner, halozati eszkoz, TV, IoT, kamera, stb.), majd
#  osszefuzi a mar meglevo macouilist.json tartalmaval.
#
#  Hasznalat:
#      .\Build-OuiList.ps1
#          -> letolti az IEEE listat, leszuri, es frissiti a
#             datas\macouilist.json fajlt (a mar meglevo,
#             kezzel ellenorzott bejegyzeseket megtartja).
#
#      .\Build-OuiList.ps1 -Keywords "sonos","denon","yamaha"
#          -> sajat kulcsszavakkal egesziti ki a keresest a
#             beepitett lista mellett.
#
#      .\Build-OuiList.ps1 -SourceFile "C:\letoltve\oui.csv"
#          -> ha mar van helyi masolatod az IEEE CSV-rol
#             (pl. mert a tuzfal blokkolja a kozvetlen letoltest),
#             ebbol dolgozik, nem probal letolteni semmit.
#
#  MEGJEGYZES: a script NEM talal ki OUI-kat - csak azt irja be,
#  amit a hivatalos listaban ENNEK A GYARTONAK A NEVEHEZ talal.
#  Ha egy gyarto nincs a talalatok kozott, az azt jelenti, hogy
#  vagy mas nev alatt van bejegyezve, vagy nincs sajat OUI blokkja
#  (pl. egy olcsobb ASIC miner gyarto egy masik cegtol veszi a
#  halozati modult, es AZ O OUI-jat hasznalja).
# ============================================================

param(
    [string]$MacOuiListPath = "$PSScriptRoot\datas\macouilist.json",
    [string[]]$Keywords = @(),
    [string]$SourceFile
)

$IeeeUrl = "https://standards-oui.ieee.org/oui/oui.csv"
$FallbackUrl = "https://www.wireshark.org/download/automated/data/manuf"

# Gyarto-nev kulcsszo -> kategoria terkep. Bovitheto: csak vegy fel uj sort.
$VendorCategoryMap = [ordered]@{
    # --- ASIC miner gyartok ---
    "Bitmain"                  = "miner"
    "MicroBT"                  = "miner"
    "Canaan"                   = "miner"
    "Innosilicon"               = "miner"
    "Ebang"                    = "miner"
    "Goldshell"                = "miner"
    "IceRiver"                 = "miner"
    "iPollo"                   = "miner"
    "StrongU"                  = "miner"
    "Auradine"                 = "miner"
    "iBeLink"                  = "miner"
    # --- Halozati / router / switch gyartok ---
    "Ubiquiti"                 = "network"
    "MikroTik"                 = "network"
    "MikroTikls"               = "network"
    "TP-LINK"                  = "network"
    "TP-Link"                  = "network"
    "Netgear"                  = "network"
    "D-Link"                   = "network"
    "Cisco"                    = "network"
    "Huawei"                   = "network"
    "Zyxel"                    = "network"
    "ASUSTek"                  = "network"
    "Aruba"                    = "network"
    "Juniper"                  = "network"
    "Tenda"                    = "network"
    "H3C"                      = "network"
    # --- Kamera / biztonsagtechnika ---
    "Hikvision"                = "camera"
    "Dahua"                    = "camera"
    "Axis Communications"      = "camera"
    "Reolink"                  = "camera"
    "Foscam"                   = "camera"
    # --- TV / media ---
    "Samsung Electronics"      = "tv"
    "LG Electronics"           = "tv"
    "Sony"                     = "tv"
    "Philips"                  = "tv"
    "Hisense"                  = "tv"
    "TCL"                      = "tv"
    "Vizio"                    = "tv"
    "Roku"                     = "tv"
    # --- Telefon / szamitogep ---
    "Apple"                    = "phone"
    "Samsung Electro"          = "phone"
    "Xiaomi"                   = "phone"
    "Huawei Device"            = "phone"
    "OnePlus"                  = "phone"
    "Dell"                     = "computer"
    "Hewlett Packard"          = "computer"
    "HP Inc"                   = "computer"
    "Lenovo"                   = "computer"
    "Intel Corporate"          = "computer"
    "Microsoft"                = "computer"
    "Raspberry Pi"             = "computer"
    # --- IoT / okoseszkoz ---
    "Espressif"                = "iot"
    "Sonos"                    = "iot"
    "Amazon Technologies"      = "iot"
    "Google"                   = "iot"
    "Nest Labs"                = "iot"
    "Philips Lighting"         = "iot"
    "Signify"                  = "iot"
    "Tuya"                     = "iot"
    "Shelly"                   = "iot"
    "Sonoff"                   = "iot"
    "Itead"                    = "iot"
    # --- Gaming ---
    "Sony Interactive"         = "gaming"
    "Nintendo"                 = "gaming"
    "Microsoft Xbox"           = "gaming"
    # --- Nyomtato ---
    "Canon"                    = "printer"
    "Epson"                    = "printer"
    "Brother"                  = "printer"
}

foreach ($kw in $Keywords) {
    if (-not $VendorCategoryMap.Contains($kw)) {
        $VendorCategoryMap[$kw] = "egyeb"
    }
}

Write-Host "=== Build-OuiList.ps1 ===" -ForegroundColor Cyan
Write-Host "Kereset gyarto-kulcsszavak szama: $($VendorCategoryMap.Count)"

# --- 1) Forras CSV megszerzese ---
$csvPath = $null
if ($SourceFile) {
    if (Test-Path $SourceFile) {
        $csvPath = $SourceFile
        Write-Host "Helyi forras hasznalva: $SourceFile" -ForegroundColor Green
    } else {
        Write-Host "A megadott -SourceFile nem talalhato: $SourceFile" -ForegroundColor Red
        exit 1
    }
} else {
    $tempCsv = Join-Path $env:TEMP "oui_$(Get-Random).csv"
    Write-Host "Letoltes: $IeeeUrl ..." -ForegroundColor Yellow
    try {
        Invoke-WebRequest -Uri $IeeeUrl -OutFile $tempCsv -UseBasicParsing -TimeoutSec 60
        $csvPath = $tempCsv
        Write-Host "Sikeres letoltes (IEEE)." -ForegroundColor Green
    } catch {
        Write-Host "IEEE letoltes sikertelen ($($_.Exception.Message)), probalom a Wireshark tukrot..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri $FallbackUrl -OutFile $tempCsv -UseBasicParsing -TimeoutSec 60
            $csvPath = $tempCsv
            Write-Host "Sikeres letoltes (Wireshark manuf)." -ForegroundColor Green
        } catch {
            Write-Host "A letoltes mindket forrasbol sikertelen: $($_.Exception.Message)" -ForegroundColor Red
            Write-Host "Toltsd le kezzel innen: $IeeeUrl" -ForegroundColor Yellow
            Write-Host "majd futtasd ujra: .\Build-OuiList.ps1 -SourceFile <letoltott_fajl_utvonala>" -ForegroundColor Yellow
            exit 1
        }
    }
}

# --- 2) CSV feldolgozasa ---
# Az IEEE oui.csv oszlopai: Registry,Assignment,Organization Name,Organization Address
# A Wireshark 'manuf' fajl mas formatumu (tab-elvalasztott: OUI<TAB>rovid-nev<TAB>teljes-nev),
# ezert ket eltero feldolgozasi agra van szukseg - a fajl tartalma alapjan donti el a script.
$rawLines = Get-Content -Path $csvPath -ErrorAction Stop
$isCsvFormat = $rawLines[0] -match '^Registry,Assignment'

$matches = New-Object System.Collections.Generic.List[object]

if ($isCsvFormat) {
    Write-Host "Formatum: IEEE CSV" -ForegroundColor Cyan
    $csvData = $rawLines | ConvertFrom-Csv
    foreach ($row in $csvData) {
        $orgName = $row.'Organization Name'
        if ([string]::IsNullOrWhiteSpace($orgName)) { continue }
        foreach ($kw in $VendorCategoryMap.Keys) {
            if ($orgName -match [regex]::Escape($kw)) {
                $oui = ($row.Assignment -replace '[^0-9A-Fa-f]', '').ToUpper()
                if ($oui.Length -eq 6) {
                    $matches.Add([PSCustomObject]@{
                        oui        = $oui
                        vendor     = $orgName.Trim()
                        category   = $VendorCategoryMap[$kw]
                        note       = "Build-OuiList.ps1 altal talalva ('$kw' kulcsszora)"
                        confidence = "ieee_auto_$(Get-Date -Format 'yyyy-MM-dd')"
                    })
                }
                break
            }
        }
    }
} else {
    Write-Host "Formatum: Wireshark manuf (tab-elvalasztott)" -ForegroundColor Cyan
    foreach ($line in $rawLines) {
        if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line -split "`t"
        if ($parts.Count -lt 2) { continue }
        $oui = ($parts[0] -replace '[^0-9A-Fa-f]', '').ToUpper()
        if ($oui.Length -lt 6) { continue }
        $oui = $oui.Substring(0, 6)
        $fullName = if ($parts.Count -ge 3) { $parts[2] } else { $parts[1] }
        foreach ($kw in $VendorCategoryMap.Keys) {
            if ($fullName -match [regex]::Escape($kw)) {
                $matches.Add([PSCustomObject]@{
                    oui        = $oui
                    vendor     = $fullName.Trim()
                    category   = $VendorCategoryMap[$kw]
                    note       = "Build-OuiList.ps1 altal talalva ('$kw' kulcsszora)"
                    confidence = "wireshark_manuf_auto_$(Get-Date -Format 'yyyy-MM-dd')"
                })
                break
            }
        }
    }
}

Write-Host "Talalt egyezes: $($matches.Count)" -ForegroundColor Green

if ($matches.Count -eq 0) {
    Write-Host "Nincs uj talalat - a meglevo macouilist.json valtozatlan marad." -ForegroundColor Yellow
    exit 0
}

# --- 3) Osszefuzes a meglevo macouilist.json-nal (OUI szerint dedupe, a kezi/verifikalt bejegyzesek elsobbseget elveznek) ---
$existing = $null
if (Test-Path $MacOuiListPath) {
    try {
        $existing = Get-Content -Path $MacOuiListPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Write-Host "A meglevo macouilist.json nem olvashato ($($_.Exception.Message)) - uj fajl keszul." -ForegroundColor Yellow
    }
}

$existingEntries = @{}
if ($existing -and $existing.entries) {
    foreach ($e in $existing.entries) {
        $existingEntries[$e.oui] = $e
    }
}

$addedCount = 0
$skippedCount = 0
foreach ($m in $matches) {
    if ($existingEntries.ContainsKey($m.oui)) {
        $skippedCount++
        continue
    }
    $existingEntries[$m.oui] = $m
    $addedCount++
}

$finalEntries = $existingEntries.Values | Sort-Object category, vendor, oui

$output = [ordered]@{
    version         = "1.1"
    updated         = (Get-Date -Format "yyyy-MM-dd")
    description     = if ($existing.description) { $existing.description } else { "MAC-cim OUI (gyarto-elotag) -> gyarto/kategoria lista." }
    how_it_works    = if ($existing.how_it_works) { $existing.how_it_works } else { "" }
    confidence_note = if ($existing.confidence_note) { $existing.confidence_note } else { "" }
    categories      = @("miner","computer","network","tv","phone","iot","camera","gaming","printer","egyeb")
    entries         = $finalEntries
}

$outDir = Split-Path $MacOuiListPath -Parent
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}
$output | ConvertTo-Json -Depth 6 | Out-File -FilePath $MacOuiListPath -Encoding UTF8

Write-Host ""
Write-Host "Kesz." -ForegroundColor Green
Write-Host "Uj bejegyzes hozzaadva: $addedCount"
Write-Host "Mar letezett (kihagyva, a regi maradt): $skippedCount"
Write-Host "Osszes bejegyzes a fajlban: $($finalEntries.Count)"
Write-Host "Mentve: $MacOuiListPath"

if (-not $SourceFile -and (Test-Path $csvPath)) {
    Remove-Item $csvPath -Force -ErrorAction SilentlyContinue
}
