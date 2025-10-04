#!/bin/bash

# Script para aplicar migration V3 manualmente
# Adiciona coluna lcu_match_data na tabela custom_matches

echo "🔧 Aplicando Migration V3 - lcu_match_data"
echo "=========================================="
echo ""

# Verificar se o script SQL existe
if [ ! -f "scripts/apply-migration-v3-safe.sql" ]; then
    echo "❌ Arquivo apply-migration-v3-safe.sql não encontrado!"
    exit 1
fi

# Ler configurações do banco de dados do application.properties
DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-3306}
DB_NAME=${DB_NAME:-lol_matchmaking}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-root}

echo "📊 Configurações do Banco:"
echo "   Host: $DB_HOST"
echo "   Port: $DB_PORT"
echo "   Database: $DB_NAME"
echo "   User: $DB_USER"
echo ""

# Executar script SQL
echo "⏳ Executando migration..."
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" < scripts/apply-migration-v3-safe.sql

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Migration V3 aplicada com sucesso!"
    echo ""
    echo "📝 Próximos passos:"
    echo "   1. Reinicie o backend Spring Boot"
    echo "   2. Verifique os logs para confirmar que não há mais erros"
    echo "   3. Teste o botão 'Simular Última Partida Ranqueada'"
else
    echo ""
    echo "❌ Erro ao aplicar migration!"
    echo ""
    echo "💡 Dicas:"
    echo "   - Verifique se o MySQL está rodando"
    echo "   - Confirme as credenciais do banco de dados"
    echo "   - Execute manualmente: mysql -u root -p lol_matchmaking < scripts/apply-migration-v3-safe.sql"
fi
