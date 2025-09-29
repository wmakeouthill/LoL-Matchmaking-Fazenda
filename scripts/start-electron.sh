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
# try to source a gateway token file if present in repo root
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$PROJECT_ROOT/.gateway_token" ]; then
    export BACKEND_GATEWAY_TOKEN=$(cat "$PROJECT_ROOT/.gateway_token" | tr -d '\r\n' | tr -d ' ')
    echo "Loaded BACKEND_GATEWAY_TOKEN from .gateway_token"
elif [ -f "$PROJECT_ROOT/gateway.token" ]; then
    export BACKEND_GATEWAY_TOKEN=$(cat "$PROJECT_ROOT/gateway.token" | tr -d '\r\n' | tr -d ' ')
    echo "Loaded BACKEND_GATEWAY_TOKEN from gateway.token"
fi

# default backend URL and env
export BACKEND_URL=http://localhost:8080
export NODE_ENV=production

echo ""
echo "🚀 Iniciando Electron..."
echo "Backend URL: $BACKEND_URL"
echo ""

# If backend not running, optionally start a local WS test server (if marker file exists)
if ! curl -s "$BACKEND_URL/actuator/health" > /dev/null 2>&1; then
    if [ -f "$PROJECT_ROOT/.start-ws-test" ]; then
        echo "Backend not found, starting local WS test server as fallback"
        # start ws test server in background
        node "$PROJECT_ROOT/scripts/ws-gateway-test.js" &
        # point BACKEND_URL to the test server so electron will connect to it
        export BACKEND_URL=http://localhost:8090
        echo "BACKEND_URL set to $BACKEND_URL (ws test server)"
    else
        # fall back to original behaviour: present instructions and exit
        cd "$(dirname "$0")/.."
        echo "Backend não está rodando em $BACKEND_URL"
        echo "Para iniciar o backend, execute:" 
        echo "  docker-compose up -d"
        echo "Ou execute o build completo:" 
        echo "  ./scripts/build-all.sh"
        echo "  docker-compose up -d"
        echo ""
        read -p "Pressione Enter para continuar..."
        exit 1
    fi
fi

# Run electron from project root so the root package.json (where deps are) is used
cd "$PROJECT_ROOT"
echo "Starting Electron (using project root package.json)"
npm run electron

if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Erro ao iniciar o Electron"
    echo "Certifique-se de que as dependências estão instaladas:"
    echo "  cd electron"
    echo "  npm install"
    read -p "Pressione Enter para continuar..."
    exit 1
fi
