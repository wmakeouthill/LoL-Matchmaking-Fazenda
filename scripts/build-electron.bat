@echo off
echo ====================================
echo  LOL Matchmaking - Electron Build
echo ====================================
echo.

echo [1/3] Construindo frontend Angular...
cd /d "%~dp0\.."
cd frontend
call npm install
if %ERRORLEVEL% neq 0 (
    echo Erro ao instalar dependencias do frontend
    exit /b %ERRORLEVEL%
)

call npm run build:prod
if %ERRORLEVEL% neq 0 (
    echo Erro ao construir frontend
    exit /b %ERRORLEVEL%
)

cd ..
echo.
echo [2/3] Construindo Electron TypeScript...
call npm install
if %ERRORLEVEL% neq 0 (
    echo Erro ao instalar dependencias do Electron
    exit /b %ERRORLEVEL%
)

call npm run build
if %ERRORLEVEL% neq 0 (
    echo Erro ao construir Electron
    exit /b %ERRORLEVEL%
)

echo.
echo [3/3] Empacotando aplicacao Electron...
call npm run build:electron
if %ERRORLEVEL% neq 0 (
    echo Erro ao empacotar aplicacao
    exit /b %ERRORLEVEL%
)

echo.
echo ====================================
echo  Electron build concluido!
echo ====================================
echo Frontend: frontend/dist/browser/
echo Electron: dist/
echo Aplicacao: dist-electron/
echo.
