import { ActionIcon, Stack, Text, Tooltip } from '@mantine/core';
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

const STATUS_LABEL: Record<RealtimeStatus, string> = {
    idle: 'Tap to start',
    requesting_mic: 'Requesting microphone…',
    connecting: 'Connecting…',
    recording: 'Listening — tap to stop',
    speaking: 'Agent speaking…',
    closed: 'Tap to start',
    error: 'Error — tap to retry',
};

/**
 * Large round mic button with a pulsing ring while listening or speaking.
 *
 * Click toggles the realtime session via the parent `onStart`/`onStop`
 * callbacks. Visual states map to the {@link RealtimeStatus} enum from
 * the realtime client.
 */
export default function MicButton({ status, onStart, onStop }: MicButtonProps) {
    const active = status === 'recording' || status === 'speaking';
    const busy = status === 'requesting_mic' || status === 'connecting';
    const color = STATUS_COLOR[status];

    const handleClick = () => {
        if (active || busy) onStop();
        else onStart();
    };

    return (
        <Stack align="center" gap="xs">
            <Tooltip label={STATUS_LABEL[status]}>
                <ActionIcon
                    aria-label="Toggle voice session"
                    data-testid="mic-button"
                    data-status={status}
                    size={96}
                    radius="xl"
                    variant="filled"
                    color={color}
                    onClick={handleClick}
                    style={{
                        position: 'relative',
                        boxShadow: active
                            ? `0 0 0 8px var(--mantine-color-${color}-1), 0 0 0 16px var(--mantine-color-${color}-0)`
                            : '0 4px 12px rgba(0,0,0,0.12)',
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
                {STATUS_LABEL[status]}
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
