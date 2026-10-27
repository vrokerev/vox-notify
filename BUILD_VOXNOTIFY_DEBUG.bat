@echo off
setlocal
cd /d "%~dp0"
echo ==========================================
echo  VoxNotify v1.0.5+15 - Build Debug
echo ==========================================
call flutter pub get || goto :error
call flutter analyze || goto :error
call flutter test || goto :error
call flutter build apk --debug || goto :error
if not exist releases mkdir releases
copy /Y "build\app\outputs\flutter-apk\app-debug.apk" "releases\VoxNotify-v1.0.5+15-debug.apk" >nul
if errorlevel 1 goto :error
echo.
echo LISTO: releases\VoxNotify-v1.0.5+15-debug.apk
echo.
pause
exit /b 0
:error
echo.
echo ERROR: revisa la salida anterior.
echo.
pause
exit /b 1
