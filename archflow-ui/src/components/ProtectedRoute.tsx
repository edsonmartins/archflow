import { useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../stores/auth-store';
import { useTenantStore, type UserRole } from '../stores/useTenantStore';
import { tokenExpiresAt } from '../services/api';
import { loginPathFor } from '../lib/sessionNavigation';

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

// Uma busca do usuário por vez: as rotas de Administração aninham ProtectedRoutes, e cada um montaria a sua.
let carregandoUsuario: Promise<void> | null = null;
function carregarUsuario(): void {
    carregandoUsuario ??= useAuthStore.getState().loadUser().finally(() => { carregandoUsuario = null; });
}

export default function ProtectedRoute({ children, requiredRole }: Props) {
    const isAuthenticated = useAuthStore((s) => s.isAuthenticated());
    const hasUser = useAuthStore((s) => s.user !== null);
    const { currentRole, impersonating } = useTenantStore();
    const location = useLocation();
    const sessionOk = isAuthenticated && hasValidToken();

    // Depois de um F5 o token volta do sessionStorage, mas o usuário só era buscado dentro do login(): a barra
    // lateral perdia o bloco do usuário e o único botão Sair (RCHFLW-144). Com sessão e sem usuário, busca.
    useEffect(() => {
        if (sessionOk && !hasUser) carregarUsuario();
    }, [sessionOk, hasUser]);

    if (!sessionOk) {
        // Guarda a página pedida: depois de entrar, o login volta para ela (RCHFLW-188).
        return <Navigate to={loginPathFor(`${location.pathname}${location.search}${location.hash}`)} replace />;
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
