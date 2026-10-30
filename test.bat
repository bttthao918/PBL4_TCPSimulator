@echo off
setlocal
cd /d "%~dp0"

call build.bat
if errorlevel 1 exit /b 1

javac -cp bin -d bin tests\tcpsim\*.java
if errorlevel 1 exit /b 1

java -cp bin tcpsim.ServerMultiClientTest
exit /b %errorlevel%
