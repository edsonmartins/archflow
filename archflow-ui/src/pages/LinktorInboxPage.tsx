import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Group, Paper, SimpleGrid, Skeleton, Stack, Tabs, Text, ThemeIcon,
} from '@mantine/core'
import {
    IconAddressBook, IconBroadcast, IconCircleCheck, IconHeadset, IconInbox, IconMessages, IconRefresh,
    IconMessageCircle, IconPlugConnectedX, IconAlertCircle,
} from '@tabler/icons-react'
import {
    linktorInboxApi,
    type LinktorChannel,
    type LinktorContact,
    type LinktorConversation,
} from '../services/linktor-inbox-api'
import { ApiError } from '../services/api'
import { formatInstant } from '../lib/format'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import { VxTable, colunaApagada, colunaDupla, colunaStatus, type VxColumn } from '../components/VTable'
import { canalDoLinktor } from '../components/linktor/canal'

const statusColor = (s: string) => (s === 'resolved' ? 'green' : s === 'assigned' ? 'blue' : 'yellow')

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

    const counts = useMemo(() => {
        const by = (s: string) => conversations.filter(c => String(c.status ?? 'open') === s).length
        return { open: by('open'), assigned: by('assigned'), resolved: by('resolved') }
    }, [conversations])

    const colunasConversas = useMemo((): VxColumn[] => [
        colunaDupla(
            { field: 'lastMessagePreview', title: t('linktor.inbox.columns.lastMessage'), width: 'auto', minWidth: 300 },
            (r) => (typeof r.lastMessagePreview === 'string' && r.lastMessagePreview ? r.lastMessagePreview : '—'),
            (r) => String(r.id ?? '').slice(0, 10),
        ),
        colunaStatus(
            { field: 'status', title: t('linktor.inbox.columns.status'), width: 150,
              fieldFormat: (r) => {
                  const s = String(r.status ?? '') || 'open'
                  return t(`linktor.status.${s}`, { defaultValue: s })
              } },
            (r) => statusColor(String(r.status ?? '')),
        ),
        colunaStatus(
            { field: 'channelType', title: t('linktor.inbox.columns.channel'), width: 160,
              fieldFormat: (r) => String(r.channelType ?? r.channelId ?? '') },
            (r) => canalDoLinktor(String(r.channelType ?? '')).color,
        ),
        colunaApagada({ field: 'updatedAt', title: t('linktor.inbox.columns.updated'), width: 210,
          fieldFormat: (r) => formatInstant(String(r.updatedAt ?? r.createdAt ?? ''), locale) }),
    ], [t, locale])

    const colunasContatos = useMemo((): VxColumn[] => [
        colunaDupla(
            { field: 'name', title: t('linktor.inbox.columns.name'), width: 'auto', minWidth: 260 },
            (r) => String(r.name ?? '—'),
            (r) => (r.email ? String(r.email) : undefined),
        ),
        colunaApagada({ field: 'phone', title: t('linktor.inbox.columns.phone'), width: 180,
          fieldFormat: (r) => String(r.phone ?? '—') }),
        { field: 'tags', title: t('linktor.inbox.columns.tags'), width: 260,
          fieldFormat: (r) => (Array.isArray(r.tags) ? (r.tags as unknown[]).map(String).join(' · ') : '') },
    ], [t])

    const conversationsLabel = t('linktor.inbox.tabs.conversations', { count: conversations.length })

    return (
        <Stack gap="lg" p="md" data-testid="linktor-inbox">
            <PageHeader
                icon={IconInbox}
                accent="teal"
                title={t('linktor.inbox.title')}
                subtitle={t('linktor.inbox.subtitle')}
                actions={
                    <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => reload(tab)}>
                        {t('common.refresh')}
                    </Button>
                }
            />

            {error && (
                // O backend responde `{"error":"..."}`: mostra só a frase. Integração desligada não é falha —
                // é um aviso neutro com o atalho para ligá-la.
                linktorDesligado(error) ? (
                    <Alert color="blue" variant="light" icon={<IconPlugConnectedX size={18} />}
                           title={t('linktor.inbox.disabledTitle')} data-testid="linktor-inbox-error">
                        <Group justify="space-between" wrap="wrap" gap="sm">
                            <Text size="sm">{t('linktor.inbox.disabledBody')}</Text>
                            <Button size="xs" variant="light" onClick={() => navigate('/admin/linktor')}>
                                {t('linktor.inbox.openConfig')}
                            </Button>
                        </Group>
                    </Alert>
                ) : (
                    <Alert color="red" variant="light" icon={<IconAlertCircle size={18} />}
                           title={t('linktor.config.title')} data-testid="linktor-inbox-error">
                        {mensagemDoErro(error)}
                    </Alert>
                )
            )}

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('linktor.inbox.stats.total')} value={conversations.length}
                          icon={IconMessages} accent="teal" hint={t('linktor.inbox.stats.totalHint')} />
                <StatCard label={t('linktor.inbox.stats.open')} value={counts.open}
                          color={counts.open > 0 ? 'orange' : undefined}
                          icon={IconMessageCircle} accent="amber" hint={t('linktor.inbox.stats.openHint')} />
                <StatCard label={t('linktor.inbox.stats.assigned')} value={counts.assigned}
                          icon={IconHeadset} accent="blue" hint={t('linktor.inbox.stats.assignedHint')} />
                <StatCard label={t('linktor.inbox.stats.resolved')} value={counts.resolved}
                          icon={IconCircleCheck} accent="green" hint={t('linktor.inbox.stats.resolvedHint')} />
            </SimpleGrid>

            <Tabs value={tab} onChange={v => setTab((v as typeof tab) ?? 'conversations')}>
                <Tabs.List>
                    <Tabs.Tab value="conversations" leftSection={<IconMessages size={15} />}>{conversationsLabel}</Tabs.Tab>
                    <Tabs.Tab value="contacts" leftSection={<IconAddressBook size={15} />}>{t('linktor.inbox.tabs.contacts')}</Tabs.Tab>
                    <Tabs.Tab value="channels" leftSection={<IconBroadcast size={15} />}>{t('linktor.inbox.tabs.channels')}</Tabs.Tab>
                </Tabs.List>
            </Tabs>

            {tab === 'conversations' && (
                <SectionCard flush icon={IconMessages} accent="teal"
                             title={t('linktor.inbox.conversationsTitle')}
                             description={t('linktor.inbox.conversationsHint')}>
                    <VxTable
                        legenda={t('linktor.inbox.conversationsTitle')}
                        records={error ? [] : conversations}
                        columns={colunasConversas}
                        rowHeight={58}
                        loading={loading}
                        onRowClick={(r) => navigate(`/admin/linktor/inbox/${encodeURIComponent(String(r.id ?? ''))}`)}
                        emptyMessage={t('linktor.inbox.emptyConversations')}
                        emptyDescription={t('linktor.inbox.emptyConversationsHint')}
                        emptyIcon={<IconMessages />}
                        emptyAccent="teal"
                        testid="lk-conversations"
                        rowTestId={(r) => `lk-conv-${String(r.id ?? '')}`}
                    />
                </SectionCard>
            )}

            {tab === 'contacts' && (
                <SectionCard flush icon={IconAddressBook} accent="teal"
                             title={t('linktor.inbox.tabs.contacts')}
                             description={t('linktor.inbox.contactsHint')}>
                    <VxTable
                        legenda={t('linktor.inbox.tabs.contacts')}
                        records={error ? [] : contacts}
                        columns={colunasContatos}
                        rowHeight={58}
                        loading={loading}
                        emptyMessage={t('linktor.inbox.emptyContacts')}
                        emptyIcon={<IconAddressBook />}
                        emptyAccent="teal"
                        testid="lk-contacts"
                    />
                </SectionCard>
            )}

            {tab === 'channels' && (
                loading ? (
                    <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                        {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} height={84} radius="md" />)}
                    </SimpleGrid>
                ) : channels.length === 0 ? (
                    <Paper withBorder>
                        <EmptyState icon={<IconBroadcast />} accent="teal" title={t('linktor.inbox.emptyChannels')}
                                    description={t('linktor.inbox.emptyChannelsHint')} />
                    </Paper>
                ) : (
                    <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                        {channels.map(ch => {
                            const id = String(ch.id ?? '')
                            const type = String(ch.type ?? '')
                            const status = String(ch.status ?? '')
                            const { icon: Icon, color } = canalDoLinktor(type)
                            const up = /active|connected|online|enabled/i.test(status)
                            return (
                                <Paper key={id} withBorder p="md">
                                    <Group gap="md" wrap="nowrap">
                                        <ThemeIcon size={42} radius="md" variant="light" color={color} aria-hidden>
                                            <Icon size={22} />
                                        </ThemeIcon>
                                        <Stack gap={4} style={{ minWidth: 0, flex: 1 }}>
                                            <Text fw={600} truncate>{String(ch.name ?? id)}</Text>
                                            <Group gap={6}>
                                                {type && <Badge size="xs" variant="light" color={color} radius="sm">{type}</Badge>}
                                                {status && (
                                                    <Badge size="xs" variant="dot" color={up ? 'green' : 'gray'} radius="sm">
                                                        {status}
                                                    </Badge>
                                                )}
                                            </Group>
                                        </Stack>
                                    </Group>
                                </Paper>
                            )
                        })}
                    </SimpleGrid>
                )
            )}
        </Stack>
    )
}

/** O backend devolve o erro como JSON (`{"error":"..."}`); fica só a frase. */
function mensagemDoErro(bruto: string): string {
    try {
        const corpo = JSON.parse(bruto) as { error?: unknown; message?: unknown }
        const msg = corpo.error ?? corpo.message
        return typeof msg === 'string' && msg ? msg : bruto
    } catch {
        return bruto
    }
}

function linktorDesligado(bruto: string): boolean {
    return /disabled/i.test(mensagemDoErro(bruto))
}
