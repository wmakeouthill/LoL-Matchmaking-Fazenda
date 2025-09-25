#!/bin/bash
# scripts/docker-build-full.sh - build completa com frontend e backend
echo "===================================="
echo "  Docker Build Full (Frontend + Backend)"
echo "===================================="
echo

# Vai para o diretório do projeto
cd "$(dirname "$0")/.."

echo "[1/2] Construindo imagem Docker completa (multi-stage)..."
echo "Isso incluirá build do frontend Angular + backend Spring Boot"
echo
docker build -f Dockerfile -t lol-matchmaking:latest .
if [ $? -ne 0 ]; then
    echo "[ERRO] Falha ao construir imagem Docker"
    exit 1
fi

echo
echo "[2/2] Imagem construída com sucesso!"
echo
echo "Para executar localmente:"
echo "  docker run -p 8080:8080 --env SPRING_PROFILES_ACTIVE=docker lol-matchmaking:latest"
echo
echo "Para executar em background:"
echo "  docker run -d -p 8080:8080 --name lol-matchmaking --env SPRING_PROFILES_ACTIVE=docker lol-matchmaking:latest"
echo
echo "Para Google Cloud (tag para deploy):"
echo "  docker tag lol-matchmaking:latest gcr.io/YOUR_PROJECT_ID/lol-matchmaking:latest"
echo "  docker push gcr.io/YOUR_PROJECT_ID/lol-matchmaking:latest"
echo
