import { catalogApi } from '../services/catalog-api'
import { NODE_TYPE_TO_CATEGORY, isAnnotationStep } from '../components/FlowCanvas/constants'

/**
 * Nós de tipo que nem o designer nem o servidor conhecem (RCHFLW-134).
 *
 * <p>Um passo escrito no YAML com {@code type: foo-bar} era aceito pelo Salvar YAML e virava, no motor, um passo
 * de componente do catálogo: o fluxo rodava os passos anteriores (gastando o LLM) e só falhava ao chegar nele,
 * com "component not found". O Salvar YAML passa a dar o mesmo aviso que o Executar dá aos nós sem execução.</p>
 *
 * <p>"Desconhecido" pede o catálogo do servidor: o designer não sabe quais plugins estão carregados, e um
 * componente de plugin é um tipo válido (PortableFlowStepFactory cai no catálogo pelo componentId, ou pelo
 * type sem ele). Conhecidos são os tipos da paleta (NODE_TYPE_TO_CATEGORY), os que o motor resolve sem catálogo
 * e a paleta não oferece, e os ids de agentes, assistentes e ferramentas do catálogo.</p>
 */

/**
 * Tipos que o motor executa sem passar pelo catálogo e a paleta não lista: os aliases de adapter
 * (AdapterNodeTypes: {@code chat}, {@code vectorstore}) e o agente MCP (ServerNodes.MCP_AGENT_COMPONENT_ID).
 */
const ENGINE_NODE_TYPES: ReadonlySet<string> = new Set(['chat', 'vectorstore', 'mcp-agent'])

export interface UnknownNodeStep {
  id: string
  label?: string
  componentId?: string
  type?: string
}

/** O tipo pelo qual o motor resolve o passo: o componentId e, sem ele, o type (como o kindOf do Executar). */
function kindOf(step: UnknownNodeStep): string {
  const raw = [step.componentId, step.type].find(k => typeof k === 'string' && k.trim() !== '')
  return (raw ?? '').trim().toLowerCase()
}

/**
 * Os passos de tipo desconhecido, no formato do {@code notExecutable} do runBlockers (para o aviso ser o mesmo).
 * {@code null} quando o catálogo não respondeu: sem ele não dá para dizer que um tipo não existe, e um aviso
 * falso sobre um plugin carregado seria pior do que nenhum.
 */
export async function unknownNodeTypeSteps(
  steps: UnknownNodeStep[],
): Promise<{ id: string; label: string; type: string }[] | null> {
  const candidates = steps.filter(step => {
    if (isAnnotationStep(step)) return false
    const kind = kindOf(step)
    return kind !== '' && !(kind in NODE_TYPE_TO_CATEGORY) && !ENGINE_NODE_TYPES.has(kind)
  })
  if (candidates.length === 0) return []
  let catalog: Set<string>
  try {
    const lists = await Promise.all([catalogApi.listAgents(), catalogApi.listAssistants(), catalogApi.listTools()])
    catalog = new Set(lists.flat().map(item => item.id.trim().toLowerCase()))
  } catch {
    return null
  }
  return candidates
    .filter(step => !catalog.has(kindOf(step)))
    .map(step => ({ id: step.id, label: step.label?.trim() || step.id, type: kindOf(step) }))
}
