@echo off
color 0c
title Halozati Biztonsag Ellenorzo
set "datum=%date:~0,10%"
set "ido=%time:~0,8%"
set "biztonsagi_jelentes=biztonsagi_jelentes_%datum%_%ido%.txt"
set "biztonsagi_jelentes=%biztonsagi_jelentes::=-%"
set "biztonsagi_jelentes=%biztonsagi_jelentes: =_%"
set "biztonsagi_jelentes=%biztonsagi_jelentes:/=-%"
echo ================================================ > %biztonsagi_jelentes%
echo        HALOZATI BIZTONSAGI JELENTES
echo        %datum% %ido%
echo ================================================ >> %biztonsagi_jelentes%
echo. >> %biztonsagi_jelentes%
echo 1. Nyitott portok ellenorzese...
echo 1. NYITOTT PORTOK: >> %biztonsagi_jelentes%
echo ------------------ >> %biztonsagi_jelentes%
netstat -an | findstr LISTENING >> %biztonsagi_jelentes%
echo. >> %biztonsagi_jelentes%
echo 2. Kimenő kapcsolatok...
echo 2. KIMENO KAPCSOLATOK: >> %biztonsagi_jelentes%
echo ---------------------- >> %biztonsagi_jelentes%
netstat -an | findstr ESTABLISHED >> %biztonsagi_jelentes%
echo. >> %biztonsagi_jelentes%
echo 3. Processzek halozati aktivitasa...
echo 3. PROCESSZEK HALOZATI AKTIVITASA: >> %biztonsagi_jelentes%
echo ---------------------------------- >> %biztonsagi_jelentes%
netstat -ano | findstr /v "0.0.0.0" >> %biztonsagi_jelentes%
echo. >> %biztonsagi_jelentes%
echo 4. Tuzfal allapot...
echo 4. TUZFAL ALLAPOT: >> %biztonsagi_jelentes%
echo ------------------ >> %biztonsagi_jelentes%
netsh advfirewall show allprofiles state >> %biztonsagi_jelentes%
echo. >> %biztonsagi_jelentes%
echo 5. Kulso IP cim...
echo 5. KULSO IP CIM: >> %biztonsagi_jelentes%
echo ---------------- >> %biztonsagi_jelentes%
nslookup myip.opendns.com resolver1.opendns.com >> %biztonsagi_jelentes%
echo.
echo Biztonsagi ellenorzes kesz!
echo Jelentes: %biztonsagi_jelentes%
echo.
pause
