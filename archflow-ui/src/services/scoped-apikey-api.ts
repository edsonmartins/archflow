import { api } from './api'

/**
 * Um escopo que uma chave pode receber, como o backend o publica em `GET /api/apikeys/scopes`.
 * `name` (ex.: `WORKFLOW_EXECUTE`) é o valor que a criação aceita e que a listagem devolve em
 * `scopes` — o único vocabulário (RCHFLW-83). A UI não mantém uma lista própria: a antiga
 * (`workflow.read`…) não existia no backend e toda criação falhava com 400.
 */
export interface ApiKeyScopeOption {
    name: string
    permission: string
    description: string
    /** O papel atual do usuário concede este escopo; a criação recusa (403) o que não conceder. */
    grantable: boolean
    /** Alguma rota aceita uma chave com este escopo; `false` = uma chave só com ele recebe 403 em tudo. */
    opensEndpoints: boolean
}

export interface ScopedApiKey {
    id: string
    keyId: string
    name: string
    scopes: string[]
    createdAt: string
    expiresAt?: string
    lastUsedAt?: string
    enabled: boolean
    /** Venceu pela validade: não autentica, embora siga `enabled` (RCHFLW-195 item 12). */
    expired?: boolean
}

export interface CreateScopedApiKeyRequest {
    name: string
    scopes: string[]
    /** Instante ISO-8601 com fuso (`…Z`); ausente = não vence. */
    expiresAt?: string
}

export interface CreateScopedApiKeyResponse extends ScopedApiKey {
    keySecret: string
    /** Credencial completa para `Authorization: Bearer` — só existe nesta resposta. */
    apiKey: string
}

/**
 * `/api/apikeys/*` pelo `request()` comum: token, refresh e erro legível (`ApiError.message` já é a
 * frase do corpo, não o JSON cru). O dono da chave é o usuário do token — o servidor ignora
 * `X-User-Id`, que esta camada mandava (RCHFLW-86).
 *
 * <p>Ver, consultar e revogar usam o `keyId` (id público), que é o que o backend procura.</p>
 */
export const scopedApiKeyApi = {
    scopes: () => api.get<ApiKeyScopeOption[]>('/apikeys/scopes'),
    list:   () => api.get<ScopedApiKey[]>('/apikeys'),
    get:    (keyId: string) => api.get<ScopedApiKey>(`/apikeys/${encodeURIComponent(keyId)}`),
    create: (body: CreateScopedApiKeyRequest) => api.post<CreateScopedApiKeyResponse>('/apikeys', body),
    remove: (keyId: string) => api.delete<void>(`/apikeys/${encodeURIComponent(keyId)}`),
}
