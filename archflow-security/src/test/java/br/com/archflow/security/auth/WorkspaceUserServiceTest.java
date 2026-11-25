package br.com.archflow.security.auth;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.WorkspaceUserService.WorkspaceUserException;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A tela de usuários do workspace opera sobre o login de verdade (RCHFLW-85): o convite cria um
 * acesso pendente que só entra depois do aceite, no tenant do workspace; trocar o papel e remover
 * mudam o que o {@link AuthService} e o {@link ApiKeyService} autenticam.
 */
@DisplayName("WorkspaceUserService — usuários reais do workspace")
class WorkspaceUserServiceTest {

    private static final String TENANT = "tenant_acme";
    private static final String OTHER_TENANT = "tenant_outro";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
    private InMemoryUserRepository users;
    private PasswordService passwords;
    private JwtService jwt;
    private AuthService auth;
    private WorkspaceUserService service;
    private User admin;

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        passwords = new PasswordService(4);
        jwt = new JwtService("uma-chave-de-teste-bem-longa-para-o-hmac-sha-256-0123456789");
        auth = new AuthService(jwt, passwords, users);
        service = new WorkspaceUserService(users, passwords, Duration.ofHours(72), clock);

        admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@acme.com");
        admin.setTenantId(TENANT);
        admin.setPasswordHash(passwords.hash("senha-do-admin"));
        admin.addRole(Role.createAdminRole());
        users.save(admin);
    }

    @Test
    @DisplayName("convite cria usuário pendente que não entra; aceite define a senha e o login entra no tenant")
    void inviteThenAcceptActivatesLoginInTheTenant() {
        WorkspaceUserService.Invitation invitation = service.invite(TENANT, " Viewer.Teste@Example.com ", "VIEWER");

        User pending = invitation.user();
        assertThat(pending.getUsername()).isEqualTo("viewer.teste@example.com");
        assertThat(pending.isEnabled()).isFalse();
        assertThat(pending.isInvitationPending()).isTrue();
        assertThat(pending.getPasswordHash()).isNull();
        assertThat(pending.getTenantId()).isEqualTo(TENANT);
        // Só o hash do segredo fica guardado; o token completo só existe na resposta.
        assertThat(invitation.token()).startsWith(pending.getId() + ".");
        assertThat(pending.getInvitationTokenHash()).isNotBlank()
                .doesNotContain(invitation.token().substring(pending.getId().length() + 1));
        assertThat(invitation.expiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 28, 12, 0));
        assertThatThrownBy(() -> auth.authenticate("viewer.teste@example.com", "qualquer-senha"))
                .isInstanceOf(AuthService.AuthenticationException.class)
                .hasMessageContaining("Invitation not accepted");

        User accepted = service.acceptInvitation(invitation.token(), "senha-nova-123");

        assertThat(accepted.isEnabled()).isTrue();
        assertThat(accepted.isInvitationPending()).isFalse();
        AuthService.AuthenticationResult login = auth.authenticate("viewer.teste@example.com", "senha-nova-123");
        assertThat(login.roles()).containsExactly(Role.ROLE_VIEWER);
        // O token leva o tenant do workspace que convidou — é o que o ImpersonationFilter lê.
        assertThat(jwt.validateToken(login.accessToken()).get("tenantId", String.class)).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("o link de aceite vale uma vez só")
    void invitationIsSingleUse() {
        var invitation = service.invite(TENANT, "ana@acme.com", "DESIGNER");
        service.acceptInvitation(invitation.token(), "senha-nova-123");

        assertThatThrownBy(() -> service.acceptInvitation(invitation.token(), "outra-senha-456"))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.INVALID));
        assertThat(auth.authenticate("ana@acme.com", "senha-nova-123").userId())
                .isEqualTo(invitation.user().getId());
    }

    @Test
    @DisplayName("segredo errado, token malformado e convite vencido são recusados com frase clara")
    void rejectsWrongExpiredAndMalformedTokens() {
        var invitation = service.invite(TENANT, "ana@acme.com", "VIEWER");

        String segredoErrado = invitation.user().getId() + ".segredo-errado";
        assertThatThrownBy(() -> service.acceptInvitation(segredoErrado, "senha-nova-123"))
                .hasMessageContaining("invalid or has already been used");
        assertThatThrownBy(() -> service.acceptInvitation("sem-separador", "senha-nova-123"))
                .hasMessageContaining("invalid or has already been used");
        assertThatThrownBy(() -> service.acceptInvitation(invitation.token(), "curta"))
                .hasMessageContaining("at least 8 characters");

        clock.advance(Duration.ofHours(73));
        assertThatThrownBy(() -> service.acceptInvitation(invitation.token(), "senha-nova-123"))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.EXPIRED));
    }

    @Test
    @DisplayName("e-mail repetido, e-mail inválido e papel fora do vocabulário do backend são recusados")
    void rejectsDuplicatesAndUnknownRoles() {
        service.invite(TENANT, "ana@acme.com", "VIEWER");

        assertThatThrownBy(() -> service.invite(OTHER_TENANT, "ANA@acme.com", "VIEWER"))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.CONFLICT));
        assertThatThrownBy(() -> service.invite(TENANT, "sem-arroba", "VIEWER"))
                .hasMessageContaining("Invalid e-mail");
        // "viewer" (o nome que a UI usava) não é um papel do backend: recusado, nunca convertido.
        assertThatThrownBy(() -> service.invite(TENANT, "bia@acme.com", "viewer"))
                .hasMessageContaining("Unknown role: viewer");
    }

    @Test
    @DisplayName("a lista é a do tenant: usuários reais (o admin incluído), sem os de outro tenant")
    void listsOnlyTheTenantsRealUsers() {
        service.invite(TENANT, "ana@acme.com", "VIEWER");
        service.invite(OTHER_TENANT, "zeca@outro.com", "VIEWER");

        assertThat(service.listUsers(TENANT)).extracting(User::getUsername)
                .containsExactly("admin", "ana@acme.com");
        assertThat(service.listUsers(OTHER_TENANT)).extracting(User::getUsername)
                .containsExactly("zeca@outro.com");
    }

    @Test
    @DisplayName("usuário sem tenant é do tenant padrão")
    void userWithoutTenantBelongsToDefaultTenant() {
        User legacy = new User();
        legacy.setUsername("legado");
        legacy.setPasswordHash(passwords.hash("senha-legada"));
        users.save(legacy);

        assertThat(service.listUsers("__default__")).extracting(User::getUsername).containsExactly("legado");
    }

    @Test
    @DisplayName("trocar o papel muda o acesso real: o refresh traz o papel novo e a chave perde o escopo")
    void changingRoleChangesRealAccess() {
        var invitation = service.invite(TENANT, "ana@acme.com", "DESIGNER");
        service.acceptInvitation(invitation.token(), "senha-nova-123");
        var login = auth.authenticate("ana@acme.com", "senha-nova-123");
        ApiKeyService keys = new ApiKeyService(new MapApiKeyRepository(), users);
        var key = keys.createApiKey(TENANT, login.userId(), "integração", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
        assertThat(keys.authenticate(key.credential()).ownerPermits(ApiKeyScope.WORKFLOW_EXECUTE)).isTrue();

        service.changeRole(TENANT, admin.getId(), login.userId(), "VIEWER");

        assertThat(auth.refreshToken(login.refreshToken()).roles()).containsExactly(Role.ROLE_VIEWER);
        assertThat(keys.authenticate(key.credential()).ownerPermits(ApiKeyScope.WORKFLOW_EXECUTE)).isFalse();
    }

    @Test
    @DisplayName("remover apaga o login: senha e refresh recusados, chave de API para na hora")
    void removingRevokesRealAccess() {
        var invitation = service.invite(TENANT, "ana@acme.com", "DESIGNER");
        service.acceptInvitation(invitation.token(), "senha-nova-123");
        var login = auth.authenticate("ana@acme.com", "senha-nova-123");
        ApiKeyService keys = new ApiKeyService(new MapApiKeyRepository(), users);
        var key = keys.createApiKey(TENANT, login.userId(), "integração", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        service.removeUser(TENANT, admin.getId(), login.userId());

        assertThat(users.findById(login.userId())).isEmpty();
        assertThatThrownBy(() -> auth.authenticate("ana@acme.com", "senha-nova-123"))
                .isInstanceOf(AuthService.AuthenticationException.class);
        assertThatThrownBy(() -> auth.refreshToken(login.refreshToken()))
                .isInstanceOf(AuthService.AuthenticationException.class);
        assertThatThrownBy(() -> keys.authenticate(key.credential()))
                .isInstanceOf(ApiKeyService.InvalidApiKeyException.class);
    }

    @Test
    @DisplayName("revogar o convite apaga o pendente e o link deixa de valer; só convites são revogáveis")
    void revokingInvitationInvalidatesTheLink() {
        var invitation = service.invite(TENANT, "ana@acme.com", "VIEWER");

        service.revokeInvitation(TENANT, invitation.user().getId());

        assertThat(users.findById(invitation.user().getId())).isEmpty();
        assertThatThrownBy(() -> service.acceptInvitation(invitation.token(), "senha-nova-123"))
                .hasMessageContaining("invalid or has already been used");
        assertThatThrownBy(() -> service.revokeInvitation(TENANT, admin.getId()))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.CONFLICT));
    }

    @Test
    @DisplayName("usuário de outro tenant não é encontrado; o admin não muda o próprio papel nem se remove")
    void refusesOtherTenantsAndSelf() {
        var other = service.invite(OTHER_TENANT, "zeca@outro.com", "VIEWER");

        assertThatThrownBy(() -> service.changeRole(TENANT, admin.getId(), other.user().getId(), "ADMIN"))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> service.revokeInvitation(TENANT, other.user().getId()))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> service.changeRole(TENANT, admin.getId(), admin.getId(), "VIEWER"))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.FORBIDDEN));
        assertThatThrownBy(() -> service.removeUser(TENANT, admin.getId(), admin.getId()))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.FORBIDDEN));
        assertThat(admin.hasRole(Role.ROLE_ADMIN)).isTrue();
    }

    @Test
    @DisplayName("os papéis expostos são os quatro do backend")
    void exposesBackendRoles() {
        assertThat(WorkspaceUserService.roles()).extracting(Role::getName)
                .containsExactly(Role.ROLE_ADMIN, Role.ROLE_DESIGNER, Role.ROLE_EXECUTOR, Role.ROLE_VIEWER);
    }

    /**
     * Dois aceites simultâneos do mesmo link: os dois leem o convite ainda pendente antes de qualquer
     * um gravar. A leitura velha é simulada por um repositório que devolve uma cópia do usuário como
     * estava antes do primeiro aceite. Antes, o segundo também passava (gravação incondicional) e a
     * última senha vencia; agora a escrita compara e apaga o hash, e o segundo é recusado.
     */
    @Test
    @DisplayName("aceite concorrente: só um consome o convite, o outro é recusado como link usado")
    void concurrentAcceptConsumesTheInvitationOnce() {
        StaleReadRepository stale = new StaleReadRepository();
        WorkspaceUserService concurrent = new WorkspaceUserService(stale, passwords, Duration.ofHours(72), clock);
        var invitation = concurrent.invite(TENANT, "ana@acme.com", "VIEWER");
        stale.freeze(invitation.user().getId());

        concurrent.acceptInvitation(invitation.token(), "senha-do-primeiro");

        assertThatThrownBy(() -> concurrent.acceptInvitation(invitation.token(), "senha-do-segundo"))
                .isInstanceOfSatisfying(WorkspaceUserException.class,
                        e -> assertThat(e.code()).isEqualTo(WorkspaceUserException.Code.INVITATION_INVALID));
        User stored = stale.storedUser(invitation.user().getId());
        assertThat(passwords.verify("senha-do-primeiro", stored.getPasswordHash())).isTrue();
        assertThat(stored.isInvitationPending()).isFalse();
    }

    /**
     * Dois convites simultâneos do mesmo e-mail: os dois passam pela conferência antes de qualquer um
     * gravar (simulado por um repositório cuja conferência não enxerga o primeiro). O segundo esbarra na
     * unicidade do nome de login e recebe o conflito tipado — antes, um erro genérico de gravação (500).
     */
    @Test
    @DisplayName("convite concorrente do mesmo e-mail vira conflito tipado, não erro de gravação")
    void concurrentInviteOfTheSameEmailIsAConflict() {
        InMemoryUserRepository blindCheck = new InMemoryUserRepository() {
            @Override
            public boolean existsByUsername(String username) {
                return false;
            }

            @Override
            public boolean existsByEmail(String email) {
                return false;
            }
        };
        WorkspaceUserService concurrent =
                new WorkspaceUserService(blindCheck, passwords, Duration.ofHours(72), clock);
        var first = concurrent.invite(TENANT, "ana@acme.com", "VIEWER");

        assertThatThrownBy(() -> concurrent.invite(TENANT, "ana@acme.com", "DESIGNER"))
                .isInstanceOfSatisfying(WorkspaceUserException.class, e -> {
                    assertThat(e.code()).isEqualTo(WorkspaceUserException.Code.USER_EXISTS);
                    assertThat(e.reason()).isEqualTo(WorkspaceUserException.Reason.CONFLICT);
                });
        assertThat(blindCheck.findByUsername("ana@acme.com")).map(User::getId).contains(first.user().getId());
        assertThat(blindCheck.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("cada recusa leva um código estável para a UI traduzir")
    void refusalsCarryAStableCode() {
        assertThat(codeOf(() -> service.invite(TENANT, "sem-arroba", "VIEWER")))
                .isEqualTo(WorkspaceUserException.Code.EMAIL_INVALID);
        assertThat(codeOf(() -> service.invite(TENANT, "x@acme.com", "SUPERUSER")))
                .isEqualTo(WorkspaceUserException.Code.ROLE_UNKNOWN);
        assertThat(codeOf(() -> service.invite(TENANT, "admin@acme.com", "VIEWER")))
                .isEqualTo(WorkspaceUserException.Code.USER_EXISTS);
        var invitation = service.invite(TENANT, "bia@acme.com", "VIEWER");
        assertThat(codeOf(() -> service.acceptInvitation(invitation.token(), "curta")))
                .isEqualTo(WorkspaceUserException.Code.PASSWORD_TOO_SHORT);
        assertThat(codeOf(() -> service.removeUser(TENANT, admin.getId(), invitation.user().getId())))
                .isEqualTo(WorkspaceUserException.Code.INVITATION_PENDING);
        assertThat(codeOf(() -> service.removeUser(TENANT, admin.getId(), admin.getId())))
                .isEqualTo(WorkspaceUserException.Code.SELF_REMOVE);
        assertThat(codeOf(() -> service.changeRole(TENANT, admin.getId(), admin.getId(), "VIEWER")))
                .isEqualTo(WorkspaceUserException.Code.SELF_ROLE_CHANGE);
        assertThat(codeOf(() -> service.revokeInvitation(TENANT, "nao-existe")))
                .isEqualTo(WorkspaceUserException.Code.USER_NOT_FOUND);
        clock.advance(Duration.ofHours(73));
        assertThat(codeOf(() -> service.acceptInvitation(invitation.token(), "senha-nova-123")))
                .isEqualTo(WorkspaceUserException.Code.INVITATION_EXPIRED);
    }

    private static WorkspaceUserException.Code codeOf(Runnable call) {
        try {
            call.run();
        } catch (WorkspaceUserException e) {
            return e.code();
        }
        throw new AssertionError("expected a WorkspaceUserException");
    }

    /**
     * Repositório em memória que, depois de {@link #freeze}, devolve para aquele id uma cópia do
     * usuário como estava no congelamento — a leitura de quem leu antes de outro gravar.
     */
    private static final class StaleReadRepository extends InMemoryUserRepository {
        private final Map<String, User> frozen = new ConcurrentHashMap<>();

        void freeze(String id) {
            User current = super.findById(id).orElseThrow();
            User copy = new User();
            copy.setId(current.getId());
            copy.setUsername(current.getUsername());
            copy.setEmail(current.getEmail());
            copy.setTenantId(current.getTenantId());
            copy.setEnabled(current.isEnabled());
            copy.setRoles(new java.util.HashSet<>(current.getRoles()));
            copy.setInvitationTokenHash(current.getInvitationTokenHash());
            copy.setInvitationExpiresAt(current.getInvitationExpiresAt());
            frozen.put(id, copy);
        }

        User storedUser(String id) {
            return super.findById(id).orElseThrow();
        }

        @Override
        public Optional<User> findById(String id) {
            User copy = frozen.get(id);
            if (copy == null) {
                return super.findById(id);
            }
            // Cópia nova a cada leitura: quem leu não vê o que outro gravou depois.
            User fresh = new User();
            fresh.setId(copy.getId());
            fresh.setUsername(copy.getUsername());
            fresh.setEmail(copy.getEmail());
            fresh.setTenantId(copy.getTenantId());
            fresh.setEnabled(copy.isEnabled());
            fresh.setRoles(new java.util.HashSet<>(copy.getRoles()));
            fresh.setInvitationTokenHash(copy.getInvitationTokenHash());
            fresh.setInvitationExpiresAt(copy.getInvitationExpiresAt());
            return Optional.of(fresh);
        }
    }

    /** Relógio que o teste avança. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Repositório de chaves em mapa, só para o teste. */
    private static final class MapApiKeyRepository implements ApiKeyService.ApiKeyRepository {
        private final Map<String, ApiKey> byKeyId = new ConcurrentHashMap<>();

        @Override
        public ApiKey save(ApiKey apiKey) {
            byKeyId.put(apiKey.getKeyId(), apiKey);
            return apiKey;
        }

        @Override
        public List<ApiKey> findByOwnerId(String ownerId) {
            return new ArrayList<>(byKeyId.values().stream().filter(k -> ownerId.equals(k.getOwnerId())).toList());
        }

        @Override
        public List<ApiKey> findByTenantId(String tenantId) {
            return byKeyId.values().stream().filter(k -> tenantId.equals(k.getTenantId())).toList();
        }

        @Override
        public Optional<ApiKey> findByKeyId(String keyId) {
            return Optional.ofNullable(byKeyId.get(keyId));
        }

        @Override
        public Optional<ApiKey> findById(String id) {
            return byKeyId.values().stream().filter(k -> id.equals(k.getId())).findFirst();
        }

        @Override
        public void delete(ApiKey apiKey) {
            byKeyId.remove(apiKey.getKeyId());
        }

        @Override
        public void markUsed(String id, LocalDateTime usedAt) {
            findById(id).ifPresent(k -> k.setLastUsedAt(usedAt));
        }
    }
}
