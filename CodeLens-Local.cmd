@echo off
setlocal
cd /d "%~dp0"
where node.exe >nul 2>nul
if errorlevel 1 (
  echo Node.js 24 or newer is required. Install it and try again.
  pause
  exit /b 1
)
where java.exe >nul 2>nul
if errorlevel 1 (
  echo Java 17 or newer is required. Install it and try again.
  pause
  exit /b 1
)
where git.exe >nul 2>nul
if errorlevel 1 (
  echo Git is required. Install it and try again.
  pause
  exit /b 1
)
if not exist "node_modules\tsx\package.json" (
  echo Installing locked CodeLens dependencies. Internet is needed for this step.
  call npm.cmd ci --ignore-scripts
  if errorlevel 1 (
    echo Dependency installation failed. Check the connection and retry.
    pause
    exit /b 1
  )
)
node --import tsx scripts/start-local-review.ts --open
set "CODELENS_LAUNCH_EXIT=%errorlevel%"
pause
exit /b %CODELENS_LAUNCH_EXIT%
