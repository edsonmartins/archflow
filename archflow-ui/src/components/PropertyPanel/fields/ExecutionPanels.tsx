import { useTranslation } from 'react-i18next'
import type { NodeExecutionState } from '../../FlowCanvas/types'

export function ExecutionLogs({ execState }: { execState: NodeExecutionState | null }) {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  if (!execState) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        {t('editor.properties.fields.logs.noData')}
      </div>
    )
  }

  const ts = new Date(execState.startedAt ?? Date.now()).toLocaleTimeString(locale)

  return (
    <div style={{ background: 'var(--color-background-secondary)', borderRadius: 7, padding: '10px 12px', fontFamily: 'var(--font-mono)', fontSize: 11, lineHeight: 1.7 }}>
      <div style={{ color: 'var(--color-text-tertiary)' }}>{t('editor.properties.fields.logs.nodeStarted', { ts })}</div>
      {execState.status === 'success' && (
        <div style={{ color: 'var(--color-text-success)' }}>{t('editor.properties.fields.logs.completedIn', { ts, ms: execState.durationMs?.toFixed(0) })}</div>
      )}
      {execState.status === 'error' && (
        <div style={{ color: 'var(--color-text-danger)' }}>[{ts}] {execState.error}</div>
      )}
      {execState.status === 'running' && (
        <div style={{ color: 'var(--color-text-warning)' }}>{t('editor.properties.fields.logs.running', { ts })}</div>
      )}
    </div>
  )
}

// ── Output Preview ──────────────────────────────────────────────

export function OutputPreview({ execState }: { execState: NodeExecutionState | null }) {
  const { t } = useTranslation()
  if (!execState?.output) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        {t('editor.properties.fields.logs.noOutput')}
      </div>
    )
  }

  return (
    <pre style={{ background: 'var(--color-background-secondary)', borderRadius: 7, padding: '10px 12px', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--color-text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 }}>
      {JSON.stringify(execState.output, null, 2)}
    </pre>
  )
}

// ── Empty Panel ─────────────────────────────────────────────────
