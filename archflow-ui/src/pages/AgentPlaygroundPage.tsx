import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Grid, Group, Loader, Select, SimpleGrid, Stack, Tabs, Text, Textarea, TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertTriangle, IconInfoCircle, IconMessage, IconPlayerPlayFilled, IconRefresh, IconRobot, IconSend,
    IconTerminal2,
} from '@tabler/icons-react'
import { useCatalog } from '../components/PropertyPanel/useCatalog'
import {
    agentPlaygroundApi, InvocationNotFoundError, isTerminal,
    type InvocationResult, type InvocationStatus,
} from '../services/agent-playground-api'
import { JsonViewer } from '../components/JsonViewer'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import EmptyState from '../components/EmptyState'
import { useActingTenant } from '../lib/actingTenant'

/** Which endpoint produced the last response — shown as a tag on the response panel. */
type Origem = 'invoke' | 'message'

/** Intervalo entre consultas do resultado: o agente roda em segundo plano, na fila. */
export const POLL_INTERVAL_MS = 1000
/** Depois disso a tela para de consultar e oferece "Consultar de novo" — não fica girando para sempre. */
export const POLL_DEADLINE_MS = 120_000
/**
 * 404s seguidos tolerados: na fila em memória há um instante entre o consumidor retirar a invocação e
 * registrá-la em que a consulta ainda não a acha. Mais que isso é um requestId inexistente para o tenant.
 */
export const MAX_NOT_FOUND = 3

type Acompanhada = { requestId: string; tenantId: string }
type FalhaDaConsulta = { kind: 'notFound' } | { kind: 'http'; message: string }

const STATUS_COLOR: Record<InvocationStatus, string> = {
    QUEUED: 'gray', RUNNING: 'blue', COMPLETED: 'green', FAILED: 'red',
}

/**
 * Consulta o resultado da invocação até ele ficar final (RCHFLW-80). O invoke e o chat só respondem
 * ACCEPTED; sem isto a tela mostrava "aceito" e nunca o que o agente fez.
 */
function useInvocationResult(acompanhada: Acompanhada | null) {
    const [result, setResult] = useState<InvocationResult | null>(null)
    const [falha, setFalha] = useState<FalhaDaConsulta | null>(null)
    const [esgotou, setEsgotou] = useState(false)

    useEffect(() => {
        setResult(null)
        setFalha(null)
        setEsgotou(false)
        if (!acompanhada) return
        let cancelado = false
        let timer: ReturnType<typeof setTimeout> | undefined
        let naoAchou = 0
        const prazo = Date.now() + POLL_DEADLINE_MS
        const consultar = async () => {
            try {
                const r = await agentPlaygroundApi.invocation(acompanhada.requestId, acompanhada.tenantId)
                if (cancelado) return
                naoAchou = 0
                setResult(r)
                if (isTerminal(r.status)) return
            } catch (e) {
                if (cancelado) return
                if (!(e instanceof InvocationNotFoundError)) {
                    setFalha({ kind: 'http', message: e instanceof Error ? e.message : String(e) })
                    return
                }
                if (++naoAchou >= MAX_NOT_FOUND) {
                    setFalha({ kind: 'notFound' })
                    return
                }
            }
            if (Date.now() >= prazo) {
                setEsgotou(true)
                return
            }
            timer = setTimeout(consultar, POLL_INTERVAL_MS)
        }
        void consultar()
        return () => {
            cancelado = true
            clearTimeout(timer)
        }
    }, [acompanhada])

    const consultando = acompanhada != null && !falha && !esgotou && !(result && isTerminal(result.status))
    return { result, falha, esgotou, consultando }
}

/**
 * Playground for the two external-trigger endpoints that previously
 * had no UI: {@code POST /archflow/agents/{id}/invoke} (demand
 * trigger) and {@code POST /archflow/events/message} (conversation
 * trigger). Useful for smoke testing wire-up and for demos.
 */
export default function AgentPlaygroundPage() {
    const { t } = useTranslation()
    const catalog = useCatalog()
    // RCHFLW-103: a invocação roda no tenant da identidade. Quem não é admin vê o próprio tenant e não
    // o troca (o servidor recusaria com 403); o admin começa no tenant em que está; sem login (dev), livre.
    const acting = useActingTenant()
    const [tenantInput, setTenant] = useState(() => acting.tenantId ?? 'tenant_demo')
    const tenantId = acting.locked ? (acting.tenantId ?? tenantInput) : tenantInput
    const [sessionId, setSess]  = useState('')
    const [agentId, setAgent]   = useState<string | null>(null)
    const [payload, setPayload] = useState('{"topic":"weather"}')
    const [message, setMessage] = useState('Hello, agent!')
    const [response, setResp]   = useState<unknown>(null)
    const [origem, setOrigem]   = useState<Origem | null>(null)
    const [busy, setBusy]       = useState<Origem | null>(null)
    const [acompanhada, setAcompanhada] = useState<Acompanhada | null>(null)
    const { result, falha, esgotou, consultando } = useInvocationResult(acompanhada)

    const agentOptions = [
        ...catalog.agents.map(a => ({ value: a.id, label: `${a.displayName} (${t('agentPlayground.agentLabel')})` })),
        ...catalog.assistants.map(a => ({ value: a.id, label: `${a.displayName} (${t('agentPlayground.assistantLabel')})` })),
    ]

    const handleInvoke = async () => {
        if (!agentId) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.agent'), message: t('agentPlayground.selectFirst') })
            return
        }
        setBusy('invoke')
        try {
            const parsed = payload.trim() ? JSON.parse(payload) : {}
            const res = await agentPlaygroundApi.invoke(agentId, tenantId, sessionId || undefined, parsed)
            setResp(res)
            setOrigem('invoke')
            setAcompanhada({ requestId: res.requestId, tenantId })
            notifications.show({ color: 'green', title: t('agentPlayground.invoked'), message: t('agentPlayground.requestIdMsg', { id: res.requestId }) })
        } catch (e) {
            notifications.show({ color: 'red', title: t('agentPlayground.invokeFailed'),
                message: e instanceof Error ? e.message : String(e) })
        } finally {
            setBusy(null)
        }
    }

    const handleMessage = async () => {
        if (!agentId) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.agent'), message: t('agentPlayground.selectFirst') })
            return
        }
        if (!message.trim()) {
            notifications.show({ color: 'yellow', title: t('agentPlayground.message'), message: t('agentPlayground.emptyMessage') })
            return
        }
        setBusy('message')
        try {
            const res = await agentPlaygroundApi.message(tenantId, agentId, message, sessionId || undefined)
            setResp(res)
            setOrigem('message')
            setAcompanhada({ requestId: res.requestId, tenantId })
            notifications.show({ color: 'green', title: t('agentPlayground.accepted'), message: t('agentPlayground.requestIdMsg', { id: res.requestId }) })
        } catch (e) {
            notifications.show({ color: 'red', title: t('agentPlayground.sendFailed'),
                message: e instanceof Error ? e.message : String(e) })
        } finally {
            setBusy(null)
        }
    }

    return (
        <Stack gap="lg" p="md" data-testid="agent-playground">
            <PageHeader
                icon={IconPlayerPlayFilled}
                accent="violet"
                title={t('agentPlayground.title')}
                subtitle={t('agentPlayground.subtitle')}
            />

            <Grid gap="lg" align="flex-start">
                <Grid.Col span={{ base: 12, lg: 5 }}>
                    <SectionCard
                        icon={IconRobot}
                        accent="violet"
                        title={t('agentPlayground.requestTitle')}
                        description={t('agentPlayground.requestHint')}
                    >
                        <Stack gap="md">
                            <Alert color="blue" variant="light" icon={<IconInfoCircle size={16} />} p="sm">
                                {t('agentPlayground.info').split(/<code>|<\/code>/).map((seg, i) =>
                                    i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
                            </Alert>

                            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                                <TextInput size="sm" label={t('agentPlayground.tenantId')} value={tenantId}
                                        readOnly={acting.locked}
                                        description={acting.locked ? t('agentPlayground.tenantLockedHint') : undefined}
                                        onChange={e => setTenant(e.currentTarget.value)}
                                        data-testid="pg-tenant" />
                                <TextInput size="sm" label={t('agentPlayground.sessionId')} value={sessionId}
                                        onChange={e => setSess(e.currentTarget.value)}
                                        placeholder={t('agentPlayground.sessionPlaceholder')} />
                            </SimpleGrid>

                            <Select
                                size="sm"
                                label={t('agentPlayground.agent')}
                                value={agentId}
                                onChange={setAgent}
                                data={agentOptions}
                                searchable
                                placeholder={t('agentPlayground.agentPlaceholder')}
                                data-testid="pg-agent"
                            />

                            <Tabs defaultValue="invoke">
                                <Tabs.List grow>
                                    <Tabs.Tab value="invoke" leftSection={<IconTerminal2 size={14} />}>
                                        {t('agentPlayground.tabs.invoke')}
                                    </Tabs.Tab>
                                    <Tabs.Tab value="message" leftSection={<IconMessage size={14} />}>
                                        {t('agentPlayground.tabs.message')}
                                    </Tabs.Tab>
                                </Tabs.List>

                                <Tabs.Panel value="invoke" pt="md">
                                    <Stack gap="sm">
                                        <Textarea
                                            size="sm"
                                            label={t('agentPlayground.jsonPayload')}
                                            value={payload}
                                            onChange={e => setPayload(e.currentTarget.value)}
                                            autosize minRows={5}
                                            data-testid="pg-payload"
                                            styles={{ input: { fontFamily: 'var(--font-mono)' } }}
                                        />
                                        <Group justify="flex-end">
                                            <Button onClick={handleInvoke} loading={busy === 'invoke'}
                                                    leftSection={<IconPlayerPlayFilled size={14} />}
                                                    data-testid="pg-invoke-btn">
                                                {t('agentPlayground.invokeBtn')}
                                            </Button>
                                        </Group>
                                    </Stack>
                                </Tabs.Panel>

                                <Tabs.Panel value="message" pt="md">
                                    <Stack gap="sm">
                                        <Textarea
                                            size="sm"
                                            label={t('agentPlayground.message')}
                                            value={message}
                                            onChange={e => setMessage(e.currentTarget.value)}
                                            autosize minRows={5}
                                            data-testid="pg-message"
                                        />
                                        <Group justify="flex-end">
                                            <Button onClick={handleMessage} loading={busy === 'message'}
                                                    leftSection={<IconSend size={14} />}
                                                    data-testid="pg-message-btn">
                                                {t('agentPlayground.sendBtn')}
                                            </Button>
                                        </Group>
                                    </Stack>
                                </Tabs.Panel>
                            </Tabs>
                        </Stack>
                    </SectionCard>
                </Grid.Col>

                <Grid.Col span={{ base: 12, lg: 7 }}>
                    <SectionCard
                        icon={IconTerminal2}
                        accent="violet"
                        title={t('agentPlayground.response')}
                        description={t('agentPlayground.responseHint')}
                        actions={origem && (
                            <Badge variant="light" color="violet">{t(`agentPlayground.tabs.${origem}`)}</Badge>
                        )}
                    >
                        {response != null ? (
                            <Stack gap="md">
                                {acompanhada && (
                                    <Stack gap="sm" role="status" aria-live="polite" data-testid="pg-result">
                                        <Group gap="xs">
                                            <Text size="sm" fw={600}>{t('agentPlayground.result.title')}</Text>
                                            {result ? (
                                                <Badge variant="light" color={STATUS_COLOR[result.status]}
                                                       data-testid="pg-result-status">
                                                    {t(`agentPlayground.result.status.${result.status}`)}
                                                </Badge>
                                            ) : null}
                                            {consultando && <Loader size="xs" data-testid="pg-result-loading" />}
                                        </Group>
                                        {result?.status === 'FAILED' && (
                                            <Alert color="red" variant="light" icon={<IconAlertTriangle size={16} />}
                                                   title={t('agentPlayground.result.failedTitle')}
                                                   data-testid="pg-result-error">
                                                {result.error || t('agentPlayground.result.noErrorMessage')}
                                            </Alert>
                                        )}
                                        {result?.status === 'COMPLETED' && (
                                            result.output == null
                                                ? <Text size="sm" c="dimmed">{t('agentPlayground.result.noOutput')}</Text>
                                                : <JsonViewer value={result.output} autoExpandDepth={2}
                                                              testId="pg-result-output" />
                                        )}
                                        {(falha || esgotou) && (
                                            <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}
                                                   data-testid="pg-result-unknown">
                                                <Stack gap="xs" align="flex-start">
                                                    <span>
                                                        {falha?.kind === 'notFound'
                                                            ? t('agentPlayground.result.notFound')
                                                            : falha?.kind === 'http'
                                                                ? t('agentPlayground.result.queryFailed',
                                                                    { message: falha.message })
                                                                : t('agentPlayground.result.stillRunning')}
                                                    </span>
                                                    <Button size="xs" variant="light"
                                                            leftSection={<IconRefresh size={14} />}
                                                            onClick={() => setAcompanhada({ ...acompanhada })}
                                                            data-testid="pg-result-retry">
                                                        {t('agentPlayground.result.retry')}
                                                    </Button>
                                                </Stack>
                                            </Alert>
                                        )}
                                    </Stack>
                                )}
                                <Stack gap={4}>
                                    <Text size="xs" c="dimmed">{t('agentPlayground.result.acceptance')}</Text>
                                    <JsonViewer value={response} autoExpandDepth={2} testId="pg-response" />
                                </Stack>
                            </Stack>
                        ) : (
                            <EmptyState
                                compact
                                accent="violet"
                                icon={<IconTerminal2 />}
                                title={t('agentPlayground.emptyTitle')}
                                description={t('agentPlayground.emptyHint')}
                            />
                        )}
                    </SectionCard>
                </Grid.Col>
            </Grid>
        </Stack>
    )
}
