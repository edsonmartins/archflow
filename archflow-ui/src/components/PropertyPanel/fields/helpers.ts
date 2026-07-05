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

export function getConfig<T>(data: FlowNodeData, key: string, fallback: T): T extends string ? string : T {
  return ((data.config?.[key] as T) ?? fallback) as T extends string ? string : T
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
