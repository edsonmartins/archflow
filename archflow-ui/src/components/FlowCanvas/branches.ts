/**
 * Ramos de uma aresta.
 *
 * Os nós de controle ({@code condition}, {@code switch}) e o de decisão ({@code decision}) têm mais de
 * uma saída, e cada aresta que sai deles declara o seu {@code branch}. O backend converte o ramo na
 * condição da aresta (`${id.branch} == 'x'`, ou `${id.route} == 'AUTO'` para a decisão) — a regra mora
 * lá, num lugar só; aqui só se escolhe o ramo.
 */

/** As rotas que o nó `decision` devolve, na ordem em que aparecem ao conectar. */
export const DECISION_ROUTES = ['AUTO', 'REVIEW', 'ESCALATE'] as const

/** Os ramos que um nó oferece; vazio quando o nó não ramifica. */
export function branchOptions(nodeType: string, config: Record<string, unknown> | undefined): string[] {
  switch (nodeType) {
    case 'condition':
      return ['true', 'false']
    case 'switch': {
      const raw = config?.switchCases
      const cases = typeof raw === 'string'
        ? raw.split(/\r?\n/)
        : Array.isArray(raw) ? raw.map(String) : []
      const unique = [...new Set(cases.map(c => c.trim()).filter(Boolean))]
      return unique.includes('default') ? unique : [...unique, 'default']
    }
    case 'decision':
      return [...DECISION_ROUTES]
    default:
      return []
  }
}

/** O primeiro ramo ainda não usado pelas arestas que já saem do nó; vazio se não houver livre. */
export function nextFreeBranch(options: string[], used: Array<string | undefined>): string | undefined {
  const taken = new Set(used.filter((b): b is string => !!b))
  return options.find(o => !taken.has(o))
}
