/**
 * Shared display formatters. These were copy-pasted across half a dozen
 * pages (execution history/detail, dashboard, tool calls, approvals) and
 * had already drifted in precision/null handling — this module is the
 * single source of truth.
 */

/** 950 → "950ms", 8_500 → "8.5s", 65_000 → "1m 5s". Null-safe: returns "—". */
export function formatDuration(ms: number | null | undefined): string {
    if (ms == null) return '—'
    if (ms < 1000) return `${Math.round(ms)}ms`
    const seconds = ms / 1000
    if (seconds < 60) return `${seconds.toFixed(seconds >= 10 ? 0 : 1)}s`
    const min = Math.floor(seconds / 60)
    const rem = Math.round(seconds % 60)
    return `${min}m ${rem}s`
}

/** Locale-aware date+time for an ISO string; returns "—" for missing values. */
export function formatInstant(iso: string | null | undefined, locale: string): string {
    if (!iso) return '—'
    try {
        return new Date(iso).toLocaleString(locale)
    } catch {
        return iso
    }
}
