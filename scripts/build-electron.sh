#!/bin/bash
echo "===================================="
echo "  LOL Matchmaking - Electron Build"
echo "===================================="
echo

echo "[1/3] Construindo frontend Angular..."
cd "$(dirname "$0")/.."
cd frontend
npm install
if [ $? -ne 0 ]; then
    echo "Erro ao instalar dependencias do frontend"
    exit 1
fi

npm run build:prod
if [ $? -ne 0 ]; then
    echo "Erro ao construir frontend"
    exit 1
fi

cd ..
echo
echo "[2/3] Construindo Electron TypeScript..."
npm install
if [ $? -ne 0 ]; then
    echo "Erro ao instalar dependencias do Electron"
    exit 1
fi

npm run build
if [ $? -ne 0 ]; then
    echo "Erro ao construir Electron"
    exit 1
fi

echo
echo "[3/3] Empacotando aplicacao Electron..."
npm run build:electron
if [ $? -ne 0 ]; then
    echo "Erro ao empacotar aplicacao"
    exit 1
fi

echo
echo "===================================="
echo "  Electron build concluido!"
echo "===================================="
echo "Frontend: frontend/dist/browser/"
echo "Electron: dist/"
echo "Aplicacao: dist-electron/"
echo
