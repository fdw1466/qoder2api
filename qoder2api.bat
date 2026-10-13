@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

set "JAVA_HOME=D:\Java\openjdk-21.0.4.7"
set "PROJECT_DIR=%~dp0"

echo ========================================
echo   Qoder2API Build ^& Launch Script
echo ========================================
echo.

:: Check JDK
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found at: %JAVA_HOME%
    echo Please check the path and try again.
    pause
    exit /b 1
)

echo [1/3] Using JDK: %JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr "version"
echo.

:: Build
echo [2/3] Building project with Maven...
cd /d "%PROJECT_DIR%"
call mvnw.cmd clean package -DskipTests
if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)
echo [OK] Build complete.
echo.

:: Launch
echo [3/3] Starting Qoder2API server...
echo.
echo   Config: Edit config.yaml in project root
echo     - pat        : Qoder Personal Access Token
echo     - app.host   : Local bind address (default: 127.0.0.1)
echo     - app.port   : Local server port (default: 8080)
echo     - qoder.base_prompt : Custom base prompt file (optional)
echo.
echo   Build output: target\qoder-client-0.1.0.jar
echo.
echo Press Ctrl+C to stop.
echo ========================================
echo.

"%JAVA_HOME%\bin\java.exe" -Xmx512m -jar target\qoder-client-0.1.0.jar

pause
