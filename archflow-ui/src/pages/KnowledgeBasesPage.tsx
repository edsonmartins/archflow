import { useCallback, useEffect, useRef, useState } from 'react'
import {
    ActionIcon, Alert, Badge, Button, Divider, FileButton, Grid, Group, Modal,
    Paper, SimpleGrid, Skeleton, Stack, Tabs, Text,
    TextInput, Tooltip, UnstyledButton,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertCircle, IconBook2, IconBooks, IconCloudUpload, IconDatabase, IconDatabaseSearch,
    IconFileText, IconHistory, IconPlus, IconPuzzle, IconRefresh, IconSearch,
} from '@tabler/icons-react'
import { useTranslation } from 'react-i18next'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import {
    VxTable, colunaAcoes, colunaApagada, colunaDupla, colunaStatus, type Registro, type VxColumn,
} from '../components/VTable'
import { confirmAction } from '../lib/confirm'
import { usePermissions } from '../lib/permissions'
import { PermissionTooltip } from '../components/PermissionTooltip'
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
    // Indexado, mas fora da versão ativa do índice (depois de restaurar uma anterior): a busca não o
    // alcança, e o reenvio o reindexa (RCHFLW-157).
    NOT_IN_ACTIVE_INDEX: 'orange',
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
    // Criar base, enviar arquivo (upload + ingestão) e trocar a versão ativa do índice são escritas que o
    // servidor exige workflow:update (QA2 RCHFLW-158). A busca é um POST que só lê: segue para todos.
    const canUpdate = usePermissions()('workflow:update')
    const missing = canUpdate ? null : 'workflow:update' as const
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
        // A mesma regra do servidor (FileStorageService): arquivo de 0 byte não é enviado. Antes o toast
        // mostrava o JSON cru da recusa, em inglês (RCHFLW-194).
        if (file.size === 0) {
            notifications.show({ color: 'red', title: t('knowledge.uploadFailed'),
                message: t('knowledge.emptyFile') })
            return
        }
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
            // Um job reaberto mantém o id: sem isto o fim do reprocessamento não recarregaria a
            // lista nem avisaria, porque o id já constava como concluído.
            completedJobs.current.delete(submission.job.id)
            // Fora do QUEUED o servidor devolveu um documento que já existia e pode ter apagado
            // duplicados antigos que nunca foram processados; a lista é recarregada para refletir.
            if (submission.outcome !== 'QUEUED') void loadDetail(selectedId, true)
            notifications.show({
                color: submission.outcome === 'ALREADY_PRESENT' ? 'yellow' : 'blue',
                title: t(submission.outcome === 'ALREADY_PRESENT' ? 'knowledge.ingestionAlreadyPresent'
                    : submission.outcome === 'REPROCESSING' ? 'knowledge.ingestionReprocessing'
                        : 'knowledge.ingestionQueued'),
                message: file.name,
            })
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

    const documentRows = documents.map(document => {
        const file = files[document.fileId]
        const job = jobForDocument(document)
        const jobActive = !!job && (job.status === 'QUEUED' || job.status === 'RUNNING')
        const effectiveStatus = job?.status === 'RUNNING' ? 'PROCESSING' : document.status
        return {
            id: document.id,
            name: file?.originalName ?? document.fileId,
            detail: document.error ?? formatBytes(file?.size),
            hasError: !!document.error,
            status: effectiveStatus,
            progress: jobActive ? job.progress : null,
            chunks: document.chunkCount,
            updatedAt: document.updatedAt,
        }
    })
    const totalChunks = documents.reduce((n, document) => n + (document.chunkCount ?? 0), 0)
    const readyDocuments = documents.filter(document => document.status === 'READY').length
    const activeVersion = versions.find(version => version.status === 'ACTIVE')

    const documentColumns: VxColumn[] = [
        colunaDupla({ field: 'name', title: t('knowledge.file'), width: 'auto', minWidth: 260 },
            r => String(r.name), r => String(r.detail ?? '')),
        colunaStatus({ field: 'status', title: t('knowledge.status'), width: 190,
            fieldFormat: (r: Registro) => {
                const label = t(`knowledge.statuses.${r.status}`, { defaultValue: String(r.status) })
                return r.progress == null ? label : `${label} · ${Math.round(Number(r.progress))}%`
            } },
            r => statusColor(String(r.status))),
        { field: 'chunks', title: t('knowledge.chunks'), width: 110,
            fieldFormat: (r: Registro) => String(r.chunks ?? 0) } as VxColumn,
        colunaApagada({ field: 'updatedAt', title: t('knowledge.updated'), width: 210,
            fieldFormat: (r: Registro) => formatDate(r.updatedAt as string | null) }),
    ]

    const versionColumns: VxColumn[] = [
        colunaApagada({ field: 'id', title: t('knowledge.versionId'), width: 'auto', minWidth: 240 }),
        colunaStatus({ field: 'status', title: t('knowledge.status'), width: 150,
            fieldFormat: (r: Registro) => t(`knowledge.statuses.${r.status}`, { defaultValue: String(r.status) }) },
            r => statusColor(String(r.status))),
        colunaApagada({ field: 'createdAt', title: t('knowledge.created'), width: 200,
            fieldFormat: (r: Registro) => formatDate(r.createdAt as string | null) }),
        colunaApagada({ field: 'basedOnVersionId', title: t('knowledge.basedOnCol'), width: 'auto', minWidth: 180,
            fieldFormat: (r: Registro) => (r.basedOnVersionId ? String(r.basedOnVersionId) : '—') }),
        colunaAcoes([{ nome: 'rollback', icone: 'refresh', rotulo: t('knowledge.rollback'), cor: 'blue',
            mostrar: r => canUpdate && r.status === 'INACTIVE' }]),
    ]

    return (
        <Stack gap="lg" p="md" data-testid="knowledge-bases-page">
            <PageHeader
                icon={IconBook2}
                accent="teal"
                title={t('knowledge.title')}
                subtitle={t('knowledge.subtitle')}
                actions={<PermissionTooltip missing={missing}>
                    <Button leftSection={<IconPlus size={16} />} disabled={!canUpdate}
                        data-testid="knowledge-new-base"
                        onClick={() => setCreateOpen(true)}>{t('knowledge.newBase')}</Button>
                </PermissionTooltip>}
            />

            {error && <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{error}</Alert>}

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('knowledge.stats.bases')} value={loading ? '—' : bases.length}
                          icon={IconBooks} accent="teal" hint={t('knowledge.hints.bases')} />
                <StatCard label={t('knowledge.documents')} value={selected ? documents.length : '—'}
                          icon={IconFileText} accent="blue"
                          hint={selected ? t('knowledge.hints.documents', { count: readyDocuments }) : t('knowledge.hints.noBase')} />
                <StatCard label={t('knowledge.chunks')} value={selected ? totalChunks : '—'}
                          icon={IconPuzzle} accent="violet" hint={t('knowledge.hints.chunks')} />
                <StatCard label={t('knowledge.stats.activeVersion')}
                          value={<Text component="span" inherit fz={20}>{activeVersion ? activeVersion.id.slice(-8) : '—'}</Text>}
                          icon={IconHistory} accent="amber"
                          hint={t('knowledge.hints.versions', { count: versions.length })} />
            </SimpleGrid>

            <Grid gap="md" align="flex-start">
                <Grid.Col span={{ base: 12, md: 4, lg: 3 }}>
                    <SectionCard
                        flush
                        icon={IconBooks}
                        accent="teal"
                        title={t('knowledge.bases')}
                        description={t('knowledge.basesHint')}
                        actions={
                            <Tooltip label={t('common.refresh')}>
                                <ActionIcon variant="subtle" color="gray" onClick={() => void loadBases()}
                                            aria-label={t('common.refresh')}>
                                    <IconRefresh size={15} />
                                </ActionIcon>
                            </Tooltip>
                        }
                    >
                        {loading ? (
                            <Stack gap="xs" p="md">
                                {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} height={44} radius="md" />)}
                            </Stack>
                        ) : bases.length === 0 ? (
                            <EmptyState compact icon={<IconBook2 />} accent="teal"
                                        title={t('knowledge.emptyTitle')} description={t('knowledge.empty')}
                                        actionLabel={t('knowledge.newBase')} onAction={() => setCreateOpen(true)} />
                        ) : (
                            <Stack gap={4} p="xs">
                                {bases.map(base => {
                                    const active = selectedId === base.id
                                    return (
                                        <UnstyledButton key={base.id} onClick={() => setSelectedId(base.id)}
                                            aria-current={active || undefined}
                                            px="sm" py={8}
                                            style={{
                                                borderRadius: 10,
                                                background: active ? 'var(--mantine-color-teal-light)' : undefined,
                                                border: `1px solid ${active ? 'var(--mantine-color-teal-light-hover)' : 'transparent'}`,
                                            }}>
                                            <Group gap="sm" wrap="nowrap">
                                                <span className="af-stat-icon" data-accent={active ? 'teal' : 'slate'} aria-hidden
                                                      style={{ width: 30, height: 30, borderRadius: 8 }}>
                                                    <IconBook2 size={15} stroke={1.8} />
                                                </span>
                                                <Stack gap={0} style={{ minWidth: 0 }}>
                                                    <Text fw={600} size="sm" truncate>{base.name}</Text>
                                                    <Text size="xs" c="dimmed">{formatDate(base.createdAt)}</Text>
                                                </Stack>
                                            </Group>
                                        </UnstyledButton>
                                    )
                                })}
                            </Stack>
                        )}
                    </SectionCard>
                </Grid.Col>

                <Grid.Col span={{ base: 12, md: 8, lg: 9 }}>
                    {!selected ? (
                        <Paper withBorder>
                            <EmptyState icon={<IconDatabaseSearch />} accent="teal"
                                        title={t('knowledge.selectBaseTitle')}
                                        description={t('knowledge.selectBase')} />
                        </Paper>
                    ) : (
                        <SectionCard
                            flush
                            icon={IconDatabase}
                            accent="teal"
                            title={selected.name}
                            description={selected.id}
                            actions={
                                <PermissionTooltip missing={missing}>
                                    <FileButton onChange={upload} disabled={!canUpdate}
                                        accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document">
                                        {props => <Button {...props} size="sm" loading={uploading}
                                            disabled={!canUpdate} data-testid="knowledge-upload"
                                            leftSection={<IconCloudUpload size={16} />}>
                                            {t('knowledge.upload')}
                                        </Button>}
                                    </FileButton>
                                </PermissionTooltip>
                            }
                        >
                            {detailLoading ? (
                                <Stack gap="xs" p="lg">
                                    {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height={36} radius="sm" />)}
                                </Stack>
                            ) : (
                                <Tabs defaultValue="documents" keepMounted={false}>
                                    <Tabs.List px="md">
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

                                    <Tabs.Panel value="documents">
                                        <VxTable
                                            legenda={t('knowledge.documents')}
                                            records={documentRows}
                                            columns={documentColumns}
                                            rowHeight={58}
                                            emptyMessage={t('knowledge.noDocumentsTitle')}
                                            emptyDescription={t('knowledge.noDocuments')}
                                            emptyIcon={<IconCloudUpload />}
                                            emptyAccent="teal"
                                            testid="knowledge-documents"
                                            rowTestId={r => `knowledge-doc-${r.id}`}
                                        />
                                    </Tabs.Panel>

                                    <Tabs.Panel value="search" p="lg">
                                        <Stack>
                                            <Group align="flex-end">
                                                <TextInput style={{ flex: 1 }}
                                                    size="sm"
                                                    label={t('knowledge.query')}
                                                    placeholder={t('knowledge.queryPlaceholder')}
                                                    leftSection={<IconSearch size={14} />}
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
                                                <Paper key={hit.chunkId} withBorder p="md">
                                                    <Group justify="space-between" mb="xs" wrap="nowrap">
                                                        <Group gap={6} wrap="nowrap" style={{ minWidth: 0 }}>
                                                            <IconFileText size={14} style={{ flexShrink: 0, color: 'var(--mantine-color-dimmed)' }} />
                                                            <Text size="xs" c="dimmed" truncate>
                                                                {files[documents.find(d => d.id === hit.documentId)?.fileId ?? '']
                                                                    ?.originalName ?? hit.documentId}
                                                            </Text>
                                                        </Group>
                                                        <Badge variant="light" color="teal">
                                                            {(hit.score * 100).toFixed(1)}%
                                                        </Badge>
                                                    </Group>
                                                    <Text size="sm" style={{ whiteSpace: 'pre-wrap' }} lh={1.55}>
                                                        {hit.content}
                                                    </Text>
                                                </Paper>
                                            ))}
                                            {!searching && query && hits.length === 0 &&
                                                <EmptyState compact icon={<IconDatabaseSearch />} accent="teal"
                                                            title={t('knowledge.noResults')}
                                                            description={t('knowledge.noResultsHint')} />}
                                        </Stack>
                                    </Tabs.Panel>

                                    <Tabs.Panel value="versions">
                                        <VxTable
                                            legenda={t('knowledge.versions')}
                                            records={versions as unknown as Registro[]}
                                            columns={versionColumns}
                                            onIconClick={(nome, r) => {
                                                if (nome === 'rollback') void rollback(r as unknown as IndexVersion)
                                            }}
                                            emptyMessage={t('knowledge.noVersionsTitle')}
                                            emptyDescription={t('knowledge.noVersions')}
                                            emptyIcon={<IconHistory />}
                                            emptyAccent="amber"
                                            testid="knowledge-versions"
                                            rowTestId={r => `knowledge-version-${r.id}`}
                                        />
                                    </Tabs.Panel>
                                </Tabs>
                            )}
                        </SectionCard>
                    )}
                </Grid.Col>
            </Grid>

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
