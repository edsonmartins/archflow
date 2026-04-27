# archflow v1 — Operations & Release Notes

This document captures the constraints and configuration knobs that
ship in the first functional release. Read it once before deploying to
production; it covers items the audit flagged but that are intentional
v1 design decisions rather than bugs.

## Persistence model (in-memory by default)

The following admin-facing controllers store their state **in-memory
only**:

| Controller                       | What it stores                          |
|---------------------------------|------------------------------------------|
| `TenantControllerImpl`          | Tenant catalogue                         |
| `WorkspaceControllerImpl`       | Per-tenant users, scoped API keys        |
| `BrainSentryConfigControllerImpl` | Per-tenant BrainSentry config          |
| `LinktorConfigControllerImpl`   | Per-tenant Linktor config                |
| `SkillsControllerImpl`          | Per-tenant active-skill set              |
| `GlobalConfigControllerImpl`    | Platform-wide LLM models, plan defaults  |
| `ScheduledTriggerControllerImpl`| Quartz job + cron registrations          |

**Implication:** every redeploy resets these registries. For v1 this is
acceptable for two reasons:
1. The execution-state path that *does* need durability
   (`JdbcStateRepository` → `flow_states` table) is already wired.
2. The admin surfaces are configuration-only; loss is recoverable from
   the operator's UI session.

Production deployments that require durability must override the bean
definitions in `ArchflowBeanConfiguration` with JDBC-backed
implementations. The interfaces are stable; only the persistence layer
changes.

## Database migrations (Flyway)

Only `V001__create_flow_state.sql` ships in v1. It creates the two
tables the execution engine needs (`flow_states`, `audit_logs`). When
moving an admin controller from in-memory to JDBC, add a new migration
version (`V002__...`) — never edit V001.

## Plugin loading (offline operation)

The plugin loader (`ArchflowPluginManager` +
`ArchflowPluginClassLoader`) supports loading external JARs at runtime.
Two operational caveats:

1. **No internet during runtime.** The classloader resolves jars from a
   local URL list — typically `file:` URLs into a pre-cached plugin
   directory. Production images must bake the plugin set into the
   container; do not depend on Maven Central or the public Jeka cache.
2. **Hot-reload now releases handles.** `ArchflowPluginManager.unload`
   and `close()` walk the registered classloaders and call `close()`
   so jar file descriptors are released. Always go through these
   methods rather than dropping references — `URLClassLoader` does not
   release file handles on GC alone.

## Authentication (JWT filter)

`JwtAuthenticationFilter` is **disabled by default**
(`archflow.security.auth.enabled=false`). Production
(`application-prod.yml`) flips it on. The filter:

- Validates `Authorization: Bearer <token>` against `JwtService`.
- Populates request attributes: `archflow.userId`, `archflow.username`,
  `archflow.roles`, `archflow.jwtTenantId`.
- Skips public paths: `/api/auth/login`, `/api/auth/refresh`,
  `/api/auth/logout`, `/api/health`, `/actuator/**`, `/realtime/**`,
  `/ws/**`.
- Returns `401 Unauthorized` on missing or invalid tokens for
  protected paths.

`ImpersonationFilter` runs after the JWT filter and only honours the
`X-Impersonate-Tenant` header when both
`archflow.admin.impersonation.enabled=true` AND the caller's JWT has
the `superadmin` role. When the JWT filter is disabled, the property
gate alone applies (dev / E2E mode).

## Required environment variables (prod)

| Variable                       | Purpose                                  |
|--------------------------------|------------------------------------------|
| `ARCHFLOW_JWT_SECRET`          | HS256 signing key (≥256 bits)            |
| `SPRING_DATASOURCE_URL`        | JDBC URL for `flow_states`               |
| `SPRING_DATASOURCE_USERNAME`   | Database user                            |
| `SPRING_DATASOURCE_PASSWORD`   | Database password                        |
| `SPRING_DATA_REDIS_HOST`       | Optional — falls back to `localhost`     |
| `OTEL_EXPORTER_OTLP_ENDPOINT`  | Optional tracing endpoint                |

The application refuses to start when any of the required variables are
absent (Spring evaluates `${VAR}` placeholders eagerly).

## Profiles

- **dev** (`application-dev.yml`): seeds demo tenants/users, allows
  impersonation, leaves JWT filter off so the mock E2E suite passes.
- **prod** (`application-prod.yml`): no seed data, JWT filter on,
  impersonation off, observability endpoints exposed via actuator.

## Known follow-ups (post-v1)

The following items were deliberately **not** done in v1:

- JDBC repositories for tenant / workspace / config controllers.
- K8s / Helm manifests (Docker Compose only).
- Plugin classloader marketplace integration (Jeka resolver).
- `@RequiresPermission` aspect wiring at controller methods. The
  archflow-security module has the annotation; activating it requires
  AspectJ weaving config in archflow-api.
- Distributed Quartz (cluster-mode triggers).
