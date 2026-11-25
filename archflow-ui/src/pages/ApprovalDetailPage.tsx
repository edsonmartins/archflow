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
import { JsonViewer } from '../components/JsonViewer';
import { Meta } from '../components/Meta';
import { formatInstant } from '../lib/format';
import { confirmAction } from '../lib/confirm';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';

export default function ApprovalDetailPage() {
    const { id } = useParams<{ id: string }>();
    // A execução do pedido, quando o link a traz (fila, detalhe da execução): é o que permite ao
    // servidor achar um pedido já decidido e devolver quem decidiu.
    const [searchParams] = useSearchParams();
    const flowId = searchParams.get('flowId') || undefined;
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();

    const [approval, setApproval] = useState<ApprovalResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [comment, setComment] = useState('');
    const [editMode, setEditMode] = useState(false);
    const [editedPayload, setEditedPayload] = useState('');

    useEffect(() => {
        if (!id) return;
        let cancelled = false;
        setLoading(true);
        approvalApi
            .get(id, flowId)
            .then((dto) => {
                if (cancelled) return;
                setApproval(dto);
                setEditedPayload(JSON.stringify(dto.proposal, null, 2));
            })
            .catch((e) => {
                if (!cancelled) setError(e instanceof Error ? e.message : t('approvals.notFound'));
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [id, flowId]);

    const decide = async (decision: 'APPROVED' | 'REJECTED' | 'EDITED') => {
        if (!id || !approval) return;
        setSubmitting(true);
        setError(null);
        try {
            let parsedPayload: unknown | undefined;
            if (decision === 'EDITED') {
                try {
                    parsedPayload = JSON.parse(editedPayload);
                } catch {
                    throw new Error(t('approvals.detail.invalidJson'));
                }
            }
            const result = await approvalApi.submit(id, {
                // O tenant da própria pendência, vindo do servidor — nunca um palpite da UI.
                tenantId: approval.tenantId,
                decision,
                editedPayload: parsedPayload,
                comment: comment.trim() || undefined,
            });
            // Em nome de quem o servidor registrou — é o que vale, não o que esta tela supõe.
            notifications.show({
                title: t('approvals.detail.decisionSubmitted'),
                message: result.decidedBy
                    ? t('approvals.detail.decidedMsg', { decision, who: result.decidedBy })
                    : `${decision} · ${result.requestId.slice(0, 12)}`,
                color:
                    decision === 'APPROVED'
                        ? 'teal'
                        : decision === 'REJECTED'
                            ? 'red'
                            : 'blue',
            });
            navigate('/approvals');
        } catch (e) {
            setError(e instanceof Error ? e.message : t('approvals.detail.submitFailed'));
        } finally {
            setSubmitting(false);
        }
    };

    // Approve/reject submit an irreversible workflow decision, so confirm first.
    const confirmDecide = (decision: 'APPROVED' | 'REJECTED' | 'EDITED') => {
        const isReject = decision === 'REJECTED';
        confirmAction({
            title: t(isReject ? 'confirmations.rejectApprovalTitle' : 'confirmations.approveApprovalTitle'),
            message: t(isReject ? 'confirmations.rejectApprovalMessage' : 'confirmations.approveApprovalMessage'),
            confirmLabel: t(isReject ? 'confirmations.reject' : 'confirmations.approve'),
            danger: isReject,
            onConfirm: () => decide(decision),
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
    const expired = approval.expiresAt ? new Date(approval.expiresAt).getTime() <= Date.now() : false;
    // Decidido não é acionável: no lugar da proposta e dos botões, quem decidiu, quando e por quê.
    const decided = approval.status !== 'PENDING';

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
                        <Badge size="lg" radius="sm" variant="dot" color={STATUS_COLOR[approval.status] ?? 'gray'}>
                            {approval.status}
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
                        <Meta label={t('approvals.detail.flow')} value={approval.flowId ?? '—'} />
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
                    </Stack>
                </SectionCard>
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
                            !editMode && (
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
                                onChange={(e) => setEditedPayload(e.currentTarget.value)}
                                minRows={10}
                                autosize
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
                            <Group gap="sm" justify="flex-end">
                                {editMode && (
                                    <Button variant="subtle" color="gray" onClick={() => setEditMode(false)}>
                                        {t('approvals.detail.cancelEdit')}
                                    </Button>
                                )}
                                <Button
                                    leftSection={<IconX size={14} />}
                                    color="red"
                                    variant="light"
                                    loading={submitting}
                                    disabled={submitting}
                                    onClick={() => confirmDecide('REJECTED')}
                                    data-testid="approval-reject"
                                >
                                    {t('approvals.detail.reject')}
                                </Button>
                                <Button
                                    leftSection={<IconCheck size={14} />}
                                    color="teal"
                                    loading={submitting}
                                    disabled={submitting}
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
