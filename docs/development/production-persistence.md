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
| `V002__create_suspended_conversations.sql` | archflow-conversation | `suspended_conversations` (suspend/resume) |
| `V1__CreateAuditLogTable.sql` | archflow-observability | `af_audit_log` |
| `V001__create_security.sql` | archflow-security | `users`, `user_roles`, `api_keys` |
| `V001__create_quartz.sql` | archflow-api | `QRTZ_*` (JDBCJobStore do Quartz) |

Com Flyway no classpath (`org.flywaydb:flyway-core` +
`flyway-database-postgresql`), o Spring Boot aplica tudo automaticamente.
Sem Flyway, aplique os arquivos manualmente na ordem.

## 3. Liga automática (StateManager + AuditRepository)

A forma mais simples: ligue a flag e forneça um `DataSource`.

```yaml
archflow:
  persistence:
    jdbc:
      enabled: true
```

Com `archflow.persistence.jdbc.enabled=true` + um `DataSource` no contexto
(via `spring-boot-starter-jdbc` + `spring.datasource.*`), o
`JdbcPersistenceConfiguration` liga automaticamente:

- **StateManager** durável (estado de execução — resume sobrevive a restart),
  via `RepositoryStateManager` sobre `JdbcStateRepository`;
- **AuditRepository** durável, via `JdbcAuditRepository`.

O default em memória do `StateManager` é desligado pela mesma flag (mutuamente
exclusivo, sem corrida de ordenação).

O **FlowRepository** não é ligado automaticamente: serializar um `Flow` depende
da implementação concreta do seu deployment (não há codec universal seguro), e
o caminho do designer usa um formato de nó específico. Forneça o bean — uma
linha — com o seu `FlowJsonCodec`. Se esquecer, o `ProductionReadinessGuard`
**falha o boot** em produção pedindo o bean durável (a rede de segurança
guia você):

```java
@Bean
public FlowRepository flowRepository(DataSource ds, FlowJsonCodec codec) {
    return new JdbcFlowRepository(ds, codec);
}
```

## 4. Conversação durável (histórico + suspend/resume)

Também ligados automaticamente pela flag `archflow.persistence.jdbc.enabled=true`
(vencem os defaults em memória via `@ConditionalOnMissingBean`):

- **`SuspendedConversationStore`** durável (`JdbcSuspendedConversationStore`,
  tabela `suspended_conversations`): conversas aguardando input humano
  (suspend/resume) **sobrevivem a restart**. O `ConversationManager` agora
  consome este store por injeção (o `getInstance()` singleton segue existindo,
  mas só para compat de testes; produção usa o bean). O formulário (`FormData`)
  é persistido como JSON — o modelo é round-trippável via `@JsonCreator`.
- **`ConversationRepository`** (`JdbcConversationRepository`): histórico de
  conversas + mensagens, isolado por tenant.
- **`PromptRegistry`** (`JdbcPromptRegistry`): versionamento de prompts.

Fora de dev/test, o `ProductionReadinessGuard` **falha o boot** se o
`SuspendedConversationStore` ativo ainda for o in-memory — a perda de conversas
suspensas no restart deixa de ser silenciosa.

O **Quartz** (triggers agendados) passa a usar `JDBCJobStore` (`JobStoreTX`)
automaticamente sob a flag `archflow.persistence.jdbc.enabled=true`, sobre o
mesmo `DataSource` — o `RAMJobStore` default recua via
`@ConditionalOnMissingBean`. Aplique a migration
`archflow-api/.../db/migration/V001__create_quartz.sql` (tabelas `QRTZ_*`, DDL
oficial do Quartz para PostgreSQL). O delegate JDBC é configurável via
`archflow.persistence.quartz.driver-delegate` (default
`org.quartz.impl.jdbcjobstore.PostgreSQLDelegate`; troque para o delegate do
seu banco se não for PostgreSQL).

**ApiKeyRepository** e **UserRepository** já têm implementações JDBC prontas
em `archflow-security` (`JdbcUserRepository`, `JdbcApiKeyRepository`), ligadas
automaticamente pela flag `archflow.persistence.jdbc.enabled=true` (mesma
`JdbcPersistenceConfiguration` acima). Aplique a migration
`archflow-security/.../db/migration/V001__create_security.sql` (tabelas
`users`, `user_roles`, `api_keys`). Na primeira subida, o usuário `admin` é
semeado de forma idempotente com a senha de `archflow.security.admin-password`
(ou `ARCHFLOW_ADMIN_PASSWORD`); sem ela, uma senha aleatória é gerada e logada
uma vez. Restarts não sobrescrevem uma senha já rotacionada.

## 5. ArchFlowAgent embarcado

Quem usa o agente como biblioteca injeta os repositórios no construtor:

```java
var agent = new ArchFlowAgent(config,
        new JdbcStateRepository(dataSource),
        new JdbcFlowRepository(dataSource, codec));
```

O construtor de um argumento continua existindo (in-memory, com WARNING) —
correto para o runner standalone one-shot.

## 6. Conferência

Suba com o profile de produção: se algo em memória sobrou, o boot falha com
a lista exata dos beans e o que configurar. Os testes de integração
(`JdbcStateRepositoryPostgresTest`, `ConversationPersistencePostgresTest`,
`JdbcUserRepositoryPostgresTest`, `JdbcApiKeyRepositoryPostgresTest`,
`DurableQuartzSchedulerPostgresTest`, `JdbcSuspendedConversationStorePostgresTest`)
provam o caminho completo contra PostgreSQL real via Testcontainers, incluindo
sobrevivência a restart.
