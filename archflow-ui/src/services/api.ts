import { mensagemDoCorpoDeErro } from '../lib/errorMessage';

const API_BASE = import.meta.env.VITE_API_BASE || '/api';

/**
 * Erro HTTP de `request()`. `message` é a frase legível extraída do corpo (`{message|detail|error}` ou texto
 * puro) — é o que alertas e toasts mostram; o corpo cru fica em `body` para quem precisar dos outros campos
 * (ex.: `line`/`column` do YAML inválido). Antes `message` recebia o corpo inteiro e a tela exibia JSON cru
 * (RCHFLW-65/94).
 */
class ApiError extends Error {
    constructor(public status: number, message: string, public body: string = message) {
        super(message);
        this.name = 'ApiError';
    }

    /** Corpo interpretado como JSON, ou `null` quando não é um objeto JSON. */
    get details(): Record<string, unknown> | null {
        try {
            const parsed: unknown = JSON.parse(this.body);
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
                ? parsed as Record<string, unknown> : null;
        } catch {
            return null;
        }
    }
}

// ── Refresh automático de token ─────────────────────────────────────
// Renova o access token antes de expirar (margem de 60s). Single-flight:
// requests concorrentes aguardam o mesmo refresh em vez de disparar vários.
const REFRESH_MARGIN_MS = 60_000;
let refreshInFlight: Promise<void> | null = null;

/** Payload do JWT sem validar assinatura — quem valida é o servidor; aqui só se lê para decidir o que mandar. */
function tokenPayload(token: string): Record<string, unknown> | null {
    try {
        const payload: unknown = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        return payload && typeof payload === 'object' ? payload as Record<string, unknown> : null;
    } catch {
        return null;
    }
}

/** Lê o `exp` do payload do JWT sem validar assinatura (só para agendar o refresh). */
export function tokenExpiresAt(token: string): number | null {
    const payload = tokenPayload(token);
    return payload && typeof payload.exp === 'number' ? payload.exp * 1000 : null;
}

/**
 * O tenant do usuário, pela claim `tenantId` do access token; `null` sem a claim (usuário do tenant padrão).
 * Existe para o caminho que leva o tenant por extenso (o WebSocket de voz): o servidor exige que ele seja o do
 * token, e o SPA não guarda o tenant próprio do usuário em outro lugar.
 */
export function tokenTenantId(token: string): string | null {
    const tenant = tokenPayload(token)?.tenantId;
    return typeof tenant === 'string' && tenant.trim() !== '' ? tenant.trim() : null;
}

async function ensureFreshToken(): Promise<void> {
    const token = sessionStorage.getItem('archflow_token');
    const refreshToken = sessionStorage.getItem('archflow_refresh_token');
    if (!token || !refreshToken) return;

    const expiresAt = tokenExpiresAt(token);
    if (expiresAt === null || expiresAt - Date.now() > REFRESH_MARGIN_MS) return;

    if (!refreshInFlight) {
        refreshInFlight = (async () => {
            try {
                const response = await fetch(`${API_BASE}/auth/refresh`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ refreshToken }),
                });
                if (!response.ok) return; // o request original recebe 401 e redireciona
                const res = await response.json();
                if (res.accessToken) sessionStorage.setItem('archflow_token', res.accessToken);
                if (res.refreshToken) sessionStorage.setItem('archflow_refresh_token', res.refreshToken);
            } catch {
                // rede indisponível: deixa o request original decidir (401 → login)
            } finally {
                refreshInFlight = null;
            }
        })();
    }
    await refreshInFlight;
}

/** Endpoints de auth não devem disparar refresh (evita recursão/loop). */
function skipsRefresh(path: string): boolean {
    return path.startsWith('/auth/login') || path.startsWith('/auth/refresh');
}

/**
 * `fetch` autenticado para URLs ABSOLUTAS (fora do prefixo `/api`, ex.:
 * `/ag-ui/agent`, `/archflow/...`). Aplica a MESMA lógica de `request()`:
 * refresh single-flight do token, header Authorization, X-Impersonate-Tenant
 * e Content-Type default. Ao contrário de `request()`, devolve o `Response`
 * cru (o chamador decide como parsear) e NÃO redireciona no 401 — cada
 * chamador (SSE/streaming/copilot) trata a falha do seu jeito. Use isto em
 * vez de `fetch` manual para não perder o refresh de token em sessões longas.
 */
export async function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
    await ensureFreshToken();
    const token = sessionStorage.getItem('archflow_token');
    const headers = new Headers(options.headers);
    const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
    if (!headers.has('Content-Type') && options.body && !isFormData) {
        headers.set('Content-Type', 'application/json');
    }
    if (token && !headers.has('Authorization')) {
        headers.set('Authorization', `Bearer ${token}`);
    }
    const impersonating = sessionStorage.getItem('archflow_impersonate_tenant');
    if (impersonating && !headers.has('X-Impersonate-Tenant')) {
        headers.set('X-Impersonate-Tenant', impersonating);
    }
    return fetch(url, { ...options, headers });
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    if (!skipsRefresh(path)) {
        await ensureFreshToken();
    }
    const token = sessionStorage.getItem('archflow_token');
    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...((options.headers as Record<string, string>) || {}),
    };
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    const impersonating = sessionStorage.getItem('archflow_impersonate_tenant');
    if (impersonating) {
        headers['X-Impersonate-Tenant'] = impersonating;
    }

    const response = await fetch(`${API_BASE}${path}`, { ...options, headers });

    if (response.status === 401) {
        sessionStorage.removeItem('archflow_token');
        sessionStorage.removeItem('archflow_refresh_token');
        window.location.href = '/login';
        throw new ApiError(401, 'Unauthorized');
    }

    if (!response.ok) {
        const body = await response.text().catch(() => '');
        const fallback = response.statusText || `HTTP ${response.status}`;
        throw new ApiError(response.status, mensagemDoCorpoDeErro(body, fallback), body);
    }

    if (response.status === 204) return undefined as T;
    return response.json();
}

export const api = {
    get: <T>(path: string) => request<T>(path),
    post: <T>(path: string, body?: unknown) =>
        request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
    put: <T>(path: string, body?: unknown) =>
        request<T>(path, { method: 'PUT', body: body ? JSON.stringify(body) : undefined }),
    patch: <T>(path: string, body?: unknown) =>
        request<T>(path, { method: 'PATCH', body: body ? JSON.stringify(body) : undefined }),
    delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};

// Auth API
export const authApi = {
    login: async (username: string, password: string) => {
        const res = await api.post<{ accessToken: string; refreshToken: string; userId: string; username: string; email: string; roles: string[] }>('/auth/login', { username, password });
        return { token: res.accessToken, refreshToken: res.refreshToken, userId: res.userId, username: res.username, email: res.email, roles: res.roles };
    },
    refresh: async (refreshToken: string) => {
        const res = await api.post<{ accessToken: string; refreshToken: string }>('/auth/refresh', { refreshToken });
        return { token: res.accessToken, refreshToken: res.refreshToken };
    },
    me: () => api.get<{ id: string; username: string; email: string; roles: string[] }>('/auth/me'),
    logout: () => api.post('/auth/logout'),
};

// Workflow API
export interface WorkflowSummary {
    id: string;
    name: string;
    description: string;
    version: string;
    /** Rótulo do ciclo de vida (draft/active/archived) que o "Ativar" troca; não muda o que roda. */
    status: string;
    /**
     * O número da versão implantada em PRODUCTION — a que o Rodar da lista executa —, ou {@code null} quando ele
     * executa o rascunho salvo (RCHFLW-132). Ausente num servidor anterior a esse campo.
     */
    publishedVersion?: number | null;
    updatedAt: string;
    stepCount: number;
}

export interface WorkflowDetail {
    id: string;
    metadata: { name: string; description: string; version: string; category: string; tags: string[] };
    steps: unknown[];
    configuration: unknown;
    /**
     * Carimbo da última gravação, posto pelo servidor. É a versão que o Salvar do editor manda no If-Match
     * (RCHFLW-125): se o fluxo mudou desde a leitura, o PUT responde 409.
     */
    updatedAt?: string;
}

export interface WorkflowVersion {
    id: string;
    workflowId: string;
    number: number;
    document: WorkflowDetail;
    comment: string | null;
    createdAt: string;
}

export interface WorkflowDeployment {
    workflowId: string;
    environment: string;
    versionId: string;
    deployedAt: string;
}

export const workflowApi = {
    list: () => api.get<WorkflowSummary[]>('/workflows'),
    get: (id: string) => api.get<WorkflowDetail>(`/workflows/${id}`),
    create: (workflow: Partial<WorkflowDetail>) => api.post<WorkflowDetail>('/workflows', workflow),
    /**
     * Com `expectedUpdatedAt`, o servidor só grava se o fluxo ainda está nessa versão — senão 409 (RCHFLW-125).
     * Sem ele, grava por cima, como sempre (o "Sobrescrever" do aviso de conflito).
     */
    update: (id: string, workflow: Partial<WorkflowDetail>, expectedUpdatedAt?: string) =>
        request<WorkflowDetail>(`/workflows/${id}`, {
            method: 'PUT',
            body: JSON.stringify(workflow),
            headers: expectedUpdatedAt ? { 'If-Match': `"${expectedUpdatedAt}"` } : undefined,
        }),
    delete: (id: string) => api.delete(`/workflows/${id}`),
    setStatus: (id: string, status: 'draft' | 'active' | 'archived') =>
        api.patch<WorkflowDetail>(`/workflows/${id}/status`, { status }),
    execute: (id: string, input?: Record<string, unknown>) =>
        api.post<{
            executionId: string; status: string; workflowVersionId: string | null;
            workflowDraft?: boolean; workflowVersionNumber?: number;
        }>(
            `/workflows/${id}/execute`, input),
    versions: (id: string) =>
        api.get<WorkflowVersion[]>(`/workflows/${id}/versions`),
    version: (id: string, versionId: string) =>
        api.get<WorkflowVersion>(`/workflows/${id}/versions/${versionId}`),
    publish: (id: string, comment?: string, environment = 'PRODUCTION') =>
        api.post<{ version: WorkflowVersion; deployment: WorkflowDeployment }>(
            `/workflows/${id}/publish`, { comment, environment }),
    deploy: (id: string, environment: string, versionId: string) =>
        api.put<WorkflowDeployment>(
            `/workflows/${id}/deployments/${environment}`, { versionId }),
    deployment: (id: string, environment = 'PRODUCTION') =>
        api.get<WorkflowDeployment>(`/workflows/${id}/deployments/${environment}`),
};

// Execution API
export interface ExecutionSummary {
    id: string;
    workflowId: string;
    workflowName: string;
    status: string;
    startedAt: string;
    completedAt: string | null;
    duration: number | null;
    error: string | null;
    /**
     * O que rodou, gravado na criação (RCHFLW-89): {@code true} = o rascunho; {@code false} = a versão
     * publicada {@link workflowVersionNumber}. Ausente num registro anterior a essa gravação.
     */
    workflowDraft?: boolean;
    workflowVersionId?: string | null;
    workflowVersionNumber?: number;
}

export const executionApi = {
    list: (options?: { workflowId?: string; limit?: number }) => {
        const params = new URLSearchParams();
        if (options?.workflowId) params.set('workflowId', options.workflowId);
        if (options?.limit) params.set('limit', String(options.limit));
        const query = params.toString();
        return api.get<ExecutionSummary[]>(query ? `/executions?${query}` : '/executions');
    },
    get: (id: string) => api.get<ExecutionSummary>(`/executions/${id}`),
    /** Cancela no servidor (marca CANCELLED; o passo em curso termina e o resultado é descartado). */
    cancel: (id: string) => api.post<ExecutionSummary>(`/executions/${encodeURIComponent(id)}/cancel`),
};

export { ApiError };
