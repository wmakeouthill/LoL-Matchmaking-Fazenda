# Multi-stage Dockerfile: build frontend + backend, run with Temurin JRE
FROM node:22-alpine AS frontend-build
WORKDIR /workspace/frontend
# Copiar arquivos do frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
# Build do frontend Angular
RUN npm run build:prod

FROM maven:3.9.4-eclipse-temurin-21 AS backend-build
WORKDIR /workspace
# Copiar arquivos do backend
COPY pom.xml ./
COPY src ./src
# Copiar frontend buildado diretamente para resources/static
COPY --from=frontend-build /workspace/frontend/dist/browser ./src/main/resources/static
# Build do pacote Spring Boot (pula o frontend plugin com -Dfrontend.skip)
RUN mvn -B -DskipTests package -Dfrontend.skip=true

# Runtime menor
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copiar o jar gerado (já contém os arquivos estáticos do frontend)
COPY --from=backend-build /workspace/target/*.jar app.jar

# Porta padrão (Cloud Run fornece PORT env var)
EXPOSE 8080

# Variáveis de ambiente para configuração
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE="gcp"

# Iniciar aplicacao e respeitar PORT do ambiente (Cloud Run fornece PORT)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
