package br.com.archflow.security.apikey;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.User;
import br.com.archflow.security.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing API keys.
 *
 * <p>This service handles:
 * <ul>
 *   <li>Creating new API keys with secure random generation</li>
 *   <li>Retrieving API keys by owner</li>
 *   <li>Revoking/disabling API keys</li>
 *   <li>Authenticating the credential a client presents ({@link #authenticate})</li>
 * </ul>
 */
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "ak_";
    private static final int KEY_ID_LENGTH = 16;
    private static final int KEY_SECRET_LENGTH = 32;

    /**
     * Separa o id público do segredo na credencial que o cliente envia. Fora do alfabeto base64url
     * em que os dois são gerados, então a divisão é inequívoca.
     */
    public static final String CREDENTIAL_SEPARATOR = ".";

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;

    /**
     * @param apiKeyRepository onde as chaves ficam
     * @param userRepository   o mesmo repositório de usuários do login: a chave age como o dono, então
     *                         o dono precisa existir, estar ativo e ter, pelo papel, cada permissão que
     *                         a chave exerce — sem ele não há como saber, e por isso é obrigatório
     */
    public ApiKeyService(ApiKeyRepository apiKeyRepository, UserRepository userRepository) {
        this.apiKeyRepository = Objects.requireNonNull(apiKeyRepository, "apiKeyRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    }

    /**
     * Creates a new API key for a user, in a tenant.
     *
     * <p>Dono e tenant são os do principal autenticado que pediu a chave — quem chama passa o que o
     * token garante, nunca um valor do cliente (RCHFLW-86). Os dois são obrigatórios: a requisição
     * autenticada pela chave age como este dono neste tenant, e uma chave sem tenant não teria onde
     * agir.
     *
     * <p>A chave não pode valer mais que o dono: cada escopo precisa de uma permissão que o papel dele
     * concede ({@link #grantableScopes}), no mesmo vocabulário {@code recurso:acao} dos papéis. Sem
     * isso, um visualizador criaria uma chave {@code WORKFLOW_DELETE} e excluiria por ela o que o
     * próprio papel não deixa.
     *
     * @param tenantId The tenant the key acts in
     * @param ownerId The ID of the user who will own the key
     * @param name The name of the API key
     * @param scopes The scopes (permissions) for the key
     * @param expiresAt Optional expiration date
     * @return The created API key with the secret (only shown once)
     * @throws ApiKeyForbiddenException dono inexistente ou desativado, ou escopo que o papel dele não concede
     */
    public ApiKeyWithSecret createApiKey(String tenantId, String ownerId, String name,
                                         Set<ApiKeyScope> scopes, LocalDateTime expiresAt) {
        requireText(tenantId, "tenantId");
        requireText(ownerId, "ownerId");
        User owner = activeOwner(ownerId).orElseThrow(() -> new ApiKeyForbiddenException(
                "The user '" + ownerId + "' does not exist or is disabled and cannot own an API key"));
        Set<ApiKeyScope> beyondRole = scopes == null ? Set.of() : scopes.stream()
                .filter(scope -> !owner.hasPermission(scope.getPermission()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ApiKeyScope.class)));
        if (!beyondRole.isEmpty()) {
            throw new ApiKeyForbiddenException("Your role does not grant the scope(s) "
                    + beyondRole.stream().map(Enum::name).collect(Collectors.joining(", "))
                    + "; an API key cannot exceed the permissions of its owner");
        }
        log.info("Creating API key for user: {} in tenant: {} with scopes: {}", ownerId, tenantId, scopes);

        String keyId = generateKeyId();
        String keySecret = generateKeySecret();
        String hashedSecret = hashSecret(keySecret);

        ApiKey apiKey = new ApiKey();
        apiKey.setId(generateId());
        apiKey.setKeyId(keyId);
        apiKey.setKeySecret(hashedSecret);
        apiKey.setOwnerId(ownerId);
        apiKey.setTenantId(tenantId);
        apiKey.setName(name);
        apiKey.setScopes(scopes);
        apiKey.setEnabled(true);
        apiKey.setCreatedAt(LocalDateTime.now());
        apiKey.setExpiresAt(expiresAt);

        ApiKey saved = apiKeyRepository.save(apiKey);

        log.info("API key created: {} for user: {}", keyId, ownerId);

        return new ApiKeyWithSecret(saved, keySecret);
    }

    /**
     * Os escopos que o usuário pode conceder a uma chave: os que o papel atual dele cobre. Usuário
     * inexistente ou desativado não concede nenhum.
     */
    public Set<ApiKeyScope> grantableScopes(String ownerId) {
        return activeOwner(ownerId)
                .map(owner -> Arrays.stream(ApiKeyScope.values())
                        .filter(scope -> owner.hasPermission(scope.getPermission()))
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(ApiKeyScope.class))))
                .orElseGet(() -> EnumSet.noneOf(ApiKeyScope.class));
    }

    /**
     * Lists the API keys of a user in a tenant.
     *
     * @param tenantId The tenant
     * @param ownerId The ID of the user
     * @return List of API keys
     */
    public List<ApiKey> listApiKeys(String tenantId, String ownerId) {
        return apiKeyRepository.findByOwnerId(ownerId).stream()
                .filter(key -> tenantId != null && tenantId.equals(key.getTenantId()))
                .toList();
    }

    /**
     * Quantas chaves do tenant ainda autenticam, de todos os donos — o número do resumo do workspace.
     * Nenhuma credencial sai daqui.
     *
     * <p>"Autentica" é a mesma condição de {@link #authenticate}: ativa, no prazo <b>e</b> com o dono
     * existente e ativo. Sem a última, a chave de um usuário removido seguia contada como ativa — ela
     * recebe 401 e não aparece em tela de chaves nenhuma, então o número dizia que havia acesso onde
     * não há.
     */
    public int countActiveKeys(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        java.util.Map<String, Boolean> ownerActive = new java.util.HashMap<>();
        return (int) apiKeyRepository.findByTenantId(tenantId).stream()
                .filter(ApiKey::isEnabled)
                .filter(key -> !key.isExpired())
                .filter(key -> ownerActive.computeIfAbsent(key.getOwnerId(),
                        owner -> activeOwner(owner).isPresent()))
                .count();
    }

    /**
     * Gets an API key by its ID.
     *
     * @param tenantId The tenant (for ownership verification)
     * @param ownerId The ID of the user (for ownership verification)
     * @param keyId The public key ID
     * @return The API key
     * @throws ApiKeyNotFoundException if the key doesn't exist or doesn't belong to the user in the tenant
     */
    public ApiKey getApiKey(String tenantId, String ownerId, String keyId) {
        return apiKeyRepository.findByKeyId(keyId)
                .filter(key -> key.getOwnerId().equals(ownerId))
                .filter(key -> tenantId != null && tenantId.equals(key.getTenantId()))
                .orElseThrow(() -> new ApiKeyNotFoundException("API key not found: " + keyId));
    }

    /**
     * Revokes (disables) an API key.
     *
     * @param tenantId The tenant (for ownership verification)
     * @param ownerId The ID of the user (for ownership verification)
     * @param keyId The public key ID
     * @throws ApiKeyNotFoundException if the key doesn't exist or doesn't belong to the user in the tenant
     */
    public void revokeApiKey(String tenantId, String ownerId, String keyId) {
        ApiKey apiKey = getApiKey(tenantId, ownerId, keyId);
        apiKey.setEnabled(false);
        apiKeyRepository.save(apiKey);

        log.info("API key revoked: {} for user: {}", keyId, ownerId);
    }

    /**
     * True quando o token tem o formato da credencial de chave de API ({@code ak_<id>.<segredo>}).
     *
     * <p>É o que separa uma chave de um JWT no mesmo {@code Authorization: Bearer}: um JWT começa pelo
     * cabeçalho JSON em base64url ({@code eyJ...}) e nunca por {@code ak_}.
     */
    public static boolean isApiKeyCredential(String token) {
        return token != null && token.startsWith(KEY_PREFIX);
    }

    /**
     * Autentica a credencial apresentada por um cliente ({@code ak_<id>.<segredo>}, a que
     * {@link ApiKeyWithSecret#credential()} devolve na criação).
     *
     * <p>Confere o segredo antes de olhar o estado da chave: "revogada"/"expirada" só é dito a quem
     * provou ter o segredo, os outros recebem "inválida". Em sucesso, registra o último uso.
     *
     * <p>O dono é relido a cada requisição, como o refresh do JWT relê o usuário: desativado ou
     * removido, as chaves dele param na hora — sem isso uma chave sem validade seguiria agindo como
     * ele para sempre. E o papel atual dele volta junto ({@link AuthenticatedApiKey#ownerPermits}),
     * para que rebaixar o papel corte também o que a chave alcança.
     *
     * @return a chave válida e o dono — dono, tenant e escopos são a identidade da requisição
     * @throws InvalidApiKeyException malformada, desconhecida, segredo errado, revogada, expirada ou
     *                                com o dono inexistente/desativado
     */
    public AuthenticatedApiKey authenticate(String credential) {
        int separator = credential == null ? -1 : credential.indexOf(CREDENTIAL_SEPARATOR);
        if (!isApiKeyCredential(credential) || separator <= KEY_PREFIX.length()
                || separator == credential.length() - 1) {
            throw new InvalidApiKeyException(InvalidApiKeyException.Reason.INVALID, "Invalid API key");
        }
        ApiKey apiKey;
        try {
            apiKey = validateApiKey(credential.substring(0, separator), credential.substring(separator + 1));
        } catch (ApiKeyNotFoundException e) {
            throw new InvalidApiKeyException(InvalidApiKeyException.Reason.INVALID, "Invalid API key");
        }
        if (!apiKey.isEnabled()) {
            throw new InvalidApiKeyException(InvalidApiKeyException.Reason.REVOKED, "API key has been revoked");
        }
        if (apiKey.isExpired()) {
            throw new InvalidApiKeyException(InvalidApiKeyException.Reason.EXPIRED, "API key has expired");
        }
        User owner = activeOwner(apiKey.getOwnerId()).orElseThrow(() -> new InvalidApiKeyException(
                InvalidApiKeyException.Reason.OWNER_INACTIVE,
                "The owner of this API key is disabled or no longer exists"));
        // Só a coluna do último uso: regravar a linha inteira com o objeto lido aqui desfaria uma
        // revogação gravada entre a leitura e esta escrita.
        LocalDateTime now = LocalDateTime.now();
        apiKeyRepository.markUsed(apiKey.getId(), now);
        apiKey.setLastUsedAt(now);
        return new AuthenticatedApiKey(apiKey, owner);
    }

    /**
     * Validates an API key by checking the hash.
     *
     * @param keyId The public key ID
     * @param keySecret The secret to verify
     * @return The API key if valid
     * @throws ApiKeyNotFoundException if the key doesn't exist or the secret is invalid
     */
    public ApiKey validateApiKey(String keyId, String keySecret) {
        ApiKey apiKey = apiKeyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new ApiKeyNotFoundException("API key not found: " + keyId));

        // Comparação constant-time: equals() de String vaza o prefixo
        // coincidente pelo tempo de resposta (timing attack).
        byte[] expected = apiKey.getKeySecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = hashSecret(keySecret).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new ApiKeyNotFoundException("Invalid API key secret");
        }

        return apiKey;
    }

    // Private helper methods

    /** O dono como o login o vê: existe e está ativo (a mesma condição do refresh do JWT). */
    private java.util.Optional<User> activeOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return java.util.Optional.empty();
        }
        return userRepository.findById(ownerId).filter(User::isEnabled);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required to create an API key");
        }
    }

    private String generateKeyId() {
        byte[] bytes = new byte[KEY_ID_LENGTH];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateKeySecret() {
        byte[] bytes = new byte[KEY_SECRET_LENGTH];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashSecret(String secret) {
        // SHA-256 sem salt é adequado AQUI (e é a prática de GitHub/Stripe para
        // tokens de API): o segredo é aleatório de 192 bits gerado por CSPRNG —
        // dicionário/rainbow são inviáveis e um KDF caro (BCrypt/Argon2) só
        // adicionaria ~100ms a CADA request autenticado. KDF caro é para senhas
        // escolhidas por humanos (ver PasswordService). A comparação no
        // validateApiKey é constant-time.
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // Inner classes

    /**
     * Uma chave que autenticou e o dono dela, relido nesta requisição.
     *
     * @param apiKey a chave (tenant, escopos)
     * @param owner  o dono, ativo, com o papel atual
     */
    public record AuthenticatedApiKey(ApiKey apiKey, User owner) {

        /**
         * True quando o papel atual do dono concede a permissão do escopo. A chave nunca vale mais que
         * o dono: um escopo concedido quando o papel era maior para de valer quando o papel diminui.
         */
        public boolean ownerPermits(ApiKeyScope scope) {
            return owner.hasPermission(scope.getPermission());
        }
    }

    /**
     * Wrapper for API key that includes the secret (only shown once).
     */
    public static class ApiKeyWithSecret {
        private final ApiKey apiKey;
        private final String secret;

        public ApiKeyWithSecret(ApiKey apiKey, String secret) {
            this.apiKey = apiKey;
            this.secret = secret;
        }

        public ApiKey apiKey() {
            return apiKey;
        }

        public String secret() {
            return secret;
        }

        public ApiKey getApiKey() {
            return apiKey;
        }

        public String getSecret() {
            return secret;
        }

        /**
         * A credencial completa que o cliente envia em {@code Authorization: Bearer}: id público e
         * segredo juntos. Como o segredo, só existe na resposta da criação.
         */
        public String credential() {
            return apiKey.getKeyId() + CREDENTIAL_SEPARATOR + secret;
        }
    }

    /**
     * Credencial de chave de API recusada. {@link Reason} diz por quê, para a resposta 401 ser
     * legível; nenhum dos casos devolve o segredo.
     */
    public static class InvalidApiKeyException extends RuntimeException {

        /** Por que a credencial foi recusada. */
        public enum Reason { INVALID, REVOKED, EXPIRED, OWNER_INACTIVE }

        private final Reason reason;

        public InvalidApiKeyException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /**
     * O usuário não pode ter esta chave: não existe/está desativado, ou pediu um escopo além do papel
     * dele. A mensagem é legível e não contém segredo nenhum.
     */
    public static class ApiKeyForbiddenException extends RuntimeException {
        public ApiKeyForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when an API key is not found.
     */
    public static class ApiKeyNotFoundException extends RuntimeException {
        public ApiKeyNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Repository interface for API key persistence.
     */
    public interface ApiKeyRepository {
        ApiKey save(ApiKey apiKey);
        List<ApiKey> findByOwnerId(String ownerId);

        /** As chaves de um tenant, de todos os donos — para a contagem do workspace. */
        List<ApiKey> findByTenantId(String tenantId);
        java.util.Optional<ApiKey> findByKeyId(String keyId);
        java.util.Optional<ApiKey> findById(String id);
        void delete(ApiKey apiKey);

        /** Grava só o último uso da chave (sem tocar em {@code enabled}, que uma revogação pode ter mudado). */
        void markUsed(String id, LocalDateTime usedAt);
    }
}
