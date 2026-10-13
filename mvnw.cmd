@REM Maven Wrapper - Script-only mode (no JAR needed)
@REM Downloads Maven on first run, then reuses it.

@echo off
setlocal EnableDelayedExpansion

set "WRAPPER_DIR=%~dp0"
set "WRAPPER_DIR=%WRAPPER_DIR:~0,-1%"

@REM Use JAVA_HOME from environment if set, otherwise try to find a JDK
if not defined JAVA_HOME (
    for /f "tokens=*" %%i in ('where java 2^>nul') do (
        set "JAVA_HOME=%%~dpi.."
        goto :foundJava
    )
    echo [mvnw] WARNING: JAVA_HOME not set and no JDK found in PATH. Maven may fail to compile.
    goto :skipJavaCheck
)
:foundJava
set "MAVEN_OPTS=-Djava.home=%JAVA_HOME%"
:skipJavaCheck

set "MVN_VERSION=3.9.9"
set "MVN_BASE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MVN_VERSION%-bin"
set "MVN_HOME=%MVN_BASE%\apache-maven-%MVN_VERSION%"

if exist "%MVN_HOME%\bin\mvn.cmd" goto runMvn

echo [mvnw] Maven %MVN_VERSION% not found, downloading...
if not exist "%MVN_BASE%" mkdir "%MVN_BASE%"

set "ZIP=%MVN_BASE%\apache-maven-%MVN_VERSION%-bin.zip"
if not exist "%ZIP%" (
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION%-bin.zip' -OutFile '%ZIP%'"
)

powershell -Command "Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%MVN_BASE%'"

:runMvn
set "JAVA_HOME=%JAVA_HOME%"
"%MVN_HOME%\bin\mvn.cmd" -f "%WRAPPER_DIR%\pom.xml" %*
