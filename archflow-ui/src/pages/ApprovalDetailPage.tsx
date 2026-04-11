import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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
                if (!cancelled) setError(e instanceof Error ? e.message : 'Not found');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
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
                    throw new Error('Edited payload is not valid JSON');
                }
            }
            const result = await approvalApi.submit(id, {
                tenantId: approval.tenantId ?? tenantId,
                decision,
                editedPayload: parsedPayload,
                responderId: 'me',
            });
            notifications.show({
                title: 'Decision submitted',
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
            setError(e instanceof Error ? e.message : 'Failed to submit decision');
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
                    {error ?? 'Approval not found.'}
                </Alert>
                <Button variant="default" onClick={() => navigate('/approvals')}>
                    Back to queue
                </Button>
            </Stack>
        );
    }

    return (
        <Stack p="md" gap="md">
            <Group gap="sm">
                <Tooltip label="Back to queue">
                    <ActionIcon variant="subtle" onClick={() => navigate('/approvals')}>
                        <IconArrowLeft size={18} />
                    </ActionIcon>
                </Tooltip>
                <Stack gap={0} style={{ flex: 1 }}>
                    <Title order={3}>Approval {approval.requestId.slice(0, 12)}</Title>
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
                    <Meta label="Tenant" value={approval.tenantId ?? '—'} />
                    <Meta label="Flow" value={approval.flowId ?? '—'} />
                    <Meta label="Step" value={approval.stepId ?? '—'} />
                    <Meta label="Created" value={formatDate(approval.createdAt)} />
                    <Meta label="Expires" value={formatDate(approval.expiresAt)} />
                </Group>
                {approval.description && (
                    <Paper mt="sm" p="sm" radius="sm" bg="var(--mantine-color-gray-0)">
                        <Text size="sm">{approval.description}</Text>
                    </Paper>
                )}
            </Paper>

            <Paper withBorder radius="md" p="md">
                <Group justify="space-between" mb="xs">
                    <Title order={5}>Proposal</Title>
                    {!editMode && (
                        <Button
                            size="xs"
                            variant="subtle"
                            leftSection={<IconEdit size={14} />}
                            onClick={() => setEditMode(true)}
                            data-testid="approval-edit-toggle"
                        >
                            Edit before approve
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
                    Your decision
                </Title>
                <Textarea
                    placeholder="Optional comment (free-form)"
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
                        {editMode ? 'Save & approve' : 'Approve'}
                    </Button>
                    <Button
                        leftSection={<IconX size={14} />}
                        color="red"
                        variant="light"
                        disabled={submitting}
                        onClick={() => decide('REJECTED')}
                        data-testid="approval-reject"
                    >
                        Reject
                    </Button>
                    {editMode && (
                        <Button variant="subtle" onClick={() => setEditMode(false)}>
                            Cancel edit
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

function formatDate(iso: string | null): string {
    if (!iso) return '—';
    try {
        return new Date(iso).toLocaleString();
    } catch {
        return iso;
    }
}
