@echo off
REM Script para iniciar o Electron conectado ao backend containerizado

echo ========================================
echo LOL Matchmaking - Iniciando Electron
echo ========================================

REM Verificar se o backend está rodando
echo Verificando se o backend está rodando...
curl -s http://localhost:8080/actuator/health >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Backend não está rodando em localhost:8080
    echo.
    echo Para iniciar o backend, execute:
    echo   docker-compose up -d
    echo.
    echo Ou execute o build completo:
    echo   scripts\build-all.bat
    echo   docker-compose up -d
    echo.
    pause
    exit /b 1
)

echo ✅ Backend está rodando!

REM Configurar variáveis de ambiente para o Electron
set BACKEND_URL=http://localhost:8080
set NODE_ENV=production

echo.
echo 🚀 Iniciando Electron...
echo Backend URL: %BACKEND_URL%
echo.

REM Navegar para o diretório do Electron e iniciar
cd /d "%~dp0..\electron"
npm start

if %errorlevel% neq 0 (
    echo.
    echo ❌ Erro ao iniciar o Electron
    echo Certifique-se de que as dependências estão instaladas:
    echo   cd electron
    echo   npm install
    pause
    exit /b 1
)
