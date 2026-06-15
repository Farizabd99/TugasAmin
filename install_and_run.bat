@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo ----------------------------------------------------
echo 1. Memulai build dan install aplikasi ke HP...
echo ----------------------------------------------------
call gradlew.bat installDebug --no-daemon 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Gagal melakukan build atau install ke HP.
    exit /b %ERRORLEVEL%
)

echo.
echo ----------------------------------------------------
echo 2. Menjalankan aplikasi di HP secara otomatis...
echo ----------------------------------------------------
"%ADB_PATH%" shell am start -n com.amin.wartel/com.example.phonebilling.MainActivity

if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Gagal menjalankan otomatis. Mencoba via monkey...
    "%ADB_PATH%" shell monkey -p com.amin.wartel 1
)

echo.
echo ----------------------------------------------------
echo [SUKSES] Aplikasi berhasil diinstall dan dijalankan!
echo ----------------------------------------------------
