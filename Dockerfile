# ---- Stage 1: Build backend ----
FROM maven:3.9-eclipse-temurin-25 AS backend-build

WORKDIR /app

# Copy pom files first for dependency caching
COPY pom.xml .
COPY archflow-model/pom.xml archflow-model/
COPY archflow-core/pom.xml archflow-core/
COPY archflow-api/pom.xml archflow-api/
COPY archflow-agent/pom.xml archflow-agent/
COPY archflow-security/pom.xml archflow-security/
COPY archflow-observability/pom.xml archflow-observability/
COPY archflow-templates/pom.xml archflow-templates/
COPY archflow-conversation/pom.xml archflow-conversation/
COPY archflow-marketplace/pom.xml archflow-marketplace/
COPY archflow-workflow-tool/pom.xml archflow-workflow-tool/
COPY archflow-performance/pom.xml archflow-performance/
COPY archflow-plugin-api/pom.xml archflow-plugin-api/
COPY archflow-plugin-loader/pom.xml archflow-plugin-loader/
COPY archflow-langchain4j/pom.xml archflow-langchain4j/
COPY archflow-langchain4j/archflow-langchain4j-core/pom.xml archflow-langchain4j/archflow-langchain4j-core/
COPY archflow-langchain4j/archflow-langchain4j-openai/pom.xml archflow-langchain4j/archflow-langchain4j-openai/
COPY archflow-langchain4j/archflow-langchain4j-anthropic/pom.xml archflow-langchain4j/archflow-langchain4j-anthropic/
COPY archflow-langchain4j/archflow-langchain4j-provider-hub/pom.xml archflow-langchain4j/archflow-langchain4j-provider-hub/
COPY archflow-langchain4j/archflow-langchain4j-memory-redis/pom.xml archflow-langchain4j/archflow-langchain4j-memory-redis/
COPY archflow-langchain4j/archflow-langchain4j-memory-jdbc/pom.xml archflow-langchain4j/archflow-langchain4j-memory-jdbc/
COPY archflow-langchain4j/archflow-langchain4j-chain-rag/pom.xml archflow-langchain4j/archflow-langchain4j-chain-rag/
COPY archflow-langchain4j/archflow-langchain4j-embedding-openai/pom.xml archflow-langchain4j/archflow-langchain4j-embedding-openai/
COPY archflow-langchain4j/archflow-langchain4j-embedding-local/pom.xml archflow-langchain4j/archflow-langchain4j-embedding-local/
COPY archflow-langchain4j/archflow-langchain4j-vectorstore-redis/pom.xml archflow-langchain4j/archflow-langchain4j-vectorstore-redis/
COPY archflow-langchain4j/archflow-langchain4j-vectorstore-pgvector/pom.xml archflow-langchain4j/archflow-langchain4j-vectorstore-pgvector/
COPY archflow-langchain4j/archflow-langchain4j-vectorstore-pinecone/pom.xml archflow-langchain4j/archflow-langchain4j-vectorstore-pinecone/
COPY archflow-langchain4j/archflow-langchain4j-mcp/pom.xml archflow-langchain4j/archflow-langchain4j-mcp/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B || true

# Copy source and build
COPY . .
RUN mvn clean package -DskipTests -B

# ---- Stage 2: Build frontend ----
FROM node:18-alpine AS frontend-build

WORKDIR /app
COPY archflow-ui/package*.json ./
RUN npm ci
COPY archflow-ui/ .
RUN npm run build

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S archflow && adduser -S archflow -G archflow

WORKDIR /app

# Copy backend artifact (adjust path to your Spring Boot jar)
COPY --from=backend-build /app/archflow-api/target/*.jar app.jar

# Copy frontend build to serve as static files
COPY --from=frontend-build /app/dist/ /app/static/

RUN chown -R archflow:archflow /app
USER archflow

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.resources.static-locations=file:/app/static/"]
