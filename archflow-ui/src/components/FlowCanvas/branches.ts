/**
 * Ramos de uma aresta.
 *
 * Os nós de controle ({@code condition}, {@code switch}) e o de decisão ({@code decision}) têm mais de
 * uma saída, e cada aresta que sai deles declara o seu {@code branch}. O backend converte o ramo na
 * condição da aresta (`${id.branch} == 'x'`, ou `${id.route} == 'AUTO'` para a decisão) — a regra mora
 * lá, num lugar só; aqui só se escolhe o ramo.
 */

/** As rotas que o nó `decision` devolve, na ordem em que aparecem no seletor da aresta. */
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

/**
 * O ramo que uma aresta nova recebe sem o usuário escolher.
 *
 * {@code condition} e {@code switch} seguem ganhando o primeiro ramo livre: lá a aresta sem ramo não
 * tem sentido e o ramo é visível no nó. A {@code decision} não ganha nenhum — o ramo vira a condição
 * `${id.route} == 'AUTO'` no backend, e ligar a primeira aresta já condicionada a AUTO fazia o passo
 * seguinte só rodar quando a decisão saísse confiante, sem o usuário ter escolhido isso. A aresta fica
 * incondicional até alguém escolher a rota no seletor da aresta.
 */
export function defaultBranch(
  nodeType: string,
  config: Record<string, unknown> | undefined,
  used: Array<string | undefined>,
): string | undefined {
  if (nodeType === 'decision') return undefined
  return nextFreeBranch(branchOptions(nodeType, config), used)
}

/**
 * Os casos declarados de um switch, na ordem do campo, sem vazios, sem repetidos e sem o "default" — que o nó sempre
 * oferece, escrito ou não (branchOptions): escrevê-lo ou apagá-lo não muda caso nenhum.
 */
function declaredCases(config: Record<string, unknown> | undefined): string[] {
  return branchOptions('switch', config).filter(c => c !== 'default')
}

/**
 * O caso de um switch que foi renomeado entre duas configs, ou null quando a troca não é um rename sem ambiguidade.
 *
 * <p>RCHFLW-147: trocar "arrependimento" por "arrependido" nos casos deixava a aresta no ramo antigo — que nenhum
 * valor alcança, porque o switch só grava um caso declarado ou "default" — e a execução que caía no caso novo
 * terminava COMPLETED sem fazer nada. Só é rename quando a lista tem o mesmo tamanho e muda exatamente uma posição:
 * aí o caso novo ocupa o lugar do antigo e a aresta acompanha. Qualquer outra troca (caso apagado, inserido, dois
 * mudados, ordem trocada) não diz de onde veio cada caso; a aresta fica onde estava e o editor a acusa como ramo
 * não declarado (FlowEdge e runBlockers), em vez de adivinhar.</p>
 */
export function renamedCase(
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
): { from: string; to: string } | null {
  const old = declaredCases(before)
  const now = declaredCases(after)
  if (old.length !== now.length) return null
  const changed = old.map((c, i) => i).filter(i => old[i] !== now[i])
  if (changed.length !== 1) return null
  const i = changed[0]
  return { from: old[i], to: now[i] }
}

/**
 * O ramo declarado numa aresta que o nó de origem não oferece, ou null quando a aresta está bem. Só conta o ramo
 * que vale: com uma condição escrita à mão, é ela que o backend segue (BranchCondition), e a aresta de erro não tem
 * ramo. Um nó que não ramifica ignora o ramo, como o backend.
 *
 * <p>O ramo é comparado como a BranchCondition o lê, sem apóstrofos: a aresta "d'água" vira a condição
 * `== 'dágua'`, que o caso "d'água" nunca satisfaz. O ExecutableNodesValidator faz a mesma conta, e o aviso do
 * editor e a recusa do servidor dão a mesma resposta. Devolve o ramo como está gravado na aresta.</p>
 */
export function undeclaredBranch(
  nodeType: string,
  config: Record<string, unknown> | undefined,
  edge: { branch?: string; condition?: string; isErrorPath?: boolean },
): string | null {
  const branch = typeof edge.branch === 'string' ? edge.branch.trim() : ''
  if (!branch || edge.isErrorPath || (typeof edge.condition === 'string' && edge.condition.trim() !== '')) return null
  const options = branchOptions(nodeType, config)
  if (options.length === 0) return null
  return options.includes(branch.replace(/'/g, '')) ? null : branch
}
