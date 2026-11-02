@echo off
REM Double-click to start the emulator (if no phone is connected),
REM build My Habits, install it and open it.
setlocal
set SDK=%LOCALAPPDATA%\Android\Sdk
set ADB=%SDK%\platform-tools\adb.exe
cd /d "%~dp0"

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo Starting emulator...
    start "Emulator - close this to shut down the phone" /min "%SDK%\emulator\emulator.exe" -avd PocoX7Pro
)

echo Waiting for the phone to boot...
"%ADB%" wait-for-device
:wait_boot
"%ADB%" shell getprop sys.boot_completed 2>nul | find "1" >nul
if errorlevel 1 (
    ping -n 3 127.0.0.1 >nul
    goto wait_boot
)

echo Building and installing...
call "%~dp0gradlew.bat" :uhabits-android:installDebug --console=plain -q
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Scroll up for the error.
    pause
    exit /b 1
)

"%ADB%" shell input keyevent KEYCODE_WAKEUP >nul 2>&1
"%ADB%" shell wm dismiss-keyguard >nul 2>&1
"%ADB%" shell monkey -p org.isoron.uhabits.alex -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo.
echo Done. My Habits is open on the phone.
ping -n 6 127.0.0.1 >nul
