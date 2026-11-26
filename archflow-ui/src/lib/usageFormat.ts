/**
 * Formatação do consumo medido (RCHFLW-75). O custo vem da tabela de preços do servidor, em reais;
 * `null` quer dizer "sem preço declarado" — nunca é mostrado como zero, que seria "de graça".
 */

/** Tokens compactos: 4.3M, 50K, 900. */
export const compactTokens = (n: number): string =>
  n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${Math.round(n / 1_000)}K` : String(n)

/** O menor valor que quatro casas mostram; um custo positivo abaixo dele vira `< R$ 0,0001`. */
const MENOR_REAIS_VISIVEL = 0.0001

/**
 * Reais com até quatro casas: quase toda chamada custa fração de centavo, e duas casas mostrariam
 * `R$ 0,00` para um gasto real.
 *
 * Abaixo de R$ 0,0001 nem quatro casas bastam: 14 + 50 tokens de um modelo barato custam
 * R$ 0,0000214 e apareciam como `R$ 0,00` (RCHFLW-105). Um custo positivo nunca é mostrado como
 * zero — zero quer dizer "de graça", e aqui houve preço e gasto. Zero de verdade continua `R$ 0,00`.
 */
export function formatReais(value: number, locale: string): string {
  const fmt = new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'BRL', minimumFractionDigits: 2, maximumFractionDigits: 4,
  })
  if (value > 0 && value < MENOR_REAIS_VISIVEL) return `< ${fmt.format(MENOR_REAIS_VISIVEL)}`
  return fmt.format(value)
}

/** Dólares declarados pelo provedor, com até quatro casas. */
export function formatUsd(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 4,
  }).format(value)
}

/**
 * Fatia do total em %, com uma casa. Um tenant que usou de verdade, mas menos de 0,1% do total, aparecia
 * como `0%` — o mesmo de quem não usou nada (RCHFLW-195 item 7). Positivo abaixo de 0,1 vira `< 0,1%`;
 * o servidor manda o valor sem arredondar para a tela conseguir distinguir.
 */
export function formatShare(pct: number | null | undefined, locale: string): string {
  if (pct == null) return ''
  const fmt = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 })
  if (pct > 0 && pct < 0.1) return `< ${fmt.format(0.1)}%`
  return `${fmt.format(pct)}%`
}

/** `yyyy-MM` do mês corrente e dos onze anteriores — antes a lista era fixa em 2026-01..04. */
export function recentMonths(now: Date = new Date(), count = 12): string[] {
  return Array.from({ length: count }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  })
}
