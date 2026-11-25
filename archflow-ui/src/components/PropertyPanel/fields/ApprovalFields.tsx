import { useTranslation } from 'react-i18next'
import { Alert, NumberInput, Text, TextInput, Textarea } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import { FIELD_STYLES, MONO_INPUT } from '../fieldStyles'
import { getConfig } from './helpers'
import type { FieldProps } from './FieldProps'

/** A única ação de prazo vencido que o motor aceita (ver HumanApprovalStep). */
const ON_TIMEOUT_REJECT = 'reject'

/**
 * Campos do nó de Aprovação humana. Cada chave aqui é lida pelo `HumanApprovalStep` — antes o
 * designer gravava estas chaves e o motor lia outras, então a fila mostrava a descrição vazia, a
 * saída do passo anterior no lugar da proposta e o prazo global no lugar do do nó (RCHFLW-60).
 *
 * <p>Sem valores-padrão inventados: título, descrição e proposta mostram só o que o nó tem (o que
 * aparece vazio vai vazio ao motor), e o prazo vazio quer dizer "o global do servidor" — o "30" que
 * aparecia antes nunca chegava ao motor.</p>
 *
 * <p>Não há escolha do que acontece no prazo vencido: vencer quer dizer que ninguém olhou, e
 * "ninguém olhou" nunca pode virar "pode executar". O motor sempre recusa; a opção "Aprovação
 * automática" prometia o que, por segurança, não existe. Um nó antigo que ainda a tenha gravada
 * ganha um aviso — o motor a ignora.</p>
 */
export function ApprovalFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const legacyOnTimeout = nodeData.config?.approvalOnTimeout
  const hasLegacyOnTimeout = legacyOnTimeout != null && legacyOnTimeout !== ''
    && String(legacyOnTimeout).trim().toLowerCase() !== ON_TIMEOUT_REJECT

  return (
    <>
      <TextInput
        label={f('approvalTitle')}
        description={f('approvalTitleHint')}
        value={getConfig(nodeData, 'approvalTitle', '')}
        onChange={e => update('approvalTitle', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
      <Textarea
        label={f('approvalDescription')}
        value={getConfig(nodeData, 'approvalDescription', '')}
        onChange={e => update('approvalDescription', e.currentTarget.value)}
        autosize minRows={2}
        size="xs"
        styles={FIELD_STYLES}
        description={f('approvalDescriptionHint')}
      />
      <Textarea
        label={f('proposalPayload')}
        value={getConfig(nodeData, 'approvalProposal', '')}
        onChange={e => update('approvalProposal', e.currentTarget.value)}
        autosize minRows={2}
        size="xs"
        styles={MONO_INPUT}
        description={f('proposalPayloadHint')}
        data-testid="approval-proposal-input"
      />
      <NumberInput
        label={f('timeoutMin')}
        description={f('timeoutMinHint')}
        placeholder={f('timeoutMinPlaceholder')}
        value={getConfig<number | string>(nodeData, 'approvalTimeoutMinutes', '')}
        onChange={v => update('approvalTimeoutMinutes', v)}
        min={1} max={24 * 60}
        size="xs"
        styles={FIELD_STYLES}
        data-testid="approval-timeout-input"
      />
      <div>
        <Text size="xs" fw={500}>{f('onTimeout')}</Text>
        <Text size="xs" c="dimmed" data-testid="approval-on-timeout">{f('onTimeoutAlwaysReject')}</Text>
      </div>
      {hasLegacyOnTimeout && (
        <Alert color="orange" variant="light" p="xs" icon={<IconAlertTriangle size={14} />}
               data-testid="approval-on-timeout-legacy">
          <Text size="xs">{f('onTimeoutLegacy', { value: String(legacyOnTimeout) })}</Text>
        </Alert>
      )}
    </>
  )
}
