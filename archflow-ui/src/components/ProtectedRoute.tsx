import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth-store';
import { useTenantStore, type UserRole } from '../stores/useTenantStore';
import { tokenExpiresAt } from '../services/api';

interface Props {
    children: React.ReactNode;
    requiredRole?: UserRole;
}

/**
 * Sessão utilizável: access token válido, OU expirado mas com refresh token
 * (o interceptor da API renova na próxima chamada). Presença sozinha deixava
 * sessões mortas navegarem até o primeiro 401.
 */
function hasValidToken(): boolean {
    const token = sessionStorage.getItem('archflow_token');
    if (!token) return false;
    const expiresAt = tokenExpiresAt(token);
    if (expiresAt === null || expiresAt > Date.now()) return true;
    return !!sessionStorage.getItem('archflow_refresh_token');
}

export default function ProtectedRoute({ children, requiredRole }: Props) {
    const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
    const { currentRole, impersonating } = useTenantStore();

    if (!isAuthenticated || !hasValidToken()) {
        return <Navigate to="/login" replace />;
    }

    if (requiredRole) {
        const hasAccess =
            currentRole === 'superadmin' ||
            currentRole === requiredRole ||
            (requiredRole === 'tenant_admin' && impersonating !== null);

        if (!hasAccess) {
            return <Navigate to="/" replace />;
        }
    }

    return <>{children}</>;
}
