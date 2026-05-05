@echo off
setlocal

echo ===========================================
echo  Model GUI Launcher
echo ===========================================

cd /d "%~dp0"

echo [1/4] Checking Python...
set "PYTHON_EXE=python"
%PYTHON_EXE% --version >nul 2>&1
if errorlevel 1 (
  set "PYTHON_EXE=py"
  %PYTHON_EXE% --version >nul 2>&1
  if errorlevel 1 (
    echo [ERROR] Python is not available in PATH.
    echo [NEXT ] Install Python 3.10+ and retry.
    pause
    exit /b 1
  )
)

echo [2/4] Checking dependencies...
%PYTHON_EXE% -c "import customtkinter,requests,bs4,ruamel.yaml" >nul 2>&1
if errorlevel 1 (
  echo [WARN ] Missing dependencies. Installing requirements...
  %PYTHON_EXE% -m pip install -r requirements.txt
  if errorlevel 1 (
    echo [ERROR] Dependency install failed.
    echo [NEXT ] Run manually: %PYTHON_EXE% -m pip install -r requirements.txt
    pause
    exit /b 1
  )
)

echo [3/4] Starting GUI...
%PYTHON_EXE% GUI\gui.py
if errorlevel 1 (
  echo [ERROR] GUI startup failed.
  echo [NEXT ] Run manually: %PYTHON_EXE% GUI\gui.py
  pause
  exit /b 1
)

echo [4/4] GUI closed.
endlocal
