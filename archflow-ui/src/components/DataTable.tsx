import type { KeyboardEvent, ReactNode } from 'react'
import { Alert, Button, Group, Skeleton, Table, Text, type TableProps } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useTranslation } from 'react-i18next'

/**
 * Shared data-table chrome so every list across the app gets the same
 * accessible, responsive behavior for free:
 *
 *  - Wrapped in Table.ScrollContainer → no horizontal overflow on mobile.
 *  - Unified loading (skeleton rows), empty, and error+retry states that
 *    keep the header visible instead of collapsing the whole table.
 *
 * Callers still own their header and row markup (badges, custom cells),
 * passed via `head` and `children`; this component only swaps the body
 * for the appropriate state.
 */
interface DataTableProps extends Omit<TableProps, 'children'> {
    /** Column count — sizes the skeleton/empty/error rows' colSpan. */
    columns: number
    /** Header row(s): a <Table.Tr> of <Table.Th>. */
    head: ReactNode
    /** Body rows, rendered when not loading/empty/error. */
    children: ReactNode
    /** Min table width before horizontal scroll kicks in. Default 700. */
    minWidth?: number
    loading?: boolean
    error?: string | null
    onRetry?: () => void
    isEmpty?: boolean
    emptyMessage?: ReactNode
    /** Optional call-to-action rendered under the empty message (e.g. a "create" button). */
    emptyAction?: ReactNode
    /** Skeleton row count while loading. Default 5. */
    skeletonRows?: number
}

export function DataTable({
    columns,
    head,
    children,
    minWidth = 700,
    loading = false,
    error = null,
    onRetry,
    isEmpty = false,
    emptyMessage,
    emptyAction,
    skeletonRows = 5,
    ...tableProps
}: DataTableProps) {
    const { t } = useTranslation()

    let body: ReactNode
    if (error) {
        body = (
            <Table.Tr>
                <Table.Td colSpan={columns}>
                    <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                        <Group justify="space-between" wrap="nowrap" gap="sm">
                            <Text size="sm">{error}</Text>
                            {onRetry && (
                                <Button size="xs" variant="white" color="red" onClick={onRetry}>
                                    {t('common.refresh')}
                                </Button>
                            )}
                        </Group>
                    </Alert>
                </Table.Td>
            </Table.Tr>
        )
    } else if (loading) {
        body = Array.from({ length: skeletonRows }).map((_, r) => (
            <Table.Tr key={`skeleton-${r}`}>
                {Array.from({ length: columns }).map((__, c) => (
                    <Table.Td key={c}>
                        <Skeleton height={16} my={6} />
                    </Table.Td>
                ))}
            </Table.Tr>
        ))
    } else if (isEmpty) {
        body = (
            <Table.Tr>
                <Table.Td colSpan={columns}>
                    <Text c="dimmed" ta="center" pt="lg" pb={emptyAction ? 4 : 'lg'} size="sm">
                        {emptyMessage ?? t('common.noData')}
                    </Text>
                    {emptyAction && (
                        <Group justify="center" pb="lg">
                            {emptyAction}
                        </Group>
                    )}
                </Table.Td>
            </Table.Tr>
        )
    } else {
        body = children
    }

    return (
        <Table.ScrollContainer minWidth={minWidth}>
            <Table {...tableProps}>
                <Table.Thead>{head}</Table.Thead>
                <Table.Tbody>{body}</Table.Tbody>
            </Table>
        </Table.ScrollContainer>
    )
}

/**
 * Spread onto a <Table.Tr> to make a whole row behave as an accessible
 * button: clickable, keyboard-activatable (Enter/Space), focusable, and
 * announced as a button to screen readers.
 *
 *   <Table.Tr {...clickableRow(() => navigate(`/x/${id}`))}>
 */
export function clickableRow(onActivate: () => void) {
    return {
        onClick: onActivate,
        onKeyDown: (e: KeyboardEvent) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onActivate()
            }
        },
        tabIndex: 0,
        role: 'button' as const,
        style: { cursor: 'pointer' },
    }
}

/**
 * Style object for numeric cells (durations, counts, money, timers) so
 * digits use tabular figures and columns don't jitter as values update.
 * Spread into a style prop: `style={{ ...tabularNums }}` or `style={tabularNums}`.
 */
export const tabularNums = { fontVariantNumeric: 'tabular-nums' } as const
