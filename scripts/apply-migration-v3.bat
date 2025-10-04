@echo off
REM Script para aplicar migration V3 manualmente (Windows)
REM Adiciona coluna lcu_match_data na tabela custom_matches

echo ======================================
echo Aplicando Migration V3 - lcu_match_data
echo ======================================
echo.

REM Verificar se o script SQL existe
if not exist "scripts\apply-migration-v3-safe.sql" (
    echo ERRO: Arquivo apply-migration-v3-safe.sql nao encontrado!
    exit /b 1
)

REM Configuracoes do banco de dados (ajuste se necessario)
set DB_HOST=localhost
set DB_PORT=3306
set DB_NAME=lol_matchmaking
set DB_USER=root

echo Configuracoes do Banco:
echo    Host: %DB_HOST%
echo    Port: %DB_PORT%
echo    Database: %DB_NAME%
echo    User: %DB_USER%
echo.

REM Solicitar senha
set /p DB_PASS="Digite a senha do MySQL (root): "
echo.

REM Executar script SQL
echo Executando migration...
mysql -h %DB_HOST% -P %DB_PORT% -u %DB_USER% -p%DB_PASS% %DB_NAME% < scripts\apply-migration-v3-safe.sql

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo Migration V3 aplicada com sucesso!
    echo ========================================
    echo.
    echo Proximos passos:
    echo    1. Reinicie o backend Spring Boot
    echo    2. Verifique os logs para confirmar que nao ha mais erros
    echo    3. Teste o botao 'Simular Ultima Partida Ranqueada'
    echo.
) else (
    echo.
    echo ========================================
    echo ERRO ao aplicar migration!
    echo ========================================
    echo.
    echo Dicas:
    echo    - Verifique se o MySQL esta rodando
    echo    - Confirme as credenciais do banco de dados
    echo    - Execute manualmente: mysql -u root -p lol_matchmaking ^< scripts\apply-migration-v3-safe.sql
    echo.
)

pause
