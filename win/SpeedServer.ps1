#Requires -Version 3.0
# Verzio: v1.0.0 - 2026-09-27
# ============================================================
#  SpeedServer.ps1  -  LAN sebessegmero szerver (a telefonos
#  Network Tool's app "Sebessegteszt > LAN-meres" partnere)
#
#  Mire jo?
#    A telefon onmagaban csak az INTERNET fele tud valodi
#    sebesseget merni. Ha ezt a scriptet elinditod egy PC-n a
#    helyi halozaton, a telefon a PC-hez kepest is tud le- es
#    feltoltest merni - igy kiderul, hogy a router/switch/kabel
#    lanc (a PC es a telefon kozott) mennyit tud valojaban
#    (pl. egy 100 Mbps-os switch vagy serult kabel ~94 Mbps-nal
#    "plafonozik" gigabites halozatban is).
#
#  Vegpontok (ugyanazok, mint a Cloudflare speed.cloudflare.com-on):
#    GET  /__down?bytes=N   -> N bajt veletlen adat
#    POST /__up             -> a feltoltott adatot eldobja
#    GET  /                 -> rovid leiras
#
#  Hasznalat:
#      .\SpeedServer.ps1                (alap port: 8765)
#      .\SpeedServer.ps1 -Port 9000
#  Leallitas: Q billentyu (vagy az ablak bezarasa).
#
#  Tuzfal: rendszergazdakent futtatva a script IDEIGLENES bejovo
#  szabalyt vesz fel a portra, es kilepeskor torli. Rendszergazda
#  nelkul a Windows rakerdezhet, vagy blokkolhatja a kapcsolatot.
# ============================================================

param(
    [int]$Port = 8765
)

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$Host.UI.RawUI.WindowTitle = "Network-Tools SpeedServer (port $Port)"

$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = (Get-Location).Path }
$logDir = Join-Path $ScriptRoot "LOG"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$logFile = Join-Path $logDir ("SpeedServer-LOG-" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".txt")

function Write-Log {
    param([string]$Text, [string]$Color = "Gray")
    $line = "[" + (Get-Date -Format "HH:mm:ss") + "] " + $Text
    Write-Host $line -ForegroundColor $Color
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

# ------------------------------------------------------------
#  Tuzfal (csak rendszergazdakent)
# ------------------------------------------------------------
$isAdmin = $false
try {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
} catch { }

$ruleName = "NetworkTools-SpeedServer-$Port"
$ruleAdded = $false
if ($isAdmin) {
    & netsh advfirewall firewall add rule name="$ruleName" dir=in action=allow protocol=TCP localport=$Port profile=private,domain | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $ruleAdded = $true
        Write-Log "Ideiglenes tuzfal-szabaly felveve: $ruleName (csak privat/tartomanyi halozaton)" "DarkGray"
    }
} else {
    Write-Log "Nem rendszergazdakent fut: ha a telefon nem eri el, engedelyezd a tuzfalon, vagy inditsd rendszergazdakent." "Yellow"
}

# ------------------------------------------------------------
#  Kapcsolat-kezelo (kulon runspace-ben fut, egyszerre tobb is)
# ------------------------------------------------------------
$handler = {
    param($client)
    $stream = $null
    try {
        $client.NoDelay = $true
        $client.ReceiveTimeout = 20000
        $client.SendTimeout = 20000
        $stream = $client.GetStream()

        # HTTP fejlec beolvasasa
        $sb = New-Object System.Text.StringBuilder
        $one = New-Object byte[] 1
        while ($true) {
            $n = $stream.Read($one, 0, 1)
            if ($n -le 0) { return }
            [void]$sb.Append([char]$one[0])
            if ($sb.Length -ge 4 -and $sb.ToString($sb.Length - 4, 4) -eq "`r`n`r`n") { break }
            if ($sb.Length -gt 16384) { return }
        }
        $lines = $sb.ToString() -split "`r`n"
        $first = $lines[0] -split ' '
        $method = $first[0]
        $path = if ($first.Count -gt 1) { $first[1] } else { "/" }
        [int64]$contentLength = 0
        foreach ($l in $lines) {
            if ($l -match '^(?i)content-length:\s*(\d+)') { $contentLength = [int64]$matches[1] }
        }

        $block = New-Object byte[] 1048576
        (New-Object System.Random 42).NextBytes($block)

        if ($method -eq 'GET' -and $path -like '/__down*') {
            [int64]$bytes = 100000000
            if ($path -match 'bytes=(\d+)') { $bytes = [int64]$matches[1] }
            $hdr = "HTTP/1.1 200 OK`r`nContent-Type: application/octet-stream`r`nContent-Length: $bytes`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
            $hb = [System.Text.Encoding]::ASCII.GetBytes($hdr)
            $stream.Write($hb, 0, $hb.Length)
            [int64]$left = $bytes
            while ($left -gt 0) {
                $len = [int][Math]::Min([int64]$block.Length, $left)
                $stream.Write($block, 0, $len)
                $left -= $len
            }
        }
        elseif ($method -eq 'POST' -and $path -like '/__up*') {
            [int64]$left = $contentLength
            while ($left -gt 0) {
                $n = $stream.Read($block, 0, [int][Math]::Min([int64]$block.Length, $left))
                if ($n -le 0) { break }
                $left -= $n
            }
            $body = [System.Text.Encoding]::ASCII.GetBytes('{"received":' + ($contentLength - $left) + '}')
            $hdr = "HTTP/1.1 200 OK`r`nContent-Type: application/json`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
            $hb = [System.Text.Encoding]::ASCII.GetBytes($hdr)
            $stream.Write($hb, 0, $hb.Length)
            $stream.Write($body, 0, $body.Length)
        }
        else {
            $body = [System.Text.Encoding]::UTF8.GetBytes("Network-Tools SpeedServer`nGET /__down?bytes=N  |  POST /__up`n")
            $hdr = "HTTP/1.1 200 OK`r`nContent-Type: text/plain; charset=utf-8`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
            $hb = [System.Text.Encoding]::ASCII.GetBytes($hdr)
            $stream.Write($hb, 0, $hb.Length)
            $stream.Write($body, 0, $body.Length)
        }
    }
    catch { }
    finally {
        if ($stream) { try { $stream.Close() } catch { } }
        try { $client.Close() } catch { }
    }
}

# ------------------------------------------------------------
#  Inditas
# ------------------------------------------------------------
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
try {
    $listener.Start()
} catch {
    Write-Log "A $Port-es port nem nyithato meg: $($_.Exception.Message)" "Red"
    if ($ruleAdded) { & netsh advfirewall firewall delete rule name="$ruleName" | Out-Null }
    return
}

$pool = [runspacefactory]::CreateRunspacePool(1, 32)
$pool.Open()
$workers = New-Object System.Collections.ArrayList

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Network-Tools SpeedServer fut - port: $Port" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  A telefonon (Sebessegteszt > LAN-meres) ezek kozul add meg:" -ForegroundColor Yellow
$addrs = @()
try {
    $addrs = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and -not $_.ToString().StartsWith("127.") -and -not $_.ToString().StartsWith("169.254.") }
} catch { }
foreach ($a in $addrs) { Write-Host ("     " + $a.ToString() + ":" + $Port) -ForegroundColor Green }
Write-Host ""
Write-Host "  Leallitas: Q" -ForegroundColor DarkGray
Write-Host ""
Write-Log "SpeedServer elindult (port $Port, cimek: $(($addrs | ForEach-Object { $_.ToString() }) -join ', '))" "DarkGray"

$connCount = 0
$lastPeer = ""
try {
    while ($true) {
        $quit = $false
        try {
            if ([Console]::KeyAvailable) {
                $k = [Console]::ReadKey($true)
                if ($k.Key -eq 'Q') { $quit = $true }
            }
        } catch { }
        if ($quit) { break }

        if ($listener.Pending()) {
            $client = $listener.AcceptTcpClient()
            $connCount++
            $peer = ""
            try { $peer = $client.Client.RemoteEndPoint.Address.ToString() } catch { }
            if ($peer -ne $lastPeer) {
                Write-Log "Kapcsolat innen: $peer" "Green"
                $lastPeer = $peer
            }
            $ps = [powershell]::Create()
            $ps.RunspacePool = $pool
            [void]$ps.AddScript($handler).AddArgument($client)
            $h = $ps.BeginInvoke()
            [void]$workers.Add(@{ PS = $ps; H = $h })
        } else {
            Start-Sleep -Milliseconds 15
        }

        for ($i = $workers.Count - 1; $i -ge 0; $i--) {
            $w = $workers[$i]
            if ($w.H.IsCompleted) {
                try { [void]$w.PS.EndInvoke($w.H) } catch { }
                $w.PS.Dispose()
                $workers.RemoveAt($i)
            }
        }
    }
}
finally {
    Write-Log "Leallitas... (osszesen $connCount kapcsolat)" "Yellow"
    try { $listener.Stop() } catch { }
    foreach ($w in $workers) { try { $w.PS.Stop(); $w.PS.Dispose() } catch { } }
    try { $pool.Close() } catch { }
    if ($ruleAdded) {
        & netsh advfirewall firewall delete rule name="$ruleName" | Out-Null
        Write-Log "Ideiglenes tuzfal-szabaly torolve." "DarkGray"
    }
    Write-Log "LOG: $logFile" "Cyan"
}
