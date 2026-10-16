@echo off
REM ============================================================
REM build.bat - Bien dich toan bo du an.
REM Script nay duoc goi tu ben trong run_server.bat va run_client.bat,
REM ban khong can chay file nay truc tiep (nhung chay cung khong sao).
REM ============================================================

if not exist bin mkdir bin

echo Dang bien dich chuong trinh...
javac -d bin src\tcpsim\*.java src\tcpsim\gui\*.java

if errorlevel 1 (
    echo.
    echo ========================================================
    echo   LOI BIEN DICH! Xem chi tiet loi o phia tren.
    echo   Kiem tra lai cac file trong src\tcpsim va src\tcpsim\gui
    echo ========================================================
    exit /b 1
)

echo Bien dich thanh cong.
exit /b 0
