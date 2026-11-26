package br.com.archflow.api.apikey.impl;

import br.com.archflow.api.apikey.ApiKeyController;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.security.apikey.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyControllerImpl")
class ApiKeyControllerImplTest {

    private static final String T = "tenant-a";

    @Mock
    private ApiKeyService apiKeyService;

    private ApiKeyControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new ApiKeyControllerImpl(apiKeyService);
    }

    private ApiKey createMockApiKey(String id, String keyId, String name) {
        var apiKey = new ApiKey();
        apiKey.setId(id);
        apiKey.setKeyId(keyId);
        apiKey.setName(name);
        apiKey.setOwnerId("owner-1");
        apiKey.setScopes(Set.of(ApiKeyScope.WORKFLOW_READ));
        apiKey.setEnabled(true);
        apiKey.setCreatedAt(LocalDateTime.now());
        return apiKey;
    }

    @Nested
    @DisplayName("createApiKey")
    class CreateApiKeyTest {

        @Test
        @DisplayName("should create api key successfully")
        void shouldCreateSuccessfully() {
            var request = new CreateApiKeyRequest("My Key", Set.of("WORKFLOW_READ"), null);
            var apiKey = createMockApiKey("id-1", "key-1", "My Key");
            var result = new ApiKeyService.ApiKeyWithSecret(apiKey, "secret-123");

            when(apiKeyService.createApiKey(T, "owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null))
                    .thenReturn(result);

            var response = controller.createApiKey(T, "owner-1", request);

            assertThat(response.id()).isEqualTo("id-1");
            assertThat(response.keyId()).isEqualTo("key-1");
            assertThat(response.keySecret()).isEqualTo("secret-123");
            // A credencial que o cliente põe no "Authorization: Bearer" — o segredo sozinho não autentica.
            assertThat(response.apiKey()).isEqualTo("key-1.secret-123");
            assertThat(response.name()).isEqualTo("My Key");
            assertThat(response.enabled()).isTrue();
        }

        @Test
        @DisplayName("should pass expiration to service")
        void shouldPassExpiration() {
            var expiry = LocalDateTime.now().plusDays(30);
            var request = new CreateApiKeyRequest("Key", Set.of("ADMIN"), expiry.toString());
            var apiKey = createMockApiKey("id-1", "key-1", "Key");
            var result = new ApiKeyService.ApiKeyWithSecret(apiKey, "secret");

            when(apiKeyService.createApiKey(T, "owner-1", "Key", Set.of(ApiKeyScope.ADMIN), expiry))
                    .thenReturn(result);

            controller.createApiKey(T, "owner-1", request);

            verify(apiKeyService).createApiKey(T, "owner-1", "Key", Set.of(ApiKeyScope.ADMIN), expiry);
        }
    }

    @Nested
    @DisplayName("escopos (RCHFLW-83)")
    class ScopesTest {

        @Test
        @DisplayName("publica o vocabulário do backend: nome da constante, permissão e descrição")
        void listsTheBackendScopes() {
            when(apiKeyService.grantableScopes("owner-1")).thenReturn(Set.of(ApiKeyScope.values()));
            var scopes = controller.listScopes("owner-1");

            assertThat(scopes).extracting(s -> s.name())
                    .containsExactly(java.util.Arrays.stream(ApiKeyScope.values()).map(Enum::name)
                            .toArray(String[]::new));
            assertThat(scopes).anySatisfy(s -> {
                assertThat(s.name()).isEqualTo("WORKFLOW_EXECUTE");
                assertThat(s.permission()).isEqualTo("workflow:execute");
            });
        }

        @Test
        @DisplayName("marca o que o papel de quem pergunta concede e o que ainda não abre endpoint nenhum")
        void marksGrantableAndUnusedScopes() {
            when(apiKeyService.grantableScopes("viewer-1"))
                    .thenReturn(Set.of(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.METRICS_READ));

            var scopes = controller.listScopes("viewer-1");

            assertThat(scopes).filteredOn(s -> s.grantable()).extracting(s -> s.name())
                    .containsExactlyInAnyOrder("WORKFLOW_READ", "METRICS_READ");
            // Nenhum endpoint aceita hoje uma chave só com escopo de agente ou de métricas.
            assertThat(scopes).filteredOn(s -> !s.opensEndpoints()).extracting(s -> s.name())
                    .containsExactlyInAnyOrder("AGENT_READ", "AGENT_MANAGE", "METRICS_READ");
        }

        @Test
        @DisplayName("cria com os escopos do vocabulário")
        void createsWithBackendScopes() {
            var request = new CreateApiKeyRequest("CI", Set.of("WORKFLOW_EXECUTE", "EXECUTION_READ"), null);
            var result = new ApiKeyService.ApiKeyWithSecret(createMockApiKey("id-1", "key-1", "CI"), "s");
            when(apiKeyService.createApiKey(T, "owner-1", "CI",
                    Set.of(ApiKeyScope.WORKFLOW_EXECUTE, ApiKeyScope.EXECUTION_READ), null)).thenReturn(result);

            assertThat(controller.createApiKey(T, "owner-1", request).keyId()).isEqualTo("key-1");
        }

        @Test
        @DisplayName("escopo fora do vocabulário é recusado com o valor e a lista válida — sem segundo formato")
        void rejectsUnknownScopeReadably() {
            for (String other : List.of("workflow.read", "workflow:read", "workflow_read")) {
                assertThatThrownBy(() -> controller.createApiKey(T, "owner-1",
                        new CreateApiKeyRequest("CI", Set.of(other), null)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Unknown scope '" + other + "'")
                        .hasMessageContaining("WORKFLOW_READ");
            }
            verifyNoInteractions(apiKeyService);
        }

        @Test
        @DisplayName("nome vazio ou nenhum escopo: 400 legível, nada criado")
        void requiresNameAndScopes() {
            assertThatThrownBy(() -> controller.createApiKey(T, "owner-1",
                    new CreateApiKeyRequest(" ", Set.of("WORKFLOW_READ"), null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("API key name is required");
            assertThatThrownBy(() -> controller.createApiKey(T, "owner-1",
                    new CreateApiKeyRequest("CI", Set.of(), null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("At least one scope is required");
            verifyNoInteractions(apiKeyService);
        }
    }

    @Nested
    @DisplayName("listApiKeys")
    class ListApiKeysTest {

        @Test
        @DisplayName("should list all keys for user")
        void shouldListKeys() {
            var keys = List.of(
                    createMockApiKey("id-1", "key-1", "Key 1"),
                    createMockApiKey("id-2", "key-2", "Key 2")
            );
            when(apiKeyService.listApiKeys(T, "owner-1")).thenReturn(keys);

            var response = controller.listApiKeys(T, "owner-1");

            assertThat(response).hasSize(2);
            assertThat(response.get(0).name()).isEqualTo("Key 1");
            assertThat(response.get(1).name()).isEqualTo("Key 2");
        }

        @Test
        @DisplayName("should return empty list when no keys")
        void shouldReturnEmptyList() {
            when(apiKeyService.listApiKeys(T, "owner-1")).thenReturn(List.of());

            var response = controller.listApiKeys(T, "owner-1");

            assertThat(response).isEmpty();
        }
    }

    @Nested
    @DisplayName("getApiKey")
    class GetApiKeyTest {

        @Test
        @DisplayName("should return key by id")
        void shouldReturnKey() {
            var apiKey = createMockApiKey("id-1", "key-1", "My Key");
            when(apiKeyService.getApiKey(T, "owner-1", "key-1")).thenReturn(apiKey);

            var response = controller.getApiKey(T, "owner-1", "key-1");

            assertThat(response.keyId()).isEqualTo("key-1");
            assertThat(response.name()).isEqualTo("My Key");
        }

        @Test
        @DisplayName("should throw NotFoundException when key not found")
        void shouldThrowWhenNotFound() {
            when(apiKeyService.getApiKey(T, "owner-1", "missing"))
                    .thenThrow(new ApiKeyService.ApiKeyNotFoundException("Not found"));

            assertThatThrownBy(() -> controller.getApiKey(T, "owner-1", "missing"))
                    .isInstanceOf(ApiKeyController.NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("revokeApiKey")
    class RevokeApiKeyTest {

        @Test
        @DisplayName("should revoke key successfully")
        void shouldRevokeSuccessfully() {
            assertThatNoException().isThrownBy(() -> controller.revokeApiKey(T, "owner-1", "key-1"));
            verify(apiKeyService).revokeApiKey(T, "owner-1", "key-1");
        }

        @Test
        @DisplayName("should throw NotFoundException when key not found")
        void shouldThrowWhenNotFound() {
            doThrow(new ApiKeyService.ApiKeyNotFoundException("Not found"))
                    .when(apiKeyService).revokeApiKey(T, "owner-1", "missing");

            assertThatThrownBy(() -> controller.revokeApiKey(T, "owner-1", "missing"))
                    .isInstanceOf(ApiKeyController.NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("audit trail")
    class AuditTest {

        private final br.com.archflow.observability.audit.InMemoryAuditRepository auditRepo =
                new br.com.archflow.observability.audit.InMemoryAuditRepository();

        private java.util.List<br.com.archflow.observability.audit.AuditEvent> events() {
            return auditRepo.query(
                    br.com.archflow.observability.audit.AuditRepository.AuditQuery.builder().limit(10));
        }

        @Test
        @DisplayName("criação de chave grava CREATE com o keyId e o ator")
        void createIsAudited() {
            controller.setAuditTrail(new br.com.archflow.api.audit.AuditTrail(() -> auditRepo));
            var request = new CreateApiKeyRequest("My Key", Set.of("WORKFLOW_READ"), null);
            var result = new ApiKeyService.ApiKeyWithSecret(
                    createMockApiKey("id-1", "key-1", "My Key"), "secret-123");
            when(apiKeyService.createApiKey(T, "owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null))
                    .thenReturn(result);

            controller.createApiKey(T, "owner-1", request);

            assertThat(events()).singleElement().satisfies(e -> {
                assertThat(e.getAction())
                        .isEqualTo(br.com.archflow.observability.audit.AuditAction.CREATE);
                assertThat(e.getResourceType()).isEqualTo("apikey");
                assertThat(e.getResourceId()).isEqualTo("key-1");
                assertThat(e.getUserId()).isEqualTo("owner-1");
            });
        }

        @Test
        @DisplayName("revogação grava DELETE; not-found grava falha")
        void revokeIsAudited() {
            controller.setAuditTrail(new br.com.archflow.api.audit.AuditTrail(() -> auditRepo));

            controller.revokeApiKey(T, "owner-1", "key-1");
            doThrow(new ApiKeyService.ApiKeyNotFoundException("Not found"))
                    .when(apiKeyService).revokeApiKey(T, "owner-1", "missing");
            assertThatThrownBy(() -> controller.revokeApiKey(T, "owner-1", "missing"))
                    .isInstanceOf(ApiKeyController.NotFoundException.class);

            var events = events();
            assertThat(events).hasSize(2);
            assertThat(events).allMatch(e ->
                    e.getAction() == br.com.archflow.observability.audit.AuditAction.DELETE);
            assertThat(events).anyMatch(e -> e.getResourceId().equals("key-1") && e.isSuccess());
            assertThat(events).anyMatch(e -> e.getResourceId().equals("missing") && !e.isSuccess());
        }
    }

    /** RCHFLW-195 itens 11 e 12: datas com fuso, validade no passado recusada e a chave vencida marcada. */
    @Nested
    @DisplayName("validade e datas (QA2)")
    class ValidadeQa2 {

        @Test
        @DisplayName("validade com Z é aquele instante, convertido para a hora local do servidor")
        void validadeComFuso() {
            String emUtc = java.time.Instant.now().plus(java.time.Duration.ofDays(3))
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
            var esperado = LocalDateTime.ofInstant(java.time.Instant.parse(emUtc), java.time.ZoneId.systemDefault());
            var apiKey = createMockApiKey("id-1", "key-1", "Key");
            when(apiKeyService.createApiKey(T, "owner-1", "Key", Set.of(ApiKeyScope.ADMIN), esperado))
                    .thenReturn(new ApiKeyService.ApiKeyWithSecret(apiKey, "secret"));

            controller.createApiKey(T, "owner-1", new CreateApiKeyRequest("Key", Set.of("ADMIN"), emUtc));

            verify(apiKeyService).createApiKey(T, "owner-1", "Key", Set.of(ApiKeyScope.ADMIN), esperado);
        }

        @Test
        @DisplayName("validade que já passou é recusada antes de criar a chave")
        void validadeNoPassado() {
            assertThatThrownBy(() -> controller.createApiKey(T, "owner-1",
                    new CreateApiKeyRequest("Key", Set.of("ADMIN"), "2020-11-25T10:00:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("future");
            org.mockito.Mockito.verifyNoInteractions(apiKeyService);
        }

        @Test
        @DisplayName("a listagem diz que a chave venceu e devolve instantes UTC")
        void vencidaNaListagem() {
            var vencida = createMockApiKey("id-1", "key-1", "Velha");
            var criada = LocalDateTime.now().minusDays(2);
            vencida.setCreatedAt(criada);
            vencida.setExpiresAt(LocalDateTime.now().minusDays(1));
            when(apiKeyService.listApiKeys(T, "owner-1")).thenReturn(java.util.List.of(vencida));

            var lista = controller.listApiKeys(T, "owner-1");

            assertThat(lista).singleElement().satisfies(r -> {
                assertThat(r.expired()).isTrue();
                assertThat(r.createdAt()).isEqualTo(criada.atZone(java.time.ZoneId.systemDefault()).toInstant());
            });
        }
    }
}
