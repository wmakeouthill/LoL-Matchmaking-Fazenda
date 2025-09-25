@echo off
REM scripts\deploy-gcp.bat - deploy para Google Cloud Run
echo ====================================
echo  Deploy para Google Cloud Run
echo ====================================
echo.

REM Verifica se o gcloud CLI está instalado
gcloud version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Google Cloud CLI (gcloud) nao encontrado
    echo Instale em: https://cloud.google.com/sdk/docs/install
    pause
    exit /b 1
)

REM Pede o PROJECT_ID se não estiver definido
IF "%GOOGLE_CLOUD_PROJECT%"=="" (
    set /p GOOGLE_CLOUD_PROJECT="Digite o PROJECT_ID do Google Cloud: "
)

IF "%GOOGLE_CLOUD_PROJECT%"=="" (
    echo [ERRO] PROJECT_ID e obrigatorio
    pause
    exit /b 1
)

echo Usando PROJECT_ID: %GOOGLE_CLOUD_PROJECT%
echo.

REM Vai para o diretório do projeto
cd /d "%~dp0\.."

echo [1/4] Configurando Docker para Google Cloud...
gcloud auth configure-docker
IF %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha ao configurar Docker para GCP
    pause
    exit /b 1
)

echo.
echo [2/4] Construindo imagem Docker...
docker build -f Dockerfile -t gcr.io/%GOOGLE_CLOUD_PROJECT%/lol-matchmaking:latest .
IF %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha ao construir imagem Docker
    pause
    exit /b 1
)

echo.
echo [3/4] Enviando imagem para Google Container Registry...
docker push gcr.io/%GOOGLE_CLOUD_PROJECT%/lol-matchmaking:latest
IF %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha ao enviar imagem para GCR
    pause
    exit /b 1
)

echo.
echo [4/4] Deployando para Cloud Run...
gcloud run deploy lol-matchmaking ^
    --image gcr.io/%GOOGLE_CLOUD_PROJECT%/lol-matchmaking:latest ^
    --platform managed ^
    --region us-central1 ^
    --allow-unauthenticated ^
    --set-env-vars SPRING_PROFILES_ACTIVE=gcp ^
    --memory 1Gi ^
    --cpu 1 ^
    --max-instances 10 ^
    --port 8080

IF %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha ao fazer deploy no Cloud Run
    pause
    exit /b 1
)

echo.
echo ====================================
echo  Deploy concluido com sucesso!
echo ====================================
echo.
echo A aplicacao estara disponivel na URL fornecida pelo Cloud Run.
echo.
echo Para configurar variaveis de ambiente adicionais:
echo   gcloud run services update lol-matchmaking --set-env-vars KEY=VALUE
echo.
pause
