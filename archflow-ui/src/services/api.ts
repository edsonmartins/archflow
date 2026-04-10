const API_BASE = import.meta.env.VITE_API_BASE || '/api';

class ApiError extends Error {
    constructor(public status: number, message: string) {
        super(message);
        this.name = 'ApiError';
    }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
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
    login: (username: string, password: string) =>
        api.post<{ token: string; refreshToken: string }>('/auth/login', { username, password }),
    refresh: (refreshToken: string) =>
        api.post<{ token: string; refreshToken: string }>('/auth/refresh', { refreshToken }),
    me: () => api.get<{ id: string; username: string; name: string; roles: string[] }>('/auth/me'),
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
