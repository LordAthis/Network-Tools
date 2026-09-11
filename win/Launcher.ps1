#Requires -Version 3.0
# ============================================================
#  Launcher.ps1  -  Halozati eszkozok indito menu
#  Bat es PS1 scriptek inditasa ugyanabban az ablakban
#  Futtatasi hazirend: Process szinten Bypass (semmit nem tilt)
# ============================================================

$Host.UI.RawUI.WindowTitle = "Halozati Eszkozok - Launcher"

# Konzol UTF-8 kimenet (a fajl mar UTF-8 BOM-mal van mentve, ez itt csak a
# konzol-ablak sajat kodlapjat allitja at, hogy az ekezetes szoveg - es a
# lentebbi DiagMailer hibauzenetek - is helyesen jelenjenek meg).
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    chcp 65001 > $null
} catch { }

# Futtatasi szint emelese: semmilyen script futtatast ne tiltson ebben a folyamatban
try {
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force -ErrorAction Stop
} catch {
    # Ha nem sikerul, a meghivott PS1-eknel ugyis Bypass-szal inditunk
}

Clear-Host

$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = Get-Location }

# Elerheto scriptek
# Kesobb csak ezt a tombot kell boviteni / modositani
$Scripts = @(
    @{
        Number      = "1"
        Name        = "Gyors eszkozlista"
        File        = "gyors_eszkozlista.bat"
        Type        = "bat"
        Description = "Gyors ARP alapu eszkozlista + alap IP config"
    },
    @{
        Number      = "2"
        Name        = "Gyors eszkozlista (PS1)"
        File        = "gyors_eszkozlista.ps1"
        Type        = "ps1"
        Description = "PowerShell valtozat (meg kidolgozas alatt)"
    },
    @{
        Number      = "3"
        Name        = "Halozat felderites"
        File        = "halozat_felderites.bat"
        Type        = "bat"
        Description = "ARP + ipconfig + netstat + teljes ping sweep"
    },
    @{
        Number      = "4"
        Name        = "Halozat felderites (PS1)"
        File        = "halozat_felderites.ps1"
        Type        = "ps1"
        Description = "PowerShell valtozat (meg kidolgozas alatt)"
    },
    @{
        Number      = "5"
        Name        = "Halozati biztonsag"
        File        = "halozati_biztonsag.bat"
        Type        = "bat"
        Description = "Biztonsagi ellenorzesek (meg kidolgozas alatt)"
    },
    @{
        Number      = "6"
        Name        = "Halozati biztonsag (PS1)"
        File        = "halozati_biztonsag.ps1"
        Type        = "ps1"
        Description = "PowerShell valtozat (meg kidolgozas alatt)"
    },
    @{
        Number      = "7"
        Name        = "Reszletes halozati jelentes"
        File        = "reszletes_halozati_jelentes.bat"
        Type        = "bat"
        Description = "Reszletes jelentes fajlba mentessel"
    },
    @{
        Number      = "8"
        Name        = "Reszletes halozati jelentes (PS1)"
        File        = "reszletes_halozati_jelentes.ps1"
        Type        = "ps1"
        Description = "PowerShell valtozat (meg kidolgozas alatt)"
    },
    @{
        Number      = "9"
        Name        = "NetworkDiag MAX v2"
        File        = "NetworkDiag_MAX_v2.ps1"
        Type        = "ps1"
        Description = "Legalaposabb diagnostika (ASIC miner, nema eszkoz, pool, stb.)"
    },
    @{
        Number      = "10"
        Name        = "MinerSearch"
        File        = "MinerSearch.ps1"
        Type        = "ps1"
        Description = "Miner felfedezes LOG-okbol (kulcsszo, port, IP lista)"
    },
    @{
        Number      = "11"
        Name        = "MinerStatus"
        File        = "MinerStatus.ps1"
        Type        = "ps1"
        Description = "Aktiv miner lekerdezes (API / web / SSH probe)"
    },
    @{
        Number      = "12"
        Name        = "LOGtoINDEX (osszes IP dashboard)"
        File        = "LOGtoINDEX.ps1"
        Type        = "ps1"
        Description = "Az osszes LOG fajlbol kattinthato IP-tablazatot general bongeszoben"
    },
    @{
        Number      = "13"
        Name        = "TrafficSnapshot"
        File        = "TrafficSnapshot.ps1"
        Type        = "ps1"
        Description = "Helyi gepi forgalom pillanatkep (pktmon) - csak ezen a gepen atmeno forgalom"
    },
    @{
        Number      = "14"
        Name        = "IPv4/IPv6 + CGNAT teszt"
        File        = "IPv5_IPv6_CGNAT_Test.ps1"
        Type        = "ps1"
        Description = "IPv6 elerhetoseg + publikus IP / CGNAT vizsgalat (Start-Transcript logolassal)"
    }
)

function Show-Menu {
    Clear-Host
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "           HALOZATI ESZKOZOK - LAUNCHER" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Valassz egy scriptet:" -ForegroundColor Yellow
    Write-Host ""

    foreach ($script in $Scripts) {
        $fullPath = Join-Path $ScriptRoot $script.File
        $exists   = Test-Path $fullPath

        if ($exists) {
            $color = if ($script.Type -eq "ps1") { "Magenta" } else { "Green" }
            Write-Host "  [$($script.Number)]  $($script.Name)" -ForegroundColor $color
        } else {
            Write-Host "  [$($script.Number)]  $($script.Name)  (fajl nem talalhato)" -ForegroundColor DarkGray
        }
        Write-Host "       $($script.Description)" -ForegroundColor DarkGray
        Write-Host ""
    }

    Write-Host "  [E]  LOG-ok elkuldese a beallitott karbantartoi emailre (DiagMailer)" -ForegroundColor Cyan
    Write-Host "       Elso hasznalatkor automatikusan letoltodik GitHub-rol, ha meg nincs meg" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "  [0]  Kilepes" -ForegroundColor Red
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
}

function Start-Script {
    param([hashtable]$ScriptInfo)

    $fullPath = Join-Path $ScriptRoot $ScriptInfo.File

    if (-not (Test-Path $fullPath)) {
        Write-Host ""
        Write-Host "  HIBA: A fajl nem talalhato:" -ForegroundColor Red
        Write-Host "  $fullPath" -ForegroundColor Red
        Write-Host ""
        Write-Host "  Nyomj Enter-t a folytatashoz..." -ForegroundColor Yellow
        Read-Host | Out-Null
        return
    }

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  Inditas: $($ScriptInfo.Name)" -ForegroundColor Cyan
    Write-Host "  Fajl:    $($ScriptInfo.File)" -ForegroundColor DarkGray
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""

    Push-Location $ScriptRoot

    try {
        if ($ScriptInfo.Type -eq "bat") {
            # Ugyanabban az ablakban futtatjuk a bat-ot
            & cmd.exe /c "`"$fullPath`""
        }
        else {
            # PowerShell: Bypass atadasa a meghivott scriptnek is (semmit ne tiltson)
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "`"$fullPath`""
        }
    }
    catch {
        Write-Host ""
        Write-Host "  HIBA a script futtatasa kozben:" -ForegroundColor Red
        Write-Host "  $($_.Exception.Message)" -ForegroundColor Red
    }
    finally {
        Pop-Location
    }

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  A script befejezodott." -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Nyomj Enter-t a menube visszatereshez..." -ForegroundColor Yellow
    Read-Host | Out-Null
}

# ============================================================
#  DIAGMAILER - LOG-ok elkuldese emailben
#  Kulon repo: https://github.com/LordAthis/DiagMailer
#  A DiagMailer sajat config.json-ja alapertelmezetten "..\LOG"-ot
#  var logFolder-kent, ezert a DiagMailer mappanak KOZVETLEN
#  testvermappajanak kell lennie a LOG mappaval - vagyis ide:
#  $ScriptRoot\DiagMailer\ (nem a repo gyokerebe, nem egy szinttel feljebb).
# ============================================================
$DiagMailerRepoUrl = "https://github.com/LordAthis/DiagMailer.git"
$DiagMailerZipUrl   = "https://github.com/LordAthis/DiagMailer/archive/refs/heads/main.zip"
$DiagMailerDir      = Join-Path $ScriptRoot "DiagMailer"
$DiagMailerLauncher = Join-Path $DiagMailerDir "Launcher.ps1"

function Ensure-DiagMailer {
    if (Test-Path $DiagMailerLauncher) {
        return $true
    }

    Write-Host ""
    Write-Host "  [DiagMailer] Nem talalhato meg - ez az elso hasznalat." -ForegroundColor Yellow
    Write-Host "  [DiagMailer] Vart hely: $DiagMailerDir" -ForegroundColor DarkGray
    Write-Host "  [DiagMailer] Forras: $DiagMailerRepoUrl" -ForegroundColor DarkGray
    Write-Host ""

    # 1. probalkozas: git clone (ha van git a gepen, ez a leggyorsabb es leg-frissitheto)
    $gitCmd = Get-Command git -ErrorAction SilentlyContinue
    if ($gitCmd) {
        Write-Host "  [DiagMailer] git clone inditasa..." -ForegroundColor Cyan
        try {
            git clone $DiagMailerRepoUrl $DiagMailerDir 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0 -and (Test-Path $DiagMailerLauncher)) {
                Write-Host "  [DiagMailer] Sikeres letoltes (git clone)." -ForegroundColor Green
                return $true
            }
        } catch { }
        Write-Host "  [DiagMailer] git clone sikertelen, probalom ZIP-pel..." -ForegroundColor Yellow
    }

    # 2. probalkozas: GitHub ZIP letoltese + kicsomagolas (nincs szukseg gitre)
    try {
        $tempZip = Join-Path $env:TEMP "DiagMailer_$(Get-Random).zip"
        $tempExtract = Join-Path $env:TEMP "DiagMailer_extract_$(Get-Random)"
        Write-Host "  [DiagMailer] Letoltes: $DiagMailerZipUrl" -ForegroundColor Cyan
        Invoke-WebRequest -Uri $DiagMailerZipUrl -OutFile $tempZip -UseBasicParsing -TimeoutSec 60

        if (Get-Command Expand-Archive -ErrorAction SilentlyContinue) {
            Expand-Archive -Path $tempZip -DestinationPath $tempExtract -Force
        } else {
            # PS3/4 - nincs Expand-Archive, .NET ZIP-el csomagoljuk ki
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [System.IO.Compression.ZipFile]::ExtractToDirectory($tempZip, $tempExtract)
        }

        # A GitHub zip egy "DiagMailer-main" nevu almappaba csomagol ki - ennek tartalmat
        # kell athelyezni a vegleges $DiagMailerDir helyre.
        $extractedRoot = Get-ChildItem -Path $tempExtract -Directory | Select-Object -First 1
        if ($extractedRoot) {
            if (Test-Path $DiagMailerDir) { Remove-Item $DiagMailerDir -Recurse -Force -ErrorAction SilentlyContinue }
            Move-Item -Path $extractedRoot.FullName -Destination $DiagMailerDir -Force
        }

        Remove-Item $tempZip -Force -ErrorAction SilentlyContinue
        Remove-Item $tempExtract -Recurse -Force -ErrorAction SilentlyContinue

        if (Test-Path $DiagMailerLauncher) {
            Write-Host "  [DiagMailer] Sikeres letoltes (ZIP)." -ForegroundColor Green
            return $true
        }
    } catch {
        # A nyers .NET kivetel-szoveg (pl. "A tavoli nev feloldasa nem sikerult",
        # tanusitvany-hiba, timeout) tobbnyire ertelmezhetetlenul jelenik meg a
        # felhasznalonak - ezert itt egy roviden ertelmezett, magyar okot irunk
        # ki, a teljes technikai reszletet pedig kulon sorba tesszuk.
        $okStr = "ismeretlen ok"
        if ($_.Exception.Message -match "timed out|timeout") { $okStr = "tulleptuk az idokeretet (lassú vagy nincs internet-kapcsolat)" }
        elseif ($_.Exception.Message -match "SSL|certificate|trust") { $okStr = "biztonsagos (SSL) kapcsolat hiba - tuzfal/proxy/vallalati halozat blokkolhatja" }
        elseif ($_.Exception.Message -match "name resolution|resolve") { $okStr = "nincs internet-eleres vagy a DNS nem mukodik" }
        elseif ($_.Exception.Message -match "403|404") { $okStr = "a GitHub nem talalta/engedte a fajlt (403/404)" }
        Write-Host "  [DiagMailer] A letoltes sikertelen - valoszinu ok: $okStr" -ForegroundColor Red
        Write-Host "  [DiagMailer] Technikai reszlet: $($_.Exception.Message)" -ForegroundColor DarkGray
    }

    Write-Host "  [DiagMailer] A letoltes nem sikerult - ez NEM allitja meg a tobbi eszkozt," -ForegroundColor Yellow
    Write-Host "  [DiagMailer] csak a LOG-ok emailes kikuldese (E menupont) nem lesz elerheto." -ForegroundColor Yellow
    Write-Host "  [DiagMailer] Ha kesobb szeretned hasznalni, told le kezzel innen:" -ForegroundColor Yellow
    Write-Host "  $DiagMailerRepoUrl" -ForegroundColor Yellow
    Write-Host "  Cel mappa: $DiagMailerDir" -ForegroundColor Yellow
    return $false
}

function Start-DiagMailer {
    if (-not (Ensure-DiagMailer)) {
        Write-Host ""
        Write-Host "  Nyomj Enter-t a menube visszatereshez..." -ForegroundColor Yellow
        Read-Host | Out-Null
        return
    }

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  Inditas: DiagMailer (LOG-ok elkuldese)" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""

    Push-Location $DiagMailerDir
    try {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "`"$DiagMailerLauncher`""
    } catch {
        Write-Host ""
        Write-Host "  HIBA a DiagMailer futtatasa kozben:" -ForegroundColor Red
        Write-Host "  $($_.Exception.Message)" -ForegroundColor Red
    } finally {
        Pop-Location
    }

    Write-Host ""
    Write-Host "  Nyomj Enter-t a menube visszatereshez..." -ForegroundColor Yellow
    Read-Host | Out-Null
}

# Fo ciklus
Write-Host ""
Write-Host "  [DiagMailer] Inditaskori ellenorzes..." -ForegroundColor DarkGray
[void](Ensure-DiagMailer)
Start-Sleep -Milliseconds 600

do {
    Show-Menu
    $choice = Read-Host "  Valasztas"
    $choiceUpper = $choice.Trim().ToUpper()

    switch ($choiceUpper) {
        "0" {
            Write-Host ""
            Write-Host "  Kilepes..." -ForegroundColor Yellow
            Start-Sleep -Milliseconds 400
            exit
        }
        "E" {
            Start-DiagMailer
        }
        default {
            $selected = $Scripts | Where-Object { $_.Number -eq $choiceUpper }
            if ($selected) {
                Start-Script -ScriptInfo $selected
            } else {
                Write-Host ""
                Write-Host "  Ervenytelen valasztas!" -ForegroundColor Red
                Start-Sleep -Seconds 1
            }
        }
    }
} while ($true)
