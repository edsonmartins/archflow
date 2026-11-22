import {
    TextInput, PasswordInput, Button, Stack, Text, Alert, Code, Title,
} from '@mantine/core';
import {
    IconAlertCircle, IconInfoCircle, IconArrowRight, IconSchema, IconShieldCheck, IconCoffee,
} from '@tabler/icons-react';
import { useState, type FormEvent, type ComponentType } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../stores/auth-store';
import LanguageSwitcher from '../components/layout/LanguageSwitcher';
import brandLogo from '../assets/brand/logo-dark-bg.png';
import brandMark from '../assets/brand/mark.png';
import LoginArchitecture from './LoginArchitecture';

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
    const { t } = useTranslation();
    const { login, loading, error, clearError } = useAuthStore();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        try {
            await login(username, password);
            navigate('/');
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
                                loading={loading}
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
