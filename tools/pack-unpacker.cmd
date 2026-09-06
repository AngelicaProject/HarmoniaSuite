@echo off
rem Packs our XivExdUnpacker build (with ConsoleGuard fix) for translators.
rem Usage: pack-unpacker.cmd [path-to-XivExdUnpacker-repo]
setlocal
set REPO=%~1
if "%REPO%"=="" set REPO=D:\xivrepos\XivExdUnpacker
cd /d "%REPO%" || exit /b 1
dotnet build XivExdUnpacker.csproj -c Release --nologo -v q || exit /b 1
tar -a -c -f "%~dp0unpacker-win-x64.zip" --exclude=config.yml -C "%REPO%\bin\Release\net10.0" .
echo packed: %~dp0unpacker-win-x64.zip
