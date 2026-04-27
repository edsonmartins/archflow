import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    ActionIcon,
    Alert,
    Badge,
    Button,
    Center,
    Code,
    Group,
    Loader,
    Paper,
    Stack,
    Text,
    Textarea,
    Title,
    Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
    IconAlertCircle,
    IconArrowLeft,
    IconCheck,
    IconEdit,
    IconX,
} from '@tabler/icons-react';
import { approvalApi, type ApprovalResponse } from '../services/approval-api';
import { useTenantStore } from '../stores/useTenantStore';

export default function ApprovalDetailPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const { impersonating, currentRole } = useTenantStore();
    const tenantId = impersonating ?? (currentRole === 'superadmin' ? 'all' : 'default');

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
            .get(id)
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
    }, [id]);

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
                tenantId: approval.tenantId ?? tenantId,
                decision,
                editedPayload: parsedPayload,
                responderId: 'me',
            });
            notifications.show({
                title: t('approvals.detail.decisionSubmitted'),
                message: `${decision} · ${result.requestId.slice(0, 12)}`,
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

    if (loading) {
        return (
            <Center py="xl">
                <Loader size="sm" />
            </Center>
        );
    }

    if (error || !approval) {
        return (
            <Stack p="md">
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error ?? t('approvals.notFound')}
                </Alert>
                <Button variant="default" onClick={() => navigate('/approvals')}>
                    {t('approvals.backToQueue')}
                </Button>
            </Stack>
        );
    }

    return (
        <Stack p="md" gap="md">
            <Group gap="sm">
                <Tooltip label={t('approvals.backToQueue')}>
                    <ActionIcon variant="subtle" onClick={() => navigate('/approvals')}>
                        <IconArrowLeft size={18} />
                    </ActionIcon>
                </Tooltip>
                <Stack gap={0} style={{ flex: 1 }}>
                    <Title order={3}>{t('approvals.detail.heading', { id: approval.requestId.slice(0, 12) })}</Title>
                    <Group gap={6}>
                        <Text size="xs" c="dimmed" ff="DM Mono, monospace">
                            {approval.requestId}
                        </Text>
                        <Badge size="xs" variant="light" color="orange">
                            {approval.status}
                        </Badge>
                    </Group>
                </Stack>
            </Group>

            <Paper withBorder radius="md" p="md">
                <Group gap="xl">
                    <Meta label={t('approvals.detail.tenant')} value={approval.tenantId ?? '—'} />
                    <Meta label={t('approvals.detail.flow')} value={approval.flowId ?? '—'} />
                    <Meta label={t('approvals.detail.step')} value={approval.stepId ?? '—'} />
                    <Meta label={t('approvals.detail.created')} value={formatDate(approval.createdAt, i18n.resolvedLanguage ?? i18n.language)} />
                    <Meta label={t('approvals.detail.expires')} value={formatDate(approval.expiresAt, i18n.resolvedLanguage ?? i18n.language)} />
                </Group>
                {approval.description && (
                    <Paper mt="sm" p="sm" radius="sm" bg="var(--mantine-color-gray-0)">
                        <Text size="sm">{approval.description}</Text>
                    </Paper>
                )}
            </Paper>

            <Paper withBorder radius="md" p="md">
                <Group justify="space-between" mb="xs">
                    <Title order={5}>{t('approvals.detail.proposal')}</Title>
                    {!editMode && (
                        <Button
                            size="xs"
                            variant="subtle"
                            leftSection={<IconEdit size={14} />}
                            onClick={() => setEditMode(true)}
                            data-testid="approval-edit-toggle"
                        >
                            {t('approvals.detail.editBeforeApprove')}
                        </Button>
                    )}
                </Group>
                {editMode ? (
                    <Textarea
                        value={editedPayload}
                        onChange={(e) => setEditedPayload(e.currentTarget.value)}
                        minRows={10}
                        autosize
                        styles={{ input: { fontFamily: 'DM Mono, monospace', fontSize: 12 } }}
                        data-testid="approval-edit-payload"
                    />
                ) : (
                    <Code
                        block
                        style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                    >
                        {JSON.stringify(approval.proposal, null, 2)}
                    </Code>
                )}
            </Paper>

            <Paper withBorder radius="md" p="md">
                <Title order={5} mb="xs">
                    {t('approvals.detail.yourDecision')}
                </Title>
                <Textarea
                    placeholder={t('approvals.detail.commentPlaceholder')}
                    minRows={2}
                    autosize
                    value={comment}
                    onChange={(e) => setComment(e.currentTarget.value)}
                    mb="sm"
                />
                <Group gap="sm">
                    <Button
                        leftSection={<IconCheck size={14} />}
                        color="teal"
                        disabled={submitting}
                        onClick={() => decide(editMode ? 'EDITED' : 'APPROVED')}
                        data-testid="approval-approve"
                    >
                        {editMode ? t('approvals.detail.saveAndApprove') : t('approvals.detail.approve')}
                    </Button>
                    <Button
                        leftSection={<IconX size={14} />}
                        color="red"
                        variant="light"
                        disabled={submitting}
                        onClick={() => decide('REJECTED')}
                        data-testid="approval-reject"
                    >
                        {t('approvals.detail.reject')}
                    </Button>
                    {editMode && (
                        <Button variant="subtle" onClick={() => setEditMode(false)}>
                            {t('approvals.detail.cancelEdit')}
                        </Button>
                    )}
                </Group>
            </Paper>
        </Stack>
    );
}

function Meta({ label, value }: { label: string; value: string }) {
    return (
        <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                {label}
            </Text>
            <Text size="sm" ff="DM Mono, monospace">
                {value}
            </Text>
        </Stack>
    );
}

function formatDate(iso: string | null, locale: string): string {
    if (!iso) return '—';
    try {
        return new Date(iso).toLocaleString(locale);
    } catch {
        return iso;
    }
}
