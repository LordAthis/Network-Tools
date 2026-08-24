@echo off
color 0c
title Halozati Biztonsag Ellenorzo

REM Script konyvtara es LOG mappa
set "SCRIPT_DIR=%~dp0"
set "LOG_DIR=%SCRIPT_DIR%LOG"

REM LOG mappa letrehozasa, ha nem letezik
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

set "datum=%date:~0,10%"
set "ido=%time:~0,8%"
set "biztonsagi_jelentes=biztonsagi_jelentes_%datum%_%ido%.txt"
set "biztonsagi_jelentes=%biztonsagi_jelentes::=-%"
set "biztonsagi_jelentes=%biztonsagi_jelentes: =_%"
set "biztonsagi_jelentes=%biztonsagi_jelentes:/=-%"

REM Teljes utvonal a LOG mappaba
set "kimenet=%LOG_DIR%\%biztonsagi_jelentes%"

echo ================================================ > "%kimenet%"
echo        HALOZATI BIZTONSAGI JELENTES
echo        %datum% %ido%
echo ================================================ >> "%kimenet%"
echo. >> "%kimenet%"

echo 1. Nyitott portok ellenorzese...
echo 1. NYITOTT PORTOK: >> "%kimenet%"
echo ------------------ >> "%kimenet%"
netstat -an | findstr LISTENING >> "%kimenet%"
echo. >> "%kimenet%"

echo 2. Kimeno kapcsolatok...
echo 2. KIMENO KAPCSOLATOK: >> "%kimenet%"
echo ---------------------- >> "%kimenet%"
netstat -an | findstr ESTABLISHED >> "%kimenet%"
echo. >> "%kimenet%"

echo 3. Processzek halozati aktivitasa...
echo 3. PROCESSZEK HALOZATI AKTIVITASA: >> "%kimenet%"
echo ---------------------------------- >> "%kimenet%"
netstat -ano | findstr /v "0.0.0.0" >> "%kimenet%"
echo. >> "%kimenet%"

echo 4. Tuzfal allapot...
echo 4. TUZFAL ALLAPOT: >> "%kimenet%"
echo ------------------ >> "%kimenet%"
netsh advfirewall show allprofiles state >> "%kimenet%"
echo. >> "%kimenet%"

echo 5. Kulso IP cim...
echo 5. KULSO IP CIM: >> "%kimenet%"
echo ---------------- >> "%kimenet%"
nslookup myip.opendns.com resolver1.opendns.com >> "%kimenet%"

echo.
echo Biztonsagi ellenorzes kesz!
echo Jelentes: %kimenet%
echo.
pause
