@echo off
title PBL4 - TCP Simulator - CLIENT
cd /d "%~dp0"

call build.bat
if errorlevel 1 (
    pause
    exit /b 1
)

if not exist client-config.txt (
    echo.
    echo ================================================================
    echo   CHUA CO file client-config.txt - dang tao file mac dinh...
    echo ================================================================
    (
        echo # File cau hinh Client - CHI SUA DONG server_ip BEN DUOI
        echo # Dien dia chi IP cua may dang chay Server ^(xem o cua so Server^)
        echo server_ip=127.0.0.1
        echo loss_percent=0
        echo delay_min_ms=0
        echo delay_max_ms=0
    ) > client-config.txt
    echo Da tao file client-config.txt.
    echo Hay MO file nay bang Notepad, sua dong "server_ip=..." thanh
    echo dia chi IP that cua may Server, luu lai, roi chay lai file nay.
    echo.
    notepad client-config.txt
    pause
    exit /b 0
)

echo.
echo Dang doc cau hinh tu client-config.txt:
type client-config.txt
echo.
echo Dang khoi dong CLIENT va mo cua so truc quan hoa...
echo.

java -cp bin tcpsim.Client

echo.
echo Client da ket thuc.
pause
