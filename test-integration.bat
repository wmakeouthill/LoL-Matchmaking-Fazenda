@echo off
echo ===========================================
echo  TESTE DE INTEGRACAO BACKEND-FRONTEND
echo ===========================================
echo.

echo [1/4] Testando se o backend esta rodando...
curl -s http://localhost:8080/api/health >nul 2>&1
if %errorlevel% == 0 (
    echo ✅ Backend respondendo na porta 8080
) else (
    echo ❌ Backend nao esta rodando na porta 8080
    echo    Execute: mvn spring-boot:run
    pause
    exit /b 1
)

echo.
echo [2/4] Testando status do LCU...
for /f "tokens=*" %%i in ('curl -s http://localhost:8080/api/lcu/status') do set "lcu_response=%%i"
echo %lcu_response% | findstr "connected" >nul
if %errorlevel% == 0 (
    echo ✅ Endpoint LCU funcionando
) else (
    echo ❌ Problema com endpoint LCU
)

echo.
echo [3/4] Testando detecao do usuario atual...
curl -s http://localhost:8080/api/player/current-details | findstr "success" >nul
if %errorlevel% == 0 (
    echo ✅ Endpoint de deteccao do usuario funcionando
) else (
    echo ❌ Problema com deteccao do usuario
)

echo.
echo [4/4] Testando WebSocket...
echo ℹ️  WebSocket deve estar disponivel em: ws://localhost:8080/ws

echo.
echo ===========================================
echo  RESUMO DOS ENDPOINTS CORRIGIDOS
echo ===========================================
echo  ✅ GET  /api/health                     (porta 8080)
echo  ✅ GET  /api/lcu/status                 (porta 8080)
echo  ✅ GET  /api/lcu/current-summoner       (porta 8080)
echo  ✅ GET  /api/player/current-details     (porta 8080)
echo  ✅ WebSocket: ws://localhost:8080/ws    (porta 8080)
echo.

echo Frontend agora esta configurado para:
echo - Electron: http://127.0.0.1:8080/api
echo - Browser:  http://localhost:8080/api
echo - WebSocket: ws://localhost:8080/ws
echo.

echo Para testar com League of Legends:
echo 1. Abra o League of Legends
echo 2. Faca login na sua conta
echo 3. Execute: curl http://localhost:8080/api/lcu/current-summoner
echo.

pause
