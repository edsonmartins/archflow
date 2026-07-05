import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Paper, TextInput, Text, UnstyledButton, ScrollArea } from '@mantine/core'
import { IconSearch } from '@tabler/icons-react'
import { NODE_CATEGORIES } from './constants'
import { NodeIcon } from './nodeIcons'
import { searchPaletteEntries } from './paletteSearch'

/**
 * Searchable node menu shown when the user drops a connection on empty
 * canvas (n8n-style "release to add"): picking an entry creates the node
 * at the drop point and wires it to the origin. Search semantics are
 * shared with the sidebar palette via {@link searchPaletteEntries}.
 */
export function AddNodeMenu({
    left, top, onPick, onClose,
}: {
    left: number
    top: number
    onPick: (info: { componentId: string; label: string }) => void
    onClose: () => void
}) {
    const { t } = useTranslation()
    const [query, setQuery] = useState('')

    const entries = useMemo(
        () => searchPaletteEntries(t, query, { includeAnnotations: false }),
        [query, t])

    return (
        <Paper
            role="dialog"
            aria-label={t('editor.canvasActions.addNode')}
            withBorder
            shadow="lg"
            radius="md"
            style={{
                position: 'absolute',
                left: Math.max(8, left),
                top: Math.max(8, top),
                zIndex: 20,
                width: 252,
                overflow: 'hidden',
            }}
            onKeyDown={(e) => {
                if (e.key === 'Escape') onClose()
                if (e.key === 'Enter' && entries.length > 0) onPick(entries[0])
            }}
        >
            <TextInput
                autoFocus
                size="xs"
                m={8}
                value={query}
                onChange={(e) => setQuery(e.currentTarget.value)}
                placeholder={t('editor.palette.search')}
                aria-label={t('editor.palette.search')}
                leftSection={<IconSearch size={13} />}
            />
            <ScrollArea.Autosize mah={240} p={4}>
                {entries.length === 0 && (
                    <Text size="xs" c="dimmed" p="sm">
                        {t('editor.palette.empty')}
                    </Text>
                )}
                {entries.map(n => {
                    const cat = NODE_CATEGORIES[n.category]
                    return (
                        <UnstyledButton
                            key={n.componentId}
                            onClick={() => onPick(n)}
                            className="af-add-node-option"
                            style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: 8,
                                width: '100%',
                                padding: '6px 8px',
                                borderRadius: 7,
                            }}
                        >
                            <span
                                style={{
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    width: 24, height: 24, borderRadius: 6, flexShrink: 0,
                                    background: cat.colorLight, color: cat.colorDark,
                                }}
                            >
                                <NodeIcon componentId={n.componentId} size={14} />
                            </span>
                            <span style={{ minWidth: 0 }}>
                                <Text size="xs" fw={500} component="span" display="block">{n.label}</Text>
                                <Text size="xs" c="dimmed" component="span" display="block" truncate>
                                    {n.description}
                                </Text>
                            </span>
                        </UnstyledButton>
                    )
                })}
            </ScrollArea.Autosize>
        </Paper>
    )
}
