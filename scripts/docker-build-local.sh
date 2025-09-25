#!/bin/bash
# scripts/docker-build-local.sh - build local docker image usando Dockerfile.local
echo "===================================="
echo "  Docker Build Local"
echo "===================================="
echo

# Vai para o diretório do projeto
cd "$(dirname "$0")/.."

# Verifica se o JAR existe
if [ ! -f target/*.jar ]; then
    echo "[ERRO] JAR não encontrado em target/"
    echo "Execute primeiro: ./scripts/build-all.sh ou mvn clean package"
    echo
    exit 1
fi

echo "[1/2] Construindo imagem Docker local..."
docker build -f Dockerfile.local -t lol-matchmaking:local .
if [ $? -ne 0 ]; then
    echo "[ERRO] Falha ao construir imagem Docker"
    exit 1
fi

echo
echo "[2/2] Imagem construída com sucesso!"
echo
echo "Para executar:"
echo "  docker run -p 8080:8080 --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local"
echo
echo "Para executar em background:"
echo "  docker run -d -p 8080:8080 --name lol-matchmaking-local --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local"
echo
