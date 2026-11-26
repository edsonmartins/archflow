package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.security.password.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Os usuários de um workspace (tenant), sobre o MESMO {@link UserRepository} do login (RCHFLW-85).
 *
 * <h2>Por que existe</h2>
 *
 * <p>A tela Admin › Usuários guardava usuários num mapa em memória semeado com dados de
 * demonstração: o convite só acrescentava uma linha, mudar a função ou remover não mexia em acesso
 * nenhum, e o administrador acreditava controlar acessos que não controlava. Aqui cada operação muda
 * o usuário que o {@link AuthService} autentica.
 *
 * <h2>Convite e aceite</h2>
 *
 * <p>O produto não envia e-mail. O convite cria um usuário <b>pendente</b> (sem senha, desativado)
 * no tenant do workspace e devolve, uma única vez, o segredo do link de aceite — o administrador
 * repassa o link. Só o hash do segredo é guardado, como o de uma chave de API. Aceitar define a
 * senha, ativa o login e consome o convite: o mesmo link não serve duas vezes.
 *
 * <h2>Papéis</h2>
 *
 * <p>O vocabulário é o dos papéis que o backend já tem ({@code ADMIN}, {@code DESIGNER},
 * {@code EXECUTOR}, {@code VIEWER}) — a UI monta o seletor a partir de {@link #roles()}, sem uma
 * segunda lista que pudesse divergir. Um nome fora dela é recusado, nunca convertido.
 *
 * <p>Nunca registra o segredo do convite nem senha em log.
 */
public class WorkspaceUserService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceUserService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    /** Mínimo de caracteres da senha definida no aceite. */
    public static final int MIN_PASSWORD_LENGTH = 8;
    /** Separa o id do usuário do segredo no token do convite — fora do alfabeto base64url. */
    private static final String TOKEN_SEPARATOR = ".";

    private final UserRepository users;
    private final PasswordService passwords;
    private final Duration invitationTtl;
    private final Clock clock;

    /**
     * @param users         o repositório do login
     * @param passwords     o hash de senha do login
     * @param invitationTtl quanto um convite vale até ser aceito
     */
    public WorkspaceUserService(UserRepository users, PasswordService passwords, Duration invitationTtl) {
        this(users, passwords, invitationTtl, Clock.systemDefaultZone());
    }

    /** Com relógio injetado, para teste de validade. */
    public WorkspaceUserService(UserRepository users, PasswordService passwords, Duration invitationTtl,
                                Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        if (invitationTtl == null || invitationTtl.isZero() || invitationTtl.isNegative()) {
            throw new IllegalArgumentException("invitationTtl must be positive");
        }
        this.invitationTtl = invitationTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Os papéis que um usuário pode ter — os do backend, com as permissões de cada um. */
    public static List<Role> roles() {
        return List.of(Role.createAdminRole(), Role.createDesignerRole(), Role.createExecutorRole(),
                Role.createViewerRole());
    }

    /**
     * Os usuários do tenant: os que entram nele pelo login, ativos e convidados.
     *
     * <p>Usuário sem tenant é do tenant padrão — a mesma leitura de um token sem a claim.
     */
    public List<User> listUsers(String tenantId) {
        String tenant = requireTenant(tenantId);
        return users.findAll().stream()
                .filter(user -> tenant.equals(DefaultTenant.orDefault(user.getTenantId())))
                .sorted(Comparator.comparing(User::getUsername, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Convida um e-mail para o tenant, com um papel.
     *
     * @return o usuário pendente e o token do link de aceite — o token só existe nesta resposta
     * @throws WorkspaceUserException {@code INVALID} para e-mail ou papel inválido, {@code CONFLICT}
     *                                quando já há um usuário com esse e-mail
     */
    public Invitation invite(String tenantId, String email, String roleName) {
        String tenant = requireTenant(tenantId);
        String normalizedEmail = normalizeEmail(email);
        Role role = resolveRole(roleName);
        // O e-mail é o nome de login do convidado: é o que ele conhece, e o produto não tem outro
        // identificador para lhe dar. Um e-mail já cadastrado (em qualquer tenant) é recusado — o
        // login é global, e dois usuários com o mesmo nome não teriam como entrar.
        if (users.existsByUsername(normalizedEmail) || users.existsByEmail(normalizedEmail)) {
            throw userExists(normalizedEmail);
        }
        String secret = generateSecret();
        User user = new User();
        user.setUsername(normalizedEmail);
        user.setEmail(normalizedEmail);
        user.setTenantId(tenant);
        user.setEnabled(false);
        user.addRole(role);
        user.setInvitationTokenHash(hash(secret));
        user.setInvitationExpiresAt(now().plus(invitationTtl));
        User saved;
        try {
            saved = users.save(user);
        } catch (DuplicateUserException e) {
            // A conferência acima e a gravação não são atômicas: dois convites simultâneos do mesmo
            // e-mail passam os dois por ela, e o segundo esbarra na unicidade do nome de login. A
            // resposta é o mesmo conflito da conferência, não um erro de gravação.
            throw userExists(normalizedEmail);
        }
        log.info("User invited: id={} tenant={} role={}", saved.getId(), tenant, role.getName());
        return new Invitation(saved, saved.getId() + TOKEN_SEPARATOR + secret, saved.getInvitationExpiresAt());
    }

    /**
     * Aceita um convite: define a senha e ativa o login. O convite é consumido.
     *
     * @return o usuário ativo, com o nome de login
     * @throws WorkspaceUserException {@code INVALID} para token desconhecido/já usado ou senha fraca,
     *                                {@code EXPIRED} para convite vencido
     */
    public User acceptInvitation(String token, String password) {
        int separator = token == null ? -1 : token.indexOf(TOKEN_SEPARATOR);
        if (separator <= 0 || separator == token.length() - 1) {
            throw invalidInvitation();
        }
        String userId = token.substring(0, separator);
        User user = users.findById(userId)
                .filter(User::isInvitationPending)
                .filter(candidate -> sameHash(candidate.getInvitationTokenHash(),
                        hash(token.substring(separator + 1))))
                .orElseThrow(WorkspaceUserService::invalidInvitation);
        if (user.getInvitationExpiresAt() != null && now().isAfter(user.getInvitationExpiresAt())) {
            throw new WorkspaceUserException(WorkspaceUserException.Code.INVITATION_EXPIRED,
                    "This invitation has expired; ask the administrator for a new one");
        }
        if (password == null || password.isBlank() || password.length() < MIN_PASSWORD_LENGTH) {
            throw new WorkspaceUserException(WorkspaceUserException.Code.PASSWORD_TOO_SHORT,
                    "The password must have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        // Uso único de verdade: a leitura acima não basta, porque dois aceites simultâneos passariam os
        // dois por ela. O repositório compara o hash lido e o apaga na mesma escrita; quem chegar
        // depois encontra o convite já consumido e recebe a mesma recusa de um link usado.
        if (!users.consumeInvitation(userId, user.getInvitationTokenHash(), passwords.hash(password))) {
            throw invalidInvitation();
        }
        User saved = users.findById(userId).orElseThrow(WorkspaceUserService::invalidInvitation);
        log.info("Invitation accepted: id={} tenant={}", saved.getId(), saved.getTenantId());
        return saved;
    }

    /**
     * Troca o papel de um usuário do tenant. Vale na hora: o filtro JWT autoriza pelos papéis atuais
     * do usuário ({@link AuthService#getUserInfoFromToken}), não pelos da claim do token que ele já
     * tem, e as chaves de API dele releem o dono a cada requisição.
     *
     * @throws WorkspaceUserException {@code NOT_FOUND} fora do tenant, {@code INVALID} papel
     *                                desconhecido, {@code FORBIDDEN} quando é o próprio usuário
     */
    public User changeRole(String tenantId, String actorUserId, String userId, String roleName) {
        Role role = resolveRole(roleName);
        User user = memberOf(tenantId, userId);
        refuseSelf(actorUserId, user, WorkspaceUserException.Code.SELF_ROLE_CHANGE, "change your own role");
        user.getRoles().clear();
        user.addRole(role);
        User saved = users.save(user);
        log.info("User role changed: id={} tenant={} role={}", saved.getId(), saved.getTenantId(), role.getName());
        return saved;
    }

    /**
     * Remove um usuário ativo do tenant: o login dele deixa de existir — o access token que ele já
     * tem é recusado na próxima requisição, o refresh também — e as chaves de API dele param na hora,
     * porque o dono não existe mais.
     *
     * @throws WorkspaceUserException {@code NOT_FOUND} fora do tenant, {@code CONFLICT} quando é um
     *                                convite (use {@link #revokeInvitation}), {@code FORBIDDEN} quando
     *                                é o próprio usuário
     */
    public void removeUser(String tenantId, String actorUserId, String userId) {
        User user = memberOf(tenantId, userId);
        refuseSelf(actorUserId, user, WorkspaceUserException.Code.SELF_REMOVE, "remove yourself");
        if (user.isInvitationPending()) {
            throw new WorkspaceUserException(WorkspaceUserException.Code.INVITATION_PENDING,
                    "This user has a pending invitation; revoke the invitation instead");
        }
        users.delete(user);
        log.info("User removed: id={} tenant={}", user.getId(), user.getTenantId());
    }

    /**
     * Revoga um convite ainda não aceito: o usuário pendente é apagado e o link deixa de valer.
     *
     * @throws WorkspaceUserException {@code NOT_FOUND} fora do tenant, {@code CONFLICT} quando o
     *                                convite já foi aceito
     */
    public void revokeInvitation(String tenantId, String userId) {
        User user = memberOf(tenantId, userId);
        if (!user.isInvitationPending()) {
            throw new WorkspaceUserException(WorkspaceUserException.Code.INVITATION_ALREADY_ACCEPTED,
                    "This user has already accepted the invitation; remove the user instead");
        }
        users.delete(user);
        log.info("Invitation revoked: id={} tenant={}", user.getId(), user.getTenantId());
    }

    // ── helpers ──────────────────────────────────────────────────

    private User memberOf(String tenantId, String userId) {
        String tenant = requireTenant(tenantId);
        // Usuário de outro tenant é "não encontrado", não "proibido": a resposta não confirma que o
        // id existe em outro workspace.
        return Optional.ofNullable(userId).flatMap(users::findById)
                .filter(user -> tenant.equals(DefaultTenant.orDefault(user.getTenantId())))
                .orElseThrow(() -> new WorkspaceUserException(WorkspaceUserException.Code.USER_NOT_FOUND,
                        "User not found: " + userId));
    }

    /**
     * O administrador não muda o próprio papel nem se remove pela tela: o efeito seria trancar o
     * workspace para fora de quem o administra, sem volta pela própria tela.
     */
    private static void refuseSelf(String actorUserId, User user, WorkspaceUserException.Code code,
                                   String action) {
        if (actorUserId != null && actorUserId.equals(user.getId())) {
            throw new WorkspaceUserException(code,
                    "You cannot " + action + "; ask another administrator");
        }
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenantId.trim();
    }

    private static String normalizeEmail(String email) {
        String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        // Só a forma mínima de um endereço: algo@algo.algo, sem espaços. Validar mais que isso é
        // trabalho de quem entrega e-mail, e o produto não entrega.
        if (!value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new WorkspaceUserException(WorkspaceUserException.Code.EMAIL_INVALID,
                    "Invalid e-mail address: " + (email == null ? "" : email.trim()));
        }
        return value;
    }

    private static Role resolveRole(String roleName) {
        return roles().stream()
                .filter(role -> role.getName().equals(roleName))
                .findFirst()
                .orElseThrow(() -> new WorkspaceUserException(WorkspaceUserException.Code.ROLE_UNKNOWN,
                        "Unknown role: " + roleName + " (expected one of "
                                + roles().stream().map(Role::getName).toList() + ")"));
    }

    private static WorkspaceUserException invalidInvitation() {
        return new WorkspaceUserException(WorkspaceUserException.Code.INVITATION_INVALID,
                "This invitation link is invalid or has already been used");
    }

    private static WorkspaceUserException userExists(String email) {
        return new WorkspaceUserException(WorkspaceUserException.Code.USER_EXISTS,
                "A user with the e-mail " + email + " already exists");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 sem salt, como o segredo de uma chave de API: o segredo é aleatório de 256 bits, então
     * dicionário é inviável e um KDF caro não acrescenta nada.
     */
    private static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Comparação em tempo constante: {@code equals} vazaria o prefixo coincidente pelo tempo. */
    private static boolean sameHash(String expected, String actual) {
        return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Um convite recém-criado.
     *
     * @param user      o usuário pendente
     * @param token     o token do link de aceite ({@code <id>.<segredo>}); só existe nesta resposta
     * @param expiresAt até quando vale
     */
    public record Invitation(User user, String token, LocalDateTime expiresAt) {
    }

    /**
     * Operação recusada: o {@link Code} (o caso exato), o {@link Reason} (o status HTTP) e uma frase
     * legível em inglês, para clientes de API.
     *
     * <p>O código existe porque a frase é uma só e a UI fala pt-BR e inglês: a tela traduz pelo código,
     * e o {@code Reason} sozinho não basta — {@code INVALID} cobre e-mail, papel, senha e link.
     */
    public static class WorkspaceUserException extends RuntimeException {

        /** Por que a operação foi recusada — o que decide o status HTTP. */
        public enum Reason { NOT_FOUND, CONFLICT, INVALID, EXPIRED, FORBIDDEN }

        /** O caso exato da recusa, estável para a UI traduzir. */
        public enum Code {
            BODY_REQUIRED(Reason.INVALID),
            EMAIL_INVALID(Reason.INVALID),
            ROLE_UNKNOWN(Reason.INVALID),
            USER_EXISTS(Reason.CONFLICT),
            USER_NOT_FOUND(Reason.NOT_FOUND),
            SELF_ROLE_CHANGE(Reason.FORBIDDEN),
            SELF_REMOVE(Reason.FORBIDDEN),
            INVITATION_PENDING(Reason.CONFLICT),
            INVITATION_ALREADY_ACCEPTED(Reason.CONFLICT),
            INVITATION_INVALID(Reason.INVALID),
            INVITATION_EXPIRED(Reason.EXPIRED),
            PASSWORD_TOO_SHORT(Reason.INVALID);

            private final Reason reason;

            Code(Reason reason) {
                this.reason = reason;
            }

            public Reason reason() {
                return reason;
            }
        }

        private final Code code;

        public WorkspaceUserException(Code code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public Code code() {
            return code;
        }

        public Reason reason() {
            return code.reason();
        }
    }
}
