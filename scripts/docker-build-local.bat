@echo off
REM scripts\docker-build-local.bat - build local docker image usando Dockerfile.local
echo ====================================
echo  Docker Build Local
echo ====================================
echo.

REM Vai para o diretório do projeto
cd /d "%~dp0\.."

REM Verifica se o JAR existe
IF NOT EXIST "target\*.jar" (
    echo [ERRO] JAR nao encontrado em target\
    echo Execute primeiro: npm run build:all ou mvn clean package
    echo.
    pause
    exit /b 1
)

echo [1/2] Construindo imagem Docker local...
docker build -f Dockerfile.local -t lol-matchmaking:local .
IF %ERRORLEVEL% NEQ 0 (
    echo [ERRO] Falha ao construir imagem Docker
    pause
    exit /b 1
)

echo.
echo [2/2] Imagem construida com sucesso!
echo.
echo Para executar:
echo   docker run -p 8080:8080 --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local
echo.
echo Para executar em background:
echo   docker run -d -p 8080:8080 --name lol-matchmaking-local --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local
echo.
pause
