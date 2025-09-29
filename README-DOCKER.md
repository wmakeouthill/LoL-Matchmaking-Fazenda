# Docker & Deploy Guide - LOL Matchmaking

Este guia contém comandos prontos para executar a aplicação LOL Matchmaking em diferentes ambientes.

## 🚀 Comandos Prontos

### 1. Desenvolvimento Local Completo (Recomendado)

```bash
# 1. Build completo (frontend + backend)
./scripts/build-all.sh
./scripts/docker-build-local.sh
docker run -p 8080:8080 --name lol-matchmaking-local --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local
 
# 2. Build da imagem Docker local
./scripts/docker-build-local.sh

# 3. Subir com Docker Compose (inclui MySQL)
docker-compose up -d

# 4. Verificar logs
docker-compose logs -f app

docker run -p 8080:8080 --name lol-matchmaking-local --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local


# 5. Acessar aplicação
# Frontend + Backend: http://localhost:8080
```

### 2. Build e Execução Rápida

```bash
# Se já tem o JAR construído
./scripts/docker-build-local.sh
docker run -p 8080:8080 --name lol-matchmaking-local --env SPRING_PROFILES_ACTIVE=local lol-matchmaking:local

# Acessar: http://localhost:8080
```

### 3. Build Completo do Zero

```bash
# Build completo (frontend Angular + backend Spring Boot)
./scripts/docker-build-full.sh
docker run -p 8080:8080 --env SPRING_PROFILES_ACTIVE=docker lol-matchmaking:latest

# Acessar: http://localhost:8080
```

### 4. Deploy Google Cloud Run

```bash
# Deploy automático para Google Cloud
./scripts/deploy-gcp.sh
# (Digite o PROJECT_ID quando solicitado)
mvn spring-boot:run -Dspring-boot.run.profiles=local
# Depois configurar URLs:
gcloud run services update lol-matchmaking \
  --set-env-vars BACKEND_URL=https://SEU-SERVICO.run.app \
  --set-env-vars FRONTEND_URL=https://SEU-SERVICO.run.app
```

## 🖥️ Comandos Windows (PowerShell/CMD)

```cmd
REM Build completo
scripts\build-all.bat

REM Docker Compose
docker-compose up -d

REM Build Docker local
scripts\docker-build-local.bat

REM Deploy Google Cloud
scripts\deploy-gcp.bat
```

## 🖱️ Electron + Docker (Windows/macOS)

O app Electron pode usar o backend no Docker (porta 8080) e acessar o LCU no host automaticamente.

Pré-requisitos:
- Backend no Docker ouvindo em http://localhost:8080 (docker-compose up -d)
- League of Legends aberto (lockfile presente)

Passos (Windows CMD):

```cmd
REM 1) Validar backend
docker-compose ps
docker-compose logs -f app

REM 2) Iniciar Electron apontando para o backend
cd /d "spring-backend\scripts"
start-electron.bat
```

Dicas importantes:
- O preload do Electron lê o lockfile local e envia para o backend via POST /api/lcu/configure.
- O payload usa host="auto" por padrão; o backend tenta host.docker.internal e 127.0.0.1.
- Se o LoL não estiver aberto, /api/lcu/configure pode responder 400; abra o LoL e tente novamente (o app tenta automaticamente por alguns segundos).
- Para forçar um host específico do LCU, defina a variável antes de iniciar o Electron:

```cmd
set LCU_HOST=127.0.0.1
scripts\start-electron.bat
```

macOS: host.docker.internal funciona nativamente. Linux pode exigir extra_hosts no docker-compose.

## 📋 Como Funciona

### ✅ Backend Serve o Frontend
- O Spring Boot serve os arquivos do Angular automaticamente
- Frontend é copiado para `target/classes/static/` durante o build Maven
- Uma única URL serve tudo: **http://localhost:8080**
- Não precisa rodar o Angular separadamente (`ng serve`)

### 🔄 Fluxo de Build
```
1. Frontend: Angular build → frontend/dist/browser/
2. Maven: Copia frontend → target/classes/static/
3. Spring Boot: Serve frontend + API REST + WebSocket
4. Docker: Empacota tudo em uma imagem
```

## 🌐 URLs por Ambiente

| Ambiente | URL de Acesso | Configuração |
|----------|---------------|--------------|
| **Local** | http://localhost:8080 | `SPRING_PROFILES_ACTIVE=local` |
| **Docker** | http://localhost:8080 | `SPRING_PROFILES_ACTIVE=docker` |
| **Google Cloud** | https://seu-servico.run.app | `SPRING_PROFILES_ACTIVE=gcp` |

## 🔧 Comandos de Manutenção

```bash
# Parar tudo
docker-compose down

# Limpar containers e imagens
docker system prune -f

# Ver logs específicos
docker-compose logs mysql
docker-compose logs app

# Rebuild completo
./scripts/build-all.sh
docker-compose up --build

# Acessar banco MySQL
docker exec -it lol-matchmaking-mysql mysql -u loluser -p lolmatchmaking
```

## 🐛 Troubleshooting

### Problema: "Port already in use"
```bash
# Matar processo na porta 8080
netstat -tulpn | grep 8080
kill -9 <PID>

# Ou usar porta diferente
docker run -p 8081:8080 --env SPRING_PROFILES_ACTIVE=docker lol-matchmaking:latest
```

### Problema: Frontend não carrega
```bash
# Verificar se arquivos estão no JAR
jar -tf target/spring-backend-0.1.0-SNAPSHOT.jar | grep static

# Rebuild frontend
cd frontend && npm run build:prod
```

### Problema: CORS errors
- URLs do frontend e backend devem estar no mesmo domínio
- No Docker/GCP, usar a mesma URL para frontend e backend

### Problema: LCU não conecta (400 em /api/lcu/configure)
- Verifique se o LoL está aberto (lockfile presente)
- Confira se o container resolve host.docker.internal (Windows/macOS OK; Linux: use `extra_hosts: ['host.docker.internal:host-gateway']`)
- Tente definir `LCU_HOST=127.0.0.1` ao iniciar o Electron (ver seção acima)
- Veja os logs: `docker-compose logs -f app | findstr LCU`

## ⚡ Comandos de Uma Linha

```bash
# Desenvolvimento rápido
./scripts/build-all.sh && docker-compose up -d

# Rebuild e restart
docker-compose down && ./scripts/build-all.sh && docker-compose up --build -d

# Deploy GCP completo
./scripts/build-all.sh && ./scripts/deploy-gcp.sh

# Logs em tempo real
docker-compose logs -f app | grep -E "(ERROR|WARN|INFO)"
```

## 📁 Estrutura de Arquivos

```
spring-backend/
├── Dockerfile              # Build completo (produção)
├── Dockerfile.local         # Build rápido (desenvolvimento)
├── docker-compose.yml       # MySQL + App local
├── scripts/
│   ├── build-all.sh/.bat   # Build frontend + backend
│   ├── docker-build-*.sh   # Scripts Docker
│   └── deploy-gcp.sh       # Deploy Google Cloud
├── frontend/dist/browser/   # Frontend buildado
└── target/classes/static/   # Frontend no JAR Spring Boot
```

## 🎯 Comandos Mais Usados

```bash
# Desenvolvimento diário
./scripts/build-all.sh && docker-compose up -d

# Verificar se está funcionando
curl http://localhost:8080/actuator/health

# Deploy produção
./scripts/deploy-gcp.sh
```
