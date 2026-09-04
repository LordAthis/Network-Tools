# ============================================================
#  LOGtoINDEX.ps1
#  Ket uzemmod:
#   1) -LogFile <fajl> megadva  -> az EGY log fajlt alakitja at
#      olvashato HTML oldalla es megnyitja bongeszoben.
#      (Ezt hivjak a tobbi script a "Megnyissuk a LOG-ot?"
#      kerdesnel - notepad.exe HELYETT.)
#   2) -LogFile NELKUL          -> a teljes LOG\ mappat vegigolvassa,
#      minden fajlbol kigyujti az eszlelt IP cimeket + hozzajuk
#      tartozo ismert adatokat (hostname, MAC, statusz, miner-gyanus,
#      stb.), es egy OSSZESITO, kattinthato IP-tablazatot ad HTML-ben.
#      ("LOG -> INDEX")
#
#  Mas script hivja (Offer-OpenLog fuggvenyek), vagy onallo is fut.
#  Kimenet: LOG\_html\ mappaba, es alapertelmezetten megnyitja a
#  rendszer alapertelmezett bongeszojevel (Start-Process).
#
#  Ekezet: kod/komment ekezetes lehet, a generalt HTML SZOVEGE
#  (amit a felhasznalo lat) ekezetes marad, mert bongeszoben UTF-8-kent
#  jelenik meg (nincs PowerShell 5.1 encoding-gond, mert nem futtatott
#  kod, hanem statikus HTML fajl).
# ============================================================

param(
    [string]$LogFile,
    [string]$LogDir = "$PSScriptRoot\LOG",
    [switch]$NoOpen
)

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}
$HtmlDir = Join-Path $LogDir "_html"
if (-not (Test-Path $HtmlDir)) {
    New-Item -ItemType Directory -Path $HtmlDir -Force | Out-Null
}

function Get-HtmlHead {
    param([string]$Title)
    return @"
<!DOCTYPE html>
<html lang="hu">
<head>
<meta charset="UTF-8">
<title>$Title</title>
<style>
  body { background:#14181f; color:#e6e9ef; font-family:Consolas,'Segoe UI',monospace; margin:0; padding:24px; }
  h1 { color:#7fd1ff; font-size:20px; border-bottom:1px solid #2a3140; padding-bottom:10px; }
  h2 { color:#7fd1ff; font-size:15px; margin-top:26px; border-left:4px solid #3a7bd5; padding-left:8px; }
  .meta { color:#8b94a8; font-size:12px; margin-bottom:18px; }
  .line { padding:1px 4px; white-space:pre-wrap; word-break:break-all; font-size:13px; }
  .tag-red    { color:#ff6b6b; font-weight:bold; }
  .tag-yellow { color:#ffd166; }
  .tag-green  { color:#8ee08e; }
  .tag-magenta{ color:#e07be0; }
  .tag-cyan   { color:#7fd1ff; }
  table { border-collapse: collapse; width:100%; margin-top:10px; font-size:13px; }
  th, td { border:1px solid #2a3140; padding:6px 10px; text-align:left; vertical-align:top; }
  th { background:#1c2230; color:#7fd1ff; position:sticky; top:0; }
  tr:nth-child(even) { background:#181d28; }
  tr:hover { background:#222a3a; }
  a { color:#7fd1ff; text-decoration:none; }
  a:hover { text-decoration:underline; }
  .badge { display:inline-block; padding:2px 7px; border-radius:4px; font-size:11px; margin:1px 3px 1px 0; }
  .b-alive  { background:#1f4d2e; color:#8ee08e; }
  .b-nema   { background:#4d1f4d; color:#e07be0; }
  .b-slow   { background:#4d3a1f; color:#ffd166; }
  .b-susp   { background:#4d1f1f; color:#ff6b6b; }
  .b-known  { background:#1f3a4d; color:#7fd1ff; }
  .b-smart  { background:#1f4d47; color:#7fdccb; }
  input#filterBox { background:#1c2230; color:#e6e9ef; border:1px solid #2a3140; padding:6px 10px; width:280px; margin-bottom:10px; }
  .count { color:#8b94a8; font-size:12px; margin-left:8px; }
</style>
</head>
<body>
<h1>$Title</h1>
"@
}

function Get-HtmlTail { return "</body></html>" }

# ============================================================
# 1) MOD: EGY log fajl -> olvashato HTML
# ============================================================
function Convert-SingleLogToHtml {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        Write-Host "A megadott log fajl nem talalhato: $Path" -ForegroundColor Red
        return $null
    }

    $lines = Get-Content -Path $Path -Encoding UTF8
    $name = Split-Path $Path -Leaf
    $html = Get-HtmlHead -Title ("LOG nezet - " + $name)
    $html += "<div class='meta'>Forras: $Path | Generalva: $(Get-Date)</div>`n"

    foreach ($raw in $lines) {
        $escaped = [System.Net.WebUtility]::HtmlEncode($raw)
        if ($raw -match '^\S+\s*\|\s*===.*===\s*$' -or $raw -match '^===.*===\s*$') {
            $html += "<h2>$escaped</h2>`n"
            continue
        }
        $cls = "line"
        if ($raw -match 'GYANUS|HIBA|SEVERE|ERROR') { $cls = "line tag-red" }
        elseif ($raw -match 'NEMA|MAGENTA|LASSU') { $cls = "line tag-magenta" }
        elseif ($raw -match 'FIGYELEM|Yellow') { $cls = "line tag-yellow" }
        elseif ($raw -match '\bOK\b|ELO|SIKERES|betoltve|Green') { $cls = "line tag-green" }
        $html += "<div class='$cls'>$escaped</div>`n"
    }
    $html += Get-HtmlTail

    $outPath = Join-Path $HtmlDir ($name -replace '\.txt$', '.html')
    $html | Out-File -FilePath $outPath -Encoding UTF8
    return $outPath
}

# ============================================================
# 2) MOD: TELJES LOG mappa -> osszesito, kattinthato IP index
# ============================================================
function Build-IpIndex {
    param([string]$Dir)

    $logFiles = Get-ChildItem -Path $Dir -Filter "*.txt" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '^MinerSearch_IPs_' } |
        Sort-Object LastWriteTime

    if ($logFiles.Count -eq 0) {
        Write-Host "Nincs feldolgozhato .txt log a $Dir mappaban." -ForegroundColor Red
        return $null
    }

    # IP -> attributum-gyujtemeny
    $ipData = @{}
    $ipRegex = '\b(?:192\.168|10\.|172\.(?:1[6-9]|2[0-9]|3[01]))\.\d{1,3}\.\d{1,3}\b'
    $macRegex = '([0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}'

    # Kulcsszo -> jelvenyt/allapotot ado terkep (sorban, ha a sor tartalmazza a kulcsszot es van benne IP)
    $tagMap = [ordered]@{
        'GYANUS KAPCSOLAT'              = @{ Badge='b-susp';  Label='GYANUS KAPCSOLAT' }
        'GYANUS: ismert miner'          = @{ Badge='b-susp';  Label='MINER PORT GYANUS' }
        'GYANUS'                        = @{ Badge='b-susp';  Label='GYANUS' }
        'NEMA (ARP-ban van'             = @{ Badge='b-nema';  Label='NEMA (ARP-only)' }
        'LASSU VALASZ'                  = @{ Badge='b-slow';  Label='LASSU' }
        'ISMERT SZOLGALTATAS'           = @{ Badge='b-known'; Label='ISMERT SZOLGALTATAS' }
        'ESZKOZTIPUS'                   = @{ Badge='b-smart'; Label='FELISMERT ESZKOZ' }
        'ELO:'                          = @{ Badge='b-alive'; Label='ELO' }
    }

    foreach ($file in $logFiles) {
        $content = Get-Content -Path $file.FullName -Encoding UTF8 -ErrorAction SilentlyContinue
        if (-not $content) { continue }

        foreach ($line in $content) {
            $ipMatches = [regex]::Matches($line, $ipRegex)
            if ($ipMatches.Count -eq 0) { continue }

            foreach ($m in $ipMatches) {
                $ip = $m.Value
                if (-not $ipData.ContainsKey($ip)) {
                    $ipData[$ip] = [ordered]@{
                        Hostname = $null
                        MAC      = $null
                        Tags     = [System.Collections.Generic.HashSet[string]]::new()
                        Sources  = [System.Collections.Generic.HashSet[string]]::new()
                        Detail   = [System.Collections.Generic.List[string]]::new()
                        LastSeen = $file.LastWriteTime
                    }
                }
                $entry = $ipData[$ip]
                [void]$entry.Sources.Add($file.Name)
                if ($file.LastWriteTime -gt $entry.LastSeen) { $entry.LastSeen = $file.LastWriteTime }

                # MAC a sorban?
                if ($line -match $macRegex) {
                    $entry.MAC = $Matches[0]
                }
                # Hostname mintak (DNS / PTR utan)
                if ($line -match 'Hostname\s*\((?:DNS|PTR)\)\s*:\s*(\S+)') {
                    $entry.Hostname = $Matches[1]
                }
                # Eszkoztipus / Guess mintak (MinerStatus tablazat es NetworkDiag eszkoztipus sorok)
                if ($line -match 'ESZKOZTIPUS[^:]*:\s*(\S+)') {
                    [void]$entry.Tags.Add("SMART:$($Matches[1])")
                }
                if ($line -match 'Guess:\s*([A-Za-z0-9/_\-]+)' -and $Matches[1] -ne '-') {
                    [void]$entry.Tags.Add("MINER:$($Matches[1])")
                }

                foreach ($kw in $tagMap.Keys) {
                    if ($line -match [regex]::Escape($kw)) {
                        [void]$entry.Tags.Add($tagMap[$kw].Label)
                    }
                }

                # Rovid reszlet-sor mentese (max 6 / IP, hogy ne duzzadjon fel)
                if ($entry.Detail.Count -lt 6) {
                    $shortLine = $line.Trim()
                    if ($shortLine.Length -gt 160) { $shortLine = $shortLine.Substring(0,160) + "..." }
                    $entry.Detail.Add($shortLine)
                }
            }
        }
    }

    # --- HTML osszeallitasa ---
    $html = Get-HtmlHead -Title "Halozati IP Index (osszes LOG alapjan)"
    $html += "<div class='meta'>Feldolgozott log fajlok: $($logFiles.Count) | Talalt egyedi IP: $($ipData.Count) | Generalva: $(Get-Date)</div>`n"
    $html += "<input id='filterBox' type='text' placeholder='Szures: IP, hostname, MAC, cimke...' onkeyup='filterRows()'>"
    $html += "<span class='count' id='rowCount'></span>`n"
    $html += "<table id='ipTable'><thead><tr>"
    $html += "<th>IP (kattinthato)</th><th>Statuszok</th><th>Hostname</th><th>MAC</th><th>Forras log(ok)</th><th>Utoljara latva</th><th>Reszletek</th>"
    $html += "</tr></thead><tbody>`n"

    foreach ($ip in ($ipData.Keys | Sort-Object { [version]($_ -replace '^(\d+)\.(\d+)\.(\d+)\.(\d+)$','$1.$2.$3.$4') } -ErrorAction SilentlyContinue)) {
        $e = $ipData[$ip]
        $badges = ""
        foreach ($tag in $e.Tags) {
            $badgeClass = "b-known"
            if ($tag -match 'GYANUS|MINER PORT') { $badgeClass = "b-susp" }
            elseif ($tag -match 'NEMA') { $badgeClass = "b-nema" }
            elseif ($tag -match 'LASSU') { $badgeClass = "b-slow" }
            elseif ($tag -match 'ELO') { $badgeClass = "b-alive" }
            elseif ($tag -match 'SMART|MINER:') { $badgeClass = "b-smart" }
            $badges += "<span class='badge $badgeClass'>$([System.Net.WebUtility]::HtmlEncode($tag))</span>"
        }
        $hostnameOut = if ($e.Hostname) { [System.Net.WebUtility]::HtmlEncode($e.Hostname) } else { "-" }
        $macOut = if ($e.MAC) { [System.Net.WebUtility]::HtmlEncode($e.MAC) } else { "-" }
        $sourcesOut = ($e.Sources | Sort-Object) -join "<br>"
        $detailOut = ($e.Detail | ForEach-Object { [System.Net.WebUtility]::HtmlEncode($_) }) -join "<br>"

        $html += "<tr>"
        $html += "<td><a href='http://$ip/' target='_blank'>$ip</a></td>"
        $html += "<td>$badges</td>"
        $html += "<td>$hostnameOut</td>"
        $html += "<td>$macOut</td>"
        $html += "<td style='font-size:11px'>$sourcesOut</td>"
        $html += "<td style='font-size:11px'>$($e.LastSeen.ToString('yyyy-MM-dd HH:mm'))</td>"
        $html += "<td style='font-size:11px'>$detailOut</td>"
        $html += "</tr>`n"
    }

    $html += "</tbody></table>`n"
    $html += @"
<script>
function filterRows() {
  var q = document.getElementById('filterBox').value.toLowerCase();
  var rows = document.querySelectorAll('#ipTable tbody tr');
  var visible = 0;
  rows.forEach(function(r) {
    var match = r.textContent.toLowerCase().indexOf(q) !== -1;
    r.style.display = match ? '' : 'none';
    if (match) visible++;
  });
  document.getElementById('rowCount').textContent = visible + ' / ' + rows.length + ' sor';
}
filterRows();
</script>
"@
    $html += Get-HtmlTail

    $outPath = Join-Path $HtmlDir "IP_Index_$(Get-Date -Format 'yyyyMMdd_HHmmss').html"
    $html | Out-File -FilePath $outPath -Encoding UTF8
    return $outPath
}

# ============================================================
# Futas
# ============================================================
$resultPath = $null
if ($LogFile) {
    $resultPath = Convert-SingleLogToHtml -Path $LogFile
} else {
    $resultPath = Build-IpIndex -Dir $LogDir
}

if ($resultPath -and (Test-Path $resultPath)) {
    Write-Host "HTML elkeszult: $resultPath" -ForegroundColor Green
    if (-not $NoOpen) {
        try { Start-Process $resultPath } catch { Write-Host "Nem sikerult megnyitni a bongeszot: $_" -ForegroundColor Yellow }
    }
} else {
    Write-Host "Nem keszult HTML fajl." -ForegroundColor Red
}
