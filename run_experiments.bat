@echo off
setlocal
cd /d "%~dp0"

call build.bat
if errorlevel 1 exit /b 1

echo Chay cac phien thi nghiem (mac dinh 90; co the loc bang tham so)...
java -cp bin tcpsim.ExperimentRunner %*
if errorlevel 1 exit /b 1

python plot_results.py
exit /b %errorlevel%
