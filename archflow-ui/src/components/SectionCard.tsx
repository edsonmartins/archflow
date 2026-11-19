import type { ComponentType, ReactNode } from 'react'
import { Group, Paper, Stack, Text, type PaperProps } from '@mantine/core'
import type { Accent } from './PageHeader'

/**
 * Cartão de seção: título (com ícone opcional), uma linha de descrição, ações
 * à direita e o conteúdo. É o bloco que mais se repete nas telas (listas,
 * formulários de configuração, painéis de detalhe). `flush` tira o padding do
 * corpo — para tabelas que encostam nas bordas do cartão.
 */
interface SectionCardProps extends Omit<PaperProps, 'title'> {
    title?: ReactNode
    description?: ReactNode
    icon?: ComponentType<{ size?: number; stroke?: number }>
    accent?: Accent
    actions?: ReactNode
    flush?: boolean
    children: ReactNode
}

export function SectionCard({
    title, description, icon: Icon, accent = 'blue', actions, flush = false, children, ...paper
}: SectionCardProps) {
    const hasHeader = title || description || actions
    return (
        <Paper withBorder {...paper} style={{ overflow: 'hidden', ...(paper.style as object) }}>
            {hasHeader && (
                <Group justify="space-between" align="center" wrap="wrap" gap="sm" px="lg" pt="md" pb={flush ? 'md' : 0}
                       className={flush ? 'af-section-head-flush' : undefined}>
                    <Group gap="sm" wrap="nowrap" style={{ minWidth: 0 }}>
                        {Icon && (
                            <span className="af-stat-icon" data-accent={accent} aria-hidden>
                                <Icon size={17} stroke={1.8} />
                            </span>
                        )}
                        <Stack gap={0} style={{ minWidth: 0 }}>
                            {title && <Text fw={600} size="sm">{title}</Text>}
                            {description && <Text size="xs" c="dimmed">{description}</Text>}
                        </Stack>
                    </Group>
                    {actions && <Group gap="xs">{actions}</Group>}
                </Group>
            )}
            <div style={flush ? undefined : { padding: 'var(--mantine-spacing-lg)', paddingTop: hasHeader ? 'var(--mantine-spacing-md)' : undefined }}>
                {children}
            </div>
        </Paper>
    )
}
