@echo off
rem Harmonia Suite launcher.
rem Optional machine translation: set GEMINI_API_KEY=... before the java line.
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo [harmonia] java not found - install Temurin JDK 21+:
  echo [harmonia] https://adoptium.net/temurin/releases/?version=21
  pause
  exit /b 1
)
echo [harmonia] UI: http://127.0.0.1:8765
java -jar harmonia-suite.jar
echo.
echo [harmonia] stopped.
pause
