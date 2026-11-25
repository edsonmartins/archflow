/**
 * Formatação do consumo medido (RCHFLW-75). O custo vem da tabela de preços do servidor, em reais;
 * `null` quer dizer "sem preço declarado" — nunca é mostrado como zero, que seria "de graça".
 */

/** Tokens compactos: 4.3M, 50K, 900. */
export const compactTokens = (n: number): string =>
  n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${Math.round(n / 1_000)}K` : String(n)

/**
 * Reais com até quatro casas: quase toda chamada custa fração de centavo, e duas casas mostrariam
 * `R$ 0,00` para um gasto real.
 */
export function formatReais(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'BRL', minimumFractionDigits: 2, maximumFractionDigits: 4,
  }).format(value)
}

/** Dólares declarados pelo provedor, com até quatro casas. */
export function formatUsd(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4,
  }).format(value)
}

/** `yyyy-MM` do mês corrente e dos onze anteriores — antes a lista era fixa em 2026-01..04. */
export function recentMonths(now: Date = new Date(), count = 12): string[] {
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  })
}
