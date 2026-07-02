import { useState, type ReactNode } from 'react'
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react'
import { CopyIconButton } from './CopyIconButton'

/**
 * Collapsible JSON tree with a copy button — the shared replacement for
 * raw `JSON.stringify` dumps in user-facing screens. Objects/arrays
 * expand on click (auto-expanded down to `autoExpandDepth`); primitives
 * are color-coded via the semantic CSS tokens so the tree is readable
 * in both light and dark mode.
 */
export function JsonViewer({
    value,
    autoExpandDepth = 1,
    testId,
}: {
    value: unknown
    autoExpandDepth?: number
    testId?: string
}) {
    return (
        <div
            data-testid={testId}
            style={{
                position: 'relative',
                fontFamily: 'var(--font-mono)',
                fontSize: 12,
                lineHeight: 1.6,
                background: 'var(--color-background-secondary)',
                border: '1px solid var(--color-border-tertiary)',
                borderRadius: 8,
                padding: '8px 36px 8px 10px',
                overflowX: 'auto',
            }}
        >
            <CopyIconButton
                value={safeStringify(value)}
                style={{ position: 'absolute', top: 6, right: 6 }}
            />
            <JsonNode value={value} depth={0} autoExpandDepth={autoExpandDepth} />
        </div>
    )
}

function safeStringify(value: unknown): string {
    try {
        return JSON.stringify(value, null, 2) ?? String(value)
    } catch {
        return String(value)
    }
}

function JsonNode({
    value, depth, autoExpandDepth, propertyKey,
}: {
    value: unknown
    depth: number
    autoExpandDepth: number
    propertyKey?: string
}) {
    const [open, setOpen] = useState(depth < autoExpandDepth)

    const keyLabel = propertyKey !== undefined && (
        <span style={{ color: 'var(--color-text-secondary)' }}>{propertyKey}: </span>
    )

    if (value === null || value === undefined) {
        return <Line indent={depth}>{keyLabel}<span style={{ color: 'var(--text4, #9CA3AF)' }}>null</span></Line>
    }
    if (typeof value === 'string') {
        return <Line indent={depth}>{keyLabel}<span style={{ color: 'var(--green)' }}>"{value}"</span></Line>
    }
    if (typeof value === 'number') {
        return <Line indent={depth}>{keyLabel}<span style={{ color: 'var(--blue)' }}>{String(value)}</span></Line>
    }
    if (typeof value === 'boolean') {
        return <Line indent={depth}>{keyLabel}<span style={{ color: 'var(--purple)' }}>{String(value)}</span></Line>
    }

    const isArray = Array.isArray(value)
    const entries = isArray
        ? (value as unknown[]).map((v, i) => [String(i), v] as const)
        : Object.entries(value as Record<string, unknown>)

    if (entries.length === 0) {
        return <Line indent={depth}>{keyLabel}{isArray ? '[]' : '{}'}</Line>
    }

    return (
        <div>
            <Line indent={depth}>
                <button
                    type="button"
                    onClick={() => setOpen(o => !o)}
                    aria-expanded={open}
                    style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 2,
                        border: 'none',
                        background: 'transparent',
                        padding: 0,
                        cursor: 'pointer',
                        font: 'inherit',
                        color: 'inherit',
                    }}
                >
                    {open ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
                    {keyLabel}
                    <span style={{ color: 'var(--color-text-tertiary)' }}>
                        {isArray ? `[${entries.length}]` : `{${entries.length}}`}
                    </span>
                </button>
            </Line>
            {open && entries.map(([k, v]) => (
                <JsonNode
                    key={k}
                    propertyKey={isArray ? undefined : k}
                    value={v}
                    depth={depth + 1}
                    autoExpandDepth={autoExpandDepth}
                />
            ))}
        </div>
    )
}

function Line({ indent, children }: { indent: number; children: ReactNode }) {
    return (
        <div style={{ paddingLeft: indent * 14, whiteSpace: 'nowrap' }}>
            {children}
        </div>
    )
}
