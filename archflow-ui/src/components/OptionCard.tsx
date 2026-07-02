import type { ReactNode } from 'react'
import { Card, Group, Stack, Text, ThemeIcon } from '@mantine/core'

/**
 * Clickable card with icon + title + description — the shared shape behind
 * the create-workflow options and the dashboard shortcuts, so focus/hover
 * and accessibility fixes land in one place.
 */
export function OptionCard({
    icon, color, title, description, onClick, testId,
}: {
    icon: ReactNode
    /** Mantine color for the icon badge; omit to render the raw icon. */
    color?: string
    title: string
    description: string
    onClick: () => void
    testId?: string
}) {
    return (
        <Card
            withBorder
            radius="md"
            padding="sm"
            component="button"
            type="button"
            onClick={onClick}
            data-testid={testId}
            style={{ cursor: 'pointer', textAlign: 'left', width: '100%' }}
        >
            <Group gap="sm" wrap="nowrap">
                {color ? (
                    <ThemeIcon size={40} radius="md" variant="light" color={color}>
                        {icon}
                    </ThemeIcon>
                ) : icon}
                <Stack gap={0}>
                    <Text fw={600} size="sm">{title}</Text>
                    <Text size="xs" c="dimmed">{description}</Text>
                </Stack>
            </Group>
        </Card>
    )
}
