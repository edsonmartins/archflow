import type { ComponentType, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Anchor, Breadcrumbs, Button, Group, Stack, Text, Title } from '@mantine/core'
import { IconChevronLeft } from '@tabler/icons-react'

/**
 * Standard page header so every screen shares the same heading semantics
 * and spacing: a real <h2> title (not a styled span), optional subtitle,
 * an optional breadcrumb trail and back link for deep pages, and a slot
 * for right-aligned actions.
 *
 *   <PageHeader
 *     title={t('...')}
 *     breadcrumbs={[{ label: t('section'), to: '/x' }, { label: t('current') }]}
 *     backTo="/x"
 *     actions={<Button>…</Button>}
 *     icon={IconTopologyRing} accent="blue"
 *   />
 *
 * `icon` + `accent` dão a cada tela uma identidade: um quadrado com degradê na
 * cor da seção, ao lado do título (classes af-page-* em App.css).
 */
export type Accent = 'blue' | 'teal' | 'violet' | 'amber' | 'rose' | 'green' | 'slate'

export interface Crumb {
    label: string
    /** When set, the crumb is a link; the last/current crumb usually omits it. */
    to?: string
}

interface PageHeaderProps {
    title: ReactNode
    subtitle?: ReactNode
    actions?: ReactNode
    /** Route to navigate back to; renders a subtle back button above the title. */
    backTo?: string
    backLabel?: string
    breadcrumbs?: Crumb[]
    /** Ícone Tabler da tela, mostrado num quadrado com degradê ao lado do título. */
    icon?: ComponentType<{ size?: number; stroke?: number }>
    /** Cor do quadrado do ícone. Default 'blue'. */
    accent?: Accent
}

export function PageHeader({
    title,
    subtitle,
    actions,
    backTo,
    backLabel,
    breadcrumbs,
    icon: Icon,
    accent = 'blue',
}: PageHeaderProps) {
    return (
        <Stack gap="xs" className="af-page-header">
            {breadcrumbs && breadcrumbs.length > 0 && (
                <Breadcrumbs fz="sm">
                    {breadcrumbs.map((c, i) =>
                        c.to ? (
                            <Anchor key={i} component={Link} to={c.to} size="sm">
                                {c.label}
                            </Anchor>
                        ) : (
                            <Text key={i} size="sm" c="dimmed">
                                {c.label}
                            </Text>
                        ),
                    )}
                </Breadcrumbs>
            )}

            {backTo && (
                <Button
                    component={Link}
                    to={backTo}
                    variant="subtle"
                    size="compact-sm"
                    color="gray"
                    leftSection={<IconChevronLeft size={14} />}
                    w="fit-content"
                    px={6}
                >
                    {backLabel ?? 'Back'}
                </Button>
            )}

            <Group justify="space-between" align="center" wrap="wrap" gap="md">
                <Group gap="md" wrap="nowrap" align="center" style={{ minWidth: 0, flex: '1 1 360px' }}>
                    {Icon && (
                        <span className="af-page-icon" data-accent={accent} aria-hidden>
                            <Icon size={22} stroke={1.8} />
                        </span>
                    )}
                    <Stack gap={2} style={{ minWidth: 0 }}>
                        <Title order={2} className="af-page-title">{title}</Title>
                        {subtitle && <Text size="sm" c="dimmed" maw={760}>{subtitle}</Text>}
                    </Stack>
                </Group>
                {actions && <Group gap="sm">{actions}</Group>}
            </Group>
        </Stack>
    )
}
