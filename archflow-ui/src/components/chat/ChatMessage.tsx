import { Paper, Text, Group, Badge, Stack } from '@mantine/core';
import type { FormDataType } from '../../services/conversation-api';
import FormRenderer from './FormRenderer';

interface ChatMessageProps {
    role: 'user' | 'assistant' | 'system' | 'tool';
    content: string;
    timestamp?: string;
    formData?: FormDataType;
    onFormSubmit?: (data: Record<string, unknown>) => void;
    onFormCancel?: () => void;
}

export default function ChatMessage({
    role, content, timestamp, formData, onFormSubmit, onFormCancel,
}: ChatMessageProps) {
    if (role === 'system') {
        return (
            <Group justify="center" my="xs">
                <Text size="xs" fs="italic" c="dimmed" ta="center">
                    {content}
                </Text>
            </Group>
        );
    }

    const isUser = role === 'user';
    const isTool = role === 'tool';

    return (
        <Group justify={isUser ? 'flex-end' : 'flex-start'} align="flex-end" my="xs">
            <Stack gap={2} maw="75%">
                <Group gap={4} justify={isUser ? 'flex-end' : 'flex-start'}>
                    <Badge size="xs" variant="light" color={roleColor(role)}>
                        {role}
                    </Badge>
                    {timestamp && (
                        <Text size="xs" c="dimmed">
                            {formatTime(timestamp)}
                        </Text>
                    )}
                </Group>

                <Paper
                    px="sm"
                    py="xs"
                    radius="md"
                    withBorder
                    style={(theme) => ({
                        backgroundColor: isUser
                            ? theme.colors.blue[0]
                            : isTool
                                ? theme.colors.dark[0]
                                : theme.colors.gray[0],
                        fontFamily: isTool ? 'monospace' : undefined,
                        alignSelf: isUser ? 'flex-end' : 'flex-start',
                    })}
                >
                    {formData && onFormSubmit ? (
                        <FormRenderer
                            formData={formData}
                            onSubmit={onFormSubmit}
                            onCancel={onFormCancel}
                        />
                    ) : (
                        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                            {content}
                        </Text>
                    )}
                </Paper>
            </Stack>
        </Group>
    );
}

function roleColor(role: string): string {
    switch (role) {
        case 'user': return 'blue';
        case 'assistant': return 'gray';
        case 'tool': return 'teal';
        case 'system': return 'yellow';
        default: return 'gray';
    }
}

function formatTime(timestamp: string): string {
    try {
        return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
        return timestamp;
    }
}
