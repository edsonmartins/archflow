import {
    Button, Group, Text, TextInput, Stack, LoadingOverlay, Modal,
    Alert, SimpleGrid, Card, Badge, ActionIcon, Tooltip, Paper,
} from '@mantine/core';
import {
    IconPlus, IconSearch, IconPlayerPlay, IconPencil, IconTrash,
    IconAlertCircle, IconRobot, IconFileText, IconPlayerPause,
    IconAlertTriangle, IconTopologyRing, IconTemplate, IconSparkles, IconBolt,
} from '@tabler/icons-react';
import { useEffect, useState, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useWorkflowStore } from '../stores/workflow-store';
import { workflowApi } from '../services/api';
import { OptionCard } from '../components/OptionCard';
import { PageHeader, type Accent } from '../components/PageHeader';
import { StatCard } from '../components/admin/StatCard';
import EmptyState from '../components/EmptyState';
import { RunInputModal } from '../components/RunInputModal';
import { toExecuteBody } from '../lib/runInput';
import { productionRunDocument } from '../lib/runTarget';
import { usePermissions } from '../lib/permissions';
import { notifications } from '@mantine/notifications';
import { hasRunBlockers, runBlockers, runBlockersMessage } from '../components/FlowCanvas/runReadiness';
import { workflowDataFromDocument } from '../components/FlowCanvas/workflowSerde';

/*
 * Dois conceitos que o card misturava (RCHFLW-132), lidos do código e não inventados aqui:
 *  - o que o Rodar executa: a versão implantada em PRODUCTION ("Publicar" no editor) ou, sem ela, o rascunho
 *    salvo — a regra do SpringWorkflowCrudController.execute, que a lista devolve em `publishedVersion`;
 *  - o rótulo do ciclo de vida (`status`: draft/active/archived) que o "Ativar" troca. Nada na execução o lê.
 * O selo principal é o primeiro, porque é o que muda o resultado do Rodar; o rótulo aparece à parte, e só
 * quando não é o padrão.
 */
const STATUS_COLOR: Record<string, string> = {
    active: 'teal',
    paused: 'yellow',
    failed: 'red',
};

export default function WorkflowListPage() {
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const { workflows, loading, error, fetchWorkflows, deleteWorkflow, executeWorkflow } = useWorkflowStore();
    const [search, setSearch] = useState('');
    const [deleteId, setDeleteId] = useState<string | null>(null);
    // RCHFLW-74: só oferece o que o papel permite — o backend responde 403 ao resto.
    const can = usePermissions();
    const canCreate = can('workflow:create');
    const canUpdate = can('workflow:update');
    const canDelete = can('workflow:delete');
    const canExecute = can('workflow:execute');

    useEffect(() => { fetchWorkflows() }, [fetchWorkflows]);

    const filtered = useMemo(() => {
        const q = search.toLowerCase().trim();
        if (!q) return workflows;
        return workflows.filter(w => w.name.toLowerCase().includes(q) || w.description.toLowerCase().includes(q));
    }, [search, workflows]);

    const counts = useMemo(() => ({
        published: workflows.filter(w => w.publishedVersion != null).length,
        unpublished: workflows.filter(w => w.publishedVersion == null).length,
        attention: workflows.filter(w => w.status === 'failed' || w.status === 'paused').length,
    }), [workflows]);
    const totalSteps = useMemo(() => workflows.reduce((n, w) => n + (w.stepCount ?? 0), 0), [workflows]);

    const handleDelete = async () => { if (deleteId) { await deleteWorkflow(deleteId); setDeleteId(null); } };

    const handleToggleStatus = async (id: string, currentStatus: string) => {
        await workflowApi.setStatus(id, currentStatus === 'active' ? 'draft' : 'active');
        await fetchWorkflows();
    };
    // Rodar pede a entrada antes (RunInputModal); sem ela o fluxo rodava sempre sem input.
    const [runId, setRunId] = useState<string | null>(null);
    /**
     * Antes do modal, a mesma checagem do Executar do editor (RCHFLW-133): nó sem execução, campo obrigatório
     * vazio, condição que o motor não avalia. Pelo editor o run era impedido com o aviso nomeando o nó; pela lista
     * a execução era criada e terminava FAILED. Confere o documento que o Rodar executa — a versão em produção,
     * ou o rascunho —, não o canvas. Sem conseguir ler esse documento, segue para o modal: o servidor recusa o
     * mesmo no início da execução, e a tela não afirma o que não sabe.
     */
    const handleRunClick = async (id: string) => {
        const run = await productionRunDocument(id);
        if (run) {
            const blockers = runBlockers(workflowDataFromDocument(run.document).steps.map(s => ({
                id: s.id, label: s.label, componentId: s.componentId, type: s.type, config: s.config,
            })));
            if (hasRunBlockers(blockers)) {
                notifications.show({
                    title: t('editor.notif.runBlocked'),
                    message: runBlockersMessage(t, blockers),
                    color: 'red',
                    autoClose: false,
                });
                return;
            }
        }
        setRunId(id);
    };
    const handleExecute = async (id: string, inputText: string) => {
        setRunId(null);
        try { const eid = await executeWorkflow(id, toExecuteBody(inputText)); navigate(`/executions/${encodeURIComponent(eid)}`); } catch { /* surfaced via store error state */ }
    };

    // Creation offers three paths (modal): blank canvas, template gallery,
    // or AI generation (blank workflow + the editor's AI prompt pre-opened).
    // Começar do zero e Gerar com IA abrem um RASCUNHO no editor, sem gravar nada (RCHFLW-131): o fluxo nasce no
    // primeiro Salvar ou quando a IA monta o primeiro nó. Antes um fluxo vazio era gravado aqui, e quem fechava
    // sem fazer nada deixava um "Fluxo sem título" com 0 passos.
    const [createOpen, setCreateOpen] = useState(false);
    const createBlank = (withAi: boolean) => {
        setCreateOpen(false);
        navigate(withAi ? '/editor?new=1&ai=1' : '/editor?new=1');
    };
    const handleNew = () => setCreateOpen(true);

    // /workflows?new=1 opens the create modal; ?new=ai goes straight to the
    // AI path (both used by the dashboard's quick actions). Strip the param
    // so refresh/back don't re-trigger.
    const [searchParams, setSearchParams] = useSearchParams();
    useEffect(() => {
        const mode = searchParams.get('new');
        if (!mode) return;
        const next = new URLSearchParams(searchParams);
        next.delete('new');
        setSearchParams(next, { replace: true });
        if (!canCreate) return;
        if (mode === 'ai') createBlank(true);
        else setCreateOpen(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [searchParams]);

    return (
        <Stack gap="lg" p="md" pos="relative">
            <LoadingOverlay visible={loading && workflows.length === 0} zIndex={5} />

            <PageHeader
                icon={IconTopologyRing}
                accent="blue"
                title={t('workflows.title')}
                subtitle={t('workflows.subtitle')}
                actions={
                    <>
                        <Button variant="default" leftSection={<IconTemplate size={16} />}
                                onClick={() => navigate('/templates')}>
                            {t('workflows.browseTemplates')}
                        </Button>
                        {canCreate && (
                            <Button leftSection={<IconPlus size={16} />} onClick={handleNew} data-testid="workflow-new">
                                {t('workflows.newWorkflow')}
                            </Button>
                        )}
                    </>
                }
            />

            {error && <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{error}</Alert>}

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('workflows.stats.total')} value={workflows.length} icon={IconTopologyRing}
                          accent="blue" hint={t('workflows.hints.total', { count: totalSteps })} />
                <StatCard label={t('workflows.stats.published')} value={counts.published} icon={IconBolt}
                          accent="teal" hint={t('workflows.hints.published')} />
                <StatCard label={t('workflows.stats.unpublished')} value={counts.unpublished} icon={IconFileText}
                          accent="slate" hint={t('workflows.hints.unpublished')} />
                <StatCard label={t('workflows.stats.attention')} value={counts.attention} icon={IconAlertTriangle}
                          accent="rose" color={counts.attention > 0 ? 'red' : undefined}
                          hint={t('workflows.hints.attention')} />
            </SimpleGrid>

            <Group justify="space-between" wrap="wrap" gap="sm">
                <TextInput
                    aria-label={t('workflows.search')}
                    placeholder={t('workflows.search')}
                    leftSection={<IconSearch size={14} />}
                    value={search}
                    onChange={e => setSearch(e.currentTarget.value)}
                    w={420}
                    maw="100%"
                />
                {workflows.length > 0 && (
                    <Text size="xs" c="dimmed">
                        {t('workflows.showing', { shown: filtered.length, total: workflows.length })}
                    </Text>
                )}
            </Group>
            {workflows.length > 0 && (
                <Text size="xs" c="dimmed" mt={-8} data-testid="workflows-publish-vs-activate">
                    {t('workflows.publishVsActivate')}
                </Text>
            )}

            {filtered.length === 0 && !loading ? (
                <Paper withBorder>
                    <EmptyState
                        icon={workflows.length === 0 ? <IconTopologyRing /> : <IconSearch />}
                        accent="blue"
                        title={workflows.length === 0 ? t('workflows.empty') : t('workflows.noMatches')}
                        description={workflows.length === 0 ? t('workflows.emptyHint') : t('workflows.noMatchesHint')}
                    >
                        {workflows.length === 0 ? (
                            <Group mt="xs" gap="sm" justify="center">
                                {canCreate && (
                                    <Button leftSection={<IconPlus size={16} />} onClick={handleNew}>
                                        {t('workflows.createWorkflow')}
                                    </Button>
                                )}
                                <Button
                                    variant="light"
                                    leftSection={<IconTemplate size={16} />}
                                    onClick={() => navigate('/templates')}
                                >
                                    {t('workflows.browseTemplates')}
                                </Button>
                            </Group>
                        ) : (
                            <Button variant="light" size="xs" mt="xs" onClick={() => setSearch('')}>
                                {t('workflows.clearSearch')}
                            </Button>
                        )}
                    </EmptyState>
                </Paper>
            ) : (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                    {filtered.map(w => {
                        const published = w.publishedVersion != null;
                        const accent: Accent = published ? 'teal' : 'slate';
                        const RunsIcon = published ? IconRobot : IconFileText;
                        const open = () => navigate(`/editor/${w.id}`);
                        const toggleLabel = w.status === 'active'
                            ? t('workflows.actions.deactivate')
                            : t('workflows.actions.activate');
                        return (
                            <Card
                                key={w.id}
                                withBorder
                                padding="lg"
                                data-testid={`workflow-card-${w.id}`}
                                aria-label={w.name}
                                role="button"
                                tabIndex={0}
                                onClick={open}
                                onKeyDown={(e) => {
                                    if (e.target !== e.currentTarget) return;
                                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
                                }}
                                style={{ cursor: 'pointer', display: 'flex', flexDirection: 'column' }}
                            >
                                <Group align="flex-start" gap="sm" wrap="nowrap" mb="md">
                                    <span className="af-stat-icon" data-accent={accent} aria-hidden
                                          style={{ width: 40, height: 40, borderRadius: 11 }}>
                                        <IconTopologyRing size={20} stroke={1.8} />
                                    </span>
                                    <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
                                        <Text fw={600} size="sm" lineClamp={1}>{w.name}</Text>
                                        <Text size="xs" c="dimmed" lineClamp={2} mih={34}>
                                            {w.description || t('workflows.noDescription')}
                                        </Text>
                                    </Stack>
                                    <Stack gap={4} align="flex-end" style={{ flexShrink: 0 }}>
                                        <Tooltip multiline w={260} label={published
                                            ? t('runInput.target.published', { number: w.publishedVersion })
                                            : t('runInput.target.unpublished')}>
                                            <Badge size="sm" variant="light" color={published ? 'teal' : 'gray'}
                                                   leftSection={<RunsIcon size={11} />}
                                                   data-testid={`workflow-runs-${w.id}`}>
                                                {published
                                                    ? t('workflows.runs.published', { number: w.publishedVersion })
                                                    : t('workflows.runs.draft')}
                                            </Badge>
                                        </Tooltip>
                                        {w.status !== 'draft' && (
                                            <Tooltip multiline w={260} label={t('workflows.lifecycleHint')}>
                                                <Badge size="xs" variant="outline"
                                                       color={STATUS_COLOR[w.status] ?? 'gray'}
                                                       data-testid={`workflow-lifecycle-${w.id}`}>
                                                    {t(`workflows.status.${w.status}`, { defaultValue: w.status.charAt(0).toUpperCase() + w.status.slice(1) })}
                                                </Badge>
                                            </Tooltip>
                                        )}
                                    </Stack>
                                </Group>

                                <Group gap="lg" wrap="wrap" mb="md" mt="auto">
                                    <Stack gap={0}>
                                        <Text size="10px" c="dimmed" tt="uppercase" fw={600} style={{ letterSpacing: '0.06em' }}>
                                            {t('workflows.card.version')}
                                        </Text>
                                        <Text size="xs" fw={500}>{w.version}</Text>
                                    </Stack>
                                    <Stack gap={0}>
                                        <Text size="10px" c="dimmed" tt="uppercase" fw={600} style={{ letterSpacing: '0.06em' }}>
                                            {t('workflows.card.steps')}
                                        </Text>
                                        <Text size="xs" fw={500}>{t('workflows.stepCount', { count: w.stepCount })}</Text>
                                    </Stack>
                                    <Stack gap={0}>
                                        <Text size="10px" c="dimmed" tt="uppercase" fw={600} style={{ letterSpacing: '0.06em' }}>
                                            {t('workflows.card.updated')}
                                        </Text>
                                        <Text size="xs" fw={500}>
                                            {new Date(w.updatedAt).toLocaleDateString(i18n.resolvedLanguage ?? i18n.language)}
                                        </Text>
                                    </Stack>
                                </Group>

                                <Group
                                    gap={4}
                                    pt="sm"
                                    justify="space-between"
                                    style={{ borderTop: '1px solid var(--surface-border)' }}
                                    onClick={e => e.stopPropagation()}
                                >
                                    <Group gap={4}>
                                        {/* Sem workflow:update o card ainda abre o editor, só para leitura. */}
                                        {canUpdate && (
                                            <Tooltip label={t('workflows.actions.edit')}>
                                                <ActionIcon variant="subtle" color="gray" aria-label={t('workflows.actions.edit')}
                                                            onClick={open}>
                                                    <IconPencil size={16} />
                                                </ActionIcon>
                                            </Tooltip>
                                        )}
                                        {canUpdate && (
                                            <Tooltip multiline w={260} label={`${toggleLabel} — ${t('workflows.lifecycleHint')}`}>
                                                <ActionIcon
                                                    variant="subtle"
                                                    color={w.status === 'active' ? 'yellow' : 'teal'}
                                                    aria-label={toggleLabel}
                                                    onClick={() => void handleToggleStatus(w.id, w.status)}
                                                >
                                                    {w.status === 'active' ? <IconPlayerPause size={16} /> : <IconRobot size={16} />}
                                                </ActionIcon>
                                            </Tooltip>
                                        )}
                                        {canDelete && (
                                            <Tooltip label={t('workflows.actions.delete')}>
                                                <ActionIcon variant="subtle" color="red" aria-label={t('workflows.actions.delete')}
                                                            onClick={() => setDeleteId(w.id)}>
                                                    <IconTrash size={16} />
                                                </ActionIcon>
                                            </Tooltip>
                                        )}
                                    </Group>
                                    {canExecute && <Button
                                        size="compact-sm"
                                        variant="light"
                                        color="violet"
                                        leftSection={<IconPlayerPlay size={14} />}
                                        aria-label={t('workflows.actions.execute')}
                                        onClick={() => void handleRunClick(w.id)}
                                    >
                                        {t('workflows.actions.run')}
                                    </Button>}
                                </Group>
                            </Card>
                        );
                    })}
                </SimpleGrid>
            )}

            {runId && (
                <RunInputModal
                    workflowId={runId}
                    opened
                    onClose={() => setRunId(null)}
                    onConfirm={(text) => { void handleExecute(runId, text); }}
                    runs="production"
                />
            )}

            <Modal opened={!!deleteId} onClose={() => setDeleteId(null)} title={t('workflows.deleteModal.title')} centered>
                <Text size="sm">{t('workflows.deleteModal.message')}</Text>
                <Group justify="flex-end" mt="md">
                    <Button variant="light" onClick={() => setDeleteId(null)}>{t('common.cancel')}</Button>
                    <Button color="red" onClick={handleDelete}>{t('common.delete')}</Button>
                </Group>
            </Modal>

            <Modal
                opened={createOpen}
                onClose={() => setCreateOpen(false)}
                title={t('workflows.createModal.title')}
                centered
            >
                <Stack gap="sm">
                    <OptionCard
                        icon={<IconPlus size={20} />}
                        color="blue"
                        title={t('workflows.createModal.blank')}
                        description={t('workflows.createModal.blankHint')}
                        onClick={() => createBlank(false)}
                        testId="create-blank"
                    />
                    <OptionCard
                        icon={<IconSparkles size={20} />}
                        color="grape"
                        title={t('workflows.createModal.ai')}
                        description={t('workflows.createModal.aiHint')}
                        onClick={() => createBlank(true)}
                        testId="create-ai"
                    />
                    <OptionCard
                        icon={<IconTemplate size={20} />}
                        color="teal"
                        title={t('workflows.createModal.template')}
                        description={t('workflows.createModal.templateHint')}
                        onClick={() => { setCreateOpen(false); navigate('/templates'); }}
                        testId="create-template"
                    />
                </Stack>
            </Modal>
        </Stack>
    );
}
