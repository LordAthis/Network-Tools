# Network-Full-Test.ps1
# Teljes IPv4/IPv6 + CGNAT + adapter diagnosztika
# Vezetékes → mobil átállással

function Write-Title($text) {
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host " $text" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
}

function Get-ActiveAdapters {
    Get-NetAdapter | Where-Object { $_.Status -eq "Up" } | 
        Select-Object Name, InterfaceDescription, LinkSpeed, MacAddress
}

function Test-IPv6Status {
    Write-Host "`n--- IPv6 állapot ---" -ForegroundColor Yellow
    
    $global = Get-NetIPAddress -AddressFamily IPv6 -ErrorAction SilentlyContinue | 
        Where-Object { 
            $_.IPAddress -notlike "fe80*" -and 
            $_.IPAddress -notlike "::1" -and 
            $_.AddressState -eq "Preferred" 
        }

    if ($global) {
        Write-Host "✓ Van globális IPv6 cím:" -ForegroundColor Green
        $global | ForEach-Object {
            Write-Host ("  {0,-25} {1}/{2}" -f $_.InterfaceAlias, $_.IPAddress, $_.PrefixLength)
        }
    } else {
        Write-Host "✗ Nincs globális IPv6 cím" -ForegroundColor Red
    }

    # Gyors elérhetőség
    $targets = @("2001:4860:4860::8888", "2606:4700:4700::1111")
    foreach ($t in $targets) {
        $ok = Test-Connection -ComputerName $t -Count 1 -Quiet -ErrorAction SilentlyContinue
        if ($ok) { Write-Host "✓ $t elérhető" -ForegroundColor Green }
        else     { Write-Host "✗ $t nem elérhető" -ForegroundColor Red }
    }
}

function Test-CGNAT {
    Write-Host "`n--- CGNAT / Publikus IP vizsgálat ---" -ForegroundColor Yellow

    try {
        $publicIP = (Invoke-RestMethod -Uri "https://api.ipify.org" -TimeoutSec 8).Trim()
        Write-Host "Publikus IPv4: $publicIP" -ForegroundColor Green
    } catch {
        Write-Host "Nem sikerült lekérni a publikus IPv4-et" -ForegroundColor Red
        return
    }

    # CGNAT tipikus tartományok (RFC 6598)
    $cgnatRanges = @(
        @{ Start = [version]"100.64.0.0";  End = [version]"100.127.255.255" },  # 100.64.0.0/10
        @{ Start = [version]"10.0.0.0";    End = [version]"10.255.255.255" },
        @{ Start = [version]"172.16.0.0";  End = [version]"172.31.255.255" },
        @{ Start = [version]"192.168.0.0"; End = [version]"192.168.255.255" }
    )

    $ip = [version]$publicIP
    $isPrivateOrCGNAT = $false

    foreach ($range in $cgnatRanges) {
        if ($ip -ge $range.Start -and $ip -le $range.End) {
            $isPrivateOrCGNAT = $true
            break
        }
    }

    if ($isPrivateOrCGNAT) {
        Write-Host "⚠ A publikus IP privát / CGNAT tartományba esik → nagy eséllyel CGNAT van!" -ForegroundColor Red
    } else {
        Write-Host "A publikus IP nem esik a leggyakoribb CGNAT/privát tartományokba." -ForegroundColor Yellow
        Write-Host "  (Ez nem 100%-os bizonyosság, de jó jel.)" -ForegroundColor DarkYellow
    }

    # További jel: ha a helyi default gateway és a publikus IP "messze" van
    $gateway = (Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue | 
                Select-Object -First 1).NextHop
    if ($gateway) {
        Write-Host "Alapértelmezett gateway: $gateway"
    }
}

function Show-AdapterInfo {
    Write-Host "`n--- Aktív hálózati adapterek ---" -ForegroundColor Yellow
    $adapters = Get-ActiveAdapters
    if ($adapters) {
        $adapters | Format-Table -AutoSize
    } else {
        Write-Host "Nincs aktív adapter!" -ForegroundColor Red
    }
}

# ====================== FŐ PROGRAM ======================

Clear-Host
Write-Title "HÁLÓZATI TELJES TESZT (IPv4 / IPv6 / CGNAT)"
Write-Host "Feltételezés: most VEZETÉKES internet van csatlakoztatva." -ForegroundColor White
Write-Host "A script először a vezetékes kapcsolatot teszteli.`n"

# ---------- 1. VEZETÉKES TESZT ----------
Write-Title "1. FÁZIS – VEZETÉKES KAPCSOLAT"
Show-AdapterInfo
Test-IPv6Status
Test-CGNAT

Write-Host "`n`n" -NoNewline
Write-Host "================================================" -ForegroundColor Magenta
Write-Host "  MOST HÚZD KI A VEZETÉKES KÁBELT!" -ForegroundColor Magenta
Write-Host "  (vagy kapcsold le a vezetékes adaptert)" -ForegroundColor Magenta
Write-Host "  Utána csatlakoztasd a mobil stick-et / mobilnetet." -ForegroundColor Magenta
Write-Host "================================================" -ForegroundColor Magenta
Write-Host "`nHa kész vagy, nyomj meg BÁRMILYEN gombot a folytatáshoz..." -ForegroundColor Yellow
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# Kis várakozás, amíg a mobil kapcsolat felépül
Write-Host "`nVárakozás a mobil kapcsolat felépülésére (8 mp)..." -ForegroundColor DarkYellow
Start-Sleep -Seconds 8

# ---------- 2. MOBIL TESZT ----------
Write-Title "2. FÁZIS – MOBIL / STICK KAPCSOLAT"
Show-AdapterInfo
Test-IPv6Status
Test-CGNAT

Write-Title "TESZT VÉGE"
Write-Host "Hasonlítsd össze a két fázis eredményét!" -ForegroundColor Cyan
Write-Host "- Van-e IPv6 a vezetékesen / mobilon?"
Write-Host "- CGNAT jelei látszanak-e?"
Write-Host "- Melyik adapter volt aktív az egyes fázisokban?"
Write-Host "`nKész."