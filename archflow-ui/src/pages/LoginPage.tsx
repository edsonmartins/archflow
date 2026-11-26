import {
    TextInput, PasswordInput, Button, Stack, Text, Alert, Code, Title,
} from '@mantine/core';
import {
    IconAlertCircle, IconInfoCircle, IconArrowRight, IconSchema, IconShieldCheck, IconCoffee,
} from '@tabler/icons-react';
import { useEffect, useRef, useState, type FormEvent, type ComponentType } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth-store';
import LanguageSwitcher from '../components/layout/LanguageSwitcher';
import brandLogo from '../assets/brand/logo-dark-bg.png';
import brandMark from '../assets/brand/mark.png';
import LoginArchitecture from './LoginArchitecture';
import { LOGIN_FROM_PARAM, isLogoutNavigation, safeReturnTarget } from '../lib/sessionNavigation';

/**
 * Login em duas colunas: à esquerda o painel da marca (marinho da sidebar com
 * as manchas de luz do logo, o diagrama animado da arquitetura — canais →
 * agente → ferramentas, com os LLMs embaixo — e três destaques); à direita o formulário, sobre o mesmo degradê em malha da paleta
 * do editor. Abaixo de 960 px o painel some e o símbolo sobe para o formulário.
 *
 * Contrato dos E2E: rótulos "Username"/"Password", botão "Sign in" e o slogan
 * (login.tagline) aparecendo UMA vez na tela.
 */
export default function LoginPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const [searchParams] = useSearchParams();
    const { t } = useTranslation();
    const { login, logout, loading, error, clearError } = useAuthStore();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');

    // O Sair das barras laterais chega aqui antes de encerrar a sessão (RCHFLW-142): só agora, com a saída já
    // liberada pelo aviso de não salvo do editor, o token é removido. O replace tira o estado do histórico —
    // voltar para esta entrada não sai de novo de uma sessão nova.
    const logoutRequested = isLogoutNavigation(location.state);
    // Um logout por chegada: o StrictMode roda o efeito duas vezes em dev.
    const loggedOutRef = useRef(false);
    // O formulário espera o logout terminar: o fim dele (remover os tokens, token:null) chegando depois de um
    // login novo apagaria a sessão recém-aberta e o ProtectedRoute devolveria o usuário a esta tela. Começa
    // valendo já na primeira renderização de quem chega pelo Sair, antes do efeito.
    const [saindo, setSaindo] = useState(logoutRequested);
    useEffect(() => {
        if (!logoutRequested || loggedOutRef.current) return;
        loggedOutRef.current = true;
        void logout().finally(() => setSaindo(false));
        navigate(location.pathname, { replace: true, state: null });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [logoutRequested]);

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        // O Enter num campo (ou o preenchimento automático) também submete; o botão desabilitado não basta.
        if (saindo) return;
        try {
            await login(username, password);
            // De volta à página pedida antes do login (RCHFLW-188): o ProtectedRoute e o 401 da API mandam
            // para /login?from=<página>. Só caminhos do próprio app; o resto cai no início.
            navigate(safeReturnTarget(searchParams.get(LOGIN_FROM_PARAM)), { replace: true });
        } catch {
            // error is set in store
        }
    };

    return (
        <div className="af-login">
            {/* ── Painel da marca ─────────────────────────────────── */}
            <aside className="af-login-hero">
                <div className="af-login-glow" aria-hidden />
                <img src={brandLogo} alt={t('common.appName')} className="af-login-logo" />

                <div className="af-login-hero-body">
                    <Text className="af-login-tagline" mb={14}>{t('login.tagline')}</Text>
                    <Title order={1} className="af-login-headline" mb={28}>{t('login.heroTitle')}</Title>

                    <LoginArchitecture />

                    <ul className="af-login-features">
                        <Feature icon={IconSchema} title={t('login.featureFlowsTitle')} desc={t('login.featureFlowsDesc')} />
                        <Feature icon={IconShieldCheck} title={t('login.featureGovernanceTitle')} desc={t('login.featureGovernanceDesc')} />
                        <Feature icon={IconCoffee} title={t('login.featureJavaTitle')} desc={t('login.featureJavaDesc')} />
                    </ul>
                </div>

                <Text className="af-login-footer">{t('login.footer')}</Text>
            </aside>

            {/* ── Formulário ──────────────────────────────────────── */}
            <main className="af-login-panel">
                <div className="af-login-lang"><LanguageSwitcher /></div>

                <div className="af-login-form">
                    <img src={brandMark} alt="" aria-hidden className="af-login-mark" />
                    <Title order={2} className="af-login-welcome" mb={10}>{t('login.welcome')}</Title>
                    <Text c="dimmed" size="sm" mb={32} lh={1.6}>{t('login.subtitle')}</Text>

                    <form onSubmit={handleSubmit}>
                        <Stack gap="md">
                            {error && (
                                <Alert
                                    icon={<IconAlertCircle size={16} />}
                                    color="red"
                                    variant="light"
                                    radius="md"
                                    withCloseButton
                                    onClose={clearError}
                                >
                                    {error}
                                </Alert>
                            )}

                            <TextInput
                                label={t('login.username')}
                                placeholder={t('login.usernamePlaceholder')}
                                required
                                size="md"
                                radius="md"
                                value={username}
                                onChange={(e) => setUsername(e.currentTarget.value)}
                                autoFocus
                                autoComplete="username"
                            />

                            <PasswordInput
                                label={t('login.password')}
                                placeholder={t('login.passwordPlaceholder')}
                                required
                                size="md"
                                radius="md"
                                value={password}
                                onChange={(e) => setPassword(e.currentTarget.value)}
                                autoComplete="current-password"
                            />

                            <Button
                                type="submit"
                                fullWidth
                                size="md"
                                radius="md"
                                mt="xs"
                                loading={loading || saindo}
                                variant="gradient"
                                gradient={{ from: '#2563EB', to: '#0EA5E9', deg: 120 }}
                                rightSection={<IconArrowRight size={18} />}
                                className="af-login-submit"
                            >
                                {t('login.signIn')}
                            </Button>
                        </Stack>
                    </form>

                    {import.meta.env.DEV && (
                        <Alert
                            icon={<IconInfoCircle size={16} />}
                            color="blue"
                            variant="light"
                            mt="lg"
                            radius="md"
                            title={t('login.devCredentials')}
                        >
                            <Text size="sm">
                                {t('login.username')}: <Code>admin</Code> &nbsp; {t('login.password')}: <Code>admin123</Code>
                            </Text>
                        </Alert>
                    )}
                </div>
            </main>
        </div>
    );
}

function Feature({ icon: Icon, title, desc }: { icon: ComponentType<{ size?: number; stroke?: number }>; title: string; desc: string }) {
    return (
        <li className="af-login-feature">
            <span className="af-login-feature-icon"><Icon size={18} stroke={1.8} /></span>
            <span>
                <span className="af-login-feature-title">{title}</span>
                <span className="af-login-feature-desc">{desc}</span>
            </span>
        </li>
    );
}
