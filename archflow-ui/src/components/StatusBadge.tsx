import { Badge, type MantineSize } from '@mantine/core'
import {
    IconCircleCheck, IconCircleX, IconAlertTriangle, IconPlayerPause,
    IconPlayerPlay, IconClock, IconFileText, IconCircleMinus, IconPointFilled,
} from '@tabler/icons-react'

/**
 * Consistent status pill used across the app (executions, conversations,
 * traces, triggers, tenants…). Always pairs the status color with an icon
 * AND text so meaning never relies on color alone (WCAG), and uses the
 * `light` variant so it stays legible in both light and dark themes.
 *
 * Callers pass the (already translated) `label` and the domain's `color`
 * choice — only the icon is resolved centrally from the status keyword, so
 * domains keep their own color semantics without colliding.
 */
type TablerIcon = typeof IconCircleCheck

const ICON_FOR_STATUS: Record<string, TablerIcon> = {
    running:        IconPlayerPlay,
    active:         IconCircleCheck,
    enabled:        IconCircleCheck,
    completed:      IconCircleCheck,
    ok:             IconCircleCheck,
    success:        IconCircleCheck,
    connected:      IconCircleCheck,
    failed:         IconCircleX,
    error:          IconCircleX,
    escalated:      IconAlertTriangle,
    paused:         IconPlayerPause,
    suspended:      IconPlayerPause,
    awaiting_human: IconClock,
    pending:        IconClock,
    trial:          IconClock,
    draft:          IconFileText,
    closed:         IconCircleMinus,
    cancelled:      IconCircleMinus,
    offline:        IconCircleMinus,
}

interface StatusBadgeProps {
    /** Raw status keyword (case-insensitive) — used to pick the icon. */
    status: string
    /** Display text, already localized. */
    label: string
    /** Mantine color for this status (domain-specific). Defaults to gray. */
    color?: string
    size?: MantineSize
}

export function StatusBadge({ status, label, color = 'gray', size = 'sm' }: StatusBadgeProps) {
    const Icon = ICON_FOR_STATUS[status?.toLowerCase()] ?? IconPointFilled
    return (
        <Badge size={size} variant="light" color={color} leftSection={<Icon size={11} />}>
            {label}
        </Badge>
    )
}
