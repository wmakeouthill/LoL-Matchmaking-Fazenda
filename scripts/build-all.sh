#!/bin/bash
echo "===================================="
echo "  LOL Matchmaking - Build All Script"
echo "===================================="
echo

echo "[1/4] Limpando arquivos de build anteriores..."
cd "$(dirname "$0")/.."
npm run clean:all
if [ $? -ne 0 ]; then
    echo "Erro ao limpar arquivos"
    exit 1
fi

echo
echo "[2/4] Instalando dependencias do frontend..."
cd frontend
npm install
if [ $? -ne 0 ]; then
    echo "Erro ao instalar dependencias do frontend"
    exit 1
fi

echo
echo "[3/4] Construindo frontend Angular..."
npm run build:prod
if [ $? -ne 0 ]; then
    echo "Erro ao construir frontend"
    exit 1
fi

cd ..
echo
echo "[4/4] Construindo backend Spring Boot com Maven..."
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "Erro ao construir backend"
    exit 1
fi

echo
echo "===================================="
echo "  Build concluido com sucesso!"
echo "===================================="
echo "Frontend: frontend/dist/browser/"
echo "Backend: target/spring-backend-0.1.0-SNAPSHOT.jar"
echo "Static files copiados para: target/classes/static/"
echo
