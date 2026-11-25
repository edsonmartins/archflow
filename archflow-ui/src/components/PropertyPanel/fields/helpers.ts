import type { FlowNodeData } from '../../FlowCanvas/types'
import type { ProviderInfo } from '../../../services/workflow-config-api'

/**
 * Drops options with a repeated `value`, keeping the first. Mantine 9 throws
 * "Duplicate options are not supported" when a Select receives two options
 * with the same value — which the backend catalog can produce.
 */
export function dedupeByValue<T extends { value: string }>(options: T[]): T[] {
  const seen = new Set<string>()
  return options.filter(o => (seen.has(o.value) ? false : (seen.add(o.value), true)))
}

/**
 * Valor da opção "herdar" nos selects de provider e modelo. Escolhê-la REMOVE a chave do config do
 * nó — ausência é o que o backend entende como "use o padrão do fluxo / da plataforma".
 */
export const INHERIT = '__inherit__'

export function getConfig<T>(data: FlowNodeData, key: string, fallback: T): T extends string ? string : T {
  return ((data.config?.[key] as T) ?? fallback) as T extends string ? string : T
}

// ── O que está GRAVADO no nó (RCHFLW-92) ─────────────────────────
// O painel usava getConfig(nó, chave, exemplo) só para exibir: cerca de 60 campos mostravam um valor que nunca
// ia para o documento — a Condição aparecia com uma expressão e o motor recebia {}, e falhava pedindo a
// expressão. Os campos leem por estes helpers, que não têm padrão de exibição: vazio é vazio, e o exemplo vai no
// placeholder (exampleValue). "padrão: X" (defaultValue) só onde o motor de fato usa X quando o campo falta.

/** Texto gravado, ou '' quando não há. */
export function storedText(data: FlowNodeData, key: string): string {
  const v = data.config?.[key]
  return typeof v === 'string' ? v : v == null ? '' : String(v)
}

/** Número gravado, ou '' (o vazio do NumberInput). */
export function storedNumber(data: FlowNodeData, key: string): number | '' {
  const v = data.config?.[key]
  return typeof v === 'number' && Number.isFinite(v) ? v : ''
}

/** Opção gravada de um Select, ou null (o Select mostra o placeholder). */
export function storedChoice(data: FlowNodeData, key: string): string | null {
  const v = data.config?.[key]
  return typeof v === 'string' && v !== '' ? v : null
}

/** Booleano gravado: só true quando o nó tem true — um Switch ligado sem nada gravado era um estado falso. */
export function storedFlag(data: FlowNodeData, key: string): boolean {
  return data.config?.[key] === true
}

/**
 * onChange de NumberInput: grava o número; campo apagado remove a chave. Gravar '' fazia o motor ler texto onde
 * espera número (e o Number('') de outros caminhos virava 0).
 */
export function numberUpdater(update: (key: string, value: unknown) => void, key: string) {
  return (v: number | string) => {
    if (typeof v === 'number') update(key, v)
    else if (v === '') update(key, undefined)
  }
}

export function findProvider(providers: ProviderInfo[], providerId: string) {
  return providers.find(p => p.id === providerId)
}

export function findModel(providers: ProviderInfo[], providerId: string, modelId: string) {
  return findProvider(providers, providerId)?.models.find(m => m.id === modelId)
}

export function ctxLabel(ctx: number) {
  return ctx >= 1_000_000 ? `${(ctx / 1_000_000).toFixed(1)}M` : `${Math.round(ctx / 1000)}k`
}
