Este README descreve como containerizar e fazer deploy do backend Spring Boot no Google Cloud Run, usando o mesmo banco MySQL existente, e como configurar o Electron frontend para apontar para o serviço.

Resumo do que faremos

- Construir uma imagem Docker multi-stage (já incluída em `Dockerfile`).
- Testar localmente com Docker e conectar ao seu MySQL existente via variáveis de ambiente.
- Fazer push da imagem para Google Container Registry (ou Artifact Registry) e deploy para Cloud Run.
- Configurar variáveis de ambiente no Cloud Run para apontar ao seu banco MySQL (ou usar `DATABASE_URL`).
- Configurar o Electron para carregar a URL pública do Cloud Run via `BACKEND_URL`.

1) Pré-requisitos

- Ter o Google Cloud SDK (`gcloud`) instalado e autenticado:
  gcloud auth login
  gcloud config set project YOUR_PROJECT_ID

- Ter permissões para criar Cloud Run services e enviar imagens para o Registry/Artifact Registry.
- Ter Docker instalado localmente para testar builds locais.

2) Testar localmente (Docker)

- Na pasta `spring-backend` (onde está o `Dockerfile`):

```cmd
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
docker build -t lol-matchmaking-backend:local .
```

- Rodar localmente apontando para seu MySQL (substitua valores):

```cmd
docker run --rm -p 8080:8080 -e MYSQL_HOST=your.mysql.host -e MYSQL_PORT=3306 -e MYSQL_DATABASE=lolmatchmaking -e MYSQL_USER=dbuser -e MYSQL_PASSWORD=dbpass lol-matchmaking-backend:local
```

- Alternativamente, passe a variável `DATABASE_URL` completa (JDBC):

```cmd
docker run --rm -p 8080:8080 -e DATABASE_URL="jdbc:mysql://your.mysql.host:3306/lolmatchmaking?useSSL=false&serverTimezone=UTC" -e MYSQL_USER=dbuser -e MYSQL_PASSWORD=dbpass lol-matchmaking-backend:local
```

- Verifique em http://localhost:8080 (endpoints e, se aplicável, a UI que o backend servi).

3) Enviar imagem e fazer deploy para Cloud Run

Opção A — usar Google Cloud Build (recomendado):

```cmd
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
:: Substitua LOCATION (ex: us-central1) e PROJECT_ID
set PROJECT_ID=your-gcp-project
set IMAGE_NAME=gcr.io/%PROJECT_ID%/lol-matchmaking-backend
:: Fazer build e enviar ao GCR
gcloud builds submit --tag %IMAGE_NAME% .

:: Deploy para Cloud Run (deixar público se necessário)
gcloud run deploy lol-matchmaking-backend --image %IMAGE_NAME% --region us-central1 --platform managed --allow-unauthenticated --set-env-vars MYSQL_HOST=your.mysql.host,MYSQL_PORT=3306,MYSQL_DATABASE=lolmatchmaking,MYSQL_USER=dbuser,MYSQL_PASSWORD=dbpass
```

Opção B — build local com Docker e push (usar Artifact Registry ou GCR):

```cmd
:: Build local
docker build -t gcr.io/%PROJECT_ID%/lol-matchmaking-backend:latest .
:: Push
docker push gcr.io/%PROJECT_ID%/lol-matchmaking-backend:latest
:: Deploy (mesmo que acima)
gcloud run deploy lol-matchmaking-backend --image gcr.io/%PROJECT_ID%/lol-matchmaking-backend:latest --region us-central1 --platform managed --allow-unauthenticated --set-env-vars MYSQL_HOST=your.mysql.host,MYSQL_PORT=3306,MYSQL_DATABASE=lolmatchmaking,MYSQL_USER=dbuser,MYSQL_PASSWORD=dbpass
```

Notas sobre Cloud SQL (opcional)

- Se seu banco for Cloud SQL (MySQL), prefira usar o conector Cloud SQL. Para isso, durante o deploy, forneça `--add-cloudsql-instances=PROJECT:REGION:INSTANCE` e adicione a variável de ambiente `CLOUD_SQL_CONNECTION_NAME=PROJECT:REGION:INSTANCE`.
- Para conectar via JDBC com socket Unix, use `spring.datasource.url=jdbc:mysql://localhost/<DB>?socketFactory=com.google.cloud.sql.mysql.SocketFactory&cloudSqlInstance=${CLOUD_SQL_CONNECTION_NAME}` ou configure `DATABASE_URL` adequadamente. Consulte a documentação do Cloud SQL para detalhes.

4) Configuração de variáveis de ambiente

- `DATABASE_URL` (opcional): JDBC URL completa (se fornecida, o `application.yml` prioriza ela).
- Ou as variáveis separadas que o `application.yml` suporta:
  - MYSQL_HOST (ou DB_HOST)
  - MYSQL_PORT (ou DB_PORT)
  - MYSQL_DATABASE (ou DB_NAME)
  - MYSQL_USER (ou DB_USER)
  - MYSQL_PASSWORD (ou DB_PASSWORD)
  - DB_POOL_MAX, etc. (opcionais)

5) Configurar o Electron para usar o Cloud Run

- Ao rodar o cliente Electron localmente, exporte a variável `BACKEND_URL` apontando para a URL do Cloud Run (obtida após o deploy):

```cmd
:: Windows (cmd.exe)
set BACKEND_URL=https://lol-matchmaking-backend-xxxxx.a.run.app
npm run electron-start
```

- Se você empacotar a app Electron para distribuição, defina a variável `BACKEND_URL` no processo de empacotamento ou altere o comportamento para buscar um arquivo de configuração no tempo de execução.

6) Segurança e recomendações

- Evite expor credenciais no repositório.
- Use Secret Manager (Google) e carregue as credenciais como variáveis de ambiente no Cloud Run.
- Se possível, restrinja o acesso ao serviço Cloud Run (remover --allow-unauthenticated e usar IAM / IAP) e configure o cliente para autenticar.

7) Testes rápidos pós-deploy

- Após o deploy, acesse a URL do Cloud Run (fornecida pelo gcloud) e verifique o endpoint /actuator/health (se habilitado) ou qualquer endpoint público.
- Atualize o Electron `BACKEND_URL` para a URL do Cloud Run e abra a app.

Se quiser, eu posso:
- Adicionar um script npm para iniciar o Electron local com a variável (ex: `npm run electron-start`).
- Adicionar exemplos de como usar Secret Manager + Cloud Run.


