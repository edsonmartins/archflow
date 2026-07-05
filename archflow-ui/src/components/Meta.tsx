import { Stack, Text } from '@mantine/core'

/**
 * Uppercase label + monospaced value block used by the detail pages
 * (executions, approvals, traces). One implementation so typography and
 * dark-mode treatment stay consistent across every detail screen.
 */
export function Meta({ label, value }: { label: string; value: string }) {
    return (
        <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                {label}
            </Text>
            <Text size="sm" ff="DM Mono, monospace">
                {value}
            </Text>
        </Stack>
    )
}
