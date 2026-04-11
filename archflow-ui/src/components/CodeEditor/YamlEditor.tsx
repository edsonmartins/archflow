import { useCallback, useMemo, useRef } from 'react';

interface YamlEditorProps {
    value: string;
    onChange: (value: string) => void;
    readOnly?: boolean;
    /** Height in px or any valid CSS value. Defaults to `100%`. */
    height?: number | string;
    /** Optional validation error rendered below the editor. */
    error?: string | null;
}

/**
 * Lightweight code editor for YAML without pulling in CodeMirror or
 * Monaco. The editor is a styled {@code <textarea>} with a synchronized
 * line-number gutter rendered inline via flexbox.
 *
 * <p>Why not CodeMirror? The archflow-ui bundle is already ~1.2 MB — a
 * full editor would add another 400-600 KB of code split. For the YAML
 * round-trip use case (read/write a few hundred lines max) a native
 * textarea with DM Mono + tab handling is perfectly usable and adds
 * zero runtime dependencies.
 */
export default function YamlEditor({
    value,
    onChange,
    readOnly = false,
    height = '100%',
    error,
}: YamlEditorProps) {
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    const lineCount = useMemo(() => {
        if (!value) return 1;
        return Math.max(1, value.split('\n').length);
    }, [value]);

    const lineNumbers = useMemo(() => {
        const n = lineCount;
        const rows: string[] = [];
        for (let i = 1; i <= n; i++) rows.push(String(i));
        return rows.join('\n');
    }, [lineCount]);

    const handleKeyDown = useCallback(
        (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
            if (readOnly) return;
            // Soft-indent on Tab
            if (e.key === 'Tab') {
                e.preventDefault();
                const ta = e.currentTarget;
                const { selectionStart: start, selectionEnd: end } = ta;
                const next = value.slice(0, start) + '  ' + value.slice(end);
                onChange(next);
                // Restore caret after React re-render
                requestAnimationFrame(() => {
                    if (textareaRef.current) {
                        textareaRef.current.selectionStart =
                            textareaRef.current.selectionEnd = start + 2;
                    }
                });
            }
        },
        [value, onChange, readOnly],
    );

    return (
        <div
            data-testid="yaml-editor"
            style={{
                display: 'flex',
                flexDirection: 'column',
                height,
                border: '1px solid var(--mantine-color-gray-3)',
                borderRadius: 6,
                overflow: 'hidden',
                fontFamily: 'DM Mono, ui-monospace, monospace',
                fontSize: 12,
                background: '#0f172a',
                color: '#e2e8f0',
            }}
        >
            <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
                <pre
                    aria-hidden="true"
                    style={{
                        margin: 0,
                        padding: '12px 8px 12px 14px',
                        textAlign: 'right',
                        color: '#64748b',
                        background: '#0b1220',
                        borderRight: '1px solid #1f2a44',
                        userSelect: 'none',
                        overflow: 'hidden',
                        fontFamily: 'inherit',
                        fontSize: 'inherit',
                        lineHeight: '1.6',
                        minWidth: 36,
                    }}
                >
                    {lineNumbers}
                </pre>
                <textarea
                    ref={textareaRef}
                    value={value}
                    onChange={(e) => onChange(e.currentTarget.value)}
                    onKeyDown={handleKeyDown}
                    readOnly={readOnly}
                    spellCheck={false}
                    style={{
                        flex: 1,
                        border: 'none',
                        outline: 'none',
                        resize: 'none',
                        padding: '12px 14px',
                        background: 'transparent',
                        color: 'inherit',
                        fontFamily: 'inherit',
                        fontSize: 'inherit',
                        lineHeight: '1.6',
                        whiteSpace: 'pre',
                    }}
                />
            </div>
            {error && (
                <div
                    role="alert"
                    style={{
                        background: '#7f1d1d',
                        color: '#fecaca',
                        padding: '6px 12px',
                        fontSize: 11,
                        borderTop: '1px solid #991b1b',
                    }}
                >
                    {error}
                </div>
            )}
        </div>
    );
}
