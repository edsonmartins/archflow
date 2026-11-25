import { PasswordInput, Button, Stack, Text, Alert, Title, Anchor } from '@mantine/core'
import { IconAlertCircle, IconCircleCheck, IconArrowRight } from '@tabler/icons-react'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { acceptInvitation } from '../services/admin-api'
import { MIN_INVITE_PASSWORD, mensagemDeUsuarioDoWorkspace } from '../lib/workspaceUserError'
import LanguageSwitcher from '../components/layout/LanguageSwitcher'
import brandMark from '../assets/brand/mark.png'

/** O mesmo mínimo que o backend confere (`WorkspaceUserService.MIN_PASSWORD_LENGTH`). */
const MIN_PASSWORD = MIN_INVITE_PASSWORD

/**
 * Aceite de convite (RCHFLW-85): o convidado abre o link que o administrador repassou, define a senha e
 * passa a entrar pelo login. O produto não envia e-mail — o link aparece uma vez para quem convidou.
 *
 * <p>A página não tenta adivinhar nada do token: o backend diz se ele é inválido, já usado ou vencido, e
 * o código da recusa vira a frase no idioma da página (`lib/workspaceUserError`).</p>
 */
export default function AcceptInvitePage() {
    const { t } = useTranslation()
    const [params] = useSearchParams()
    const token = params.get('token') ?? ''
    const [password, setPassword] = useState('')
    const [confirm, setConfirm] = useState('')
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [username, setUsername] = useState<string | null>(null)

    const mismatch = confirm.length > 0 && password !== confirm
    const tooShort = password.length > 0 && password.length < MIN_PASSWORD

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault()
        if (!token || mismatch || password.length < MIN_PASSWORD) return
        setSaving(true)
        setError(null)
        try {
            const accepted = await acceptInvitation(token, password)
            setUsername(accepted.username)
        } catch (err) {
            setError(mensagemDeUsuarioDoWorkspace(err, t, t('acceptInvite.failed')))
        } finally {
            setSaving(false)
        }
    }

    return (
        <div className="af-login">
            <main className="af-login-panel" style={{ flex: 1 }}>
                <div className="af-login-lang"><LanguageSwitcher /></div>
                <div className="af-login-form" data-testid="accept-invite">
                    <img src={brandMark} alt="" aria-hidden className="af-login-mark" />
                    <Title order={2} className="af-login-welcome" mb={10}>{t('acceptInvite.title')}</Title>

                    {username ? (
                        <Stack gap="md">
                            <Alert color="teal" variant="light" icon={<IconCircleCheck size={16} />}
                                   data-testid="accept-invite-done">
                                {t('acceptInvite.done', { username })}
                            </Alert>
                            <Button component={Link} to="/login" rightSection={<IconArrowRight size={16} />}>
                                {t('acceptInvite.goToLogin')}
                            </Button>
                        </Stack>
                    ) : !token ? (
                        <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}
                               data-testid="accept-invite-error">
                            {t('acceptInvite.missingToken')}
                        </Alert>
                    ) : (
                        <form onSubmit={handleSubmit}>
                            <Stack gap="md">
                                <Text c="dimmed" size="sm" lh={1.6}>
                                    {t('acceptInvite.subtitle', { min: MIN_PASSWORD })}
                                </Text>
                                {error && (
                                    <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}
                                           data-testid="accept-invite-error">
                                        {error}
                                    </Alert>
                                )}
                                <PasswordInput label={t('acceptInvite.password')} value={password} required
                                               autoComplete="new-password"
                                               error={tooShort ? t('acceptInvite.tooShort', { min: MIN_PASSWORD }) : undefined}
                                               onChange={(e) => setPassword(e.currentTarget.value)} />
                                <PasswordInput label={t('acceptInvite.confirm')} value={confirm} required
                                               autoComplete="new-password"
                                               error={mismatch ? t('acceptInvite.mismatch') : undefined}
                                               onChange={(e) => setConfirm(e.currentTarget.value)} />
                                <Button type="submit" loading={saving}
                                        disabled={password.length < MIN_PASSWORD || password !== confirm}>
                                    {t('acceptInvite.submit')}
                                </Button>
                                <Anchor component={Link} to="/login" size="sm">{t('acceptInvite.haveAccount')}</Anchor>
                            </Stack>
                        </form>
                    )}
                </div>
            </main>
        </div>
    )
}
