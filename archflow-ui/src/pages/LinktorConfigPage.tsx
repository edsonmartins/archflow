import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Card, Group, NumberInput, Paper, PasswordInput, Stack, Switch,
    Text, TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { linktorApi, type LinktorConfig } from '../services/linktor-api'
import { ApiError } from '../services/api'

const DEFAULT: LinktorConfig = {
    enabled: false,
    apiBaseUrl: 'http://localhost:8081/api/v1',
    apiKey: '',
    accessToken: '',
    mcpCommand: 'linktor-mcp',
    timeoutSeconds: 30,
}

/**
 * Admin page for the Linktor omnichannel messaging integration.
 *
 * <p>Linktor ships an MCP server (Node package
 * <code>@linktor/mcp-server</code>) that speaks stdio. We drive it from
 * this archflow instance so agents can gain ~51 tools: list/send
 * messages on WhatsApp/Telegram/SMS/Webchat/Email/Instagram/FB,
 * manage contacts, configure bots, drive the Visual Response Engine,
 * and more.</p>
 */
export default function LinktorConfigPage() {
    const { t } = useTranslation()
    const [cfg, setCfg]       = useState<LinktorConfig>(DEFAULT)
    const [loading, setL]     = useState(true)
    const [saving, setS]      = useState(false)

    useEffect(() => {
        linktorApi.get()
            .then(c => setCfg({ ...DEFAULT, ...c }))
            .catch(e => notifications.show({ color: 'red', title: 'Linktor',
                message: e instanceof ApiError ? e.message : String(e) }))
            .finally(() => setL(false))
    }, [])

    const save = async () => {
        setS(true)
        try {
            const next = await linktorApi.update(cfg)
            setCfg({ ...DEFAULT, ...next })
            notifications.show({ color: 'green', title: t('linktor.config.title'),
                message: t('linktor.config.saved') })
        } catch (e) {
            notifications.show({ color: 'red', title: t('linktor.config.saveFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally {
            setS(false)
        }
    }

    return (
        <Stack gap="md" style={{ padding: 24, maxWidth: 760 }} data-testid="linktor-config">
            <Title order={2}>{t('linktor.config.title')}</Title>
            <Alert color="blue" variant="light">
                {t('linktor.config.info')}
            </Alert>

            {loading ? <Text c="dimmed">{t('triggers.loading')}</Text> : (
                <Paper withBorder p="md"><Stack>
                    <Switch
                        checked={cfg.enabled}
                        onChange={e => setCfg({ ...cfg, enabled: e.currentTarget.checked })}
                        label={t('linktor.config.enable')}
                        data-testid="linktor-enabled"
                    />
                    <TextInput
                        label={t('linktor.config.baseUrl')}
                        value={cfg.apiBaseUrl}
                        onChange={e => setCfg({ ...cfg, apiBaseUrl: e.currentTarget.value })}
                        placeholder="http://localhost:8081/api/v1"
                        description={t('linktor.config.baseUrlHint')}
                        data-testid="linktor-base-url"
                    />
                    <PasswordInput
                        label={t('linktor.config.apiKey')}
                        value={cfg.apiKey}
                        onChange={e => setCfg({ ...cfg, apiKey: e.currentTarget.value })}
                        description={t('linktor.config.apiKeyHint')}
                        data-testid="linktor-api-key"
                    />
                    <PasswordInput
                        label={t('linktor.config.accessToken')}
                        value={cfg.accessToken}
                        onChange={e => setCfg({ ...cfg, accessToken: e.currentTarget.value })}
                        description={t('linktor.config.accessTokenHint')}
                    />
                    <TextInput
                        label={t('linktor.config.mcpCommand')}
                        value={cfg.mcpCommand}
                        onChange={e => setCfg({ ...cfg, mcpCommand: e.currentTarget.value })}
                        placeholder="linktor-mcp"
                        description={t('linktor.config.mcpCommandHint')}
                        data-testid="linktor-mcp-command"
                    />
                    <NumberInput
                        label={t('linktor.config.timeout')}
                        value={cfg.timeoutSeconds}
                        onChange={v => setCfg({ ...cfg, timeoutSeconds: Number(v) })}
                        min={5} max={300}
                    />
                    <Group justify="flex-end">
                        <Button onClick={save} loading={saving} data-testid="linktor-save">{t('common.save')}</Button>
                    </Group>
                </Stack></Paper>
            )}

            <Card withBorder>
                <Stack gap="xs">
                    <Text size="sm" fw={600}>{t('linktor.config.installTitle')}</Text>
                    <Text size="sm" c="dimmed">
                        {t('linktor.config.installHint').split(/<code>|<\/code>/).map((seg, i) =>
                            i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
                    </Text>
                </Stack>
            </Card>
        </Stack>
    )
}
