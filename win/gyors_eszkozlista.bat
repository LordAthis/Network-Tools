@echo off
title Gyors Halozati Eszkozlista
color 0a

REM Script konyvtara es LOG mappa
set "SCRIPT_DIR=%~dp0"
set "LOG_DIR=%SCRIPT_DIR%LOG"

REM LOG mappa letrehozasa, ha nem letezik
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM Fajlnev generalasa
set "datum=%date:~0,10%"
set "ido=%time:~0,8%"
set "fajlnev=eszkozlista_%datum%_%ido%.txt"
set "fajlnev=%fajlnev::=-%"
set "fajlnev=%fajlnev: =_%"
set "fajlnev=%fajlnev:/=-%"

REM Teljes utvonal a LOG mappaba
set "kimenet=%LOG_DIR%\%fajlnev%"

echo ============================================ > "%kimenet%"
echo     GYORS HALOZATI ESZKOZLISTA
echo     Letrehozva: %datum% %ido%
echo ============================================ >> "%kimenet%"
echo. >> "%kimenet%"

echo Eszkozok keresese...
echo AKTIV ESZKOZOK: >> "%kimenet%"
echo --------------- >> "%kimenet%"

arp -a | findstr /v "Interface" | findstr /v "224.0.0" | findstr /v "239.255.255" >> "%kimenet%"

echo. >> "%kimenet%"
echo HALOZATI BEALLITASOK: >> "%kimenet%"
echo -------------------- >> "%kimenet%"
ipconfig | findstr /i "IPv4\|Subnet\|Gateway" >> "%kimenet%"

echo.
echo Lista kesz! Mentve: %kimenet%
start notepad "%kimenet%"
pause
