import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Code, Group, NumberInput, Paper, PasswordInput, SimpleGrid, Skeleton, Stack, Switch,
    Text, TextInput,
} from '@mantine/core'
import { IconDeviceFloppy, IconInfoCircle, IconKey, IconPlugConnected, IconTerminal2 } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { linktorApi, type LinktorConfig } from '../services/linktor-api'
import { ApiError } from '../services/api'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'

const DEFAULT: LinktorConfig = {
    enabled: false,
    apiBaseUrl: 'http://localhost:8081/api/v1',
    apiKey: '',
    accessToken: '',
    // Vazio, como o default do servidor (archflow.linktor.mcpCommand): `linktor-mcp` não está na allow-list
    // padrão de comandos MCP e o save respondia erro com o valor que a própria tela sugeria (RCHFLW-69).
    mcpCommand: '',
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

    const installSegments = t('linktor.config.installHint').split(/<code>|<\/code>/)

    return (
        <Stack gap="lg" p="md" data-testid="linktor-config">
            <PageHeader
                icon={IconPlugConnected}
                accent="teal"
                title={t('linktor.config.title')}
                subtitle={t('linktor.config.subtitle')}
                actions={!loading && (
                    <Badge size="lg" radius="sm" variant="dot" color={cfg.enabled ? 'green' : 'gray'}>
                        {cfg.enabled ? t('linktor.config.statusEnabled') : t('linktor.config.statusDisabled')}
                    </Badge>
                )}
            />

            <Alert color="blue" variant="light" icon={<IconInfoCircle size={16} />} maw={980}>
                {t('linktor.config.info')}
            </Alert>

            {loading ? (
                <Stack gap="lg" maw={980}>
                    <Skeleton height={260} radius="md" />
                    <Skeleton height={160} radius="md" />
                </Stack>
            ) : (
                <Stack gap="lg" maw={980}>
                    <SectionCard icon={IconKey} accent="teal"
                                 title={t('linktor.config.connectionTitle')}
                                 description={t('linktor.config.connectionHint')}>
                        <Stack gap="md">
                            <Paper p="sm" radius="md" style={{ background: 'var(--mantine-color-default-hover)' }}>
                                <Switch
                                    checked={cfg.enabled}
                                    onChange={e => setCfg({ ...cfg, enabled: e.currentTarget.checked })}
                                    label={t('linktor.config.enable')}
                                    description={t('linktor.config.enableHint')}
                                    color="teal"
                                    data-testid="linktor-enabled"
                                />
                            </Paper>
                            <TextInput
                                size="sm"
                                label={t('linktor.config.baseUrl')}
                                value={cfg.apiBaseUrl}
                                onChange={e => setCfg({ ...cfg, apiBaseUrl: e.currentTarget.value })}
                                placeholder="http://localhost:8081/api/v1"
                                description={t('linktor.config.baseUrlHint')}
                                data-testid="linktor-base-url"
                            />
                            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
                                <PasswordInput
                                    size="sm"
                                    label={t('linktor.config.apiKey')}
                                    value={cfg.apiKey}
                                    onChange={e => setCfg({ ...cfg, apiKey: e.currentTarget.value })}
                                    description={t('linktor.config.apiKeyHint')}
                                    data-testid="linktor-api-key"
                                />
                                <PasswordInput
                                    size="sm"
                                    label={t('linktor.config.accessToken')}
                                    value={cfg.accessToken}
                                    onChange={e => setCfg({ ...cfg, accessToken: e.currentTarget.value })}
                                    description={t('linktor.config.accessTokenHint')}
                                />
                            </SimpleGrid>
                        </Stack>
                    </SectionCard>

                    <SectionCard icon={IconTerminal2} accent="slate"
                                 title={t('linktor.config.mcpTitle')}
                                 description={t('linktor.config.mcpHint')}>
                        <Stack gap="md">
                            <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
                                <TextInput
                                    size="sm"
                                    style={{ gridColumn: 'span 2' }}
                                    label={t('linktor.config.mcpCommand')}
                                    value={cfg.mcpCommand}
                                    onChange={e => setCfg({ ...cfg, mcpCommand: e.currentTarget.value })}
                                    placeholder={t('linktor.config.mcpCommandPlaceholder')}
                                    description={t('linktor.config.mcpCommandHint')}
                                    data-testid="linktor-mcp-command"
                                />
                                <NumberInput
                                    size="sm"
                                    label={t('linktor.config.timeout')}
                                    value={cfg.timeoutSeconds}
                                    onChange={v => setCfg({ ...cfg, timeoutSeconds: Number(v) })}
                                    min={5} max={300}
                                />
                            </SimpleGrid>
                            <Paper p="sm" radius="md" style={{ background: 'var(--mantine-color-default-hover)' }}>
                                <Text size="xs" fw={600} c="dimmed" tt="uppercase" mb={4} style={{ letterSpacing: '0.06em' }}>
                                    {t('linktor.config.installTitle')}
                                </Text>
                                <Text size="sm" c="dimmed" lh={1.7}>
                                    {installSegments.map((seg, i) =>
                                        i % 2 === 1 ? <Code key={i}>{seg}</Code> : <span key={i}>{seg}</span>)}
                                </Text>
                            </Paper>
                        </Stack>
                    </SectionCard>

                    <Group justify="flex-end">
                        <Button onClick={save} loading={saving} leftSection={<IconDeviceFloppy size={16} />}
                                data-testid="linktor-save">
                            {t('common.save')}
                        </Button>
                    </Group>
                </Stack>
            )}
        </Stack>
    )
}
