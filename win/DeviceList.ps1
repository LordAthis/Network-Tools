#Requires -Version 3.0
# Verzio: v1.0.0 - 2026-09-21
# ============================================================
#  DeviceList.ps1  -  Felhasznalo- es eszkozlista kezelo
#
#  Mit tud:
#   - datas\Devices.json (sablon/minta) + datas\Devices.local.json (sajat,
#     verziokezelesen kivuli, valos adatok) beolvasasa es osszefesulese
#   - MAC-cim normalizalas es MAC alapu kereses (barmilyen formatumbol)
#   - ellenorzes: duplikalt id/MAC, ervenytelen MAC, ismeretlen gazda,
#     ismeretlen kategoria/statusz, hibas IP, randomizalt MAC gyanu
#
#  Hasznalat mas scriptbol (dot-source):
#      . "$PSScriptRoot\DeviceList.ps1"
#      $lista = Import-DeviceList
#      $eszkoz = Find-DeviceByMac -List $lista -Mac $arpSorbolKapottMac
#      if ($eszkoz) { "$($eszkoz.name) - $(Get-DeviceOwnerName -List $lista -Device $eszkoz)" }
#
#  Hasznalat kozvetlenul (parancssorbol):
#      .\DeviceList.ps1 -Validate                 (ellenorzes + tablazat)
#      .\DeviceList.ps1 -Validate -IncludeExamples (a minta bejegyzesekkel egyutt)
#      .\DeviceList.ps1 -FindMac "aa-bb-cc-dd-ee-ff" (egy MAC megkeresese)
#
#  A HTML lista a display.group / display.order / display.hide /
#  short_name mezoket hasznalhatja; a technikai kulcs mindig az id / MAC.
# ============================================================

# FIGYELEM: ha ezt a fajlt dot-source-olod, a lenti parameterek valtozokent
# megjelennek a hivo scriptben is - ezert a nevek szandekosan egyediek
# (ListPath, ListLocalPath, FindMac), hogy ne utkozzenek a hivo sajat valtozoival.
param(
    [string]$ListPath,
    [string]$ListLocalPath,
    [switch]$IncludeExamples,
    [switch]$Validate,
    [string]$FindMac
)

# ---------- MAC segedek ----------

# Barmilyen formatumbol 12 karakteres, nagybetus hex. Ervenytelen -> $null.
function ConvertTo-NormalizedMac {
    param([string]$Mac)
    if ([string]::IsNullOrWhiteSpace($Mac)) { return $null }
    if ($Mac -match '[^0-9A-Fa-f:\-\.\s]') { return $null }
    $hex = ($Mac -replace '[^0-9A-Fa-f]', '').ToUpper()
    if ($hex.Length -ne 12) { return $null }
    return $hex
}

# 12 hex -> "AA:BB:CC:DD:EE:FF" (elvalaszto valaszthato)
function Format-Mac {
    param([string]$Mac, [string]$Separator = ':')
    $n = ConvertTo-NormalizedMac $Mac
    if (-not $n) { return $Mac }
    $parts = @()
    for ($i = 0; $i -lt 12; $i += 2) { $parts += $n.Substring($i, 2) }
    return ($parts -join $Separator)
}

# Az OUI (elso 3 byte, 6 hex) - a Macouilist.json "oui" mezojehez illik
function Get-MacOui {
    param([string]$Mac)
    $n = ConvertTo-NormalizedMac $Mac
    if (-not $n) { return $null }
    return $n.Substring(0, 6)
}

# "Locally administered" bit (az elso byte 2-es bitje): a telefonok privat/veletlen
# wifi-MAC-je tipikusan ilyen -> az ilyen MAC valtozhat
function Test-MacLocallyAdministered {
    param([string]$Mac)
    $n = ConvertTo-NormalizedMac $Mac
    if (-not $n) { return $false }
    $firstByte = [Convert]::ToInt32($n.Substring(0, 2), 16)
    return (($firstByte -band 2) -eq 2)
}

# ---------- beolvasas ----------

function Import-DeviceList {
    param(
        [string]$Path,
        [string]$LocalPath,
        [switch]$IncludeExamples
    )

    $root = $PSScriptRoot
    if (-not $root) { $root = (Get-Location).Path }
    if (-not $Path)      { $Path = Join-Path (Join-Path $root 'datas') 'Devices.json' }
    if (-not $LocalPath) { $LocalPath = Join-Path (Split-Path -Parent $Path) 'Devices.local.json' }

    $users     = New-Object System.Collections.ArrayList
    $devices   = New-Object System.Collections.ArrayList
    $warnings  = New-Object System.Collections.ArrayList
    $sources   = New-Object System.Collections.ArrayList
    $userSrc   = @{}   # id -> melyik fajlbol jott (a .local csak KULON fajlbol jovo azonos id-t ir felul;
    $devSrc    = @{}   #        egy fajlon beluli duplikalt id-t az ellenorzes jelzi, nem nyeli el)
    $vocab     = $null
    $version   = $null

    foreach ($src in @($Path, $LocalPath)) {
        if (-not (Test-Path $src)) {
            if ($src -eq $Path) { [void]$warnings.Add("Hianyzik: $src") }
            continue
        }
        $obj = $null
        try {
            $raw = Get-Content -Path $src -Raw -Encoding UTF8
            $obj = $raw | ConvertFrom-Json
        } catch {
            [void]$warnings.Add("JSON hiba ($src): $($_.Exception.Message)")
            continue
        }
        [void]$sources.Add($src)
        if ($src -eq $Path) {
            $vocab   = $obj.vocab
            $version = $obj.version
        } elseif (-not $vocab -and $obj.vocab) {
            $vocab = $obj.vocab
        }

        foreach ($u in @($obj.users)) {
            if (-not $u) { continue }
            if ($u.example -and -not $IncludeExamples) { continue }
            $idx = -1
            for ($i = 0; $i -lt $users.Count; $i++) { if ($users[$i].id -eq $u.id) { $idx = $i; break } }
            if ($idx -ge 0 -and $userSrc[[string]$u.id] -ne $src) { $users[$idx] = $u } else { [void]$users.Add($u) }
            $userSrc[[string]$u.id] = $src
        }
        foreach ($d in @($obj.devices)) {
            if (-not $d) { continue }
            if ($d.example -and -not $IncludeExamples) { continue }
            $idx = -1
            for ($i = 0; $i -lt $devices.Count; $i++) { if ($devices[$i].id -eq $d.id) { $idx = $i; break } }
            if ($idx -ge 0 -and $devSrc[[string]$d.id] -ne $src) { $devices[$idx] = $d } else { [void]$devices.Add($d) }
            $devSrc[[string]$d.id] = $src
        }
    }

    # Indexek: MAC -> eszkoz, id -> felhasznalo
    $macIndex  = @{}
    $userIndex = @{}
    foreach ($u in $users) { if ($u.id -and -not $userIndex.ContainsKey([string]$u.id)) { $userIndex[[string]$u.id] = $u } }
    foreach ($d in $devices) {
        foreach ($m in @($d.macs)) {
            if (-not $m) { continue }
            $n = ConvertTo-NormalizedMac ([string]$m.mac)
            if ($n -and -not $macIndex.ContainsKey($n)) { $macIndex[$n] = $d }
        }
    }

    return (New-Object psobject -Property @{
        Version   = $version
        Sources   = @($sources)
        Users     = @($users)
        Devices   = @($devices)
        MacIndex  = $macIndex
        UserIndex = $userIndex
        Vocab     = $vocab
        Warnings  = @($warnings)
    })
}

# ---------- kereses ----------

function Find-DeviceByMac {
    param($List, [string]$Mac)
    $n = ConvertTo-NormalizedMac $Mac
    if (-not $n) { return $null }
    if ($List.MacIndex.ContainsKey($n)) { return $List.MacIndex[$n] }
    return $null
}

function Get-DeviceOwnerName {
    param($List, $Device)
    if (-not $Device -or -not $Device.owner_id) { return '' }
    $key = [string]$Device.owner_id
    if ($List.UserIndex.ContainsKey($key)) {
        $u = $List.UserIndex[$key]
        if ($u.short_name) { return [string]$u.short_name }
        return [string]$u.name
    }
    return "(ismeretlen: $key)"
}

# ---------- ellenorzes ----------
# Visszaad: objektumok (Szint = HIBA | FIGYELEM, Azonosito, Uzenet). Ures = minden rendben.
function Test-DeviceList {
    param($List)

    $out = New-Object System.Collections.ArrayList
    function Add-Problem([string]$Level, [string]$Id, [string]$Msg) {
        [void]$out.Add((New-Object psobject -Property @{ Szint = $Level; Azonosito = $Id; Uzenet = $Msg }))
    }

    $cats     = @(); $stats = @(); $ifTypes = @(); $roles = @()
    if ($List.Vocab) {
        $cats    = @($List.Vocab.categories)
        $stats   = @($List.Vocab.statuses)
        $ifTypes = @($List.Vocab.interface_types)
        $roles   = @($List.Vocab.user_roles)
    }

    # felhasznalok
    $seenUsers = @{}
    foreach ($u in $List.Users) {
        $uid = [string]$u.id
        if (-not $uid) { Add-Problem 'HIBA' '(nincs id)' "Felhasznalo id nelkul: $($u.name)"; continue }
        if ($seenUsers.ContainsKey($uid)) { Add-Problem 'HIBA' $uid 'Duplikalt felhasznalo id' }
        $seenUsers[$uid] = $true
        if (-not $u.name) { Add-Problem 'HIBA' $uid 'Hianyzik a felhasznalo neve (name)' }
        if ($u.role -and $roles.Count -gt 0 -and ($roles -notcontains [string]$u.role)) {
            Add-Problem 'FIGYELEM' $uid "Ismeretlen szerep (role): $($u.role)"
        }
    }

    # eszkozok
    $seenDev = @{}
    $seenMac = @{}
    foreach ($d in $List.Devices) {
        $did = [string]$d.id
        if (-not $did) { Add-Problem 'HIBA' '(nincs id)' "Eszkoz id nelkul: $($d.name)"; continue }
        if ($seenDev.ContainsKey($did)) { Add-Problem 'HIBA' $did 'Duplikalt eszkoz id' }
        $seenDev[$did] = $true

        if (-not $d.name) { Add-Problem 'HIBA' $did 'Hianyzik az eszkoz neve (name)' }

        if ($d.owner_id -and -not $List.UserIndex.ContainsKey([string]$d.owner_id)) {
            Add-Problem 'HIBA' $did "Ismeretlen gazda (owner_id): $($d.owner_id)"
        }
        if ($d.category -and $cats.Count -gt 0 -and ($cats -notcontains [string]$d.category)) {
            Add-Problem 'FIGYELEM' $did "Ismeretlen kategoria: $($d.category)"
        }
        if (-not $d.category) { Add-Problem 'FIGYELEM' $did 'Nincs kategoria (category)' }
        if ($d.status -and $stats.Count -gt 0 -and ($stats -notcontains [string]$d.status)) {
            Add-Problem 'FIGYELEM' $did "Ismeretlen statusz: $($d.status)"
        }

        $macs = @($d.macs | Where-Object { $_ })
        if ($macs.Count -eq 0) {
            Add-Problem 'FIGYELEM' $did 'Nincs MAC-cim: MAC alapjan nem azonosithato'
        }
        foreach ($m in $macs) {
            $n = ConvertTo-NormalizedMac ([string]$m.mac)
            if (-not $n) { Add-Problem 'HIBA' $did "Ervenytelen MAC-cim: '$($m.mac)'"; continue }
            if ($seenMac.ContainsKey($n) -and $seenMac[$n] -ne $did) {
                Add-Problem 'HIBA' $did "A MAC ($(Format-Mac $n)) mar a(z) $($seenMac[$n]) eszkozhoz tartozik"
            } else {
                $seenMac[$n] = $did
            }
            if ((Test-MacLocallyAdministered $n) -and -not $m.randomized) {
                Add-Problem 'FIGYELEM' $did "A MAC ($(Format-Mac $n)) 'locally administered' (valoszinuleg privat/veletlen wifi-cim, valtozhat) - ha igen, allitsd randomized:true-ra"
            }
            if ($m.interface -and $ifTypes.Count -gt 0 -and ($ifTypes -notcontains [string]$m.interface)) {
                Add-Problem 'FIGYELEM' $did "Ismeretlen csatolo-tipus (interface): $($m.interface)"
            }
        }

        if ($d.network -and $d.network.ip_static) {
            $tmp = $null
            if (-not [System.Net.IPAddress]::TryParse([string]$d.network.ip_static, [ref]$tmp)) {
                Add-Problem 'FIGYELEM' $did "Ervenytelen fix IP: $($d.network.ip_static)"
            }
        }
    }

    return @($out)
}

# ---------- parancssori mod (csak ha nem dot-source-olva) ----------

if ($MyInvocation.InvocationName -ne '.') {
    if ($Validate -or $FindMac) {
        $lista = Import-DeviceList -Path $ListPath -LocalPath $ListLocalPath -IncludeExamples:$IncludeExamples

        foreach ($w in $lista.Warnings) { Write-Host "  [!] $w" -ForegroundColor Yellow }
        Write-Host ("Forrasok: " + ($lista.Sources -join ' + ')) -ForegroundColor DarkGray
        Write-Host ("Felhasznalok: {0}   Eszkozok: {1}   Indexelt MAC-ek: {2}" -f $lista.Users.Count, $lista.Devices.Count, $lista.MacIndex.Count) -ForegroundColor Cyan

        if ($FindMac) {
            $talalat = Find-DeviceByMac -List $lista -Mac $FindMac
            if ($talalat) {
                Write-Host ("Talalat: {0} ({1}) - gazda: {2}" -f $talalat.name, $talalat.id, (Get-DeviceOwnerName -List $lista -Device $talalat)) -ForegroundColor Green
            } else {
                Write-Host ("Nincs ilyen MAC a listaban: {0}  (OUI: {1})" -f $FindMac, (Get-MacOui $FindMac)) -ForegroundColor Yellow
            }
        }

        if ($Validate) {
            $gondok = Test-DeviceList -List $lista
            if ($gondok.Count -eq 0) {
                Write-Host "Ellenorzes: minden rendben." -ForegroundColor Green
            } else {
                foreach ($g in $gondok) {
                    $szin = 'Yellow'; if ($g.Szint -eq 'HIBA') { $szin = 'Red' }
                    Write-Host ("  [{0}] {1}: {2}" -f $g.Szint, $g.Azonosito, $g.Uzenet) -ForegroundColor $szin
                }
            }
            if ($lista.Devices.Count -gt 0) {
                $lista.Devices | ForEach-Object {
                    New-Object psobject -Property @{
                        Id       = $_.id
                        Nev      = $_.name
                        Kategoria = $_.category
                        Statusz  = $_.status
                        Gazda    = (Get-DeviceOwnerName -List $lista -Device $_)
                        MAC      = ((@($_.macs) | ForEach-Object { Format-Mac ([string]$_.mac) }) -join ', ')
                        IP       = $_.network.ip_static
                    }
                } | Format-Table Id, Nev, Kategoria, Statusz, Gazda, MAC, IP -AutoSize | Out-String -Width 200 | Write-Host
            }
        }
    }
}
