import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Alert,
    Badge,
    Button,
    Group,
    Paper,
    SimpleGrid,
    Skeleton,
    Stack,
    Text,
    Textarea,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
    IconAlertCircle,
    IconArrowLeft,
    IconBraces,
    IconCheck,
    IconEdit,
    IconGavel,
    IconInfoCircle,
    IconShieldCheck,
    IconX,
} from '@tabler/icons-react';
import { approvalApi, type ApprovalResponse } from '../services/approval-api';
import { approvalTenantParam } from '../lib/approvalTenant';
import { useTenantStore } from '../stores/useTenantStore';
import { JsonViewer } from '../components/JsonViewer';
import { Meta } from '../components/Meta';
import { formatInstant } from '../lib/format';
import { confirmAction } from '../lib/confirm';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';

/** O texto do campo de edição: a própria proposta quando ela é texto; senão, o JSON dela. */
function editableProposal(proposal: unknown): string {
    return typeof proposal === 'string' ? proposal : JSON.stringify(proposal, null, 2);
}

export default function ApprovalDetailPage() {
    const { id } = useParams<{ id: string }>();
    // A execução do pedido, quando o link a traz (fila, detalhe da execução): é o que permite ao
    // servidor achar um pedido já decidido e devolver quem decidiu.
    const [searchParams] = useSearchParams();
    const flowId = searchParams.get('flowId') || undefined;
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    // O mesmo tenant da fila: o superadmin que abriu o pedido na visão "all" precisa pedi-lo assim,
    // senão o servidor só procura no tenant da requisição e responde 404 (RCHFLW-39).
    const { impersonating, currentRole } = useTenantStore();
    const tenantId = approvalTenantParam(impersonating, currentRole);

    const [approval, setApproval] = useState<ApprovalResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    // Falha ao CARREGAR: sem pedido não há tela, só o aviso e o caminho de volta.
    const [error, setError] = useState<string | null>(null);
    // Falha ao DECIDIR (prazo vencido, corrida, rede): fica junto dos botões. Antes ia para `error` e
    // trocava a tela inteira pela mensagem — a edição e o comentário se perdiam (RCHFLW-126/128).
    const [submitError, setSubmitError] = useState<string | null>(null);
    const [comment, setComment] = useState('');
    const [editMode, setEditMode] = useState(false);
    const [editedPayload, setEditedPayload] = useState('');
    const [payloadError, setPayloadError] = useState<string | null>(null);
    // O relógio da tela: um pedido que vence com a página aberta passa a não aceitar decisão.
    const [now, setNow] = useState(() => Date.now());

    useEffect(() => {
        if (!id) return;
        let cancelled = false;
        setLoading(true);
        approvalApi
            .get(id, flowId, tenantId)
            .then((dto) => {
                if (cancelled) return;
                setApproval(dto);
                setEditedPayload(editableProposal(dto.proposal));
            })
            .catch((e) => {
                // 404 é o link velho (pedido inexistente, decidido sem flowId ou de outro tenant): a frase do
                // app, não a do backend em inglês com o id cru (RCHFLW-188). Outra falha mostra o motivo.
                if (cancelled) return;
                const notFound = (e as { status?: unknown } | null)?.status === 404;
                setError(notFound || !(e instanceof Error) ? t('approvals.notFound') : e.message);
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [id, flowId, tenantId]);

    useEffect(() => {
        const expiresAt = approval?.expiresAt ? new Date(approval.expiresAt).getTime() : NaN;
        if (approval?.status !== 'PENDING' || !Number.isFinite(expiresAt)) return;
        const remaining = expiresAt - Date.now();
        if (remaining <= 0) {
            setNow(Date.now());
            return;
        }
        const timer = setTimeout(() => setNow(Date.now()), Math.min(remaining + 50, 2 ** 31 - 1));
        return () => clearTimeout(timer);
    }, [approval]);

    const decide = async (decision: 'APPROVED' | 'REJECTED' | 'EDITED', parsedPayload?: unknown) => {
        if (!id || !approval) return;
        setSubmitting(true);
        setSubmitError(null);
        try {
            const result = await approvalApi.submit(id, {
                // O tenant da própria pendência, vindo do servidor — nunca um palpite da UI.
                tenantId: approval.tenantId,
                decision,
                editedPayload: parsedPayload,
                comment: comment.trim() || undefined,
            });
            // Em nome de quem o servidor registrou — é o que vale, não o que esta tela supõe.
            const decisionLabel = t(`approvals.status.${decision}`);
            notifications.show({
                title: t('approvals.detail.decisionSubmitted'),
                message: result.decidedBy
                    ? t('approvals.detail.decidedMsg', { decision: decisionLabel, who: result.decidedBy })
                    : `${decisionLabel} · ${result.requestId.slice(0, 12)}`,
                color:
                    decision === 'APPROVED'
                        ? 'teal'
                        : decision === 'REJECTED'
                            ? 'red'
                            : 'blue',
            });
            navigate('/approvals');
        } catch (e) {
            // Outra aba (ou outra pessoa) decidiu primeiro, ou o prazo venceu: o servidor responde 404/409 com
            // frases técnicas em inglês ("Approval not found: <id>", "Flow is not awaiting approval") e a tela
            // continuava PENDENTE com os botões ativos (RCHFLW-188, item 3). Relê o pedido: se ele não está mais
            // pendente, a tela troca para a visão decidida (quem, quando, o quê) com a frase do app.
            const status = (e as { status?: unknown } | null)?.status;
            if (status === 404 || status === 409) {
                const atual = await approvalApi.get(id, flowId, tenantId).catch(() => null);
                if (atual && atual.status !== 'PENDING') {
                    setApproval(atual);
                    setEditMode(false);
                    setSubmitError(t('approvals.detail.alreadyDecided', {
                        status: t(`approvals.status.${atual.status}`, { defaultValue: atual.status }),
                        who: atual.decidedBy ?? '—',
                    }));
                    return;
                }
            }
            setSubmitError(e instanceof Error ? e.message : t('approvals.detail.submitFailed'));
        } finally {
            setSubmitting(false);
        }
    };

    // Approve/reject submit an irreversible workflow decision, so confirm first.
    const confirmDecide = (decision: 'APPROVED' | 'REJECTED' | 'EDITED') => {
        const isReject = decision === 'REJECTED';
        let parsedPayload: unknown | undefined;
        if (decision === 'EDITED') {
            // Antes de confirmar: a confirmação abria com JSON inválido e, depois dela, a tela virava
            // só o erro, perdendo a edição e o comentário (RCHFLW-128). O erro fica junto do campo.
            try {
                // Proposta em texto (a resposta de um LLM Chat): edita-se o texto, e é texto que volta. O
                // JSON.stringify dela virava uma string entre aspas com \n escapados (RCHFLW-188, item 2).
                parsedPayload = typeof approval?.proposal === 'string' ? editedPayload : JSON.parse(editedPayload);
            } catch (e) {
                const detail = e instanceof Error ? e.message : '';
                setPayloadError(detail ? `${t('approvals.detail.invalidJson')}: ${detail}` : t('approvals.detail.invalidJson'));
                return;
            }
        }
        setPayloadError(null);
        confirmAction({
            title: t(isReject ? 'confirmations.rejectApprovalTitle' : 'confirmations.approveApprovalTitle'),
            message: t(isReject ? 'confirmations.rejectApprovalMessage' : 'confirmations.approveApprovalMessage'),
            confirmLabel: t(isReject ? 'confirmations.reject' : 'confirmations.approve'),
            danger: isReject,
            onConfirm: () => decide(decision, parsedPayload),
        });
    };

    if (loading) {
        return (
            <Stack gap="lg" p="md">
                <Skeleton height={46} width={360} radius="md" />
                <Skeleton height={120} radius="md" />
                <Skeleton height={260} radius="md" />
            </Stack>
        );
    }

    if (error || !approval) {
        return (
            <Stack gap="lg" p="md">
                <PageHeader
                    icon={IconShieldCheck}
                    accent="amber"
                    title={t('approvals.title')}
                    breadcrumbs={[{ label: t('approvals.title'), to: '/approvals' }, { label: id?.slice(0, 12) ?? '—' }]}
                />
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                    {error ?? t('approvals.notFound')}
                </Alert>
                <Group>
                    <Button variant="default" leftSection={<IconArrowLeft size={16} />} onClick={() => navigate('/approvals')}>
                        {t('approvals.backToQueue')}
                    </Button>
                </Group>
            </Stack>
        );
    }

    const locale = i18n.resolvedLanguage ?? i18n.language;
    // Vencida não é acionável, nem antes de o sweeper registrar a recusa: o servidor já a devolve
    // EXPIRED, e um prazo que passa com a página aberta também conta (RCHFLW-126).
    const expired = approval.status === 'EXPIRED'
        || (approval.expiresAt ? new Date(approval.expiresAt).getTime() <= now : false);
    // Decidido não é acionável: no lugar da proposta e dos botões, quem decidiu, quando e por quê.
    // EXPIRED sem decisor ainda não foi recusada — a tela mostra o pedido, com os botões desligados.
    const decided = approval.status !== 'PENDING' && !(approval.status === 'EXPIRED' && !approval.decidedBy);

    return (
        <Stack gap="lg" p="md">
            <PageHeader
                icon={IconShieldCheck}
                accent="amber"
                title={t('approvals.detail.heading', { id: approval.requestId.slice(0, 12) })}
                subtitle={t('approvals.detail.subtitle')}
                breadcrumbs={[
                    { label: t('approvals.title'), to: '/approvals' },
                    { label: approval.requestId.slice(0, 12) },
                ]}
                actions={
                    <>
                        <Badge size="lg" radius="sm" variant="dot" color={STATUS_COLOR[approval.status] ?? 'gray'}
                               data-testid="approval-status">
                            {t(`approvals.status.${approval.status}`, { defaultValue: approval.status })}
                        </Badge>
                        <Button variant="default" leftSection={<IconArrowLeft size={16} />} onClick={() => navigate('/approvals')}>
                            {t('approvals.backToQueue')}
                        </Button>
                    </>
                }
            />

            <SectionCard
                icon={IconInfoCircle}
                accent="amber"
                title={t('approvals.detail.request')}
                description={t('approvals.detail.requestHint')}
            >
                <Stack gap="md">
                    {approval.title && (
                        <Text size="lg" fw={600} data-testid="approval-title">{approval.title}</Text>
                    )}
                    {approval.description && (
                        <Paper
                            p="md"
                            radius="md"
                            style={{
                                background: 'var(--mantine-color-orange-light)',
                                borderLeft: '3px solid var(--mantine-color-orange-filled)',
                            }}
                        >
                            <Text size="sm" fw={500}>{approval.description}</Text>
                        </Paper>
                    )}
                    <SimpleGrid cols={{ base: 2, sm: 3, lg: 5 }} spacing="lg">
                        <Meta label={t('approvals.detail.tenant')} value={approval.tenantId ?? '—'} />
                        <Meta label={t('approvals.detail.flow')} value={approval.flowName || approval.flowId || '—'} />
                        {approval.flowName && (
                            <Meta label={t('approvals.detail.execution')} value={approval.flowId ?? '—'} />
                        )}
                        <Meta label={t('approvals.detail.step')} value={approval.stepId ?? '—'} />
                        <Meta label={t('approvals.detail.created')} value={formatInstant(approval.createdAt, locale)} />
                        <Stack gap={2}>
                            <Meta label={t('approvals.detail.expires')} value={formatInstant(approval.expiresAt, locale)} />
                            {expired && (
                                <Badge size="xs" color="red" variant="light" radius="sm" w="fit-content">
                                    {t('approvals.time.expired')}
                                </Badge>
                            )}
                        </Stack>
                    </SimpleGrid>
                </Stack>
            </SectionCard>

            {/* A decisão desta tela perdeu para outra (RCHFLW-188, item 3): o aviso fica acima da que vale. */}
            {decided && submitError && (
                <Alert color="orange" variant="light" icon={<IconAlertCircle size={16} />}
                       data-testid="approval-submit-error">
                    {submitError}
                </Alert>
            )}
            {decided && (
                <SectionCard
                    icon={IconGavel}
                    accent="amber"
                    title={t('approvals.detail.decidedTitle')}
                    description={t('approvals.detail.decidedHint')}
                    data-testid="approval-decided"
                >
                    <Stack gap="md">
                        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="lg">
                            <Meta label={t('approvals.detail.decidedBy')} value={approval.decidedBy ?? '—'} />
                            <Meta label={t('approvals.detail.decidedAt')}
                                  value={formatInstant(approval.decidedAt ?? null, locale)} />
                        </SimpleGrid>
                        {approval.comment && (
                            <Stack gap={2}>
                                <Text size="xs" c="dimmed">{t('approvals.detail.decidedComment')}</Text>
                                <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}
                                      data-testid="approval-decided-comment">{approval.comment}</Text>
                            </Stack>
                        )}
                        {/* O que foi aprovado de fato, quando a pessoa editou a proposta (RCHFLW-121). */}
                        {approval.status === 'EDITED' && approval.editedPayload !== undefined
                            && approval.editedPayload !== null && (
                            <Stack gap={2}>
                                <Text size="xs" c="dimmed">{t('approvals.detail.editedPayload')}</Text>
                                <JsonViewer value={approval.editedPayload} autoExpandDepth={2}
                                            testId="approval-edited-payload" />
                            </Stack>
                        )}
                    </Stack>
                </SectionCard>
            )}

            {!decided && expired && (
                <Alert color="gray" variant="light" icon={<IconAlertCircle size={16} />}
                       title={t('approvals.detail.expiredTitle')} data-testid="approval-expired">
                    {t('approvals.detail.expiredText')}
                </Alert>
            )}

            {!decided && (
                <Group align="flex-start" gap="lg" wrap="wrap">
                    <SectionCard
                        style={{ flex: '2 1 520px', minWidth: 0 }}
                        icon={IconBraces}
                        accent="blue"
                        title={t('approvals.detail.proposal')}
                        description={editMode ? t('approvals.detail.proposalEditHint') : t('approvals.detail.proposalHint')}
                        actions={
                            !editMode && !expired && (
                                <Button
                                    size="xs"
                                    variant="light"
                                    leftSection={<IconEdit size={14} />}
                                    onClick={() => setEditMode(true)}
                                    data-testid="approval-edit-toggle"
                                >
                                    {t('approvals.detail.editBeforeApprove')}
                                </Button>
                            )
                        }
                    >
                        {editMode ? (
                            <Textarea
                                value={editedPayload}
                                onChange={(e) => {
                                    setEditedPayload(e.currentTarget.value);
                                    setPayloadError(null);
                                }}
                                minRows={10}
                                autosize
                                error={payloadError}
                                styles={{ input: { fontFamily: 'var(--font-mono)', fontSize: 12 } }}
                                data-testid="approval-edit-payload"
                            />
                        ) : (
                            <JsonViewer value={approval.proposal} autoExpandDepth={2} testId="approval-proposal" />
                        )}
                    </SectionCard>

                    <SectionCard
                        style={{ flex: '1 1 320px', minWidth: 0 }}
                        icon={IconGavel}
                        accent="amber"
                        title={t('approvals.detail.yourDecision')}
                        description={t('approvals.detail.decisionHint')}
                    >
                        <Stack gap="md">
                            <Textarea
                                size="sm"
                                label={t('approvals.detail.comment')}
                                placeholder={t('approvals.detail.commentPlaceholder')}
                                minRows={3}
                                autosize
                                value={comment}
                                onChange={(e) => setComment(e.currentTarget.value)}
                            />
                            {submitError && (
                                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}
                                       data-testid="approval-submit-error">
                                    {submitError}
                                </Alert>
                            )}
                            <Group gap="sm" justify="flex-end">
                                {editMode && (
                                    <Button variant="subtle" color="gray" onClick={() => { setEditMode(false); setPayloadError(null); }}>
                                        {t('approvals.detail.cancelEdit')}
                                    </Button>
                                )}
                                <Button
                                    leftSection={<IconX size={14} />}
                                    color="red"
                                    variant="light"
                                    loading={submitting}
                                    disabled={submitting || expired}
                                    onClick={() => confirmDecide('REJECTED')}
                                    data-testid="approval-reject"
                                >
                                    {t('approvals.detail.reject')}
                                </Button>
                                <Button
                                    leftSection={<IconCheck size={14} />}
                                    color="teal"
                                    loading={submitting}
                                    disabled={submitting || expired}
                                    onClick={() => confirmDecide(editMode ? 'EDITED' : 'APPROVED')}
                                    data-testid="approval-approve"
                                >
                                    {editMode ? t('approvals.detail.saveAndApprove') : t('approvals.detail.approve')}
                                </Button>
                            </Group>
                        </Stack>
                    </SectionCard>
                </Group>
            )}
        </Stack>
    );
}

const STATUS_COLOR: Record<string, string> = {
    PENDING: 'orange',
    APPROVED: 'teal',
    EDITED: 'blue',
    REJECTED: 'red',
    EXPIRED: 'gray',
};
