import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Select, Text } from '@mantine/core'
import { FIELD_STYLES } from '../fieldStyles'
import { storedChoice } from './helpers'
import type { FieldProps } from './FieldProps'
import { knowledgeApi, type KnowledgeBase } from '../../../services/knowledge-api'
import {
  KNOWLEDGE_BASE_KEY, LEGACY_KNOWLEDGE_BASE_KEY, knowledgeBaseOf,
} from '../../FlowCanvas/dataNodeKeys'

/**
 * Seletor de Base de conhecimento dos nós vector-search e rag (RCHFLW-55). Lista as bases do workspace pela mesma
 * API da tela de Conhecimento; a execução busca nelas pelo KnowledgeService, no tenant de quem executa. Sem base, o
 * nó segue para o backend de vetores.
 *
 * <p>Uma base gravada que não aparece na lista (apagada, ou de outro workspace — um fluxo importado) é avisada:
 * o motor falha a execução com a mesma razão, e o painel não pode fingir que está tudo certo.</p>
 */
export function KnowledgeBaseField({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const [bases, setBases] = useState<KnowledgeBase[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    knowledgeApi.listBases()
      .then(list => { if (alive) setBases(Array.isArray(list) ? list : []) })
      .catch(() => { if (alive) setFailed(true) })
    return () => { alive = false }
  }, [])

  const stored = knowledgeBaseOf(nodeData.config)
  const legacy = storedChoice(nodeData, KNOWLEDGE_BASE_KEY) == null && stored != null
  const inList = stored != null && bases != null && bases.some(b => b.id === stored)
  // O aviso de base inexistente só com a lista carregada: enquanto carrega, ou se falhou, não se sabe.
  const missing = stored != null && bases != null && !inList

  const choose = (v: string | null) => {
    update(KNOWLEDGE_BASE_KEY, v ?? undefined)
    // Trocar a base de um nó de template antigo não pode deixar a chave velha apontando para outra.
    if (legacy) update(LEGACY_KNOWLEDGE_BASE_KEY, undefined)
  }

  return (
    <>
      <Select
        label={f('knowledgeBase')}
        description={f('knowledgeBaseHint')}
        placeholder={f('knowledgeBasePlaceholder')}
        value={stored}
        onChange={choose}
        clearable
        searchable
        size="xs"
        data={[
          ...(bases ?? []).map(b => ({ value: b.id, label: b.name })),
          // O id gravado continua visível no select sempre que a lista não o tem — também enquanto ela carrega ou
          // quando falhou: o Select do Mantine mostra vazio (o placeholder "Nenhuma") para um valor fora do data,
          // e o painel diria que não há base num nó que o motor vai executar com uma.
          ...(stored && !inList ? [{ value: stored, label: stored }] : []),
        ]}
        styles={FIELD_STYLES}
        data-testid="knowledge-base-select"
      />
      {failed && (
        <Text size="xs" c="red" data-testid="knowledge-base-load-error">{f('knowledgeBaseLoadError')}</Text>
      )}
      {missing && (
        <Alert color="orange" variant="light" p="xs" data-testid="knowledge-base-missing">
          <Text size="xs">{f('knowledgeBaseMissing', { value: stored })}</Text>
        </Alert>
      )}
    </>
  )
}
