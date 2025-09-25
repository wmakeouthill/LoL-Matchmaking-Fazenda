#!/bin/bash
# scripts/deploy-gcp.sh - deploy para Google Cloud Run
echo "===================================="
echo "  Deploy para Google Cloud Run"
echo "===================================="
echo

# Verifica se o gcloud CLI está instalado
if ! command -v gcloud &> /dev/null; then
    echo "[ERRO] Google Cloud CLI (gcloud) não encontrado"
    echo "Instale em: https://cloud.google.com/sdk/docs/install"
    exit 1
fi

# Pede o PROJECT_ID se não estiver definido
if [ -z "$GOOGLE_CLOUD_PROJECT" ]; then
    read -p "Digite o PROJECT_ID do Google Cloud: " GOOGLE_CLOUD_PROJECT
fi

if [ -z "$GOOGLE_CLOUD_PROJECT" ]; then
    echo "[ERRO] PROJECT_ID é obrigatório"
    exit 1
fi

echo "Usando PROJECT_ID: $GOOGLE_CLOUD_PROJECT"
echo

# Vai para o diretório do projeto
cd "$(dirname "$0")/.."

echo "[1/4] Configurando Docker para Google Cloud..."
gcloud auth configure-docker
if [ $? -ne 0 ]; then
    echo "[ERRO] Falha ao configurar Docker para GCP"
    exit 1
fi

echo
echo "[2/4] Construindo imagem Docker..."
docker build -f Dockerfile -t gcr.io/$GOOGLE_CLOUD_PROJECT/lol-matchmaking:latest .
if [ $? -ne 0 ]; then
    echo "[ERRO] Falha ao construir imagem Docker"
    exit 1
fi

echo
echo "[3/4] Enviando imagem para Google Container Registry..."
docker push gcr.io/$GOOGLE_CLOUD_PROJECT/lol-matchmaking:latest
if [ $? -ne 0 ]; then
    echo "[ERRO] Falha ao enviar imagem para GCR"
    exit 1
fi

echo
echo "[4/4] Deployando para Cloud Run..."
gcloud run deploy lol-matchmaking \
    --image gcr.io/$GOOGLE_CLOUD_PROJECT/lol-matchmaking:latest \
    --platform managed \
    --region us-central1 \
    --allow-unauthenticated \
    --set-env-vars SPRING_PROFILES_ACTIVE=gcp \
    --memory 1Gi \
    --cpu 1 \
    --max-instances 10 \
    --port 8080

if [ $? -ne 0 ]; then
    echo "[ERRO] Falha ao fazer deploy no Cloud Run"
    exit 1
fi

echo
echo "===================================="
echo "  Deploy concluído com sucesso!"
echo "===================================="
echo
echo "A aplicação estará disponível na URL fornecida pelo Cloud Run."
echo
echo "Para configurar variáveis de ambiente adicionais:"
echo "  gcloud run services update lol-matchmaking --set-env-vars KEY=VALUE"
echo
