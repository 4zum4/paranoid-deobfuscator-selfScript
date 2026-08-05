@echo off
setlocal EnableExtensions EnableDelayedExpansion

if "%~1"=="" goto :usage
if /I "%~1"=="--help" goto :help
if /I "%~1"=="-h" goto :help

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "REPO_ROOT=%%~fI"
set "TOOL=%REPO_ROOT%\src\ParanoidSourceDeobfuscator.java"
set "JAVA_EXE="

where java.exe >nul 2>nul
if not errorlevel 1 (
  for /f "delims=" %%J in ('where java.exe') do (
    if not defined JAVA_EXE set "JAVA_EXE=%%J"
  )
)

if not defined JAVA_EXE if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVA_EXE (
  for %%J in (
    "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
    "C:\Program Files\Android\Android Studio\jre\bin\java.exe"
    "C:\Program Files\Java\jdk-21\bin\java.exe"
    "C:\Program Files\Java\jdk-17\bin\java.exe"
  ) do (
    if exist %%~J if not defined JAVA_EXE set "JAVA_EXE=%%~J"
  )
)

if not defined JAVA_EXE (
  echo [-] java.exe was not found. Install JDK/JBR 17 or newer, or add Java to PATH.
  exit /b 1
)

if not exist "%TOOL%" (
  echo [-] Tool source was not found:
  echo     %TOOL%
  exit /b 1
)

echo [+] Java: "%JAVA_EXE%"
echo [+] Tool: "%TOOL%"
echo.

"%JAVA_EXE%" "%TOOL%" %*
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
  echo [-] The tool exited with code %RC%.
) else (
  echo [+] Completed successfully.
)
exit /b %RC%

:help
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "REPO_ROOT=%%~fI"
set "TOOL=%REPO_ROOT%\src\ParanoidSourceDeobfuscator.java"
java "%TOOL%" --help
exit /b %ERRORLEVEL%

:usage
echo Usage:
echo   %~nx0 ^<input-file-or-dir^> ^<output-file-or-dir^> [support-file-or-dir ...]
echo.
echo Example:
echo   %~nx0 .\app_jadx .\patched_source .\secondary_dex_jadx
exit /b 2
