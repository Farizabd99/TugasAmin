@echo off
set "ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo ----------------------------------------------------
echo Menunggu emulator terhubung...
echo ----------------------------------------------------

:loop
%ADB_PATH% devices | findstr "emulator-" > nul
if %ERRORLEVEL% NEQ 0 (
    ping 127.0.0.1 -n 4 > nul
    goto loop
)

:: Dapatkan serial emulator secara dinamis (tanpa tanda kutip dalam kurung)
for /f "tokens=1" %%i in ('%ADB_PATH% devices ^| findstr "emulator-"') do set EMULATOR_SERIAL=%%i

echo Emulator terdeteksi: %EMULATOR_SERIAL%
echo Menunggu sistem emulator selesai booting...

:boot_loop
set "BOOT_STATE="
for /f "tokens=*" %%a in ('%ADB_PATH% -s %EMULATOR_SERIAL% shell getprop sys.boot_completed 2^>nul') do set BOOT_STATE=%%a

:: Bersihkan carriage return jika ada
if defined BOOT_STATE (
    set BOOT_STATE=%BOOT_STATE:~0,1%
)

if "%BOOT_STATE%" NEQ "1" (
    ping 127.0.0.1 -n 4 > nul
    goto boot_loop
)

echo.
echo ----------------------------------------------------
echo Menginstall aplikasi ke Emulator (%EMULATOR_SERIAL%)...
echo ----------------------------------------------------
%ADB_PATH% -s %EMULATOR_SERIAL% install -r d:\Wartel\app\build\outputs\apk\debug\app-debug.apk

echo.
echo ----------------------------------------------------
echo Menjalankan aplikasi di Emulator...
echo ----------------------------------------------------
%ADB_PATH% -s %EMULATOR_SERIAL% shell am start -n com.amin.wartel/com.example.phonebilling.MainActivity

echo.
echo ----------------------------------------------------
echo [SUKSES] Aplikasi berhasil dijalankan di Emulator!
echo ----------------------------------------------------
