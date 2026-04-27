import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Card, Group, NumberInput, Paper, PasswordInput, Stack,
    Switch, Text, TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { brainsentryApi, type BrainSentryConfig } from '../services/brainsentry-api'
import { ApiError } from '../services/api'

const DEFAULT: BrainSentryConfig = {
    enabled: false,
    baseUrl: 'http://localhost:8081/api',
    apiKey: '',
    tenantId: '',
    maxTokenBudget: 2000,
    deepAnalysisEnabled: false,
    timeoutSeconds: 10,
}

/**
 * Admin screen to edit the BrainSentry integration at runtime. The
 * API key the backend returns is masked (e.g. {@code abcd…wxyz});
 * leaving the masked value intact on save keeps the stored key
 * unchanged.
 */
export default function BrainSentryConfigPage() {
    const { t } = useTranslation()
    const [cfg, setCfg]     = useState<BrainSentryConfig>(DEFAULT)
    const [loading, setL]   = useState(true)
    const [saving, setS]    = useState(false)

    useEffect(() => {
        brainsentryApi.get()
            .then(c => setCfg({ ...DEFAULT, ...c }))
            .catch(e => notifications.show({ color: 'red', title: t('brainSentry.title'),
                message: e instanceof ApiError ? e.message : String(e) }))
            .finally(() => setL(false))
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    const save = async () => {
        setS(true)
        try {
            const next = await brainsentryApi.update(cfg)
            setCfg({ ...DEFAULT, ...next })
            notifications.show({ color: 'green', title: t('brainSentry.title'),
                message: t('brainSentry.saved') })
        } catch (e) {
            notifications.show({ color: 'red', title: t('brainSentry.saveFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally {
            setS(false)
        }
    }

    return (
        <Stack gap="md" style={{ padding: 24, maxWidth: 720 }} data-testid="brainsentry-config">
            <Title order={2}>{t('brainSentry.title')}</Title>
            <Alert color="blue" variant="light">
                {t('brainSentry.info').split(/<code>|<\/code>/).map((seg, i) =>
                    i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
            </Alert>

            {loading ? <Text c="dimmed">{t('triggers.loading')}</Text> : (
                <Paper withBorder p="md"><Stack>
                    <Switch
                        checked={cfg.enabled}
                        onChange={e => setCfg({ ...cfg, enabled: e.currentTarget.checked })}
                        label={t('brainSentry.enable')}
                        data-testid="bs-enabled"
                    />
                    <TextInput
                        label={t('brainSentry.baseUrl')}
                        value={cfg.baseUrl}
                        onChange={e => setCfg({ ...cfg, baseUrl: e.currentTarget.value })}
                        placeholder="http://localhost:8081/api"
                        data-testid="bs-base-url"
                    />
                    <PasswordInput
                        label={t('brainSentry.apiKey')}
                        value={cfg.apiKey}
                        onChange={e => setCfg({ ...cfg, apiKey: e.currentTarget.value })}
                        description={t('brainSentry.apiKeyHint')}
                        data-testid="bs-api-key"
                    />
                    <TextInput
                        label={t('brainSentry.tenantTag')}
                        value={cfg.tenantId}
                        onChange={e => setCfg({ ...cfg, tenantId: e.currentTarget.value })}
                        placeholder="acme-prod"
                    />
                    <Group grow>
                        <NumberInput
                            label={t('brainSentry.maxTokenBudget')}
                            value={cfg.maxTokenBudget}
                            onChange={v => setCfg({ ...cfg, maxTokenBudget: Number(v) })}
                            min={256} max={32000}
                        />
                        <NumberInput
                            label={t('brainSentry.timeout')}
                            value={cfg.timeoutSeconds}
                            onChange={v => setCfg({ ...cfg, timeoutSeconds: Number(v) })}
                            min={1} max={120}
                        />
                    </Group>
                    <Switch
                        checked={cfg.deepAnalysisEnabled}
                        onChange={e => setCfg({ ...cfg, deepAnalysisEnabled: e.currentTarget.checked })}
                        label={t('brainSentry.deepAnalysis')}
                    />
                    <Group justify="flex-end">
                        <Button onClick={save} loading={saving} data-testid="bs-save">{t('common.save')}</Button>
                    </Group>
                </Stack></Paper>
            )}

            <Card withBorder>
                <Text size="sm" c="dimmed">
                    {t('brainSentry.credsCard').split(/<code>|<\/code>/).map((seg, i) =>
                        i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
                </Text>
            </Card>
        </Stack>
    )
}
