import type { CSSProperties } from 'react';
import { Badge, Box, Group, Loader, Paper, Stack, Text, ThemeIcon } from '@mantine/core';
import { IconInfoCircle, IconRobot, IconTool, IconUser } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { FormDataType } from '../../services/conversation-api';
import type { Permission } from '../../lib/permissions';
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
    /** Permissão que falta para enviar / cancelar o formulário (o botão fica desabilitado com o aviso). */
    formSubmitMissing?: Permission | null;
    formCancelMissing?: Permission | null;
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

/**
 * One turn of the transcript: avatar + bubble. The user's turns sit on the
 * right in the accent colour; assistant/tool turns sit on the left on a
 * neutral surface, with the tool calls and citations of that turn under the
 * bubble. System notices are a centred pill.
 */
export default function ChatMessage({
    role,
    content,
    timestamp,
    formData,
    onFormSubmit,
    onFormCancel,
    formSubmitMissing,
    formCancelMissing,
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
            <Group justify="center" my="sm">
                <Paper
                    radius="xl"
                    px="md"
                    py={4}
                    style={{
                        background: 'var(--mantine-color-default-hover)',
                        border: '1px solid var(--mantine-color-default-border)',
                    }}
                >
                    <Group gap={6} wrap="nowrap">
                        <IconInfoCircle size={13} color="var(--mantine-color-dimmed)" />
                        <Text size="xs" c="dimmed" ta="center">
                            {content}
                        </Text>
                    </Group>
                </Paper>
            </Group>
        );
    }

    const isUser = role === 'user';
    const isTool = role === 'tool';
    const hasForm = !!(formData && onFormSubmit);

    const bubble: CSSProperties = isUser
        ? {
              background: 'var(--mantine-color-teal-filled)',
              color: 'var(--mantine-color-white)',
              borderRadius: '16px 16px 4px 16px',
          }
        : {
              background: isTool ? 'var(--mantine-color-default-hover)' : 'var(--mantine-color-body)',
              border: '1px solid var(--mantine-color-default-border)',
              borderRadius: '16px 16px 16px 4px',
              fontFamily: isTool ? 'var(--font-mono)' : undefined,
          };

    const avatar = (
        <ThemeIcon
            size={32}
            radius="xl"
            variant={isUser ? 'light' : 'gradient'}
            color={isUser ? 'teal' : undefined}
            gradient={isTool ? { from: 'gray.5', to: 'gray.7', deg: 135 } : { from: 'teal.4', to: 'cyan.7', deg: 135 }}
            aria-hidden
            style={{ flexShrink: 0 }}
        >
            {isUser ? (
                <IconUser size={17} />
            ) : isTool ? (
                <IconTool size={16} />
            ) : personaIcon ? (
                <Text size="sm" lh={1}>{personaIcon}</Text>
            ) : (
                <IconRobot size={17} />
            )}
        </ThemeIcon>
    );

    return (
        <Group
            justify={isUser ? 'flex-end' : 'flex-start'}
            align="flex-start"
            wrap="nowrap"
            gap="sm"
            my="md"
        >
            {!isUser && avatar}
            <Stack gap={6} maw={hasForm ? '92%' : '78%'} style={{ minWidth: 0 }} align={isUser ? 'flex-end' : 'flex-start'}>
                <Group gap={6} justify={isUser ? 'flex-end' : 'flex-start'} wrap="nowrap">
                    <Text size="xs" fw={600}>
                        {t(`chat.roles.${role}`, { defaultValue: role })}
                    </Text>
                    {!isUser && personaId && (
                        <Badge size="xs" variant="light" color="grape" radius="sm">
                            {personaId}
                        </Badge>
                    )}
                    {timestamp && (
                        <Text size="xs" c="dimmed">
                            {formatTime(timestamp, locale)}
                        </Text>
                    )}
                </Group>

                {toolCalls && toolCalls.length > 0 && (
                    <Stack gap={6} w="100%">
                        {toolCalls.map((tc) => (
                            <ToolCallBlock key={tc.id} call={tc} />
                        ))}
                    </Stack>
                )}

                {(hasForm || content || streaming) && (
                    <Box px="md" py="sm" style={{ ...bubble, minWidth: hasForm ? 320 : undefined, maxWidth: '100%' }}>
                        {hasForm ? (
                            <FormRenderer
                                formData={formData!}
                                onSubmit={onFormSubmit!}
                                onCancel={onFormCancel}
                                submitMissing={formSubmitMissing}
                                cancelMissing={formCancelMissing}
                            />
                        ) : (
                            <Text size="sm" lh={1.6} style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                                {content}
                                {streaming && (
                                    <span style={{ display: 'inline-flex', marginLeft: 6, verticalAlign: 'middle' }}>
                                        <Loader size="xs" type="dots" color="teal" />
                                    </span>
                                )}
                            </Text>
                        )}
                    </Box>
                )}

                {citations && citations.length > 0 && <CitationList citations={citations} />}
            </Stack>
            {isUser && avatar}
        </Group>
    );
}

function formatTime(timestamp: string, locale: string): string {
    try {
        return new Date(timestamp).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
    } catch {
        return timestamp;
    }
}
