import { create } from 'zustand';
import { authApi } from '../services/api';
import { useTenantStore, type UserRole } from './useTenantStore';

/**
 * Maps backend role names (ADMIN/DESIGNER/EXECUTOR/VIEWER, with or without
 * ROLE_ prefix) to the UI role model. The backend has no SUPERADMIN role, so
 * ADMIN gets full UI access.
 */
function mapRolesToUserRole(roles: string[]): UserRole {
    const normalized = roles.map((r) => r.replace(/^ROLE_/i, '').toLowerCase());
    if (normalized.includes('superadmin') || normalized.includes('admin')) return 'superadmin';
    if (normalized.includes('tenant_admin')) return 'tenant_admin';
    if (normalized.includes('designer') || normalized.includes('executor') || normalized.includes('editor')) return 'editor';
    return 'viewer';
}

interface User {
    id: string;
    username: string;
    name: string;
    email: string;
    roles: string[];
}

interface AuthState {
    user: User | null;
    token: string | null;
    loading: boolean;
    error: string | null;

    login: (username: string, password: string) => Promise<void>;
    logout: () => Promise<void>;
    loadUser: () => Promise<void>;
    clearError: () => void;
    isAuthenticated: () => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
    user: null,
    token: sessionStorage.getItem('archflow_token'),
    loading: false,
    error: null,

    login: async (username, password) => {
        set({ loading: true, error: null });
        try {
            const { token, refreshToken } = await authApi.login(username, password);
            sessionStorage.setItem('archflow_token', token);
            sessionStorage.setItem('archflow_refresh_token', refreshToken);
            set({ token, loading: false });
            await get().loadUser();
        } catch (e) {
            set({ loading: false, error: e instanceof Error ? e.message : 'Login failed' });
            throw e;
        }
    },

    logout: async () => {
        try {
            await authApi.logout();
        } catch {
            // ignore logout errors
        }
        sessionStorage.removeItem('archflow_token');
        sessionStorage.removeItem('archflow_refresh_token');
        useTenantStore.getState().setRole('viewer');
        sessionStorage.removeItem('archflow_role');
        set({ user: null, token: null });
    },

    loadUser: async () => {
        if (!get().token) return;
        set({ loading: true });
        try {
            const res = await authApi.me();
            set({ user: { ...res, name: res.username, email: res.email ?? '' }, loading: false });
            useTenantStore.getState().setRole(mapRolesToUserRole(res.roles ?? []));
        } catch {
            set({ user: null, loading: false });
        }
    },

    clearError: () => set({ error: null }),
    isAuthenticated: () => !!get().token,
}));
