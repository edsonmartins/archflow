import { ActionIcon, Stack, Text, Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { IconMicrophone, IconMicrophoneOff, IconPlayerStop } from '@tabler/icons-react';
import type { RealtimeStatus } from '../../services/realtime-client';

interface MicButtonProps {
    status: RealtimeStatus;
    onStart: () => void;
    onStop: () => void;
}

const STATUS_COLOR: Record<RealtimeStatus, string> = {
    idle: 'blue',
    requesting_mic: 'yellow',
    connecting: 'yellow',
    recording: 'red',
    speaking: 'teal',
    closed: 'gray',
    error: 'red',
};

/** i18n key under `voicePlayground.mic` for the hint shown under the button. */
const STATUS_LABEL_KEY: Record<RealtimeStatus, string> = {
    idle: 'start',
    requesting_mic: 'requesting',
    connecting: 'connecting',
    recording: 'listening',
    speaking: 'speaking',
    closed: 'start',
    error: 'retry',
};

/**
 * Large round mic button with a pulsing ring while listening or speaking.
 *
 * Click toggles the realtime session via the parent `onStart`/`onStop`
 * callbacks. Visual states map to the {@link RealtimeStatus} enum from
 * the realtime client.
 */
export default function MicButton({ status, onStart, onStop }: MicButtonProps) {
    const { t } = useTranslation();
    const label = t(`voicePlayground.mic.${STATUS_LABEL_KEY[status]}`);
    const active = status === 'recording' || status === 'speaking';
    const busy = status === 'requesting_mic' || status === 'connecting';
    const color = STATUS_COLOR[status];

    const handleClick = () => {
        if (active || busy) onStop();
        else onStart();
    };

    return (
        <Stack align="center" gap="xs">
            <Tooltip label={label}>
                <ActionIcon
                    aria-label={t('voicePlayground.mic.toggle')}
                    data-testid="mic-button"
                    data-status={status}
                    size={96}
                    radius={999}
                    variant="filled"
                    color={color}
                    onClick={handleClick}
                    style={{
                        position: 'relative',
                        boxShadow: active
                            ? `0 0 0 8px var(--mantine-color-${color}-1), 0 0 0 16px var(--mantine-color-${color}-0)`
                            : `0 0 0 8px var(--mantine-color-${color}-light), 0 6px 18px var(--mantine-color-${color}-light)`,
                        transition: 'box-shadow 0.2s',
                        animation: active ? 'archflow-mic-pulse 1.6s ease-in-out infinite' : undefined,
                    }}
                >
                    {status === 'error' || status === 'idle' || status === 'closed' ? (
                        <IconMicrophoneOff size={36} />
                    ) : active ? (
                        <IconPlayerStop size={36} />
                    ) : (
                        <IconMicrophone size={36} />
                    )}
                </ActionIcon>
            </Tooltip>
            <Text size="sm" c="dimmed" data-testid="mic-status-label">
                {label}
            </Text>

            {/* Pulse keyframes injected once per page render. Cheap enough not to need a stylesheet. */}
            <style>{`
                @keyframes archflow-mic-pulse {
                    0%,100% { box-shadow: 0 0 0 6px var(--mantine-color-${color}-1), 0 0 0 14px var(--mantine-color-${color}-0); }
                    50%     { box-shadow: 0 0 0 10px var(--mantine-color-${color}-1), 0 0 0 22px var(--mantine-color-${color}-0); }
                }
            `}</style>
        </Stack>
    );
}
