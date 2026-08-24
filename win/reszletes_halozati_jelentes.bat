@echo off
setlocal enabledelayedexpansion

REM Szines kimenet beallitasa
color 0b
title Reszletes Halozati Jelentes

REM Script konyvtara es LOG mappa
set "SCRIPT_DIR=%~dp0"
set "LOG_DIR=%SCRIPT_DIR%LOG"

REM LOG mappa letrehozasa, ha nem letezik
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM Fajlnev generalasa idobelyeggel
for /f %%i in ('powershell -command "Get-Date -format 'yyyyMMdd_HHmmss'"') do set timestamp=%%i
set "jelentesfajl=%LOG_DIR%\halozati_jelentes_%timestamp%.txt"

echo.
echo ================================================
echo         RESZLETES HALOZATI JELENTES
echo ================================================
echo.
echo Jelentes keszitese: %jelentesfajl%
echo.

REM Fejlec irasa
echo ================================================ > "%jelentesfajl%"
echo         RESZLETES HALOZATI JELENTES >> "%jelentesfajl%"
echo         Keszitva: %date% %time% >> "%jelentesfajl%"
echo ================================================ >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 1. Rendszerinformaciok
echo 1. Rendszer informaciok gyujtese...
echo 1. RENDSZER INFORMACIOK: >> "%jelentesfajl%"
echo ------------------------ >> "%jelentesfajl%"
systeminfo | findstr /i "Host\|OS\|System\|Domain" >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 2. Halozati adapterek
echo 2. Halozati adapterek ellenorzese...
echo 2. HALOZATI ADAPTEREK: >> "%jelentesfajl%"
echo ---------------------- >> "%jelentesfajl%"
wmic nic where "NetEnabled=true" get Name,MACAddress,Speed /format:table >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 3. IP konfiguraciok
echo 3. IP konfiguraciot gyujtese...
echo 3. IP KONFIGURACIO: >> "%jelentesfajl%"
echo ------------------- >> "%jelentesfajl%"
ipconfig /all >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 4. DNS cache
echo 4. DNS cache ellenorzese...
echo 4. DNS CACHE: >> "%jelentesfajl%"
echo ------------- >> "%jelentesfajl%"
ipconfig /displaydns | findstr /i "Record\|Data" >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 5. Routing tabla
echo 5. Routing tabla lekerese...
echo 5. ROUTING TABLA: >> "%jelentesfajl%"
echo ----------------- >> "%jelentesfajl%"
route print >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 6. Aktiv kapcsolatok
echo 6. Aktiv kapcsolatok elemzese...
echo 6. AKTIV KAPCSOLATOK: >> "%jelentesfajl%"
echo -------------------- >> "%jelentesfajl%"
netstat -ano >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 7. WiFi profilok (ha van)
echo 7. WiFi profilok lekerese...
echo 7. WIFI PROFILOK: >> "%jelentesfajl%"
echo ----------------- >> "%jelentesfajl%"
netsh wlan show profiles >> "%jelentesfajl%"
echo. >> "%jelentesfajl%"

REM 8. Halozati eszkozok ping tesztje
echo 8. Halozati eszkozok ping tesztje...
echo 8. PING TESZT EREDMENYEK: >> "%jelentesfajl%"
echo ------------------------- >> "%jelentesfajl%"

REM IP tartomany felismerese es ping teszt
for /f "tokens=2" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    set "myip=%%a"
    set "myip=!myip: =!"
    if not "!myip!"=="" (
        for /f "tokens=1,2,3 delims=." %%b in ("!myip!") do (
            set "network=%%b.%%c.%%d"
            echo Ping teszt a !network!.x tartomanyon...
            for /l %%i in (1,1,254) do (
                ping -n 1 -w 50 !network!.%%i >nul && echo !network!.%%i - ELERHETO >> "%jelentesfajl%"
            )
        )
    )
)

echo. >> "%jelentesfajl%"
echo ================================================ >> "%jelentesfajl%"
echo Jelentes vege - %date% %time% >> "%jelentesfajl%"
echo ================================================ >> "%jelentesfajl%"

echo.
echo ================================================
echo Jelentes kesz!
echo Fajl: %jelentesfajl%
echo ================================================
echo.
choice /c YN /m "Meg szeretned nyitni a jelentest"
if errorlevel 2 goto end
if errorlevel 1 start notepad "%jelentesfajl%"
:end
pause
