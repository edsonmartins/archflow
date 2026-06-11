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

## 4. Beans duráveis adicionais (manual)

Os demais stores ainda exigem wiring explícito (vencem os defaults
`@ConditionalOnMissingBean`):

```java
@Configuration
public class ProductionPersistenceConfig {

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
}
```

> Nota: `ConversationRepository`/`PromptRegistry` têm implementações JDBC
> prontas, mas o caminho de runtime (`ConversationManager` singleton) ainda
> não as consome — ligá-las hoje cobre consumidores diretos (ex.:
> `ConversationOrchestrator`), não o suspend/resume do singleton. Integrá-las
> ao `ConversationManager` é trabalho à parte.

Para o **Quartz** (triggers agendados), sobrescreva o bean `Scheduler` com
`JDBCJobStore` (tabelas `QRTZ_*` do distribution do Quartz).

Para **ApiKeyRepository** e **UserRepository**, implemente as interfaces de
`archflow-security` sobre o seu banco — os defaults em memória servem apenas
para dev.

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
(`JdbcStateRepositoryPostgresTest`, `ConversationPersistencePostgresTest`)
provam o caminho completo contra PostgreSQL real via Testcontainers,
incluindo sobrevivência a restart.
