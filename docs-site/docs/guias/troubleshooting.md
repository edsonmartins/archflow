---
title: "Troubleshooting"
sidebar_position: 8
slug: troubleshooting
---

# Troubleshooting

Guia para diagnosticar e resolver problemas comuns no archflow.

## Problemas de Build

### Maven: compilacao falha

**Sintoma:** `mvn clean install` falha com erros de compilacao.

```bash
# Verificar versao do Java
java -version
# Necessario: Java 17+

# Verificar versao do Maven
mvn -version
# Necessario: Maven 3.8+

# Limpar cache Maven e rebuildar
mvn clean install -U

# Se persistir, limpar repositorio local
rm -rf ~/.m2/repository/br/com/archflow
mvn clean install
```

**Causa comum:** versao do Java incorreta. O archflow requer Java 17+.

### Maven: testes falham

```bash
# Rodar com logs detalhados
mvn test -X

# Pular testes para build rapido (nao recomendado em CI)
mvn clean install -DskipTests

# Rodar apenas um modulo especifico
mvn test -pl archflow-core

# Rodar uma classe especifica
mvn test -pl archflow-model -Dtest=FlowStateTest
```

### Node.js: `npm install` falha

**Sintoma:** Erros ao instalar dependencias do frontend.

```bash
# Verificar versao do Node
node -v
# Necessario: Node 18+

# Limpar cache e reinstalar
cd archflow-ui
rm -rf node_modules package-lock.json
npm install

# Se usar nvm
nvm use 20
npm install
```

### Frontend: `npm run build` falha

```bash
# Verificar erros de TypeScript
cd archflow-ui
npm run lint

# Build com logs detalhados
npm run build -- --debug
```

## Erros de Conexao

### PostgreSQL: connection refused

**Sintoma:** `Connection refused` ou `FATAL: password authentication failed`.

```bash
# Verificar se PostgreSQL esta rodando
docker compose ps postgres
# ou
pg_isready -h localhost -p 5432

# Verificar logs do PostgreSQL
docker compose logs postgres

# Reiniciar PostgreSQL
docker compose restart postgres
```

**Checklist:**
- [ ] PostgreSQL esta rodando na porta 5432?
- [ ] Usuario e senha estao corretos? (default: `archflow/archflow`)
- [ ] O banco `archflow` existe?
- [ ] A extensao `pgvector` esta instalada?

```bash
# Criar banco manualmente se necessario
docker compose exec postgres psql -U archflow -c "CREATE DATABASE archflow;"
docker compose exec postgres psql -U archflow -d archflow -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### Redis: connection refused

**Sintoma:** `Unable to connect to Redis` ou `Connection refused`.

```bash
# Verificar se Redis esta rodando
docker compose ps redis
# ou
redis-cli ping
# Resposta esperada: PONG

# Verificar logs
docker compose logs redis

# Reiniciar Redis
docker compose restart redis
```

**Checklist:**
- [ ] Redis esta rodando na porta 6379?
- [ ] `SPRING_DATA_REDIS_HOST` esta configurado corretamente?
- [ ] Se usando Docker, os containers estao na mesma rede?

### Banco nao inicializa (pgvector)

**Sintoma:** Erro ao criar tabelas com colunas `vector`.

```bash
# Verificar se pgvector esta instalado
docker compose exec postgres psql -U archflow -d archflow \
  -c "SELECT * FROM pg_extension WHERE extname = 'vector';"

# Instalar pgvector
docker compose exec postgres psql -U archflow -d archflow \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

:::tip
Use a imagem `pgvector/pgvector:pg16` no docker-compose para ter pgvector pre-instalado.
:::

## Autenticacao e JWT

### Token expired

**Sintoma:** `401 Unauthorized` com mensagem `Token expired`.

```bash
# Renovar o token
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "seu-refresh-token"}'
```

**Solucao permanente:** Configure seu cliente para renovar automaticamente quando receber `401`.

### Token invalid

**Sintoma:** `401 Unauthorized` com mensagem `Invalid token`.

**Causas possiveis:**
1. Token malformado (verifique copia/cola)
2. `JWT_SECRET` mudou entre reinicializacoes
3. Token foi gerado por outra instancia com secret diferente

```bash
# Verificar se o JWT_SECRET e consistente
echo $JWT_SECRET

# Fazer novo login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin"}'
```

### Forbidden (403)

**Sintoma:** `403 Forbidden` em endpoints especificos.

**Causa:** O usuario nao tem a role ou permission necessaria.

```bash
# Verificar roles do usuario atual
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"

# Resposta mostra roles:
# {"id":"user-1","username":"admin","roles":["ADMIN"]}
```

Consulte a [matriz de permissoes](./security-rbac#matriz-de-permissoes) para verificar qual role e necessaria.

## Plugin Not Found

**Sintoma:** Workflow falha com `Component not found: my-custom-tool`.

### Checklist de verificacao

1. **Arquivo SPI existe?**
```bash
# Verificar se o arquivo SPI esta no jar
jar tf target/my-plugin.jar | grep META-INF/services
# Deve mostrar: META-INF/services/br.com.archflow.plugin.api.spi.ComponentPlugin
```

2. **Classe esta no arquivo SPI?**
```bash
# Verificar conteudo
unzip -p target/my-plugin.jar META-INF/services/br.com.archflow.plugin.api.spi.ComponentPlugin
# Deve mostrar o FQCN da sua classe
```

3. **Plugin esta no classpath?**
```bash
# Se usando plugin loader, verificar diretorio de plugins
ls -la plugins/
```

4. **ID do plugin e correto?**

Verifique se o `componentId` no workflow corresponde ao `id` retornado pelo `getMetadata()` do plugin.

```java
// No plugin
@Override
public ComponentMetadata getMetadata() {
    return new ComponentMetadata(
        "my-custom-tool",  // ← Este ID
        ...
    );
}
```

```json
// No workflow
{
  "componentId": "my-custom-tool"  // ← Deve ser igual
}
```

5. **Plugin carregou sem erros?**

```bash
# Verificar logs de startup
docker compose logs app | grep -i "plugin"
```

## Workflow Execution Timeout

**Sintoma:** Execucao do workflow falha com `Execution timed out`.

### Diagnostico

```bash
# Verificar detalhes da execucao
curl http://localhost:8080/api/executions/<execution-id> \
  -H "Authorization: Bearer $TOKEN"
```

### Solucoes

1. **Aumentar timeout global:**
```json
{
  "configuration": {
    "timeout": 60000
  }
}
```

2. **Aumentar timeout do step especifico:**
```json
{
  "id": "step-slow",
  "configuration": {
    "timeout": 120000
  }
}
```

3. **Verificar steps de IA:**
   - Modelos grandes (ex: GPT-4, Claude Opus) podem demorar mais
   - Reduza `maxTokens` se possivel
   - Use modelos menores para classificacao (ex: Haiku)

4. **Verificar steps paralelos:**
   - O timeout do bloco `PARALLEL` deve ser maior que o step mais lento
   - Use `waitStrategy: "FIRST"` se so precisa do primeiro resultado

## Out of Memory

**Sintoma:** `java.lang.OutOfMemoryError` ou container reinicia inesperadamente.

### JVM

```bash
# Aumentar memoria da JVM
export JAVA_OPTS="-Xms512m -Xmx2g"

# Ou via docker-compose
environment:
  JAVA_OPTS: "-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Xms512m -Xmx2g"
```

### Docker

```yaml
# docker-compose.yml
services:
  app:
    deploy:
      resources:
        limits:
          memory: 4G
```

### Diagnostico

```bash
# Verificar uso de memoria
docker stats archflow-app

# Heap dump (em caso de leak)
docker exec archflow-app jcmd 1 GC.heap_dump /tmp/heapdump.hprof
docker cp archflow-app:/tmp/heapdump.hprof ./heapdump.hprof
```

### Causas comuns

| Causa | Solucao |
|-------|---------|
| Muitos plugins carregados | Remova plugins nao utilizados |
| Workflows com muitos steps paralelos | Reduza paralelismo ou aumente memoria |
| Cache Redis indisponivel (fallback para memoria) | Corrija conexao com Redis |
| Vector store com embeddings grandes | Aumente memoria ou use pgvector |

## Debug Logging

### Ativar logs detalhados

Via variavel de ambiente:

```bash
export LOGGING_LEVEL_BR_COM_ARCHFLOW=DEBUG
```

Via `application.yml`:

```yaml
logging:
  level:
    br.com.archflow: DEBUG
    br.com.archflow.core.engine: TRACE
    br.com.archflow.plugin.loader: DEBUG
    br.com.archflow.security: DEBUG
```

### Logs por componente

| Logger | Descricao |
|--------|-----------|
| `br.com.archflow.core.engine` | Flow Engine -- execucao de workflows |
| `br.com.archflow.core.execution` | Execution Manager -- ciclo de vida |
| `br.com.archflow.plugin.loader` | Plugin Loader -- carregamento de plugins |
| `br.com.archflow.security` | Seguranca -- JWT, RBAC, API keys |
| `br.com.archflow.langchain4j` | Integracao LangChain4j -- chamadas a modelos |
| `br.com.archflow.performance` | Cache, pool, metricas |

### Exemplo: debug de workflow

```bash
# Ativar TRACE para o engine
export LOGGING_LEVEL_BR_COM_ARCHFLOW_CORE_ENGINE=TRACE

# Reiniciar
docker compose restart app

# Executar workflow e verificar logs
docker compose logs -f app | grep -E "(FlowEngine|StepExecutor)"
```

### Log format

O archflow usa logback com formato estruturado:

```
2026-03-12 15:30:00.123 INFO  [exec-pool-1] b.c.a.core.engine.FlowEngine : Starting workflow flow-789
2026-03-12 15:30:00.456 DEBUG [exec-pool-1] b.c.a.core.engine.StepExecutor : Executing step classifier (DECISION)
2026-03-12 15:30:01.789 DEBUG [exec-pool-1] b.c.a.core.engine.StepExecutor : Step classifier completed: {category: "technical"}
2026-03-12 15:30:01.800 DEBUG [exec-pool-1] b.c.a.core.engine.FlowEngine : Following connection classifier → assistant
```

## Problemas Frequentes

### Porta ja em uso

```bash
# Verificar o que esta usando a porta
lsof -i :8080

# Matar processo na porta
kill -9 $(lsof -t -i :8080)

# Ou alterar a porta
export SERVER_PORT=9090
```

### Docker: permissao negada

```bash
# Adicionar usuario ao grupo docker
sudo usermod -aG docker $USER

# Reiniciar sessao
newgrp docker
```

### Flyway migration falha

```bash
# Verificar estado das migrations
docker compose exec postgres psql -U archflow -d archflow \
  -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# Reparar migrations com erro
mvn flyway:repair
```

## Proximos passos

- [Quickstart Dev](./quickstart-dev) -- Configurar ambiente de desenvolvimento
- [Deploy com Docker](./deploy-docker) -- Deploy em producao
- [API Reference](../api/rest-endpoints) -- Referencia de endpoints
