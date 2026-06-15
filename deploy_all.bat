@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo ----------------------------------------------------
echo 1. Memulai build dan install aplikasi ke HP Xiaomi dan Emulator...
echo ----------------------------------------------------
call gradlew.bat installDebug --no-daemon 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Gagal melakukan build atau install.
    exit /b %ERRORLEVEL%
)

echo.
echo ----------------------------------------------------
echo 2. Menjalankan aplikasi di HP Xiaomi (lfin4xqctglbhu75)...
echo ----------------------------------------------------
"%ADB_PATH%" -s lfin4xqctglbhu75 shell am start -n com.amin.wartel/com.example.phonebilling.MainActivity

echo.
echo ----------------------------------------------------
echo 3. Menjalankan aplikasi di Emulator (emulator-5554)...
echo ----------------------------------------------------
"%ADB_PATH%" -s emulator-5554 shell am start -n com.amin.wartel/com.example.phonebilling.MainActivity

echo.
echo ----------------------------------------------------
echo [SUKSES] Aplikasi berhasil dideploy ke kedua perangkat!
echo ----------------------------------------------------
