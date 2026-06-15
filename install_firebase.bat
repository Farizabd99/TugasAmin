@echo off
set PATH=C:\Program Files\nodejs;%APPDATA%\npm;%PATH%
echo Installing Firebase CLI...
npm install -g firebase-tools 2>&1
echo.
echo Checking firebase version...
firebase --version 2>&1
echo.
echo DONE
