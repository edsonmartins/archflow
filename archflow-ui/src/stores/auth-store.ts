import { create } from 'zustand';
import { authApi } from '../services/api';

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
        set({ user: null, token: null });
    },

    loadUser: async () => {
        if (!get().token) return;
        set({ loading: true });
        try {
            const res = await authApi.me();
            set({ user: { ...res, name: res.username, email: res.email ?? '' }, loading: false });
        } catch {
            set({ user: null, loading: false });
        }
    },

    clearError: () => set({ error: null }),
    isAuthenticated: () => !!get().token,
}));
