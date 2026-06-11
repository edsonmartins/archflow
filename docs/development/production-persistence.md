# Persistência durável em produção

Por padrão o archflow sobe com stores em memória — adequados para
desenvolvimento, mas **todo estado é perdido no restart**. Fora dos profiles
`dev`/`test`, o `ProductionReadinessGuard` **recusa o boot** enquanto stores
em memória estiverem ativos (escape hatch consciente:
`archflow.allow-in-memory=true`).

Este guia mostra como trocar cada default por uma implementação durável
(PostgreSQL — já presente no `docker-compose.yml`).

## 1. DataSource

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/archflow
    username: archflow
    password: ${DB_PASSWORD}
```

## 2. Migrations

Os DDLs versionados estão nos módulos, em `src/main/resources/db/migration/`:

| Migration | Módulo | Tabelas |
|-----------|--------|---------|
| `V001__create_flow_state.sql` | archflow-core | `flow_states`, `audit_logs` |
| `V002__create_flows.sql` | archflow-core | `flows` |
| `V001__create_conversations.sql` | archflow-conversation | `conversations`, `conversation_messages`, `prompt_versions` |
| `V1__CreateAuditLogTable.sql` | archflow-observability | `af_audit_log` |

Com Flyway no classpath (`org.flywaydb:flyway-core` +
`flyway-database-postgresql`), o Spring Boot aplica tudo automaticamente.
Sem Flyway, aplique os arquivos manualmente na ordem.

## 3. Beans duráveis

Defina os beans no seu app (eles vencem os defaults `@ConditionalOnMissingBean`):

```java
@Configuration
public class ProductionPersistenceConfig {

    /** Estado de execução dos fluxos (multi-tenant). */
    @Bean
    public StateRepository stateRepository(DataSource ds) {
        return new JdbcStateRepository(ds);
    }

    /** Definições de fluxo. O codec converte a SUA implementação de Flow. */
    @Bean
    public FlowRepository flowRepository(DataSource ds, FlowJsonCodec codec) {
        return new JdbcFlowRepository(ds, codec);
    }

    /** Conversas + mensagens (isolamento por tenant em toda query). */
    @Bean
    public ConversationRepository conversationRepository(DataSource ds) {
        return new JdbcConversationRepository(ds);
    }

    /** Versionamento de prompts. */
    @Bean
    public PromptRegistry promptRegistry(DataSource ds) {
        return new JdbcPromptRegistry(ds);
    }

    /** Trilha de auditoria. */
    @Bean
    public AuditRepository auditRepository(DataSource ds) {
        return new JdbcAuditRepository(ds);
    }
}
```

Para o **Quartz** (triggers agendados), sobrescreva o bean `Scheduler` com
`JDBCJobStore` (tabelas `QRTZ_*` do distribution do Quartz).

Para **ApiKeyRepository** e **UserRepository**, implemente as interfaces de
`archflow-security` sobre o seu banco — os defaults em memória servem apenas
para dev.

## 4. ArchFlowAgent embarcado

Quem usa o agente como biblioteca injeta os repositórios no construtor:

```java
var agent = new ArchFlowAgent(config,
        new JdbcStateRepository(dataSource),
        new JdbcFlowRepository(dataSource, codec));
```

O construtor de um argumento continua existindo (in-memory, com WARNING) —
correto para o runner standalone one-shot.

## 5. Conferência

Suba com o profile de produção: se algo em memória sobrou, o boot falha com
a lista exata dos beans e o que configurar. Os testes de integração
(`JdbcStateRepositoryPostgresTest`, `ConversationPersistencePostgresTest`)
provam o caminho completo contra PostgreSQL real via Testcontainers,
incluindo sobrevivência a restart.
