import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../stores/auth-store';
import { useTenantStore, type UserRole } from '../stores/useTenantStore';

interface Props {
    children: React.ReactNode;
    requiredRole?: UserRole;
}

export default function ProtectedRoute({ children, requiredRole }: Props) {
    const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
    const { currentRole, impersonating } = useTenantStore();

    if (!isAuthenticated) {
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
