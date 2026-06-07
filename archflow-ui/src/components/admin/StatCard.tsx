import { Paper, Text } from '@mantine/core'

/**
 * Shared admin metric card (previously copy-pasted across WorkspaceOverview,
 * TenantList and UsageBilling). Mantine Paper so it follows the theme in
 * dark mode, with tabular figures so the big numbers stay aligned in a grid.
 */
interface StatCardProps {
    label: string
    value: string | number
    /** Optional accent color for the value (CSS color / Mantine token). */
    color?: string
}

export function StatCard({ label, value, color }: StatCardProps) {
    return (
        <Paper withBorder p="md" radius="md">
            <Text size="xs" fw={500} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.04em' }}>
                {label}
            </Text>
            <Text fz={28} fw={600} mt={4} c={color} style={{ fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.5px' }}>
                {value}
            </Text>
        </Paper>
    )
}
