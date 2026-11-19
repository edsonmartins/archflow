import type { ComponentType, ReactNode } from 'react'
import { Group, Paper, Text } from '@mantine/core'
import type { Accent } from '../PageHeader'

/**
 * Cartão de métrica compartilhado (Dashboard, WorkspaceOverview, TenantList,
 * UsageBilling, Observability…). Paper do tema (borda + sombra leve), número
 * grande com algarismos tabulares, ícone num quadrado suave na cor do `accent`
 * e uma linha opcional de contexto (`hint`) embaixo.
 */
interface StatCardProps {
    label: string
    value: ReactNode
    /** Cor do número (CSS color / token Mantine). Sem ela, o número usa a cor do texto. */
    color?: string
    icon?: ComponentType<{ size?: number; stroke?: number }>
    accent?: Accent
    /** Linha de contexto embaixo do número (ex.: "3 com falha", "últimas 24h"). */
    hint?: ReactNode
}

export function StatCard({ label, value, color, icon: Icon, accent = 'blue', hint }: StatCardProps) {
    return (
        <Paper withBorder p="lg" className="af-stat" data-accent={accent}>
            <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
                <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.06em' }}>
                    {label}
                </Text>
                {Icon && (
                    <span className="af-stat-icon" aria-hidden>
                        <Icon size={18} stroke={1.8} />
                    </span>
                )}
            </Group>
            <Text fz={30} fw={700} mt={6} lh={1.1} c={color} style={{ fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.5px' }}>
                {value}
            </Text>
            {hint && <Text size="xs" c="dimmed" mt={6}>{hint}</Text>}
        </Paper>
    )
}
