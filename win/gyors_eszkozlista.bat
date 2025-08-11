@echo off
title Gyors Halozati Eszközlista
color 0a
set "datum=%date:~0,10%"
set "ido=%time:~0,8%"
set "fajlnev=eszközlista_%datum%_%ido%.txt"
set "fajlnev=%fajlnev::=-%"
set "fajlnev=%fajlnev: =_%"
set "fajlnev=%fajlnev:/=-%"
echo ============================================ > %fajlnev%
echo     GYORS HALOZATI ESZKÖZLISTA
echo     Letrehozva: %datum% %ido%
echo ============================================ >> %fajlnev%
echo. >> %fajlnev%
echo Eszkozok keresese...
echo AKTIV ESZKOZOK: >> %fajlnev%
echo --------------- >> %fajlnev%
arp -a | findstr /v "Interface" | findstr /v "224.0.0" | findstr /v "239.255.255" >> %fajlnev%
echo. >> %fajlnev%
echo HALOZATI BEALLITASOK: >> %fajlnev%
echo -------------------- >> %fajlnev%
ipconfig | findstr /i "IPv4\|Subnet\|Gateway" >> %fajlnev%
echo.
echo Lista kesz! Mentve: %fajlnev%
start notepad %fajlnev%
pause
