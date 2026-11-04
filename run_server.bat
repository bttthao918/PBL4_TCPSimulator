@echo off
setlocal EnableExtensions EnableDelayedExpansion
title PBL4 - TCP Simulator - SERVER
cd /d "%~dp0"

call build.bat
if errorlevel 1 (
    pause
    exit /b 1
)

echo.
echo ================================================================
echo   IP SERVER: chi dung IPv4 cua adapter Wi-Fi/LAN dang ket noi
echo   Khong dung IP VMware/Virtual Ethernet (192.168.65.x, 192.168.159.x)
echo ================================================================
ipconfig
echo ================================================================
echo.
echo Dang khoi dong SERVER tren cong 5005 ...
echo Server ho tro nhieu CLIENT ket noi dong thoi.
echo Tren moi may CLIENT: dien IP Server vao client-config.txt roi chay run_client.bat.
echo Neu client van timeout: cho phep Java/UDP Inbound port 5005 tren Windows Firewall cua may Server.
echo Dang tao rule Firewall cho UDP 5005...
netsh advfirewall firewall add rule name="PBL4 Server UDP 5005" dir=in action=allow protocol=UDP localport=5005 profile=any >nul 2>&1
if errorlevel 1 echo CANH BAO: Khong tao duoc rule Firewall. Hay chay run_server.bat bang Run as administrator.
echo (Dong cua so nay hoac nhan Ctrl+C de dung server)
echo.

java -cp bin tcpsim.Server

echo.
echo Server da dung.
pause
