# Fase 3: Enterprise Capabilities

**Duração Estimada**: 4-6 semanas (4 sprints)
**Objetivo**: Adicionar camada enterprise para produção em ambientes corporativos

---

## Visão Geral

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         archflow Enterprise Layer                        │
│                                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │   Auth &    │  │   Observ-   │  │   Func-     │  │   Multi-    │   │
│  │    RBAC     │  │   ability   │  │   Agent     │  │   LLM Hub   │   │
│  │             │  │             │  │   Mode      │  │             │   │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │
│         ↓                ↓                ↓                ↓           │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              archflow-core (Foundation + Visual)                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Sprint 9: Auth & RBAC

### Objetivo
Implementar autenticação, autorização e controle de acesso baseado em roles.

### Arquitetura de Segurança

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Security Architecture                            │
│                                                                           │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐            │
│  │   Client     │────▶│  API Gateway │────▶│  Resource    │            │
│  │              │     │   (JWT)      │     │    Server    │            │
│  └──────────────┘     └──────┬───────┘     └──────┬───────┘            │
│                              │                     │                     │
│                              ▼                     ▼                     │
│                      ┌──────────────┐     ┌──────────────┐            │
│                      │   JWT        │     │   Method     │            │
│                      │   Validator  │     │   Security   │            │
│                      └──────────────┘     └──────┬───────┘            │
│                                                   │                     │
│                                                   ▼                     │
│                                      ┌──────────────────────────┐      │
│                                      │        RBAC Engine        │      │
│                                      │  ┌────┐ ┌────┐ ┌────┐   │      │
│                                      │  │User│→│Role│→│Perm│   │      │
│                                      │  └────┘ └────┘ └────┘   │      │
│                                      └──────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────────┘
```

### 9.1 Modelos de Domínio

```java
package org.archflow.security.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Usuário do sistema
 */
@Entity
@Table(name = "af_users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private String email;
    private String fullName;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "af_user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    // API Keys para este usuário
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private Set<ApiKey> apiKeys = new HashSet<>();

    public boolean hasPermission(String permission) {
        return roles.stream()
            .flatMap(role -> role.getPermissions().stream())
            .anyMatch(p -> p.getName().equals(permission));
    }

    public boolean hasAnyPermission(String... permissions) {
        return roles.stream()
            .flatMap(role -> role.getPermissions().stream())
            .map(Permission::getName)
            .anyMatch(p -> Set.of(permissions).contains(p));
    }

    // Getters, setters, equals, hashCode
}
```

```java
package org.archflow.security.model;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Role que agrupa permissões
 */
@Entity
@Table(name = "af_roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private Boolean builtIn = false;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "af_role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new HashSet<>();

    // Getters, setters
}
```

```java
package org.archflow.security.model;

import jakarta.persistence.*;
import java.util.Set;

/**
 * Permissão granular sobre um recurso
 */
@Entity
@Table(name = "af_permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    // Formato: RESOURCE:ACTION
    // Exemplos:
    //   workflow:read, workflow:write, workflow:delete
    //   agent:execute, tool:invoke
    //   admin:users, admin:settings

    private String description;

    @Column(nullable = false)
    private String resource;  // workflow, agent, tool, admin, etc.

    @Column(nullable = false)
    private String action;    // read, write, delete, execute, etc.

    @ManyToMany(mappedBy = "permissions")
    private Set<Role> roles;

    public static Permission of(String resource, String action) {
        Permission p = new Permission();
        p.setResource(resource);
        p.setAction(action);
        p.setName(resource + ":" + action);
        return p;
    }

    // Getters, setters
}
```

```java
package org.archflow.security.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * API Key para autenticação via Bearer Token
 */
@Entity
@Table(name = "af_api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String keyHash;  // SHA-256 hash do key value

    private String name;     // Nome descritivo

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;
    private Instant lastUsedAt;

    @Column(nullable = false)
    private Boolean active = true;

    // Scopes da API key
    @ElementCollection
    @CollectionTable(name = "af_api_key_scopes", joinColumns = @JoinColumn(name = "api_key_id"))
    @Column(name = "scope")
    private Set<String> scopes;

    // Prefixo visível da key (primeiros 8 caracteres)
    private String keyPrefix;

    /**
     * Valida scopes da chave
     */
    public boolean hasScope(String requiredScope) {
        return scopes != null && scopes.contains(requiredScope);
    }

    /**
     * Verifica se a chave está expirada
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    // Getters, setters
}
```

### 9.2 Serviço de Autenticação

```java
package org.archflow.security.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.archflow.security.model.User;
import org.archflow.security.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Key jwtKey;
    private final long jwtExpirationHours;

    public AuthService(
            UserRepository userRepository,
            @Value("${archflow.security.jwt.secret}") String jwtSecret,
            @Value("${archflow.security.jwt.expiration-hours:24}") long jwtExpirationHours) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder(12);
        this.jwtKey = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        this.jwtExpirationHours = jwtExpirationHours;
    }

    /**
     * Autentica usuário e retorna JWT
     */
    public AuthResult authenticate(String username, String password) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new AuthException("Invalid credentials"));

        if (!user.getActive()) {
            throw new AuthException("User is disabled");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException("Invalid credentials");
        }

        // Atualiza último login
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        String token = generateToken(user);
        return new AuthResult(token, user);
    }

    /**
     * Gera JWT token com claims do usuário
     */
    private String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiration = now.plus(jwtExpirationHours, ChronoUnit.HOURS);

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getId());
        claims.put("username", user.getUsername());
        claims.put("email", user.getEmail());

        // Roles
        claims.put("roles", user.getRoles().stream()
            .map(Role::getName)
            .toList());

        // Permissões flatten
        claims.put("permissions", user.getRoles().stream()
            .flatMap(role -> role.getPermissions().stream())
            .map(Permission::getName)
            .distinct()
            .toList());

        return Jwts.builder()
            .setClaims(claims)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(expiration))
            .signWith(jwtKey, SignatureAlgorithm.HS256)
            .compact();
    }

    /**
     * Valida JWT token
     */
    public Jws<Claims> validateToken(String token) {
        try {
            return Jwts.parserBuilder()
                .setSigningKey(jwtKey)
                .build()
                .parseClaimsJws(token);
        } catch (JwtException e) {
            throw new AuthException("Invalid token", e);
        }
    }

    /**
     * Cria nova API key para o usuário
     */
    public ApiKeyResult createApiKey(String userId, String name, Instant expiresAt, Set<String> scopes) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new AuthException("User not found"));

        String rawKey = "afk_" + UUID.randomUUID().toString().replace("-", "");
        String keyHash = hashKey(rawKey);
        String keyPrefix = rawKey.substring(0, 8) + "...";

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(keyPrefix);
        apiKey.setName(name);
        apiKey.setUser(user);
        apiKey.setCreatedAt(Instant.now());
        apiKey.setExpiresAt(expiresAt);
        apiKey.setActive(true);
        apiKey.setScopes(scopes);

        apiKeyRepository.save(apiKey);

        return new ApiKeyResult(rawKey, keyPrefix, apiKey);
    }

    private String hashKey(String key) {
        // SHA-256 hash
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record AuthResult(String token, User user) {}
    public record ApiKeyResult(String key, String keyPrefix, ApiKey apiKey) {}
}
```

### 9.3 Security Configuration

```java
package org.archflow.security.config;

import org.archflow.security.filter.JwtAuthenticationFilter;
import org.archflow.security.filter.ApiKeyAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    private final JwtAuthenticationFilter jwtFilter;
    private final ApiKeyAuthenticationFilter apiKeyFilter;

    public SecurityConfiguration(
            JwtAuthenticationFilter jwtFilter,
            ApiKeyAuthenticationFilter apiKeyFilter) {
        this.jwtFilter = jwtFilter;
        this.apiKeyFilter = apiKeyFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // Endpoints públicos
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/register").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/docs").permitAll()

                // Swagger/OpenAPI
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Web Component assets
                .requestMatchers("/archflow-component/**").permitAll()

                // Demais endpoints requerem autenticação
                .anyRequest().authenticated()
            )
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("*")); // Configurar para domínios específicos
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

### 9.4 Anotações de Segurança

```java
package org.archflow.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Verifica se o usuário tem a permissão especificada
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    String value();

    /**
     * Se true, requer todas as permissões listadas
     * Se false, requer pelo menos uma
     */
    boolean requireAll() default true;
}
```

```java
package org.archflow.security.aspect;

import org.archflow.security.annotation.RequiresPermission;
import org.archflow.security.model.User;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PermissionAspect {

    @Before("@annotation(requiresPermission)")
    public void checkPermission(JoinPoint joinPoint, RequiresPermission requiresPermission) {
        User user = (User) SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();

        String permission = requiresPermission.value();
        boolean hasPermission = user.hasPermission(permission);

        if (!hasPermission) {
            throw new AccessDeniedException(
                "User does not have permission: " + permission
            );
        }
    }
}
```

### 9.5 Roles e Permissões Padrão

```java
package org.archflow.security.seed;

/**
 * Roles e permissões padrão do sistema
 */
public enum DefaultRole {

    ADMIN("Administrator", "Full system access"),
    WORKFLOW_DESIGNER("Workflow Designer", "Can create and edit workflows"),
    WORKFLOW_EXECUTOR("Workflow Executor", "Can execute workflows"),
    WORKFLOW_VIEWER("Workflow Viewer", "Read-only access to workflows"),
    API_USER("API User", "Access via API keys only");

    private final String displayName;
    private final String description;

    DefaultRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Permissões por role
     */
    public String[] getPermissions() {
        return switch (this) {
            case ADMIN -> new String[] {
                "workflow:*", "agent:*", "tool:*", "admin:*",
                "user:*", "apikey:*", "settings:*"
            };
            case WORKFLOW_DESIGNER -> new String[] {
                "workflow:read", "workflow:write", "workflow:delete",
                "agent:read", "tool:read"
            };
            case WORKFLOW_EXECUTOR -> new String[] {
                "workflow:read", "workflow:execute",
                "agent:execute", "tool:invoke"
            };
            case WORKFLOW_VIEWER -> new String[] {
                "workflow:read", "agent:read", "tool:read"
            };
            case API_USER -> new String[] {
                "workflow:execute", "agent:execute"
            };
        };
    }
}
```

### 9.6 API Endpoints

```java
package org.archflow.api.controller;

import org.archflow.security.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthResult result = authService.authenticate(
            request.username(),
            request.password()
        );
        return new LoginResponse(
            result.token(),
            new UserInfo(
                result.user().getId(),
                result.user().getUsername(),
                result.user().getEmail(),
                result.user().getRoles().stream().map(Role::getName).toList()
            )
        );
    }

    @PostMapping("/logout")
    public void logout() {
        // Stateless - cliente apenas descarta o token
    }

    @GetMapping("/me")
    public UserInfo getCurrentUser() {
        User user = (User) SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();
        return toUserInfo(user);
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, UserInfo user) {}
    public record UserInfo(String id, String username, String email, List<String> roles) {}
}
```

```java
package org.archflow.api.controller;

import org.archflow.security.annotation.RequiresPermission;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apikeys")
public class ApiKeyController {

    @PostMapping
    @RequiresPermission("apikey:create")
    public ApiKeyResponse createKey(@RequestBody CreateApiKeyRequest request) {
        // Cria API key
    }

    @GetMapping
    @RequiresPermission("apikey:read")
    public List<ApiKeyInfo> listKeys() {
        // Lista keys do usuário atual
    }

    @DeleteMapping("/{keyId}")
    @RequiresPermission("apikey:delete")
    public void revokeKey(@PathVariable String keyId) {
        // Revoga key
    }
}
```

---

## Sprint 10: Observability

### Objetivo
Implementar observabilidade completa com métricas, tracing e audit logs.

### Arquitetura de Observabilidade

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Observability Stack                              │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                    Ingestion Layer                             │    │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐  │    │
│  │  │  Metrics   │ │  Tracing   │ │   Audit    │ │   Health   │  │    │
│  │  │  (Microm.) │ │ (OpenTel.) │ │  (Custom)  │ │  (Actuator)│  │    │
│  │  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘  │    │
│  └────────┼───────────────┼───────────────┼───────────────┼────────┘    │
│           │               │               │               │             │
│           ▼               ▼               ▼               ▼             │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                      Storage/Export                             │    │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐  │    │
│  │  │ Prometheus │ │   Jaeger   │ │ PostgreSQL │ │  InfluxDB  │  │    │
│  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘  │    │
│  └────────────────────────────────────────────────────────────────┘    │
│           │               │               │               │             │
│           ▼               ▼               ▼               ▼             │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                      Visualization                             │    │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────────────────┐ │    │
│  │  │ Grafana    │ │ Jaeger UI  │ │ Audit Log Console         │ │    │
│  │  └────────────┘ └────────────┘ └────────────────────────────┘ │    │
│  └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 10.1 Métricas (Micrometer)

```java
package org.archflow.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Métricas customizadas do archflow
 */
@Component
public class ArchflowMetrics {

    private final MeterRegistry registry;

    // Counters
    private final Counter workflowExecutions;
    private final Counter workflowExecutionsSuccess;
    private final Counter workflowExecutionsFailure;
    private final Counter agentExecutions;
    private final Counter toolInvocations;
    private final Counter llmTokenUsage;

    // Timers
    private final Timer workflowExecutionTimer;
    private final Timer agentExecutionTimer;
    private final Timer toolInvocationTimer;
    private final Timer llmCallTimer;

    public ArchflowMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Counters
        this.workflowExecutions = Counter.builder("archflow.workflow.executions")
            .description("Total workflow executions")
            .tag("status", "total")
            .register(registry);

        this.workflowExecutionsSuccess = Counter.builder("archflow.workflow.executions")
            .description("Successful workflow executions")
            .tag("status", "success")
            .register(registry);

        this.workflowExecutionsFailure = Counter.builder("archflow.workflow.executions")
            .description("Failed workflow executions")
            .tag("status", "failure")
            .register(registry);

        this.agentExecutions = Counter.builder("archflow.agent.executions")
            .description("Total agent executions")
            .register(registry);

        this.toolInvocations = Counter.builder("archflow.tool.invocations")
            .description("Total tool invocations")
            .register(registry);

        this.llmTokenUsage = Counter.builder("archflow.llm.tokens")
            .description("Total LLM tokens used")
            .register(registry);

        // Timers
        this.workflowExecutionTimer = Timer.builder("archflow.workflow.execution.duration")
            .description("Workflow execution duration")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);

        this.agentExecutionTimer = Timer.builder("archflow.agent.execution.duration")
            .description("Agent execution duration")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

        this.toolInvocationTimer = Timer.builder("archflow.tool.invocation.duration")
            .description("Tool invocation duration")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

        this.llmCallTimer = Timer.builder("archflow.llm.call.duration")
            .description("LLM API call duration")
            .tag("provider", "none")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    // Workflow metrics
    public void recordWorkflowExecution() {
        workflowExecutions.increment();
    }

    public void recordWorkflowSuccess() {
        workflowExecutionsSuccess.increment();
    }

    public void recordWorkflowFailure() {
        workflowExecutionsFailure.increment();
    }

    public Timer.Sample startWorkflowExecution() {
        return Timer.start(registry);
    }

    public void stopWorkflowExecution(Timer.Sample sample) {
        sample.stop(workflowExecutionTimer);
    }

    // Agent metrics
    public void recordAgentExecution() {
        agentExecutions.increment();
    }

    public Timer.Sample startAgentExecution() {
        return Timer.start(registry);
    }

    public void stopAgentExecution(Timer.Sample sample) {
        sample.stop(agentExecutionTimer);
    }

    // Tool metrics
    public void recordToolInvocation(String toolName) {
        Counter.builder("archflow.tool.invocations")
            .description("Tool invocations by name")
            .tag("tool", toolName)
            .register(registry)
            .increment();
    }

    public Timer.Sample startToolInvocation() {
        return Timer.start(registry);
    }

    public void stopToolInvocation(Timer.Sample sample) {
        sample.stop(toolInvocationTimer);
    }

    // LLM metrics
    public void recordTokenUsage(String provider, String model, int inputTokens, int outputTokens) {
        Counter.builder("archflow.llm.tokens")
            .description("LLM token usage")
            .tag("provider", provider)
            .tag("model", model)
            .tag("type", "input")
            .register(registry)
            .increment(inputTokens);

        Counter.builder("archflow.llm.tokens")
            .description("LLM token usage")
            .tag("provider", provider)
            .tag("model", model)
            .tag("type", "output")
            .register(registry)
            .increment(outputTokens);
    }

    public Timer.Sample startLlmCall() {
        return Timer.start(registry);
    }

    public void stopLlmCall(Timer.Sample sample, String provider) {
        Timer.builder("archflow.llm.call.duration")
            .description("LLM API call duration")
            .tag("provider", provider)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(sample.stop(registry));
    }
}
```

### 10.2 Tracing (OpenTelemetry)

```java
package org.archflow.observability.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Component;

@Component
public class ArchflowTracer {

    private final Tracer tracer;

    public ArchflowTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Cria span para execução de workflow
     */
    public Span startWorkflowSpan(String workflowId, String executionId) {
        return tracer.spanBuilder("archflow.workflow.execute")
            .setAttribute("archflow.workflow.id", workflowId)
            .setAttribute("archflow.execution.id", executionId)
            .startSpan();
    }

    /**
     * Cria span para execução de agente
     */
    public Span startAgentSpan(String agentId, String executionId, Context parentContext) {
        return tracer.spanBuilder("archflow.agent.execute")
            .setParent(parentContext)
            .setAttribute("archflow.agent.id", agentId)
            .setAttribute("archflow.execution.id", executionId)
            .startSpan();
    }

    /**
     * Cria span para invocação de tool
     */
    public Span startToolSpan(String toolName, String toolCallId, Context parentContext) {
        return tracer.spanBuilder("archflow.tool.invoke")
            .setParent(parentContext)
            .setAttribute("archflow.tool.name", toolName)
            .setAttribute("archflow.tool.call_id", toolCallId)
            .startSpan();
    }

    /**
     * Cria span para chamada LLM
     */
    public Span startLlmSpan(String provider, String model, Context parentContext) {
        return tracer.spanBuilder("archflow.llm.call")
            .setParent(parentContext)
            .setAttribute("archflow.llm.provider", provider)
            .setAttribute("archflow.llm.model", model)
            .startSpan();
    }

    /**
     * Wrapper para execução com span
     */
    public <T> T withSpan(String spanName, Context parent, ThrowingSupplier<T> callable) {
        Span span = tracer.spanBuilder(spanName)
            .setParent(parent)
            .startSpan();

        try (var scope = span.makeCurrent()) {
            T result = callable.get();
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
```

### 10.3 Audit Logging

```java
package org.archflow.observability.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Evento de auditoria
 */
public class AuditEvent {

    private final String id;
    private final Instant timestamp;
    private final String userId;
    private final String username;
    private final AuditAction action;
    private final String resourceType;
    private final String resourceId;
    private final Map<String, Object> metadata;
    private final String ipAddress;
    private final String userAgent;
    private final boolean success;

    private AuditEvent(Builder builder) {
        this.id = builder.id;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.userId = builder.userId;
        this.username = builder.username;
        this.action = builder.action;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.metadata = builder.metadata;
        this.ipAddress = builder.ipAddress;
        this.userAgent = builder.userAgent;
        this.success = builder.success;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String toJson() {
        return """
            {
              "id": "%s",
              "timestamp": "%s",
              "user": {"id": "%s", "username": "%s"},
              "action": "%s",
              "resource": {"type": "%s", "id": "%s"},
              "metadata": %s,
              "source": {"ip": "%s", "userAgent": "%s"},
              "success": %s
            }
            """.formatted(
                id, timestamp, userId, username, action,
                resourceType, resourceId, serializeMetadata(metadata),
                ipAddress, userAgent, success
            );
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        // Simplificado - usar Jackson em produção
        return metadata.toString();
    }

    public static class Builder {
        private String id = java.util.UUID.randomUUID().toString();
        private Instant timestamp;
        private String userId;
        private String username;
        private AuditAction action;
        private String resourceType;
        private String resourceId;
        private Map<String, Object> metadata;
        private String ipAddress;
        private String userAgent;
        private boolean success = true;

        public Builder id(String id) { this.id = id; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder action(AuditAction action) { this.action = action; return this; }
        public Builder resourceType(String resourceType) { this.resourceType = resourceType; return this; }
        public Builder resourceId(String resourceId) { this.resourceId = resourceId; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder ipAddress(String ipAddress) { this.ipAddress = ipAddress; return this; }
        public Builder userAgent(String userAgent) { this.userAgent = userAgent; return this; }
        public Builder success(boolean success) { this.success = success; return this; }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
```

```java
package org.archflow.observability.audit;

/**
 * Ações de auditoria
 */
public enum AuditAction {

    // Workflow
    WORKFLOW_CREATE("workflow.create"),
    WORKFLOW_UPDATE("workflow.update"),
    WORKFLOW_DELETE("workflow.delete"),
    WORKFLOW_EXECUTE("workflow.execute"),
    WORKFLOW_IMPORT("workflow.import"),
    WORKFLOW_EXPORT("workflow.export"),

    // Agent
    AGENT_CREATE("agent.create"),
    AGENT_UPDATE("agent.update"),
    AGENT_DELETE("agent.delete"),
    AGENT_EXECUTE("agent.execute"),

    // Tool
    TOOL_INVOKE("tool.invoke"),
    TOOL_CREATE("tool.create"),
    TOOL_UPDATE("tool.update"),
    TOOL_DELETE("tool.delete"),

    // User
    USER_LOGIN("user.login"),
    USER_LOGOUT("user.logout"),
    USER_CREATE("user.create"),
    USER_UPDATE("user.update"),
    USER_DELETE("user.delete"),

    // API Key
    APIKEY_CREATE("apikey.create"),
    APIKEY_DELETE("apikey.delete"),
    APIKEY_REVOKE("apikey.revoke"),

    // Admin
    SETTINGS_UPDATE("settings.update"),
    ROLE_CREATE("role.create"),
    ROLE_UPDATE("role.update"),
    ROLE_DELETE("role.delete");

    private final String value;

    AuditAction(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
```

```java
package org.archflow.observability.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL = """
        INSERT INTO af_audit_log (
            id, timestamp, user_id, username, action,
            resource_type, resource_id, metadata,
            ip_address, user_agent, success
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(AuditEvent event) {
        jdbcTemplate.update(
            INSERT_SQL,
            event.getId(),
            event.getTimestamp(),
            event.getUserId(),
            event.getUsername(),
            event.getAction().getValue(),
            event.getResourceType(),
            event.getResourceId(),
            event.getSerializedMetadata(),
            event.getIpAddress(),
            event.getUserAgent(),
            event.isSuccess()
        );
    }

    public void saveToElasticsearch(AuditEvent event) {
        // Opcional: enviar para Elasticsearch para busca avançada
    }
}
```

### 10.4 Audit Interceptor

```java
package org.archflow.observability.audit;

import org.archflow.security.model.User;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;

@Component
public class AuditLogger {

    private final AuditLogRepository repository;

    public AuditLogger(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void log(AuditAction action, String resourceType, String resourceId) {
        User user = getCurrentUser();
        if (user == null) return;

        AuditEvent event = AuditEvent.builder()
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .userId(user.getId())
            .username(user.getUsername())
            .build();

        repository.save(event);
    }

    public void log(AuditAction action, String resourceType, String resourceId,
                     Map<String, Object> metadata) {
        User user = getCurrentUser();
        if (user == null) return;

        AuditEvent event = AuditEvent.builder()
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .userId(user.getId())
            .username(user.getUsername())
            .metadata(metadata)
            .build();

        repository.save(event);
    }

    public void logWithRequest(AuditAction action, String resourceType, String resourceId,
                                HttpServletRequest request) {
        User user = getCurrentUser();
        if (user == null) return;

        AuditEvent event = AuditEvent.builder()
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .userId(user.getId())
            .username(user.getUsername())
            .ipAddress(getClientIp(request))
            .userAgent(request.getHeader("User-Agent"))
            .build();

        repository.save(event);
    }

    private User getCurrentUser() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();

        if (principal instanceof User user) {
            return user;
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
```

### 10.5 Configuration

```java
package org.archflow.observability.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfiguration {

    @Bean
    public MeterRegistry meterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Bean
    public OpenTelemetry openTelemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(
                OtlpGrpcSpanExporter.builder()
                    .setEndpoint("http://localhost:4317")
                    .build()
            ).build())
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(
                io.opentelemetry.context.propagation.TextMapPropagator.composite()
            ))
            .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("archflow", "1.0.0");
    }
}
```

---

## Sprint 11: Func-Agent Mode

### Objetivo
Implementar modo de execução determinística para processos críticos.

### Arquitetura Func-Agent

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Func-Agent Execution Mode                        │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                     FuncAgentExecutor                          │    │
│  │                                                                  │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │    │
│  │  │   Input      │  │   Output     │  │    Retry     │        │    │
│  │  │  Validation  │  │  Validation  │  │   Policy     │        │    │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘        │    │
│  │         │                  │                  │                │    │
│  │         └──────────────────┴──────────────────┘                │    │
│  │                            │                                    │    │
│  │                            ▼                                    │    │
│  │                   ┌─────────────────┐                          │    │
│  │                   │  Deterministic  │                          │    │
│  │                   │  Execution      │                          │    │
│  │                   └─────────────────┘                          │    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  Output: JSON/CSV/XML com schema validado                               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 11.1 Func-Agent Configuration

```java
package org.archflow.agent.func;

import java.util.Map;

/**
 * Configuração de agente funcional (determinístico)
 */
public class FuncAgentConfig {

    /**
     * Se true, executa de forma determinística
     * - Sem criatividade
     * - Output estruturado
     * - Retry estrito em caso de falha
     */
    private boolean deterministic = true;

    /**
     * Schema de saída obrigatório
     */
    private OutputSchema outputSchema;

    /**
     * Formato de saída
     */
    private OutputFormat outputFormat = OutputFormat.JSON;

    /**
     * Política de retry
     */
    private RetryPolicy retryPolicy = RetryPolicy.STRICT;

    /**
     * Timeout por step
     */
    private int timeoutSeconds = 60;

    /**
     * Máximo de iterações
     */
    private int maxIterations = 10;

    /**
     * Se true, falha em qualquer erro
     */
    private boolean failOnError = true;

    /**
     * Variáveis de ambiente/função
     */
    private Map<String, Object> variables;

    /**
     * Instruções do sistema
     */
    private String systemInstructions;

    public enum OutputFormat {
        JSON, CSV, XML, YAML, PLAIN_TEXT
    }

    public enum RetryPolicy {
        NONE,       // Sem retry
        LENIENT,    // Retry com fallback
        STRICT,     // Retry com validação de schema
        EXPONENTIAL // Retry com backoff exponencial
    }

    public static class Builder {
        private final FuncAgentConfig config = new FuncAgentConfig();

        public Builder deterministic(boolean deterministic) {
            config.deterministic = deterministic;
            return this;
        }

        public Builder outputSchema(OutputSchema schema) {
            config.outputSchema = schema;
            return this;
        }

        public Builder outputFormat(OutputFormat format) {
            config.outputFormat = format;
            return this;
        }

        public Builder retryPolicy(RetryPolicy policy) {
            config.retryPolicy = policy;
            return this;
        }

        public Builder timeout(int seconds) {
            config.timeoutSeconds = seconds;
            return this;
        }

        public Builder maxIterations(int max) {
            config.maxIterations = max;
            return this;
        }

        public Builder failOnError(boolean fail) {
            config.failOnError = fail;
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            config.variables = variables;
            return this;
        }

        public Builder systemInstructions(String instructions) {
            config.systemInstructions = instructions;
            return this;
        }

        public FuncAgentConfig build() {
            return config;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
}
```

```java
package org.archflow.agent.func;

import java.util.Map;

/**
 * Schema de saída para validação
 */
public class OutputSchema {

    private String type;
    private Map<String, Property> properties;
    private String[] required;
    private boolean additionalProperties = false;

    public static OutputSchema json(Map<String, Property> properties) {
        OutputSchema schema = new OutputSchema();
        schema.setType("object");
        schema.setProperties(properties);
        return schema;
    }

    public static OutputSchema csv(String[] columns) {
        OutputSchema schema = new OutputSchema();
        schema.setType("csv");
        // Define colunas...
        return schema;
    }

    public static OutputSchema plainText() {
        OutputSchema schema = new OutputSchema();
        schema.setType("string");
        return schema;
    }

    public static class Property {
        private String type;
        private String description;
        private Object defaultValue;

        public Property(String type) {
            this.type = type;
        }

        public static Property string() {
            return new Property("string");
        }

        public static Property number() {
            return new Property("number");
        }

        public static Property booleanValue() {
            return new Property("boolean");
        }

        public static Property array(String itemType) {
            Property prop = new Property("array");
            prop.setItemType(itemType);
            return prop;
        }

        // Getters, setters
        private String itemType;
    }

    // Getters, setters
}
```

### 11.2 Func-Agent Executor

```java
package org.archflow.agent.func;

import org.archflow.observability.metrics.ArchflowMetrics;
import org.archflow.observability.tracing.ArchflowTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Executor de agente funcional (determinístico)
 */
public class FuncAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(FuncAgentExecutor.class);

    private final FuncAgentConfig config;
    private final ArchflowMetrics metrics;
    private final ArchflowTracer tracer;

    private final ExecutorService executor;

    public FuncAgentExecutor(FuncAgentConfig config,
                              ArchflowMetrics metrics,
                              ArchflowTracer tracer) {
        this.config = config;
        this.metrics = metrics;
        this.tracer = tracer;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executa agente de forma determinística
     */
    public FuncAgentResult execute(FuncAgentInput input) {
        var span = tracer.startWorkflowSpan("func-agent", input.getId());
        var timer = metrics.startWorkflowExecution();

        try {
            // Valida input
            validateInput(input);

            // Executa com retries
            FuncAgentResult result = executeWithRetry(input, 0);

            // Valida output
            validateOutput(result);

            metrics.recordWorkflowSuccess();
            return result;

        } catch (Exception e) {
            metrics.recordWorkflowFailure();
            if (config.isFailOnError()) {
                throw new FuncAgentExecutionException("Func-agent execution failed", e);
            }
            return FuncAgentResult.error(e.getMessage());

        } finally {
            span.end();
            timer.stop(metrics::stopWorkflowExecution);
        }
    }

    private FuncAgentResult executeWithRetry(FuncAgentInput input, int attempt) {
        try {
            return doExecute(input);

        } catch (Exception e) {
            if (shouldRetry(e, attempt)) {
                log.warn("Execution failed at attempt {}, retrying...", attempt);
                return executeWithRetry(input, attempt + 1);
            }
            throw e;
        }
    }

    private FuncAgentResult doExecute(FuncAgentInput input) {
        // Future para timeout
        Future<FuncAgentResult> future = executor.submit(() -> {
            // Executa lógica determinística
            return executeDeterministicLogic(input);
        });

        try {
            return future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            future.cancel(true);
            throw new FuncAgentTimeoutException(
                "Execution timeout after " + config.getTimeoutSeconds() + "s"
            );

        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new FuncAgentExecutionException("Execution interrupted", e);
        }
    }

    private FuncAgentResult executeDeterministicLogic(FuncAgentInput input) {
        // Executa com prompt estruturado para saída determinística
        String prompt = buildDeterministicPrompt(input);

        // Chama LLM com temperatura zero
        // ...

        String rawOutput = ""; // do LLM call

        // Parse e valida schema
        Object parsedOutput = parseOutput(rawOutput);

        return FuncAgentResult.success(parsedOutput);
    }

    private String buildDeterministicPrompt(FuncAgentInput input) {
        StringBuilder prompt = new StringBuilder();

        // Instruções do sistema
        if (config.getSystemInstructions() != null) {
            prompt.append(config.getSystemInstructions()).append("\n\n");
        }

        // Instruções de saída
        prompt.append("IMPORTANT: You must respond ONLY with valid ")
            .append(config.getOutputFormat())
            .append(" output. No explanations, no markdown.\n\n");

        // Schema se JSON
        if (config.getOutputFormat() == OutputFormat.JSON && config.getOutputSchema() != null) {
            prompt.append("Output Schema:\n")
                .append(config.getOutputSchema().toJson())
                .append("\n\n");
        }

        // Input
        prompt.append("Input:\n").append(input.toJson());

        return prompt.toString();
    }

    private void validateInput(FuncAgentInput input) {
        // Valida contra schema se definido
    }

    private void validateOutput(FuncAgentResult result) {
        if (config.getOutputSchema() != null) {
            config.getOutputSchema().validate(result.getOutput());
        }
    }

    private Object parseOutput(String rawOutput) {
        return switch (config.getOutputFormat()) {
            case JSON -> parseJson(rawOutput);
            case CSV -> parseCsv(rawOutput);
            case XML -> parseXml(rawOutput);
            case YAML -> parseYaml(rawOutput);
            case PLAIN_TEXT -> rawOutput;
        };
    }

    private boolean shouldRetry(Exception e, int attempt) {
        return switch (config.getRetryPolicy()) {
            case NONE -> false;
            case STRICT, LENIENT -> attempt < 3;
            case EXPONENTIAL -> attempt < 5;
        };
    }

    private Object parseJson(String raw) { /* ... */ return null; }
    private Object parseCsv(String raw) { /* ... */ return null; }
    private Object parseXml(String raw) { /* ... */ return null; }
    private Object parseYaml(String raw) { /* ... */ return null; }
}
```

### 11.3 Func-Agent DSL

```java
package org.archflow.agent.func;

/**
 * DSL para criar agentes funcionais
 */
public class FuncAgent {

    public static FuncAgentBuilder define(String name) {
        return new FuncAgentBuilder(name);
    }

    public static class FuncAgentBuilder {
        private final String name;
        private FuncAgentConfig config = new FuncAgentConfig();

        private FuncAgentBuilder(String name) {
            this.name = name;
        }

        public FuncAgentBuilder deterministic() {
            config.setDeterministic(true);
            return this;
        }

        public FuncAgentBuilder outputFormat(OutputFormat format) {
            config.setOutputFormat(format);
            return this;
        }

        public FuncAgentBuilder outputSchema(OutputSchema schema) {
            config.setOutputSchema(schema);
            return this;
        }

        public FuncAgentBuilder timeout(int seconds) {
            config.setTimeoutSeconds(seconds);
            return this;
        }

        public FuncAgentBuilder retry(RetryPolicy policy) {
            config.setRetryPolicy(policy);
            return this;
        }

        public FuncAgentBuilder instructions(String instructions) {
            config.setSystemInstructions(instructions);
            return this;
        }

        public FuncAgentBuilder variable(String name, Object value) {
            if (config.getVariables() == null) {
                config.setVariables(new ConcurrentHashMap<>());
            }
            config.getVariables().put(name, value);
            return this;
        }

        public FuncAgentExecutor build() {
            return new FuncAgentExecutor(config, metrics, tracer);
        }
    }
}
```

### 11.4 Exemplos de Uso

```java
// Exemplo 1: Extrator de dados estruturados
FuncAgent dataExtractor = FuncAgent.define("data-extractor")
    .deterministic()
    .outputFormat(OutputFormat.JSON)
    .outputSchema(OutputSchema.json(Map.of(
        "name", Property.string(),
        "email", Property.string(),
        "phone", Property.string(),
        "address", Property.string()
    )))
    .instructions("""
        Extract the following information from the text:
        - Full name
        - Email address
        - Phone number
        - Postal address

        Return ONLY valid JSON.
        """)
    .timeout(30)
    .retry(RetryPolicy.STRICT)
    .build();

// Exemplo 2: Processador de CSV
FuncAgent csvProcessor = FuncAgent.define("csv-processor")
    .deterministic()
    .outputFormat(OutputFormat.CSV)
    .outputSchema(OutputSchema.csv(new String[]{"id", "name", "value"}))
    .instructions("""
        Process the input data and generate CSV output.
        """)
    .build();

// Exemplo 3: Validador de documentos
FuncAgent documentValidator = FuncAgent.define("doc-validator")
    .deterministic()
    .outputFormat(OutputFormat.JSON)
    .outputSchema(OutputSchema.json(Map.of(
        "valid", Property.booleanValue(),
        "errors", Property.array("string"),
        "warnings", Property.array("string")
    )))
    .instructions("""
        Validate the document according to compliance rules.
        """)
    .failOnError(false)
    .build();
```

---

## Sprint 12: Multi-LLM Hub

### Objetivo
Implementar camada de abstração para múltiplos provedores LLM.

### Arquitetura Multi-LLM

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           LLM Provider Hub                               │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                     LLMProviderHub                              │    │
│  │                                                                  │    │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐│    │
│  │  │ OpenAI     │  │ Anthropic  │  │ Azure      │  │ AWS        ││    │
│  │  │ Provider   │  │ Provider   │  │ Provider   │  │ Provider   ││    │
│  │  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘│    │
│  └────────┼───────────────┼───────────────┼───────────────┼────────┘    │
│           │               │               │               │             │
│           ▼               ▼               ▼               ▼             │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                    Provider Registry                           │    │
│  │        + Model Mapping + Config + Load Balancing               │    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                   │                                     │
│                                   ▼                                     │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                    Unified LLM Interface                        │    │
│  │           ChatModel, StreamingChatModel, EmbeddingModel         │    │
│  └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 12.1 Provider Interface

```java
package org.archflow.llm.provider;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.image.ImageModel;

import java.util.Map;

/**
 * Provedor de LLM
 */
public interface LLMProvider {

    /**
     * Identificador único do provider
     */
    String getId();

    /**
     * Nome do provider
     */
    String getName();

    /**
     * Modelos disponíveis
     */
    Map<String, ModelInfo> getAvailableModels();

    /**
     * Cria modelo de chat
     */
    ChatLanguageModel createChatModel(String modelId, LLMConfig config);

    /**
     * Cria modelo de chat com streaming
     */
    StreamingChatLanguageModel createStreamingChatModel(String modelId, LLMConfig config);

    /**
     * Cria modelo de embedding
     */
    EmbeddingModel createEmbeddingModel(String modelId, LLMConfig config);

    /**
     * Cria modelo de imagem
     */
    ImageModel createImageModel(String modelId, LLMConfig config);

    /**
     * Valida configuração
     */
    void validateConfig(LLMConfig config) throws InvalidLLMConfigException;

    /**
     * Estima custo da requisição
     */
    CostEstimate estimateCost(String modelId, int inputTokens, int outputTokens);

    /**
     * Provider está disponível?
     */
    boolean isAvailable();
}
```

```java
package org.archflow.llm.provider;

import java.util.Map;

/**
 * Configuração de LLM
 */
public class LLMConfig {

    private String apiKey;
    private String baseUrl;
    private String organizationId;
    private String projectId;

    private double temperature = 0.7;
    private double topP = 1.0;
    private int maxTokens = 2048;
    private int maxRetries = 3;

    private Map<String, Object> additionalConfig;

    /**
     * Timeout em segundos
     */
    private int timeout = 60;

    /**
     * Se true, usa modelo com raciocínio (o1, etc.)
     */
    private boolean reasoningMode = false;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final LLMConfig config = new LLMConfig();

        public Builder apiKey(String key) { config.apiKey = key; return this; }
        public Builder baseUrl(String url) { config.baseUrl = url; return this; }
        public Builder temperature(double temp) { config.temperature = temp; return this; }
        public Builder maxTokens(int tokens) { config.maxTokens = tokens; return this; }
        public Builder timeout(int seconds) { config.timeout = seconds; return this; }
        public Builder reasoningMode(boolean reasoning) {
            config.reasoningMode = reasoning;
            return this;
        }

        public LLMConfig build() {
            return config;
        }
    }

    // Getters
}
```

```java
package org.archflow.llm.provider;

/**
 * Informações sobre um modelo
 */
public class ModelInfo {

    private final String id;
    private final String name;
    private final ModelType type;
    private final int contextWindow;
    private final Pricing pricing;
    private final boolean supportsStreaming;
    private final boolean supportsFunctionCalling;
    private final boolean supportsVision;
    private final boolean supportsReasoning;

    public enum ModelType {
        CHAT, EMBEDDING, IMAGE
    }

    public record Pricing(
        double inputPricePer1kTokens,
        double outputPricePer1kTokens
    ) {}

    public ModelInfo(String id, String name, ModelType type, int contextWindow,
                     Pricing pricing, boolean supportsStreaming,
                     boolean supportsFunctionCalling, boolean supportsVision,
                     boolean supportsReasoning) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.contextWindow = contextWindow;
        this.pricing = pricing;
        this.supportsStreaming = supportsStreaming;
        this.supportsFunctionCalling = supportsFunctionCalling;
        this.supportsVision = supportsVision;
        this.supportsReasoning = supportsReasoning;
    }

    // Getters
}
```

### 12.2 Provider Implementations

```java
package org.archflow.llm.provider.openai;

import org.archflow.llm.provider.*;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.Map;

public class OpenAIProvider implements LLMProvider {

    public static final String ID = "openai";

    private static final Map<String, ModelInfo> MODELS = Map.ofEntries(
        Map.entry("gpt-4o", new ModelInfo(
            "gpt-4o", "GPT-4o", ModelInfo.ModelType.CHAT, 128000,
            new ModelInfo.Pricing(0.0025, 0.01),
            true, true, true, false
        )),
        Map.entry("gpt-4o-mini", new ModelInfo(
            "gpt-4o-mini", "GPT-4o Mini", ModelInfo.ModelType.CHAT, 128000,
            new ModelInfo.Pricing(0.00015, 0.0006),
            true, true, true, false
        )),
        Map.entry("o1", new ModelInfo(
            "o1", "o1", ModelInfo.ModelType.CHAT, 200000,
            new ModelInfo.Pricing(0.015, 0.06),
            true, true, false, true
        )),
        Map.entry("o3-mini", new ModelInfo(
            "o3-mini", "o3-mini", ModelInfo.ModelType.CHAT, 200000,
            new ModelInfo.Pricing(0.0011, 0.0044),
            true, true, false, true
        ))
    );

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "OpenAI";
    }

    @Override
    public Map<String, ModelInfo> getAvailableModels() {
        return MODELS;
    }

    @Override
    public ChatLanguageModel createChatModel(String modelId, LLMConfig config) {
        return OpenAiChatModel.builder()
            .apiKey(config.getApiKey())
            .modelName(modelId)
            .temperature(config.getTemperature())
            .topP(config.getTopP())
            .maxTokens(config.getMaxTokens())
            .maxRetries(config.getMaxRetries())
            .timeout(java.time.Duration.ofSeconds(config.getTimeout()))
            .baseUrl(config.getBaseUrl())
            .organizationId(config.getOrganizationId())
            .build();
    }

    @Override
    public StreamingChatLanguageModel createStreamingChatModel(String modelId, LLMConfig config) {
        return OpenAiStreamingChatModel.builder()
            .apiKey(config.getApiKey())
            .modelName(modelId)
            .temperature(config.getTemperature())
            .topP(config.getTopP())
            .maxTokens(config.getMaxTokens())
            .maxRetries(config.getMaxRetries())
            .timeout(java.time.Duration.ofSeconds(config.getTimeout()))
            .baseUrl(config.getBaseUrl())
            .build();
    }

    @Override
    public CostEstimate estimateCost(String modelId, int inputTokens, int outputTokens) {
        ModelInfo model = MODELS.get(modelId);
        if (model == null) {
            return CostEstimate.unknown();
        }
        double inputCost = (inputTokens / 1000.0) * model.pricing().inputPricePer1kTokens();
        double outputCost = (outputTokens / 1000.0) * model.pricing().outputPricePer1kTokens();
        return new CostEstimate(inputCost + outputCost, "USD");
    }

    @Override
    public boolean isAvailable() {
        try {
            // Simple health check
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

```java
package org.archflow.llm.provider.anthropic;

public class AnthropicProvider implements LLMProvider {

    public static final String ID = "anthropic";

    private static final Map<String, ModelInfo> MODELS = Map.ofEntries(
        Map.entry("claude-sonnet-4-20250514", new ModelInfo(
            "claude-sonnet-4-20250514", "Claude Sonnet 4", ModelInfo.ModelType.CHAT, 200000,
            new ModelInfo.Pricing(0.003, 0.015),
            true, true, true, false
        )),
        Map.entry("claude-3-5-sonnet-20241022", new ModelInfo(
            "claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", ModelInfo.ModelType.CHAT, 200000,
            new ModelInfo.Pricing(0.003, 0.015),
            true, true, true, false
        ))
    );

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Anthropic";
    }

    @Override
    public Map<String, ModelInfo> getAvailableModels() {
        return MODELS;
    }

    @Override
    public ChatLanguageModel createChatModel(String modelId, LLMConfig config) {
        return AnthropicChatModel.builder()
            .apiKey(config.getApiKey())
            .model(modelId)
            .temperature(config.getTemperature())
            .maxTokens(config.getMaxTokens())
            .timeout(java.time.Duration.ofSeconds(config.getTimeout()))
            .build();
    }

    // ...
}
```

### 12.3 LLM Provider Hub

```java
package org.archflow.llm.hub;

import org.archflow.llm.provider.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hub central de providers LLM
 */
@Component
public class LLMProviderHub {

    private final Map<String, LLMProvider> providers = new ConcurrentHashMap<>();
    private final LoadBalancingStrategy loadBalancingStrategy;

    public LLMProviderHub(LoadBalancingStrategy loadBalancingStrategy) {
        this.loadBalancingStrategy = loadBalancingStrategy;
        registerDefaults();
    }

    private void registerDefaults() {
        register(new OpenAIProvider());
        register(new AnthropicProvider());
        register(new AzureOpenAIProvider());
        register(new AWSBedrockProvider());
        register(new GoogleGeminiProvider());
        register(new DeepSeekProvider());
    }

    public void register(LLMProvider provider) {
        providers.put(provider.getId(), provider);
    }

    public LLMProvider getProvider(String providerId) {
        LLMProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new UnknownProviderException("Provider not found: " + providerId);
        }
        return provider;
    }

    public List<LLMProvider> getAllProviders() {
        return new ArrayList<>(providers.values());
    }

    public List<LLMProvider> getAvailableProviders() {
        return providers.values().stream()
            .filter(LLMProvider::isAvailable)
            .toList();
    }

    public Map<String, ModelInfo> getAllModels() {
        Map<String, ModelInfo> allModels = new TreeMap<>();
        for (LLMProvider provider : providers.values()) {
            for (ModelInfo model : provider.getAvailableModels().values()) {
                allModels.put(provider.getId() + "/" + model.getId(), model);
            }
        }
        return allModels;
    }

    /**
     * Resolve provider:model para o modelo apropriado
     * Formato: "providerId/modelId" ou "modelId" (usa default)
     */
    public ResolvedModel resolveModel(String modelRef, LLMConfig config) {
        String[] parts = modelRef.split("/", 2);
        String providerId = parts.length == 2 ? parts[0] : getDefaultProvider();
        String modelId = parts.length == 2 ? parts[1] : parts[0];

        LLMProvider provider = getProvider(providerId);
        return new ResolvedModel(provider, modelId);
    }

    public ChatLanguageModel createChatModel(String modelRef, LLMConfig config) {
        ResolvedModel resolved = resolveModel(modelRef, config);
        return resolved.provider().createChatModel(resolved.modelId(), config);
    }

    private String getDefaultProvider() {
        return "openai"; // ou via configuração
    }

    public record ResolvedModel(LLMProvider provider, String modelId) {}
}
```

### 12.4 Model Mapping e Aliases

```java
package org.archflow.llm.hub;

/**
 * Mapeamento de modelos e aliases
 */
@Component
public class ModelRegistry {

    private final Map<String, ModelAlias> aliases = new ConcurrentHashMap<>();

    public ModelRegistry() {
        // Aliases populares
        registerAlias("gpt-4", "openai/gpt-4o");
        registerAlias("gpt-4-turbo", "openai/gpt-4o");
        registerAlias("gpt-3.5", "openai/gpt-4o-mini");
        registerAlias("claude", "anthropic/claude-sonnet-4-20250514");
        registerAlias("claude-3.5", "anthropic/claude-3-5-sonnet-20241022");
    }

    public void registerAlias(String alias, String targetModel) {
        aliases.put(alias, new ModelAlias(alias, targetModel));
    }

    public String resolve(String modelOrAlias) {
        ModelAlias alias = aliases.get(modelOrAlias);
        return alias != null ? alias.targetModel() : modelOrAlias;
    }

    public record ModelAlias(String alias, String targetModel) {}
}
```

### 12.5 Fallback e Load Balancing

```java
package org.archflow.llm.hub;

import java.util.List;

/**
 * Estratégia de load balancing para múltiplos providers
 */
public interface LoadBalancingStrategy {

    /**
     * Seleciona provider para a requisição
     */
    LLMProvider selectProvider(List<LLMProvider> providers, String modelId);
}

/**
 * Round-robin simples
 */
class RoundRobinStrategy implements LoadBalancingStrategy {

    private final java.util.concurrent.atomic.AtomicInteger counter = new AtomicInteger(0);

    @Override
    public LLMProvider selectProvider(List<LLMProvider> providers, String modelId) {
        int index = Math.abs(counter.getAndIncrement() % providers.size());
        return providers.get(index);
    }
}

/**
 * Com base em custo
 */
class CostBasedStrategy implements LoadBalancingStrategy {

    @Override
    public LLMProvider selectProvider(List<LLMProvider> providers, String modelId) {
        return providers.stream()
            .min(Comparator.comparing(p ->
                p.getAvailableModels().get(modelId).pricing().inputPricePer1kTokens()))
            .orElse(providers.get(0));
    }
}
```

```java
package org.archflow.llm.hub;

/**
 * Configuração de fallback
 */
public class FallbackConfig {

    private final String primaryModel;
    private final List<String> fallbackModels;
    private final int maxRetries;

    public FallbackConfig(String primaryModel, List<String> fallbackModels, int maxRetries) {
        this.primaryModel = primaryModel;
        this.fallbackModels = fallbackModels;
        this.maxRetries = maxRetries;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPrimaryModel() { return primaryModel; }
    public List<String> getFallbackModels() { return fallbackModels; }
    public int getMaxRetries() { return maxRetries; }

    public static class Builder {
        private String primaryModel;
        private List<String> fallbackModels = List.of();
        private int maxRetries = 3;

        public Builder primary(String model) {
            this.primaryModel = model;
            return this;
        }

        public Builder fallback(String... models) {
            this.fallbackModels = List.of(models);
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        public FallbackConfig build() {
            return new FallbackConfig(primaryModel, fallbackModels, maxRetries);
        }
    }
}
```

### 12.6 API Endpoints

```java
package org.archflow.api.controller;

import org.archflow.llm.hub.LLMProviderHub;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/llm")
public class LLMController {

    private final LLMProviderHub hub;

    @GetMapping("/providers")
    public List<ProviderInfo> listProviders() {
        return hub.getAllProviders().stream()
            .map(p -> new ProviderInfo(p.getId(), p.getName(), p.isAvailable()))
            .toList();
    }

    @GetMapping("/providers/{providerId}/models")
    public Map<String, ModelInfo> getProviderModels(@PathVariable String providerId) {
        return hub.getProvider(providerId).getAvailableModels();
    }

    @GetMapping("/models")
    public Map<String, ModelInfo> getAllModels() {
        return hub.getAllModels();
    }

    @PostMapping("/test")
    public TestResult testModel(@RequestBody TestRequest request) {
        // Testa modelo com prompt simples
    }

    public record ProviderInfo(String id, String name, boolean available) {}
    public record TestRequest(String model, String prompt) {}
    public record TestResult(boolean success, String response, long durationMs) {}
}
```

---

## Critérios de Sucesso da Fase 3

- [ ] Autenticação JWT funcionando com refresh token
- [ ] RBAC implementado com roles e permissões granulares
- [ ] API Keys para autenticação programática
- [ ] Métricas expostas via Prometheus endpoint
- [ ] Tracing com OpenTelemetry enviando para Jaeger
- [ ] Audit logs registrados em banco de dados
- [ ] Func-agent executando com output determinístico
- [ ] Switch entre providers LLM em tempo de execução
- [ ] Load balancing entre providers configurável
- [ ] Dashboard Grafana com métricas do archflow

---

## Próximos Passos

Após completar Fase 3, o sistema terá:
- Segurança enterprise (Auth, RBAC, API Keys)
- Observabilidade completa (Metrics, Tracing, Audit)
- Modo determinístico para processos críticos
- Multi-provider LLM com fallback

Próximo: **Fase 4 - Ecosystem** (Templates, Marketplace, Workflow-as-Tool)
