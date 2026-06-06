import type { ReactNode } from 'react'
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
 *   />
 */
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
}

export function PageHeader({
    title,
    subtitle,
    actions,
    backTo,
    backLabel,
    breadcrumbs,
}: PageHeaderProps) {
    return (
        <Stack gap="xs">
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

            <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm">
                <Stack gap={2} style={{ minWidth: 0 }}>
                    <Title order={2}>{title}</Title>
                    {subtitle && <Text size="sm" c="dimmed">{subtitle}</Text>}
                </Stack>
                {actions && <Group gap="sm">{actions}</Group>}
            </Group>
        </Stack>
    )
}
