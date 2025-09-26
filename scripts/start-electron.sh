#!/bin/bash
# Script para iniciar o Electron conectado ao backend containerizado

echo "========================================"
echo "LOL Matchmaking - Iniciando Electron"
echo "========================================"

# Verificar se o backend está rodando
echo "Verificando se o backend está rodando..."
if ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "❌ Backend não está rodando em localhost:8080"
    echo ""
    echo "Para iniciar o backend, execute:"
    echo "  docker-compose up -d"
    echo ""
    echo "Ou execute o build completo:"
    echo "  ./scripts/build-all.sh"
    echo "  docker-compose up -d"
    echo ""
    read -p "Pressione Enter para continuar..."
    exit 1
fi

echo "✅ Backend está rodando!"

# Configurar variáveis de ambiente para o Electron
export BACKEND_URL=http://localhost:8080
export NODE_ENV=production

echo ""
echo "🚀 Iniciando Electron..."
echo "Backend URL: $BACKEND_URL"
echo ""

# Navegar para o diretório do Electron e iniciar
cd "$(dirname "$0")/../electron"
npm start

if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Erro ao iniciar o Electron"
    echo "Certifique-se de que as dependências estão instaladas:"
    echo "  cd electron"
    echo "  npm install"
    read -p "Pressione Enter para continuar..."
    exit 1
fi
