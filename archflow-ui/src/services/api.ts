const API_BASE = import.meta.env.VITE_API_BASE || '/api';

class ApiError extends Error {
    constructor(public status: number, message: string) {
        super(message);
        this.name = 'ApiError';
    }
}

// ── Refresh automático de token ─────────────────────────────────────
// Renova o access token antes de expirar (margem de 60s). Single-flight:
// requests concorrentes aguardam o mesmo refresh em vez de disparar vários.
const REFRESH_MARGIN_MS = 60_000;
let refreshInFlight: Promise<void> | null = null;

/** Lê o `exp` do payload do JWT sem validar assinatura (só para agendar o refresh). */
export function tokenExpiresAt(token: string): number | null {
    try {
        const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        return typeof payload.exp === 'number' ? payload.exp * 1000 : null;
    } catch {
        return null;
    }
}

async function ensureFreshToken(): Promise<void> {
    const token = localStorage.getItem('archflow_token');
    const refreshToken = localStorage.getItem('archflow_refresh_token');
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
                if (res.accessToken) localStorage.setItem('archflow_token', res.accessToken);
                if (res.refreshToken) localStorage.setItem('archflow_refresh_token', res.refreshToken);
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

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    if (!skipsRefresh(path)) {
        await ensureFreshToken();
    }
    const token = localStorage.getItem('archflow_token');
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
        localStorage.removeItem('archflow_token');
        window.location.href = '/login';
        throw new ApiError(401, 'Unauthorized');
    }

    if (!response.ok) {
        const body = await response.text().catch(() => '');
        throw new ApiError(response.status, body || response.statusText);
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
    status: string;
    updatedAt: string;
    stepCount: number;
}

export interface WorkflowDetail {
    id: string;
    metadata: { name: string; description: string; version: string; category: string; tags: string[] };
    steps: unknown[];
    configuration: unknown;
}

export const workflowApi = {
    list: () => api.get<WorkflowSummary[]>('/workflows'),
    get: (id: string) => api.get<WorkflowDetail>(`/workflows/${id}`),
    create: (workflow: Partial<WorkflowDetail>) => api.post<WorkflowDetail>('/workflows', workflow),
    update: (id: string, workflow: Partial<WorkflowDetail>) => api.put<WorkflowDetail>(`/workflows/${id}`, workflow),
    delete: (id: string) => api.delete(`/workflows/${id}`),
    execute: (id: string, input?: Record<string, unknown>) =>
        api.post<{ executionId: string; status: string }>(`/workflows/${id}/execute`, input),
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
}

export const executionApi = {
    list: (workflowId?: string) =>
        api.get<ExecutionSummary[]>(workflowId ? `/executions?workflowId=${workflowId}` : '/executions'),
    get: (id: string) => api.get<ExecutionSummary>(`/executions/${id}`),
};

export { ApiError };
