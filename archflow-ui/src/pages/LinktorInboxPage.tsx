import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Card, Code, Group, Stack, Table, Tabs, Text, Title,
} from '@mantine/core'
import {
    linktorInboxApi,
    type LinktorChannel,
    type LinktorContact,
    type LinktorConversation,
} from '../services/linktor-inbox-api'
import { ApiError } from '../services/api'
import { DataTable, clickableRow } from '../components/DataTable'
import { StatusBadge } from '../components/StatusBadge'
import { formatInstant } from '../lib/format'

/**
 * Admin inbox proxying Linktor conversations / contacts / channels.
 * Acts as a lightweight read surface: clicking a conversation opens
 * the detail page where the admin can reply or trigger a human
 * handoff.
 *
 * <p>Errors from Linktor surface as banners rather than exceptions so
 * a misconfiguration is obvious to the operator without crashing the
 * page.</p>
 */
export default function LinktorInboxPage() {
    const { t, i18n } = useTranslation()
    const locale = i18n.resolvedLanguage ?? i18n.language
    const navigate = useNavigate()
    const [tab, setTab]               = useState<'conversations' | 'contacts' | 'channels'>('conversations')
    const [conversations, setC]       = useState<LinktorConversation[]>([])
    const [contacts, setContacts]     = useState<LinktorContact[]>([])
    const [channels, setChannels]     = useState<LinktorChannel[]>([])
    const [loading, setLoading]       = useState(true)
    const [error, setError]           = useState<string | null>(null)

    const reload = async (which: typeof tab) => {
        setLoading(true); setError(null)
        try {
            if (which === 'conversations') setC(await linktorInboxApi.listConversations())
            if (which === 'contacts')      setContacts(await linktorInboxApi.listContacts())
            if (which === 'channels')      setChannels(await linktorInboxApi.listChannels())
        } catch (e) {
            setError(e instanceof ApiError ? e.message : String(e))
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => { reload(tab) }, [tab]) // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <Stack gap="md" p="md" data-testid="linktor-inbox">
            <Group justify="space-between">
                <Title order={2}>{t('linktor.inbox.title')}</Title>
                <Button variant="default" onClick={() => reload(tab)}>{t('common.refresh')}</Button>
            </Group>

            {error && (
                <Alert color="red" title={t('linktor.config.title')} data-testid="linktor-inbox-error">
                    {error}
                </Alert>
            )}

            <Tabs value={tab} onChange={v => setTab((v as typeof tab) ?? 'conversations')}>
                <Tabs.List>
                    <Tabs.Tab value="conversations">{t('linktor.inbox.tabs.conversations', { count: conversations.length })}</Tabs.Tab>
                    <Tabs.Tab value="contacts">{t('linktor.inbox.tabs.contacts')}</Tabs.Tab>
                    <Tabs.Tab value="channels">{t('linktor.inbox.tabs.channels')}</Tabs.Tab>
                </Tabs.List>
            </Tabs>

            {tab === 'conversations' && (
                <DataTable
                    columns={5}
                    minWidth={760}
                    loading={loading}
                    isEmpty={!error && conversations.length === 0}
                    emptyMessage={t('linktor.inbox.emptyConversations')}
                    head={
                        <Table.Tr>
                            <Table.Th>{t('linktor.inbox.columns.id')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.status')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.channel')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.lastMessage')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.updated')}</Table.Th>
                        </Table.Tr>
                    }
                >
                    {conversations.map(c => {
                        const id     = String(c.id ?? '')
                        const status = String(c.status ?? '')
                        const lastAt = String(c.updatedAt ?? c.createdAt ?? '')
                        const channel = String(c.channelType ?? c.channelId ?? '')
                        const preview = typeof c.lastMessagePreview === 'string' ? c.lastMessagePreview : ''
                        return (
                            <Table.Tr
                                key={id}
                                data-testid={`lk-conv-${id}`}
                                {...clickableRow(() => navigate(`/admin/linktor/inbox/${encodeURIComponent(id)}`))}
                            >
                                <Table.Td><Code>{id.slice(0, 10)}</Code></Table.Td>
                                <Table.Td>
                                    <StatusBadge
                                        status={status === 'resolved' ? 'closed' : status === 'assigned' ? 'active' : 'pending'}
                                        color={status === 'resolved' ? 'green' : status === 'assigned' ? 'blue' : 'yellow'}
                                        label={t(`linktor.status.${status || 'open'}`, { defaultValue: status || 'open' })}
                                    />
                                </Table.Td>
                                <Table.Td><Text size="sm">{channel}</Text></Table.Td>
                                <Table.Td>
                                    <Text size="sm" lineClamp={1}>{preview}</Text>
                                </Table.Td>
                                <Table.Td><Text size="sm">{formatInstant(lastAt, locale)}</Text></Table.Td>
                            </Table.Tr>
                        )
                    })}
                </DataTable>
            )}

            {tab === 'contacts' && (
                <DataTable
                    columns={4}
                    minWidth={620}
                    loading={loading}
                    isEmpty={!error && contacts.length === 0}
                    emptyMessage={t('linktor.inbox.emptyContacts')}
                    head={
                        <Table.Tr>
                            <Table.Th>{t('linktor.inbox.columns.name')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.email')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.phone')}</Table.Th>
                            <Table.Th>{t('linktor.inbox.columns.tags')}</Table.Th>
                        </Table.Tr>
                    }
                >
                    {contacts.map(ct => {
                        const id = String(ct.id ?? '')
                        const tags = Array.isArray(ct.tags) ? (ct.tags as unknown[]).map(String) : []
                        return (
                            <Table.Tr key={id}>
                                <Table.Td>{String(ct.name ?? '')}</Table.Td>
                                <Table.Td>{String(ct.email ?? '')}</Table.Td>
                                <Table.Td>{String(ct.phone ?? '')}</Table.Td>
                                <Table.Td>
                                    {tags.map(tag => (
                                        <Badge key={tag} size="sm" variant="outline" mr={4}>{tag}</Badge>
                                    ))}
                                </Table.Td>
                            </Table.Tr>
                        )
                    })}
                </DataTable>
            )}

            {tab === 'channels' && (
                <Stack gap="sm">
                    {loading && <Text c="dimmed">{t('common.loading')}</Text>}
                    {!loading && channels.length === 0 && <Text c="dimmed">{t('linktor.inbox.emptyChannels')}</Text>}
                    {!loading && channels.map(ch => {
                        const id = String(ch.id ?? '')
                        return (
                            <Card key={id} withBorder>
                                <Group justify="space-between">
                                    <Stack gap={2}>
                                        <Group gap="xs">
                                            <Text fw={600}>{String(ch.name ?? id)}</Text>
                                            <Badge variant="light">{String(ch.type ?? '')}</Badge>
                                        </Group>
                                        <Text size="xs" c="dimmed">{String(ch.status ?? '')}</Text>
                                    </Stack>
                                </Group>
                            </Card>
                        )
                    })}
                </Stack>
            )}
        </Stack>
    )
}
