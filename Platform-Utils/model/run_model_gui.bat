@echo off
setlocal

echo ===========================================
echo  Model GUI Launcher
echo ===========================================

cd /d "%~dp0"

echo [1/5] Checking Python...
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

echo [2/5] Checking dependencies...
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

%PYTHON_EXE% -c "import playwright" >nul 2>&1
if errorlevel 1 (
  echo [HINT ] Playwright not installed. URL+browser login mode needs:
  echo        %PYTHON_EXE% -m pip install playwright
  echo        playwright install chromium
) else (
  %PYTHON_EXE% -c "from playwright.sync_api import sync_playwright" >nul 2>&1
  if errorlevel 1 (
    echo [HINT ] Run once: playwright install chromium
  )
)

echo [3/4] Checking local config...
if not exist "quota.local.env" (
  echo [HINT ] quota.local.env not found.
  echo        copy quota.env.example quota.local.env
  echo        Edit quota.local.env, then restart GUI to load your settings.
) else (
  echo [OK   ] Using quota.local.env for form defaults.
)

echo [4/5] Starting GUI...
%PYTHON_EXE% GUI\gui.py
if errorlevel 1 (
  echo [ERROR] GUI startup failed.
  echo [NEXT ] Run manually: %PYTHON_EXE% GUI\gui.py
  pause
  exit /b 1
)

echo [5/5] GUI closed.
endlocal
