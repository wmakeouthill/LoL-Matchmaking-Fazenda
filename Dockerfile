# Build único com Maven (usa frontend-maven-plugin para buildar Angular)
FROM maven:3.9.4-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copiar TUDO do projeto
COPY . .

# ✅ Build COMPLETO: Maven vai buildar frontend via plugin + backend
# -U: Força atualização de dependências
# clean: Limpa target antes
# package: Builda o JAR
RUN mvn -B -U clean package -DskipTests

# Runtime menor
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copiar o jar gerado (já contém os arquivos estáticos do frontend)
COPY --from=build /workspace/target/*.jar app.jar

# Porta padrão (Cloud Run fornece PORT env var)
EXPOSE 8080

# Variáveis de ambiente para configuração
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -XX:+OptimizeStringConcat -XX:+UseStringDeduplication"
ENV SPRING_PROFILES_ACTIVE="gcp"

# Health check para Cloud Run
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:${PORT:-8080}/actuator/health || exit 1

# Iniciar aplicacao e respeitar PORT do ambiente (Cloud Run fornece PORT)
# Cloud Run injeta PORT automaticamente, Spring Boot precisa de --server.port
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar --server.port=${PORT:-8080}"]
