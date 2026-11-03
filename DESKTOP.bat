@echo off
REM Double-click to build and open the My Habits desktop window.
setlocal
cd /d "%~dp0"
call "%~dp0gradlew.bat" :uhabits-desktop:run --console=plain -q
if errorlevel 1 (
    echo.
    echo BUILD FAILED. Scroll up for the error.
    pause
    exit /b 1
)
