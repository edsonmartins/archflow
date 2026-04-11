import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Alert,
    Badge,
    Button,
    Group,
    LoadingOverlay,
    Paper,
    Select,
    Stack,
    Table,
    Text,
    TextInput,
    Title,
} from '@mantine/core';
import { IconAlertCircle, IconRefresh, IconSearch } from '@tabler/icons-react';
import {
    conversationApi,
    type Conversation,
    type ConversationListParams,
    type ConversationStatus,
} from '../services/conversation-api';

const STATUS_OPTIONS: { value: ConversationListParams['status']; label: string }[] = [
    { value: 'ALL', label: 'All' },
    { value: 'ACTIVE', label: 'Active' },
    { value: 'AWAITING_HUMAN', label: 'Awaiting human' },
    { value: 'SUSPENDED', label: 'Suspended' },
    { value: 'ESCALATED', label: 'Escalated' },
    { value: 'CLOSED', label: 'Closed' },
    { value: 'COMPLETED', label: 'Completed' },
    { value: 'CANCELLED', label: 'Cancelled' },
];

const STATUS_COLOR: Record<ConversationStatus, string> = {
    ACTIVE: 'teal',
    AWAITING_HUMAN: 'orange',
    SUSPENDED: 'yellow',
    ESCALATED: 'red',
    CLOSED: 'gray',
    COMPLETED: 'blue',
    CANCELLED: 'gray',
};

export default function ConversationsListPage() {
    const navigate = useNavigate();
    const [conversations, setConversations] = useState<Conversation[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [search, setSearch] = useState('');
    const [status, setStatus] = useState<ConversationListParams['status']>('ALL');

    const load = useMemo(
        () => async () => {
            setLoading(true);
            setError(null);
            try {
                const result = await conversationApi.listConversations({
                    status: status === 'ALL' ? undefined : status,
                    search: search || undefined,
                    pageSize: 100,
                });
                setConversations(result.items);
            } catch (err) {
                console.error(err);
                setError(
                    err instanceof Error ? err.message : 'Failed to load conversations',
                );
                setConversations([]);
            } finally {
                setLoading(false);
            }
        },
        [search, status],
    );

    useEffect(() => {
        load();
    }, [load]);

    return (
        <Stack p="md" gap="md">
            <Group justify="space-between">
                <Title order={2}>Conversations</Title>
                <Button
                    leftSection={<IconRefresh size={16} />}
                    variant="subtle"
                    size="sm"
                    onClick={() => load()}
                >
                    Refresh
                </Button>
            </Group>

            <Group gap="sm" wrap="wrap">
                <TextInput
                    placeholder="Search by user, conversation id..."
                    leftSection={<IconSearch size={14} />}
                    value={search}
                    onChange={(e) => setSearch(e.currentTarget.value)}
                    style={{ flex: 1, minWidth: 240 }}
                />
                <Select
                    placeholder="Status"
                    data={STATUS_OPTIONS.map((o) => ({ value: String(o.value), label: o.label }))}
                    value={String(status)}
                    onChange={(v) => setStatus((v as ConversationListParams['status']) ?? 'ALL')}
                    w={180}
                />
            </Group>

            {error && (
                <Alert icon={<IconAlertCircle size={16} />} color="red" variant="light">
                    {error}
                </Alert>
            )}

            <Paper withBorder pos="relative" radius="md">
                <LoadingOverlay visible={loading} zIndex={10} />
                {conversations.length === 0 && !loading ? (
                    <Text size="sm" c="dimmed" ta="center" p="md">
                        No conversations found.
                    </Text>
                ) : (
                    <Table highlightOnHover striped>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>ID</Table.Th>
                                <Table.Th>User</Table.Th>
                                <Table.Th>Channel</Table.Th>
                                <Table.Th>Persona</Table.Th>
                                <Table.Th>Status</Table.Th>
                                <Table.Th>Updated</Table.Th>
                                <Table.Th>Messages</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {conversations.map((c) => (
                                <Table.Tr
                                    key={c.id}
                                    style={{ cursor: 'pointer' }}
                                    onClick={() => navigate(`/conversations/${c.id}`)}
                                >
                                    <Table.Td>
                                        <Text size="xs" ff="DM Mono, monospace">
                                            {c.id.slice(0, 8)}
                                        </Text>
                                    </Table.Td>
                                    <Table.Td>{c.userId ?? '—'}</Table.Td>
                                    <Table.Td>{c.channel ?? '—'}</Table.Td>
                                    <Table.Td>
                                        {c.persona ? (
                                            <Badge size="xs" variant="outline" color="grape">
                                                {c.persona}
                                            </Badge>
                                        ) : (
                                            '—'
                                        )}
                                    </Table.Td>
                                    <Table.Td>
                                        <Badge
                                            size="xs"
                                            variant="light"
                                            color={STATUS_COLOR[c.status] ?? 'gray'}
                                        >
                                            {c.status}
                                        </Badge>
                                    </Table.Td>
                                    <Table.Td>{formatRelative(c.updatedAt)}</Table.Td>
                                    <Table.Td>{c.messageCount ?? '—'}</Table.Td>
                                </Table.Tr>
                            ))}
                        </Table.Tbody>
                    </Table>
                )}
            </Paper>
        </Stack>
    );
}

function formatRelative(iso: string): string {
    try {
        const d = new Date(iso);
        const diffMs = Date.now() - d.getTime();
        const diffSec = Math.floor(diffMs / 1000);
        if (diffSec < 60) return `${diffSec}s ago`;
        const diffMin = Math.floor(diffSec / 60);
        if (diffMin < 60) return `${diffMin}m ago`;
        const diffH = Math.floor(diffMin / 60);
        if (diffH < 24) return `${diffH}h ago`;
        const diffD = Math.floor(diffH / 24);
        if (diffD < 7) return `${diffD}d ago`;
        return d.toLocaleDateString();
    } catch {
        return iso;
    }
}
