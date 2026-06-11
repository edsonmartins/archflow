package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.security.apikey.ApiKeyService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação em memória do ApiKeyRepository — adequada apenas para
 * dev/teste (chaves perdidas no restart). Em profiles de produção o
 * {@link ProductionReadinessGuard} falha o boot se esta implementação
 * estiver ativa; forneça um bean durável para sobrescrevê-la.
 */
class InMemoryApiKeyRepository implements ApiKeyService.ApiKeyRepository {

    private final ConcurrentHashMap<String, ApiKey> store = new ConcurrentHashMap<>();

    @Override
    public ApiKey save(ApiKey key) {
        store.put(key.getId(), key);
        return key;
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<ApiKey> findByKeyId(String keyId) {
        return store.values().stream().filter(k -> keyId.equals(k.getKeyId())).findFirst();
    }

    @Override
    public List<ApiKey> findByOwnerId(String ownerId) {
        return store.values().stream().filter(k -> ownerId.equals(k.getOwnerId())).toList();
    }

    @Override
    public void delete(ApiKey key) {
        store.remove(key.getId());
    }
}
