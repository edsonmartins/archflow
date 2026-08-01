import { useCallback, useEffect, useRef, useState } from 'react'
import {
    Alert, Badge, Button, Card, Divider, FileButton, Group, Loader, Modal,
    Paper, Progress, ScrollArea, SimpleGrid, Stack, Table, Tabs, Text,
    TextInput, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertCircle, IconBook2, IconCloudUpload, IconDatabaseSearch,
    IconHistory, IconPlus, IconRefresh, IconSearch,
} from '@tabler/icons-react'
import { useTranslation } from 'react-i18next'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { confirmAction } from '../lib/confirm'
import {
    knowledgeApi,
    type IndexVersion,
    type Job,
    type KnowledgeBase,
    type KnowledgeDocument,
    type SearchHit,
    type StoredFile,
} from '../services/knowledge-api'

const statusColor = (status: string) => ({
    READY: 'green', SUCCEEDED: 'green', ACTIVE: 'green',
    PROCESSING: 'blue', RUNNING: 'blue',
    QUEUED: 'yellow', BUILDING: 'yellow',
    FAILED: 'red', CANCELLED: 'gray', INACTIVE: 'gray',
}[status] ?? 'gray')

const formatDate = (value: string | null) =>
    value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
        .format(new Date(value)) : '—'

const formatBytes = (value?: number) => {
    if (value == null) return '—'
    if (value < 1024) return `${value} B`
    if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`
    return `${(value / 1024 ** 2).toFixed(1)} MB`
}

export default function KnowledgeBasesPage() {
    const { t } = useTranslation()
    const [bases, setBases] = useState<KnowledgeBase[]>([])
    const [selectedId, setSelectedId] = useState<string | null>(null)
    const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
    const [files, setFiles] = useState<Record<string, StoredFile>>({})
    const [versions, setVersions] = useState<IndexVersion[]>([])
    const [jobs, setJobs] = useState<Record<string, Job>>({})
    const [documentJobs, setDocumentJobs] = useState<Record<string, string>>({})
    const [hits, setHits] = useState<SearchHit[]>([])
    const [query, setQuery] = useState('')
    const [searchVersion, setSearchVersion] = useState<string | undefined>()
    const [loading, setLoading] = useState(true)
    const [detailLoading, setDetailLoading] = useState(false)
    const [uploading, setUploading] = useState(false)
    const [searching, setSearching] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [createOpen, setCreateOpen] = useState(false)
    const [newName, setNewName] = useState('')
    const [creating, setCreating] = useState(false)
    const completedJobs = useRef(new Set<string>())

    const selected = bases.find(base => base.id === selectedId)

    const loadBases = useCallback(async () => {
        setLoading(true)
        setError(null)
        try {
            const result = await knowledgeApi.listBases()
            setBases(result)
            setSelectedId(current =>
                current && result.some(base => base.id === current)
                    ? current : result[0]?.id ?? null)
        } catch (failure) {
            setError(failure instanceof Error ? failure.message : String(failure))
        } finally {
            setLoading(false)
        }
    }, [])

    const loadDetail = useCallback(async (baseId: string, quiet = false) => {
        if (!quiet) setDetailLoading(true)
        try {
            const [docs, indexVersions] = await Promise.all([
                knowledgeApi.listDocuments(baseId),
                knowledgeApi.versions(baseId),
            ])
            setDocuments(docs)
            setVersions(indexVersions)
            const metadata = await Promise.all(docs.map(document =>
                knowledgeApi.getFile(document.fileId).catch(() => null)))
            setFiles(Object.fromEntries(metadata.filter(Boolean)
                .map(file => [(file as StoredFile).id, file as StoredFile])))
        } catch (failure) {
            notifications.show({
                color: 'red',
                title: t('knowledge.loadFailed'),
                message: failure instanceof Error ? failure.message : String(failure),
            })
        } finally {
            if (!quiet) setDetailLoading(false)
        }
    }, [t])

    useEffect(() => { void loadBases() }, [loadBases])
    useEffect(() => {
        if (selectedId) void loadDetail(selectedId)
        else {
            setDocuments([])
            setVersions([])
        }
    }, [selectedId, loadDetail])

    useEffect(() => {
        const active = Object.values(jobs).filter(job =>
            job.status === 'QUEUED' || job.status === 'RUNNING')
        if (active.length === 0) return
        const timer = window.setInterval(async () => {
            const updates = await Promise.all(active.map(job =>
                knowledgeApi.getJob(job.id).catch(() => job)))
            setJobs(current => ({
                ...current,
                ...Object.fromEntries(updates.map(job => [job.id, job])),
            }))
            const completed = updates.filter(job =>
                job.status !== 'QUEUED' && job.status !== 'RUNNING'
                && !completedJobs.current.has(job.id))
            if (completed.length && selectedId) {
                completed.forEach(job => completedJobs.current.add(job.id))
                void loadDetail(selectedId, true)
                completed.forEach(job => notifications.show({
                    color: job.status === 'SUCCEEDED' ? 'green' : 'red',
                    title: t(job.status === 'SUCCEEDED'
                        ? 'knowledge.ingestionComplete' : 'knowledge.ingestionFailed'),
                    message: job.error || job.message || undefined,
                }))
            }
        }, 1500)
        return () => window.clearInterval(timer)
    }, [jobs, selectedId, loadDetail, t])

    const createBase = async () => {
        if (!newName.trim()) return
        setCreating(true)
        try {
            const base = await knowledgeApi.createBase(newName.trim())
            setBases(current => [base, ...current])
            setSelectedId(base.id)
            setNewName('')
            setCreateOpen(false)
        } catch (failure) {
            notifications.show({ color: 'red', title: t('knowledge.createFailed'),
                message: failure instanceof Error ? failure.message : String(failure) })
        } finally {
            setCreating(false)
        }
    }

    const upload = async (file: File | null) => {
        if (!file || !selectedId) return
        setUploading(true)
        try {
            const stored = await knowledgeApi.upload(file)
            const submission = await knowledgeApi.ingest(selectedId, stored.id)
            setFiles(current => ({ ...current, [stored.id]: stored }))
            setDocuments(current => [submission.document,
                ...current.filter(document => document.id !== submission.document.id)])
            setJobs(current => ({ ...current, [submission.job.id]: submission.job }))
            setDocumentJobs(current => ({
                ...current,
                [submission.document.id]: submission.job.id,
            }))
            notifications.show({ color: 'blue', title: t('knowledge.ingestionQueued'),
                message: file.name })
        } catch (failure) {
            notifications.show({ color: 'red', title: t('knowledge.uploadFailed'),
                message: failure instanceof Error ? failure.message : String(failure) })
        } finally {
            setUploading(false)
        }
    }

    const search = async () => {
        if (!selectedId || !query.trim()) return
        setSearching(true)
        try {
            setHits(await knowledgeApi.search(selectedId, query.trim(), searchVersion))
        } catch (failure) {
            notifications.show({ color: 'red', title: t('knowledge.searchFailed'),
                message: failure instanceof Error ? failure.message : String(failure) })
        } finally {
            setSearching(false)
        }
    }

    const rollback = (version: IndexVersion) => confirmAction({
        title: t('knowledge.rollbackTitle'),
        message: t('knowledge.rollbackMessage', { id: version.id }),
        confirmLabel: t('knowledge.rollback'),
        danger: false,
        onConfirm: async () => {
            if (!selectedId) return
            try {
                await knowledgeApi.rollback(selectedId, version.id)
                await loadDetail(selectedId, true)
                setSearchVersion(undefined)
                notifications.show({ color: 'green', title: t('knowledge.rollbackComplete'),
                    message: version.id })
            } catch (failure) {
                notifications.show({ color: 'red', title: t('knowledge.rollbackFailed'),
                    message: failure instanceof Error ? failure.message : String(failure) })
            }
        },
    })

    const jobForDocument = (document: KnowledgeDocument) =>
        jobs[documentJobs[document.id]]

    return (
        <Stack gap="lg" data-testid="knowledge-bases-page">
            <PageHeader
                title={t('knowledge.title')}
                subtitle={t('knowledge.subtitle')}
                actions={<Button leftSection={<IconPlus size={16} />}
                    onClick={() => setCreateOpen(true)}>{t('knowledge.newBase')}</Button>}
            />

            {error && <Alert color="red" icon={<IconAlertCircle size={16} />}>{error}</Alert>}

            <SimpleGrid cols={{ base: 1, md: 4 }} spacing="md">
                <Stack gap="xs">
                    <Group justify="space-between">
                        <Text size="sm" fw={600}>{t('knowledge.bases')}</Text>
                        <Button variant="subtle" size="compact-xs" px={5}
                            onClick={() => void loadBases()} aria-label={t('common.refresh')}>
                            <IconRefresh size={14} />
                        </Button>
                    </Group>
                    {loading ? <Loader size="sm" /> : bases.length === 0 ? (
                        <Card withBorder>
                            <Stack align="center" gap="xs" py="md">
                                <IconBook2 size={28} opacity={0.55} />
                                <Text size="sm" c="dimmed" ta="center">{t('knowledge.empty')}</Text>
                            </Stack>
                        </Card>
                    ) : bases.map(base => (
                        <Card key={base.id} withBorder padding="sm"
                            bg={selectedId === base.id ? 'var(--mantine-color-blue-light)' : undefined}
                            style={{ cursor: 'pointer' }} onClick={() => setSelectedId(base.id)}>
                            <Text fw={600} size="sm">{base.name}</Text>
                            <Text size="xs" c="dimmed">{formatDate(base.createdAt)}</Text>
                        </Card>
                    ))}
                </Stack>

                <Stack gap="md" style={{ gridColumn: 'span 3' }}>
                    {!selected ? (
                        <Paper withBorder p="xl"><Text c="dimmed" ta="center">
                            {t('knowledge.selectBase')}</Text></Paper>
                    ) : detailLoading ? <Loader /> : (
                        <>
                            <Group justify="space-between">
                                <div>
                                    <Title order={3}>{selected.name}</Title>
                                    <Text size="xs" c="dimmed">{selected.id}</Text>
                                </div>
                                <FileButton onChange={upload}
                                    accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document">
                                    {props => <Button {...props} loading={uploading}
                                        leftSection={<IconCloudUpload size={16} />}>
                                        {t('knowledge.upload')}
                                    </Button>}
                                </FileButton>
                            </Group>

                            <Tabs defaultValue="documents">
                                <Tabs.List>
                                    <Tabs.Tab value="documents" leftSection={<IconBook2 size={15} />}>
                                        {t('knowledge.documents')} ({documents.length})
                                    </Tabs.Tab>
                                    <Tabs.Tab value="search" leftSection={<IconDatabaseSearch size={15} />}>
                                        {t('knowledge.search')}
                                    </Tabs.Tab>
                                    <Tabs.Tab value="versions" leftSection={<IconHistory size={15} />}>
                                        {t('knowledge.versions')} ({versions.length})
                                    </Tabs.Tab>
                                </Tabs.List>

                                <Tabs.Panel value="documents" pt="md">
                                    {documents.length === 0 ? (
                                        <Card withBorder><Text c="dimmed" ta="center" py="lg">
                                            {t('knowledge.noDocuments')}</Text></Card>
                                    ) : (
                                        <ScrollArea>
                                            <Table verticalSpacing="sm">
                                                <Table.Thead><Table.Tr>
                                                    <Table.Th>{t('knowledge.file')}</Table.Th>
                                                    <Table.Th>{t('knowledge.status')}</Table.Th>
                                                    <Table.Th>{t('knowledge.chunks')}</Table.Th>
                                                    <Table.Th>{t('knowledge.updated')}</Table.Th>
                                                </Table.Tr></Table.Thead>
                                                <Table.Tbody>{documents.map(document => {
                                                    const file = files[document.fileId]
                                                    const job = jobForDocument(document)
                                                    const effectiveStatus = job?.status === 'RUNNING'
                                                        ? 'PROCESSING' : document.status
                                                    return <Table.Tr key={document.id}>
                                                        <Table.Td>
                                                            <Text size="sm" fw={500}>
                                                                {file?.originalName ?? document.fileId}
                                                            </Text>
                                                            <Text size="xs" c="dimmed">
                                                                {formatBytes(file?.size)}
                                                            </Text>
                                                            {job && (job.status === 'QUEUED' || job.status === 'RUNNING') &&
                                                                <Progress value={job.progress} size="xs" mt={5} />}
                                                            {document.error &&
                                                                <Text size="xs" c="red">{document.error}</Text>}
                                                        </Table.Td>
                                                        <Table.Td><StatusBadge status={effectiveStatus}
                                                            label={t(`knowledge.statuses.${effectiveStatus}`)}
                                                            color={statusColor(effectiveStatus)} /></Table.Td>
                                                        <Table.Td>{document.chunkCount}</Table.Td>
                                                        <Table.Td><Text size="xs">
                                                            {formatDate(document.updatedAt)}</Text></Table.Td>
                                                    </Table.Tr>
                                                })}</Table.Tbody>
                                            </Table>
                                        </ScrollArea>
                                    )}
                                </Tabs.Panel>

                                <Tabs.Panel value="search" pt="md">
                                    <Stack>
                                        <Group align="flex-end">
                                            <TextInput style={{ flex: 1 }}
                                                label={t('knowledge.query')}
                                                placeholder={t('knowledge.queryPlaceholder')}
                                                value={query}
                                                onChange={event => setQuery(event.currentTarget.value)}
                                                onKeyDown={event => {
                                                    if (event.key === 'Enter') void search()
                                                }} />
                                            <Button onClick={() => void search()} loading={searching}
                                                disabled={!query.trim()}
                                                leftSection={<IconSearch size={16} />}>
                                                {t('knowledge.search')}
                                            </Button>
                                        </Group>
                                        <Group gap="xs">
                                            <Text size="xs" c="dimmed">{t('knowledge.searchIn')}</Text>
                                            <Button size="compact-xs"
                                                variant={!searchVersion ? 'light' : 'subtle'}
                                                onClick={() => setSearchVersion(undefined)}>
                                                {t('knowledge.activeVersion')}
                                            </Button>
                                            {versions.filter(version => version.status !== 'ACTIVE')
                                                .slice(0, 4).map(version => (
                                                    <Button key={version.id} size="compact-xs"
                                                        variant={searchVersion === version.id ? 'light' : 'subtle'}
                                                        onClick={() => setSearchVersion(version.id)}>
                                                        {version.id.slice(-8)}
                                                    </Button>
                                                ))}
                                        </Group>
                                        {hits.map(hit => (
                                            <Card key={hit.chunkId} withBorder>
                                                <Group justify="space-between" mb="xs">
                                                    <Text size="xs" c="dimmed">
                                                        {files[documents.find(d => d.id === hit.documentId)?.fileId ?? '']
                                                            ?.originalName ?? hit.documentId}
                                                    </Text>
                                                    <Badge variant="light">
                                                        {(hit.score * 100).toFixed(1)}%
                                                    </Badge>
                                                </Group>
                                                <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                                                    {hit.content}
                                                </Text>
                                            </Card>
                                        ))}
                                        {!searching && query && hits.length === 0 &&
                                            <Text c="dimmed" ta="center">{t('knowledge.noResults')}</Text>}
                                    </Stack>
                                </Tabs.Panel>

                                <Tabs.Panel value="versions" pt="md">
                                    <Stack gap="sm">
                                        {versions.length === 0
                                            ? <Text c="dimmed">{t('knowledge.noVersions')}</Text>
                                            : versions.map(version => (
                                                <Card key={version.id} withBorder>
                                                    <Group justify="space-between" align="flex-start">
                                                        <div>
                                                            <Group gap="xs">
                                                                <Text ff="monospace" size="sm">{version.id}</Text>
                                                                <StatusBadge status={version.status}
                                                                    label={t(`knowledge.statuses.${version.status}`)}
                                                                    color={statusColor(version.status)} />
                                                            </Group>
                                                            <Text size="xs" c="dimmed" mt={4}>
                                                                {t('knowledge.createdAt', {
                                                                    date: formatDate(version.createdAt) })}
                                                            </Text>
                                                            {version.basedOnVersionId && <Text size="xs" c="dimmed">
                                                                {t('knowledge.basedOn', {
                                                                    id: version.basedOnVersionId })}
                                                            </Text>}
                                                        </div>
                                                        {version.status === 'INACTIVE' &&
                                                            <Button size="xs" variant="light"
                                                                onClick={() => rollback(version)}>
                                                                {t('knowledge.rollback')}
                                                            </Button>}
                                                    </Group>
                                                </Card>
                                            ))}
                                    </Stack>
                                </Tabs.Panel>
                            </Tabs>
                        </>
                    )}
                </Stack>
            </SimpleGrid>

            <Modal opened={createOpen} onClose={() => setCreateOpen(false)}
                title={t('knowledge.createBase')}>
                <Stack>
                    <TextInput label={t('knowledge.name')} value={newName}
                        onChange={event => setNewName(event.currentTarget.value)}
                        onKeyDown={event => {
                            if (event.key === 'Enter') void createBase()
                        }} data-autofocus />
                    <Divider />
                    <Group justify="flex-end">
                        <Button variant="default" onClick={() => setCreateOpen(false)}>
                            {t('common.cancel')}
                        </Button>
                        <Button loading={creating} disabled={!newName.trim()}
                            onClick={() => void createBase()}>{t('common.create')}</Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
    )
}
