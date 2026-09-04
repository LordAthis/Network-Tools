# ============================================================
#  Launcher.ps1  -  Hálózati eszközök indító menü
#  Bat és PS1 scriptek indítása ugyanabban az ablakban
#  Futtatási házirend: Process szinten Bypass (semmit nem tilt)
# ============================================================

$Host.UI.RawUI.WindowTitle = "Halozati Eszkozok - Launcher"

# Futtatasi szint emelese: semmilyen script futtatast ne tiltson ebben a folyamatban
try {
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force -ErrorAction Stop
} catch {
    # Ha nem sikerul, a meghivott PS1-eknel ugyis Bypass-szal inditunk
}

Clear-Host

$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = Get-Location }

# Elérhető scriptek
# Később csak ezt a tömböt kell bővíteni / módosítani
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

# Fő ciklus
do {
    Show-Menu
    $choice = Read-Host "  Valasztas"

    switch ($choice) {
        "0" {
            Write-Host ""
            Write-Host "  Kilepes..." -ForegroundColor Yellow
            Start-Sleep -Milliseconds 400
            exit
        }
        { $_ -match '^(1[01]|[1-9])$' } {
            # 1-9, 10, 11
            $selected = $Scripts | Where-Object { $_.Number -eq $choice }
            if ($selected) {
                Start-Script -ScriptInfo $selected
            }
        }
        default {
            Write-Host ""
            Write-Host "  Ervenytelen valasztas!" -ForegroundColor Red
            Start-Sleep -Seconds 1
        }
    }
} while ($true)
