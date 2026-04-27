import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Card, Group, Stack, Table, Tabs, Text, Title,
} from '@mantine/core'
import {
    linktorInboxApi,
    type LinktorChannel,
    type LinktorContact,
    type LinktorConversation,
} from '../services/linktor-inbox-api'
import { ApiError } from '../services/api'

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
    const { t } = useTranslation()
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
        <Stack gap="md" style={{ padding: 24 }} data-testid="linktor-inbox">
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

            {loading && <Text c="dimmed">{t('triggers.loading')}</Text>}

            {!loading && tab === 'conversations' && (
                <Card withBorder p={0}>
                    <Table>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('linktor.inbox.columns.id')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.status')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.channel')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.lastMessage')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.updated')}</Table.Th>
                                <Table.Th />
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {conversations.length === 0 && (
                                <Table.Tr><Table.Td colSpan={6}>
                                    <Text c="dimmed" ta="center" py="md">
                                        {t('linktor.inbox.emptyConversations')}
                                    </Text>
                                </Table.Td></Table.Tr>
                            )}
                            {conversations.map(c => {
                                const id     = String(c.id ?? '')
                                const status = String(c.status ?? '')
                                const lastAt = String(c.updatedAt ?? c.createdAt ?? '')
                                const channel = String(c.channelType ?? c.channelId ?? '')
                                const preview = typeof c.lastMessagePreview === 'string' ? c.lastMessagePreview : ''
                                return (
                                    <Table.Tr key={id} data-testid={`lk-conv-${id}`}>
                                        <Table.Td><code>{id.slice(0, 10)}</code></Table.Td>
                                        <Table.Td>
                                            <Badge color={status === 'resolved' ? 'green' :
                                                        status === 'assigned' ? 'blue' : 'yellow'}>
                                                {t(`linktor.status.${status || 'open'}`, { defaultValue: status || 'open' })}
                                            </Badge>
                                        </Table.Td>
                                        <Table.Td><Text size="sm">{channel}</Text></Table.Td>
                                        <Table.Td>
                                            <Text size="sm" lineClamp={1}>{preview}</Text>
                                        </Table.Td>
                                        <Table.Td><Text size="sm">{lastAt}</Text></Table.Td>
                                        <Table.Td>
                                            <Link to={`/admin/linktor/inbox/${encodeURIComponent(id)}`}>
                                                <Button size="xs" variant="light">{t('linktor.inbox.open')}</Button>
                                            </Link>
                                        </Table.Td>
                                    </Table.Tr>
                                )
                            })}
                        </Table.Tbody>
                    </Table>
                </Card>
            )}

            {!loading && tab === 'contacts' && (
                <Card withBorder p={0}>
                    <Table>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('linktor.inbox.columns.name')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.email')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.phone')}</Table.Th>
                                <Table.Th>{t('linktor.inbox.columns.tags')}</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {contacts.length === 0 && (
                                <Table.Tr><Table.Td colSpan={4}>
                                    <Text c="dimmed" ta="center" py="md">{t('linktor.inbox.emptyContacts')}</Text>
                                </Table.Td></Table.Tr>
                            )}
                            {contacts.map(ct => {
                                const id = String(ct.id ?? '')
                                const tags = Array.isArray(ct.tags) ? (ct.tags as unknown[]).map(String) : []
                                return (
                                    <Table.Tr key={id}>
                                        <Table.Td>{String(ct.name ?? '')}</Table.Td>
                                        <Table.Td>{String(ct.email ?? '')}</Table.Td>
                                        <Table.Td>{String(ct.phone ?? '')}</Table.Td>
                                        <Table.Td>
                                            {tags.map(t => (
                                                <Badge key={t} size="sm" variant="outline" mr={4}>{t}</Badge>
                                            ))}
                                        </Table.Td>
                                    </Table.Tr>
                                )
                            })}
                        </Table.Tbody>
                    </Table>
                </Card>
            )}

            {!loading && tab === 'channels' && (
                <Stack gap="sm">
                    {channels.length === 0 && <Text c="dimmed">{t('linktor.inbox.emptyChannels')}</Text>}
                    {channels.map(ch => {
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
