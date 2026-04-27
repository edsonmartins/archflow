import { Paper, Text, Group, Badge, Stack, Loader } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { FormDataType } from '../../services/conversation-api';
import FormRenderer from './FormRenderer';
import ToolCallBlock, { type ToolCallView } from './ToolCallBlock';
import CitationList, { type Citation } from './CitationList';

export type MessageRole = 'user' | 'assistant' | 'system' | 'tool';

interface ChatMessageProps {
    role: MessageRole;
    content: string;
    timestamp?: string;
    formData?: FormDataType;
    onFormSubmit?: (data: Record<string, unknown>) => void;
    onFormCancel?: () => void;
    /** Optional persona/agent label rendered as a badge on assistant messages. */
    personaId?: string;
    personaIcon?: string;
    /** Tool calls that were made while producing this message. */
    toolCalls?: ToolCallView[];
    /** RAG sources cited by this message. */
    citations?: Citation[];
    /** True while a delta-stream is still being applied to this message. */
    streaming?: boolean;
}

export default function ChatMessage({
    role,
    content,
    timestamp,
    formData,
    onFormSubmit,
    onFormCancel,
    personaId,
    personaIcon,
    toolCalls,
    citations,
    streaming,
}: ChatMessageProps) {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
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
            <Stack gap={4} maw="80%">
                <Group gap={4} justify={isUser ? 'flex-end' : 'flex-start'}>
                    <Badge size="xs" variant="light" color={roleColor(role)}>
                        {t(`chat.roles.${role}`, { defaultValue: role })}
                    </Badge>
                    {!isUser && personaId && (
                        <Badge size="xs" variant="outline" color="grape">
                            {personaIcon ? `${personaIcon} ` : ''}
                            {personaId}
                        </Badge>
                    )}
                    {timestamp && (
                        <Text size="xs" c="dimmed">
                            {formatTime(timestamp, locale)}
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
                            {streaming && (
                                <span style={{ display: 'inline-flex', marginLeft: 4, verticalAlign: 'middle' }}>
                                    <Loader size={10} />
                                </span>
                            )}
                        </Text>
                    )}
                </Paper>

                {toolCalls && toolCalls.length > 0 && (
                    <Stack gap={4}>
                        {toolCalls.map((tc) => (
                            <ToolCallBlock key={tc.id} call={tc} />
                        ))}
                    </Stack>
                )}

                {citations && citations.length > 0 && <CitationList citations={citations} />}
            </Stack>
        </Group>
    );
}

function roleColor(role: string): string {
    switch (role) {
        case 'user':
            return 'blue';
        case 'assistant':
            return 'gray';
        case 'tool':
            return 'teal';
        case 'system':
            return 'yellow';
        default:
            return 'gray';
    }
}

function formatTime(timestamp: string, locale: string): string {
    try {
        return new Date(timestamp).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
    } catch {
        return timestamp;
    }
}
