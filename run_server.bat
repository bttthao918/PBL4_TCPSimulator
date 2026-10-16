@echo off
title PBL4 - TCP Simulator - SERVER
cd /d "%~dp0"

call build.bat
if errorlevel 1 (
    pause
    exit /b 1
)

echo.
echo ================================================================
echo   DIA CHI IP CUA MAY NAY - doc dong "IPv4 Address" ben duoi
echo   va ghi vao file client-config.txt tren may chay CLIENT
echo ================================================================
ipconfig | findstr /R /C:"IPv4"
echo ================================================================
echo.
echo Dang khoi dong SERVER tren cong 5005 ...
echo (Dong cua so nay hoac nhan Ctrl+C de dung server)
echo.

java -cp bin tcpsim.Server

echo.
echo Server da dung.
pause
