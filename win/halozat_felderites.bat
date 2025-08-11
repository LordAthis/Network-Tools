@echo off
echo ================================================
echo        HALOZATI ESZKOZOK FELDERITESE
echo ================================================
echo.
REM Aktuális dátum és idő lekérése
for /f "tokens=2 delims==" %%a in ('wmic OS Get localdatetime /value') do set "dt=%%a"
set "YY=%dt:~2,2%" & set "YYYY=%dt:~0,4%" & set "MM=%dt:~4,2%" & set "DD=%dt:~6,2%"
set "HH=%dt:~8,2%" & set "Min=%dt:~10,2%" & set "Sec=%dt:~12,2%"
set "timestamp=%YYYY%-%MM%-%DD%_%HH%-%Min%-%Sec%"
REM Eredmény fájl neve
set "eredmeny=halozati_felderites_%timestamp%.txt"
echo Halozati felderites eredmenye - %timestamp% > %eredmeny%
echo ================================================ >> %eredmeny%
echo. >> %eredmeny%
echo 1. ARP tabla ellenorzese...
echo 1. ARP TABLA: >> %eredmeny%
arp -a >> %eredmeny%
echo. >> %eredmeny%
echo 2. Halozati konfiguració...
echo 2. HALOZATI KONFIGURACIO: >> %eredmeny%
ipconfig /all >> %eredmeny%
echo. >> %eredmeny%
echo 3. Aktiv halozati kapcsolatok...
echo 3. AKTIV KAPCSOLATOK: >> %eredmeny%
netstat -an >> %eredmeny%
echo. >> %eredmeny%
echo 4. Ping teszt a helyi halozaton...
echo 4. PING TESZT EREDMENYEK: >> %eredmeny%
REM IP tartomány automatikus felismerése
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    for /f "tokens=1,2,3,4 delims=." %%b in ("%%a") do (
        set "ip1=%%b"
        set "ip2=%%c"
        set "ip3=%%d"
    )
)
REM Ping teszt a tartományon
for /l %%i in (1,1,254) do (
    ping -n 1 -w 100 %ip1%.%ip2%.%ip3%.%%i | find "TTL" >> %eredmeny%
)
echo.
echo ================================================
echo Felderites befejezve!
echo Eredmenyek mentve: %eredmeny%
echo ================================================
pause
