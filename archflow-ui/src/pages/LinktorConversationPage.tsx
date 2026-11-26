import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Box, Button, Group, NativeSelect, Paper, SimpleGrid, Skeleton, Stack, Text, Textarea, TextInput,
    ThemeIcon,
} from '@mantine/core'
import {
    IconArrowLeft, IconCircleCheck, IconHeadset, IconInfoCircle, IconMessages, IconSend, IconUser, IconUserShare,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import {
    conversaEncerrada,
    linktorInboxApi,
    linktorRecusouCredenciais,
    linktorRecusouListarUsuarios,
    operadorAtivo,
    type ConversaDoInbox,
    type MensagemDoInbox,
    type OperadorDoLinktor,
} from '../services/linktor-inbox-api'
import { mensagemDoErro } from '../lib/errorMessage'
import { confirmAction } from '../lib/confirm'
import { useIsAdmin } from '../lib/permissions'
import { PermissionTooltip } from '../components/PermissionTooltip'
import { formatInstant } from '../lib/format'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import EmptyState from '../components/EmptyState'
import { Meta } from '../components/Meta'
import { canalDoLinktor } from '../components/linktor/canal'

/**
 * Detail view of a single Linktor conversation.
 *
 * <p>Lets the admin read the message history, send a reply (posting
 * via the inbox backend which in turn calls Linktor's
 * {@code POST /conversations/:id/messages}), assign the conversation
 * to a human operator, or mark it resolved.</p>
 */
export default function LinktorConversationPage() {
    const { t, i18n } = useTranslation()
    // /api/admin/linktor/** fala com o Linktor usando a chave guardada: o servidor exige o papel ADMIN
    // (QA2 RCHFLW-137). Responder, resolver e atribuir ficam desabilitados para quem não o tem.
    const isAdmin = useIsAdmin()
    const missing = isAdmin ? null : 'ADMIN' as const
    const locale = i18n.resolvedLanguage ?? i18n.language
    const { id = '' } = useParams()
    const [conv, setConv]         = useState<ConversaDoInbox | null>(null)
    const [messages, setMessages] = useState<MensagemDoInbox[]>([])
    const [loading, setLoading]   = useState(true)
    const [error, setError]       = useState<string | null>(null)
    const [draft, setDraft]       = useState('')
    const [assignee, setAssignee] = useState('')
    const [busy, setBusy]         = useState(false)
    // Quem pode receber a conversa: os usuários do Linktor. O Linktor atribui por user_id (um UUID), então
    // a lista é o caminho normal; o campo de id só aparece quando a lista não veio, e a tela diz por quê.
    const [operadores, setOperadores] = useState<OperadorDoLinktor[] | null>(null)
    // 'recusado' = a chave autentica mas não tem papel admin/owner (403); 'credenciais' = o Linktor não
    // aceitou a chave (401). Outra string é a mensagem de uma falha qualquer.
    const [operadoresFalha, setOperadoresFalha] = useState<'recusado' | 'credenciais' | string | null>(null)

    const load = async () => {
        setLoading(true); setError(null)
        try {
            const [c, ms] = await Promise.all([
                linktorInboxApi.getConversation(id),
                linktorInboxApi.listMessages(id),
            ])
            setConv(c); setMessages(ms)
        } catch (e) {
            setError(mensagemDoErro(e))
        } finally {
            setLoading(false)
        }
    }
    useEffect(() => { load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

    useEffect(() => {
        linktorInboxApi.listUsers()
            .then(us => { setOperadores(us); setOperadoresFalha(null) })
            .catch(e => {
                setOperadores(null)
                setOperadoresFalha(linktorRecusouListarUsuarios(e) ? 'recusado'
                    : linktorRecusouCredenciais(e) ? 'credenciais' : mensagemDoErro(e))
            })
    }, [])

    const status = useMemo(() => conv ? conv.status : 'open', [conv])
    const escolhido = operadores?.find(o => o.id === assignee) ?? null

    const send = async () => {
        if (!draft.trim()) return
        setBusy(true)
        try {
            await linktorInboxApi.sendMessage(id, draft.trim())
            setDraft('')
            await load()
        } catch (e) {
            notifications.show({ color: 'red', title: t('linktor.conversation.sendFailed'),
                message: mensagemDoErro(e) })
        } finally { setBusy(false) }
    }

    const assign = async () => {
        if (!assignee.trim()) return
        setBusy(true)
        try {
            await linktorInboxApi.assign(id, assignee.trim())
            notifications.show({ color: 'green', title: t('linktor.conversation.assigned'),
                message: t('linktor.conversation.assignedMsg', { assignee: escolhido?.nome || assignee.trim() }) })
            await load()
        } catch (e) {
            notifications.show({ color: 'red', title: t('linktor.conversation.assignFailed'),
                message: mensagemDoErro(e) })
        } finally { setBusy(false) }
    }

    const resolve = () => {
        confirmAction({
            title: t('confirmations.resolveConversationTitle'),
            message: t('confirmations.resolveConversationMessage'),
            confirmLabel: t('confirmations.resolve'),
            danger: false,
            onConfirm: async () => {
                setBusy(true)
                try {
                    await linktorInboxApi.resolve(id)
                    notifications.show({ color: 'green', title: t('linktor.conversation.resolved'),
                        message: t('linktor.conversation.resolvedMsg') })
                    await load()
                } catch (e) {
                    notifications.show({ color: 'red', title: t('linktor.conversation.resolveFailed'),
                        message: mensagemDoErro(e) })
                } finally { setBusy(false) }
            },
        })
    }

    // `closed` também encerra: é o que a lista do Inbox conta como encerrada.
    const resolved = conversaEncerrada(status)
    const channel = conv?.canalNome ?? conv?.canalTipo ?? ''
    const canal = canalDoLinktor(conv?.canalTipo)
    const CanalIcon = canal.icon

    return (
        <Stack gap="lg" p="md" data-testid="linktor-conversation">
            <PageHeader
                icon={IconMessages}
                accent="teal"
                title={t('linktor.conversation.title')}
                subtitle={t('linktor.conversation.subtitle')}
                breadcrumbs={[
                    { label: t('linktor.inbox.title'), to: '/admin/linktor/inbox' },
                    { label: id.slice(0, 12) },
                ]}
                actions={
                    <>
                        <Badge size="lg" radius="sm" variant="dot" color={statusColor(status)}>
                            {t(`linktor.status.${status}`, { defaultValue: status })}
                        </Badge>
                        <Button component={Link} to="/admin/linktor/inbox" variant="default"
                                leftSection={<IconArrowLeft size={16} />}>
                            {t('linktor.conversation.back')}
                        </Button>
                    </>
                }
            />

            {error && <Alert color="red" variant="light" title={t('linktor.config.title')}>{error}</Alert>}

            <Group align="flex-start" gap="lg" wrap="wrap">
                {/* Transcript + composer */}
                <Paper withBorder style={{ flex: '2 1 520px', minWidth: 0, overflow: 'hidden' }}>
                    <Group justify="space-between" px="md" py="xs" wrap="nowrap"
                           style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
                        <Group gap={8} wrap="nowrap">
                            <ThemeIcon size={26} radius="md" variant="light" color={canal.color} aria-hidden>
                                <CanalIcon size={15} />
                            </ThemeIcon>
                            <Text size="sm" fw={600}>{channel || t('linktor.conversation.transcript')}</Text>
                        </Group>
                        <Text size="xs" c="dimmed">
                            {t('linktor.conversation.messageCount', { count: messages.length })}
                        </Text>
                    </Group>

                    <Box px="md" py="sm" mih={320} style={{ background: 'var(--bg3)' }}>
                        {loading ? (
                            <Stack gap="md" py="sm">
                                <Skeleton height={44} width="55%" radius="lg" />
                                <Skeleton height={44} width="45%" radius="lg" ml="auto" />
                                <Skeleton height={44} width="60%" radius="lg" />
                            </Stack>
                        ) : messages.length === 0 ? (
                            <EmptyState compact icon={<IconMessages />} accent="teal"
                                        title={t('linktor.conversation.noMessages')}
                                        description={t('linktor.conversation.noMessagesHint')} />
                        ) : (
                            <Stack gap="md" py="sm" role="log" aria-label={t('linktor.conversation.transcript')}>
                                {messages.map((m, i) => {
                                    const author = t(`linktor.conversation.sender.${m.remetente}`)
                                    const content = m.conteudo
                                    const at = m.criadaEm
                                    // Do lado de cá: o operador (sender_type user) e o bot do Linktor.
                                    const mine = m.remetente === 'operador' || m.remetente === 'bot'
                                    return (
                                        <Group key={m.id || i} gap="sm" align="flex-start" wrap="nowrap"
                                               data-testid="lk-message" data-sender={m.remetente}
                                               style={{ flexDirection: mine ? 'row-reverse' : 'row' }}>
                                            <ThemeIcon size={30} radius="xl" aria-hidden
                                                       variant={mine ? 'gradient' : 'light'}
                                                       color={mine ? undefined : 'gray'}
                                                       gradient={{ from: 'teal.4', to: 'cyan.7', deg: 135 }}>
                                                {mine ? <IconHeadset size={16} /> : <IconUser size={16} />}
                                            </ThemeIcon>
                                            <Stack gap={4} maw="75%" align={mine ? 'flex-end' : 'flex-start'}
                                                   style={{ minWidth: 0 }}>
                                                <Group gap={6} wrap="nowrap">
                                                    <Text size="xs" fw={600}>{author}</Text>
                                                    {at && <Text size="xs" c="dimmed">{formatInstant(at, locale)}</Text>}
                                                </Group>
                                                <Box px="md" py="xs" style={mine ? {
                                                    background: 'var(--mantine-color-teal-filled)',
                                                    color: 'var(--mantine-color-white)',
                                                    borderRadius: '16px 16px 4px 16px',
                                                } : {
                                                    background: 'var(--mantine-color-body)',
                                                    border: '1px solid var(--mantine-color-default-border)',
                                                    borderRadius: '16px 16px 16px 4px',
                                                }}>
                                                    <Text size="sm" lh={1.6} style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                                                        {content}
                                                    </Text>
                                                </Box>
                                            </Stack>
                                        </Group>
                                    )
                                })}
                            </Stack>
                        )}
                    </Box>

                    <Stack gap="xs" p="md" style={{ borderTop: '1px solid var(--mantine-color-default-border)' }}>
                        <Textarea
                            size="sm"
                            label={t('linktor.conversation.reply')}
                            placeholder={status === 'closed' ? t('linktor.conversation.replyClosed')
                                : resolved ? t('linktor.conversation.replyResolved')
                                : t('linktor.conversation.replyPlaceholder')}
                            value={draft}
                            onChange={e => setDraft(e.currentTarget.value)}
                            autosize minRows={2}
                            disabled={busy || resolved}
                            data-testid="lk-reply-input"
                        />
                        <Group justify="flex-end">
                            <PermissionTooltip missing={missing}>
                                <Button onClick={send} color="teal" leftSection={<IconSend size={15} />}
                                        disabled={busy || !draft.trim() || resolved || !isAdmin}
                                        data-testid="lk-reply-send">{t('chat.send')}</Button>
                            </PermissionTooltip>
                        </Group>
                    </Stack>
                </Paper>

                {/* Side panel */}
                <Stack gap="lg" style={{ flex: '1 1 300px', minWidth: 280 }}>
                    <SectionCard icon={IconUserShare} accent="amber"
                                 title={t('linktor.conversation.handoff')}
                                 description={t('linktor.conversation.handoffHint')}>
                        <Stack gap="sm">
                            {operadores && operadores.length > 0 ? (
                                <NativeSelect
                                    size="sm"
                                    label={t('linktor.conversation.assignee')}
                                    value={assignee}
                                    onChange={e => setAssignee(e.currentTarget.value)}
                                    data={[
                                        { value: '', label: t('linktor.conversation.assigneeChoose') },
                                        // Inativo ou suspenso fica visível (pode ser o atual responsável),
                                        // com o status no rótulo, mas não pode ser escolhido.
                                        ...operadores.map(o => {
                                            const nome = o.email ? `${o.nome} · ${o.email}` : o.nome
                                            return operadorAtivo(o)
                                                ? { value: o.id, label: nome }
                                                : { value: o.id, disabled: true, label: `${nome} (${t(
                                                    `linktor.conversation.userStatus.${o.status}`,
                                                    { defaultValue: o.status })})` }
                                        }),
                                    ]}
                                    data-testid="lk-assignee"
                                />
                            ) : (
                                <>
                                    {operadoresFalha === 'recusado' ? (
                                        <Alert color="yellow" variant="light" data-testid="lk-users-forbidden">
                                            {t('linktor.conversation.usersForbidden')}
                                        </Alert>
                                    ) : operadoresFalha === 'credenciais' ? (
                                        <Alert color="red" variant="light" data-testid="lk-users-unauthorized">
                                            {t('linktor.conversation.usersUnauthorized')}
                                        </Alert>
                                    ) : operadoresFalha ? (
                                        <Alert color="red" variant="light" data-testid="lk-users-failed">
                                            {t('linktor.conversation.usersFailed', { error: operadoresFalha })}
                                        </Alert>
                                    ) : operadores ? (
                                        <Text size="sm" c="dimmed" data-testid="lk-users-empty">
                                            {t('linktor.conversation.usersEmpty')}
                                        </Text>
                                    ) : null}
                                    <TextInput
                                        size="sm"
                                        label={t('linktor.conversation.assignee')}
                                        placeholder={t('linktor.conversation.assigneePlaceholder')}
                                        value={assignee}
                                        onChange={e => setAssignee(e.currentTarget.value)}
                                        data-testid="lk-assignee"
                                    />
                                </>
                            )}
                            <Group justify="flex-end" gap="sm">
                                <PermissionTooltip missing={missing}>
                                    <Button color="green" variant="light" onClick={resolve}
                                            leftSection={<IconCircleCheck size={15} />}
                                            disabled={busy || resolved || !isAdmin} data-testid="lk-resolve">
                                        {t('linktor.conversation.markResolved')}
                                    </Button>
                                </PermissionTooltip>
                                <PermissionTooltip missing={missing}>
                                    <Button onClick={assign} disabled={busy || !assignee.trim() || !isAdmin}
                                            data-testid="lk-assign" leftSection={<IconUserShare size={15} />}>
                                        {t('linktor.conversation.assign')}
                                    </Button>
                                </PermissionTooltip>
                            </Group>
                        </Stack>
                    </SectionCard>

                    <SectionCard icon={IconInfoCircle} accent="teal" title={t('linktor.conversation.details')}>
                        <SimpleGrid cols={2} spacing="md">
                            <Meta label={t('linktor.inbox.columns.status')}
                                  value={t(`linktor.status.${status}`, { defaultValue: status })} />
                            <Meta label={t('linktor.inbox.columns.contact')} value={conv?.contato ?? '—'} />
                            <Meta label={t('linktor.inbox.columns.channel')} value={channel || '—'} />
                            <Meta label={t('linktor.inbox.columns.updated')}
                                  value={formatInstant(conv?.atualizadaEm, locale)} />
                            <Meta label={t('linktor.inbox.columns.id')} value={id.slice(0, 12)} />
                        </SimpleGrid>
                    </SectionCard>
                </Stack>
            </Group>
        </Stack>
    )
}

/** Status do Linktor: open, pending, resolved, closed. */
const statusColor = (s: string) => (conversaEncerrada(s) ? 'green' : s === 'pending' ? 'blue' : 'yellow')
