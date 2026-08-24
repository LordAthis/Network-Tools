# Network-Full-Test.ps1
# Teljes IPv4/IPv6 + CGNAT + adapter diagnostika
# Vezetekes -> mobil atallassal

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
    Write-Host "`n--- IPv6 allapot ---" -ForegroundColor Yellow
    
    $global = Get-NetIPAddress -AddressFamily IPv6 -ErrorAction SilentlyContinue | 
        Where-Object { 
            $_.IPAddress -notlike "fe80*" -and 
            $_.IPAddress -notlike "::1" -and 
            $_.AddressState -eq "Preferred" 
        }

    if ($global) {
        Write-Host "✓ Van globalis IPv6 cim:" -ForegroundColor Green
        $global | ForEach-Object {
            Write-Host ("  {0,-25} {1}/{2}" -f $_.InterfaceAlias, $_.IPAddress, $_.PrefixLength)
        }
    } else {
        Write-Host "✗ Nincs globalis IPv6 cim" -ForegroundColor Red
    }

    # Gyors elerhetoseg
    $targets = @("2001:4860:4860::8888", "2606:4700:4700::1111")
    foreach ($t in $targets) {
        $ok = Test-Connection -ComputerName $t -Count 1 -Quiet -ErrorAction SilentlyContinue
        if ($ok) { Write-Host "✓ $t elerheto" -ForegroundColor Green }
        else     { Write-Host "✗ $t nem elerheto" -ForegroundColor Red }
    }
}

function Test-CGNAT {
    Write-Host "`n--- CGNAT / Publikus IP vizsgalat ---" -ForegroundColor Yellow

    try {
        $publicIP = (Invoke-RestMethod -Uri "https://api.ipify.org" -TimeoutSec 8).Trim()
        Write-Host "Publikus IPv4: $publicIP" -ForegroundColor Green
    } catch {
        Write-Host "Nem sikerult lekerni a publikus IPv4-et" -ForegroundColor Red
        return
    }

    # CGNAT tipikus tartomanyok (RFC 6598)
    $cgnatRanges = @(
        @{ Start = [version]"100.64.0.0";  End = [version]"100.127.255.255" },  # 100.64.0.0/10
        @{ Start = [version]"10.0.0.0";    End = [version]"10.255.255.255" },
        @{ Start = [version]"172.16.0.0";  End = [version]"172.31.255.255" },
        @{ Start = [version]"192.168.0.0"; End = [version]"192.168.255.255" }
    )

    $ip = [version]$publicIP
    $isPrivateOrCGNAT = $false

    foreach ($range in $cgnatRanges) {