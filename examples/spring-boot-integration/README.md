# Archflow Spring Boot Integration Example

Demonstrates programmatic workflow management via the archflow REST API from a Spring Boot application.

## Prerequisites

- Java 17+
- Maven 3.8+
- Archflow backend running at `http://localhost:8080`

## Running

```bash
# Set your API token (obtain via POST /api/auth/login)
export ARCHFLOW_API_KEY=your-jwt-token

# Build and run
mvn spring-boot:run
```

The application runs on port 8081 and on startup will:

1. List all available workflows from archflow
2. Execute the first workflow with a sample customer support query
3. Poll execution status until completion

## Configuration

Edit `src/main/resources/application.yml` or use environment variables:

| Property | Env Variable | Default |
|---|---|---|
| `archflow.api.base-url` | - | `http://localhost:8080/api` |
| `archflow.api.key` | `ARCHFLOW_API_KEY` | - |
