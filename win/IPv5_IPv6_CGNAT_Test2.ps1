#Requires -Version 3.0
# Network-Full-Test.ps1
# HTML valaszbol kibanyaszos halozati diagnosztika egyedi logolassal

$logDir = Join-Path $PSScriptRoot "LOG"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# Az idobelyeg garantalja, hogy minden futtatas uj fajlt hoz letre (nem irja felul a regit)
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $logDir "IPv5-IPv6-Teszt-LOG-$timestamp.txt"
$segedHtml = Join-Path $logDir "seged_valasz-$timestamp.html"

Start-Transcript -Path $logFile -Append | Out-Null

function Write-Title($text) {
    Write-Host ======================================== -ForegroundColor Cyan
    Write-Host $text -ForegroundColor Cyan
    Write-Host ======================================== -ForegroundColor Cyan
}

function Get-ActiveAdapters {
    Get-NetAdapter | Where-Object { $_.Status -eq 'Up' } | 
        Select-Object Name, InterfaceDescription, LinkSpeed, MacAddress
}

function Test-IPv6Status {
    Write-Host IPV6_ALLAPOT -ForegroundColor Yellow
    
    $global = Get-NetIPAddress -AddressFamily IPv6 -ErrorAction SilentlyContinue | 
        Where-Object { 
            $_.IPAddress -notlike 'fe80*' -and 
            $_.IPAddress -notlike '::1' -and 
            $_.AddressState -eq 'Preferred' 
        }

    if ($global) {
        Write-Host VAN_GLOBALIS_IPV6_CIM -ForegroundColor Green
        $global | ForEach-Object {
            Write-Host $_.InterfaceAlias $_.IPAddress
        }
    } else {
        Write-Host NICNS_GLOBALIS_IPV6_CIM -ForegroundColor Red
    }

    $targets = @('2001:4860:4860::8888', '2606:4700:4700::1111')
    foreach ($t in $targets) {
        $ok = Test-Connection -ComputerName $t -Count 1 -Quiet -ErrorAction SilentlyContinue
        if ($ok) { Write-Host ELERHETO_$t -ForegroundColor Green }
        else     { Write-Host NEM_ELERHETO_$t -ForegroundColor Red }
    }
}

function Test-CGNAT {
    Write-Host CGNAT_ES_PUBLIKUS_IP_VIZSGALAT -ForegroundColor Yellow

    try {
        $rawResponse = (Invoke-RestMethod -Uri 'https://api.ipify.org' -TimeoutSec 8).Trim()
    } catch {
        Write-Host Nem_sikerult_elerni_az_API-t -ForegroundColor Red
        return
    }

    $publicIP = ""

    if ($rawResponse -like '*<html*') {
        # Most mar a seged HTML is egyedi nevet kap az idobelyeggel
        $rawResponse | Out-File -FilePath $segedHtml -Force
        
        if ($rawResponse -match '"ip":"([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})"') {
            $publicIP = $Matches[1]
            Write-Host KIBANYASZOTT_IP_A_HTML-BOL -ForegroundColor Yellow
        }
    } else {
        $publicIP = $rawResponse
    }

    if (-not $publicIP) {
        Write-Host HIBA_Nem_talalhato_IP_cim_a_valaszban -ForegroundColor Red
        return
    }

    Write-Host Publikus_IPv4_$publicIP -ForegroundColor Green

    $cgnatRanges = @(
        @{ Start = [version]'100.64.0.0';  End = [version]'100.127.255.255' },
        @{ Start = [version]'10.0.0.0';    End = [version]'10.255.255.255' },
        @{ Start = [version]'172.16.0.0';  End = [version]'172.31.255.255' },
        @{ Start = [version]'192.168.0.0'; End = [version]'192.168.255.255' }
    )

    try {
        $ip = [version]$publicIP
        $isPrivateOrCGNAT = $false

        foreach ($range in $cgnatRanges) {
            if ($ip -ge $range.Start -and $ip -le $range.End) {
                $isPrivateOrCGNAT = $true
                break
            }
        }

        if ($isPrivateOrCGNAT) {
            Write-Host FIGYELEM_A_lekert_IP_belso_vagy_CGNAT_tartomanyba_esik -ForegroundColor Yellow
        } else {
            Write-Host OK_A_publikus_IP_valodi_publikus_tartomanyban_van -ForegroundColor Green
        }
    } catch {
        Write-Host HIBA_Az_IP_konvertalasa_soran -ForegroundColor Red
    }
}

# --- FUTTATAS ---
Write-Title TESZT_INDITASA

Write-Host Aktiv_adapterek_keresese... -ForegroundColor Yellow
Get-ActiveAdapters | Format-Table -AutoSize

Test-IPv6Status
Test-CGNAT

Write-Title TESZT_VEGE

Stop-Transcript | Out-Null
Write-Host LOG_MENTVE_A_LOG_MAPPABA -ForegroundColor Cyan
