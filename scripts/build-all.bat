@echo off
echo ====================================
echo  LOL Matchmaking - Build All Script
echo ====================================
echo.

echo [1/4] Limpando arquivos de build anteriores...
cd /d "%~dp0\.."
call npm run clean:all
if %ERRORLEVEL% neq 0 (
    echo Erro ao limpar arquivos
    exit /b %ERRORLEVEL%
)

echo.
echo [2/4] Instalando dependencias do frontend...
cd frontend
call npm install
if %ERRORLEVEL% neq 0 (
    echo Erro ao instalar dependencias do frontend
    exit /b %ERRORLEVEL%
)

echo.
echo [3/4] Construindo frontend Angular...
call npm run build:prod
if %ERRORLEVEL% neq 0 (
    echo Erro ao construir frontend
    exit /b %ERRORLEVEL%
)

cd ..
echo.
echo [4/4] Construindo backend Spring Boot com Maven...
call mvn clean package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo Erro ao construir backend
    exit /b %ERRORLEVEL%
)

echo.
echo ====================================
echo  Build concluido com sucesso!
echo ====================================
echo Frontend: frontend/dist/browser/
echo Backend: target/spring-backend-0.1.0-SNAPSHOT.jar
echo Static files copiados para: target/classes/static/
echo.
