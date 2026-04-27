import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Card, Code, Group, Paper, Stack, Text, Textarea, TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    linktorInboxApi,
    type LinktorConversation,
    type LinktorMessage,
} from '../services/linktor-inbox-api'
import { ApiError } from '../services/api'

/**
 * Detail view of a single Linktor conversation.
 *
 * <p>Lets the admin read the message history, send a reply (posting
 * via the inbox backend which in turn calls Linktor's
 * {@code POST /conversations/:id/messages}), assign the conversation
 * to a human operator, or mark it resolved.</p>
 */
export default function LinktorConversationPage() {
    const { t } = useTranslation()
    const { id = '' } = useParams()
    const [conv, setConv]         = useState<LinktorConversation | null>(null)
    const [messages, setMessages] = useState<LinktorMessage[]>([])
    const [loading, setLoading]   = useState(true)
    const [error, setError]       = useState<string | null>(null)
    const [draft, setDraft]       = useState('')
    const [assignee, setAssignee] = useState('')
    const [busy, setBusy]         = useState(false)

    const load = async () => {
        setLoading(true); setError(null)
        try {
            const [c, ms] = await Promise.all([
                linktorInboxApi.getConversation(id),
                linktorInboxApi.listMessages(id),
            ])
            setConv(c); setMessages(ms)
        } catch (e) {
            setError(e instanceof ApiError ? e.message : String(e))
        } finally {
            setLoading(false)
        }
    }
    useEffect(() => { load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

    const status = useMemo(() => conv ? String(conv.status ?? 'open') : 'open', [conv])

    const send = async () => {
        if (!draft.trim()) return
        setBusy(true)
        try {
            await linktorInboxApi.sendMessage(id, draft.trim())
            setDraft('')
            await load()
        } catch (e) {
            notifications.show({ color: 'red', title: t('linktor.conversation.sendFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally { setBusy(false) }
    }

    const assign = async () => {
        if (!assignee.trim()) return
        setBusy(true)
        try {
            await linktorInboxApi.assign(id, assignee.trim())
            notifications.show({ color: 'green', title: t('linktor.conversation.assigned'),
                message: t('linktor.conversation.assignedMsg', { assignee: assignee.trim() }) })
            await load()
        } catch (e) {
            notifications.show({ color: 'red', title: t('linktor.conversation.assignFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally { setBusy(false) }
    }

    const resolve = async () => {
        setBusy(true)
        try {
            await linktorInboxApi.resolve(id)
            notifications.show({ color: 'green', title: t('linktor.conversation.resolved'),
                message: t('linktor.conversation.resolvedMsg') })
            await load()
        } catch (e) {
            notifications.show({ color: 'red', title: t('linktor.conversation.resolveFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally { setBusy(false) }
    }

    return (
        <Stack gap="md" style={{ padding: 24, maxWidth: 900 }} data-testid="linktor-conversation">
            <Group justify="space-between">
                <Group align="baseline">
                    <Title order={2}>{t('linktor.conversation.title')}</Title>
                    <Code>{id.slice(0, 12)}</Code>
                    <Badge color={status === 'resolved' ? 'green' :
                                status === 'assigned' ? 'blue' : 'yellow'}>
                        {t(`linktor.status.${status}`, { defaultValue: status })}
                    </Badge>
                </Group>
                <Link to="/admin/linktor/inbox"><Button variant="default">{t('linktor.conversation.back')}</Button></Link>
            </Group>

            {error && <Alert color="red" title={t('linktor.config.title')}>{error}</Alert>}
            {loading && <Text c="dimmed">{t('triggers.loading')}</Text>}

            {!loading && (
                <Paper withBorder p="md">
                    <Stack gap="xs">
                        {messages.length === 0 && (
                            <Text c="dimmed">{t('linktor.conversation.noMessages')}</Text>
                        )}
                        {messages.map((m, i) => {
                            const author = String(m.direction ?? m.senderType ?? 'user')
                            const content = String(m.content ?? m.text ?? '')
                            const at = String(m.createdAt ?? m.timestamp ?? '')
                            const mine = author === 'outbound' || author === 'agent'
                            return (
                                <Paper key={i} withBorder radius="md"
                                       p="xs"
                                       style={{
                                           alignSelf: mine ? 'flex-end' : 'flex-start',
                                           maxWidth: '70%',
                                           background: mine ? 'var(--mantine-color-blue-0)' : undefined,
                                       }}>
                                    <Text size="xs" c="dimmed">{author} · {at}</Text>
                                    <Text>{content}</Text>
                                </Paper>
                            )
                        })}
                    </Stack>
                </Paper>
            )}

            <Card withBorder>
                <Stack>
                    <Textarea
                        label={t('linktor.conversation.reply')}
                        value={draft}
                        onChange={e => setDraft(e.currentTarget.value)}
                        autosize minRows={2}
                        disabled={busy || status === 'resolved'}
                        data-testid="lk-reply-input"
                    />
                    <Group justify="flex-end">
                        <Button onClick={send} disabled={busy || !draft.trim() || status === 'resolved'}
                                data-testid="lk-reply-send">{t('chat.send')}</Button>
                    </Group>
                </Stack>
            </Card>

            <Card withBorder>
                <Stack>
                    <Text fw={600}>{t('linktor.conversation.handoff')}</Text>
                    <Group>
                        <TextInput
                            placeholder={t('linktor.conversation.assigneePlaceholder')}
                            value={assignee}
                            onChange={e => setAssignee(e.currentTarget.value)}
                            style={{ flex: 1 }}
                            data-testid="lk-assignee"
                        />
                        <Button variant="light" onClick={assign} disabled={busy} data-testid="lk-assign">
                            {t('linktor.conversation.assign')}
                        </Button>
                        <Button color="green" variant="outline" onClick={resolve}
                                disabled={busy || status === 'resolved'} data-testid="lk-resolve">
                            {t('linktor.conversation.markResolved')}
                        </Button>
                    </Group>
                </Stack>
            </Card>
        </Stack>
    )
}
