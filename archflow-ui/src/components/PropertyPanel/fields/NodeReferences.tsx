import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Group, Text, UnstyledButton } from '@mantine/core'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import { CopyIconButton } from '../../CopyIconButton'
import { refExpression, upstreamRefs } from './nodeRefs'

const CODE_STYLE = {
  fontFamily:   'var(--font-mono)',
  fontSize:     11,
  background:   'var(--color-background-secondary)',
  border:       '0.5px solid var(--color-border-tertiary)',
  borderRadius: 4,
  padding:      '1px 4px',
  wordBreak:    'break-all' as const,
}

/**
 * O id do nó, só leitura. O motor grava a saída sob ele (`node-<epoch>`), e era invisível: quem
 * escrevia `${<rótulo>.campo}` via o switch cair em `default` sem nenhum erro.
 */
export function NodeIdField({ nodeId }: { nodeId: string }) {
  const { t } = useTranslation()
  return (
    <div data-testid="property-panel-node-id-field">
      <Text size="xs" fw={500} mb={2}>{t('editor.properties.fields.nodeId')}</Text>
      <Group gap={4} wrap="nowrap">
        <code data-testid="property-panel-node-id" style={{ ...CODE_STYLE, flex: 1 }}>{nodeId}</code>
        <CopyIconButton value={nodeId} />
      </Group>
      <Text size="xs" c="dimmed" mt={4}>
        {t('editor.properties.fields.nodeIdHint')}{' '}
        <code data-testid="property-panel-node-id-hint" style={CODE_STYLE}>{refExpression(nodeId, 'campo')}</code>
      </Text>
    </div>
  )
}

/**
 * Lista os nós anteriores como `rótulo → ${id.campo}` clicáveis, para a expressão de um
 * condition/switch referenciar o id certo sem digitá-lo. Sem nós anteriores, não aparece.
 */
export function UpstreamRefs({ nodeId, onInsert }: { nodeId: string; onInsert: (snippet: string) => void }) {
  const { t } = useTranslation()
  const nodes = useFlowStore(s => s.nodes)
  const edges = useFlowStore(s => s.edges)
  const refs = useMemo(() => upstreamRefs(nodeId, nodes, edges), [nodeId, nodes, edges])
  if (refs.length === 0) return null

  return (
    <div data-testid="property-panel-upstream-refs">
      <Text size="xs" fw={500} mb={2}>{t('editor.properties.fields.upstreamRefs')}</Text>
      <Text size="xs" c="dimmed" mb={4}>{t('editor.properties.fields.upstreamRefsHint')}</Text>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {refs.map(r => (
          <UnstyledButton
            key={r.snippet}
            data-testid="upstream-ref"
            onClick={() => onInsert(r.snippet)}
            style={{ fontSize: 11, display: 'flex', gap: 6, alignItems: 'baseline' }}
          >
            <span style={{ color: 'var(--color-text-secondary)' }}>{r.label} →</span>
            <code style={CODE_STYLE}>{r.snippet}</code>
          </UnstyledButton>
        ))}
      </div>
    </div>
  )
}
