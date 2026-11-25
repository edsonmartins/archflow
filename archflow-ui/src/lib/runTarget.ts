import { ApiError, workflowApi } from '../services/api'

/**
 * O que um botão de executar vai rodar (RCHFLW-89).
 *
 * <p>O mesmo fluxo roda coisas diferentes conforme o caminho: o Executar do editor (AG-UI) roda o
 * rascunho — o documento salvo —, e o Rodar da lista, o Executar novamente e a API
 * ({@code POST /workflows/{id}/execute}) rodam a versão implantada em PRODUCTION, ou o rascunho quando o
 * fluxo não tem versão lá (a janela de compatibilidade do backend). Sem dizer isso, o usuário apagava um
 * nó, salvava, rodava pela lista e via o nó apagado rodar.</p>
 */
export type RunTarget =
    /** Executar do editor: o rascunho, mesmo com versão publicada. */
    | { kind: 'draft' }
    /** Caminho de produção, mas sem versão implantada: o backend roda o rascunho. */
    | { kind: 'unpublished' }
    /** Caminho de produção com versão implantada: roda essa versão, não o rascunho. */
    | { kind: 'published'; number: number }

/**
 * O que o caminho de produção ({@code /execute}) vai rodar, com os dados que a API já devolve: a
 * implantação de PRODUCTION e a lista de versões do fluxo.
 *
 * <p>Espelha a regra do {@code SpringWorkflowCrudController.execute}: sem implantação (404), ou com uma
 * que aponta para uma versão que não existe, roda o rascunho. Qualquer outra falha devolve {@code null}
 * — a tela não afirma o que não sabe.</p>
 */
export async function productionRunTarget(workflowId: string): Promise<RunTarget | null> {
    let versionId: string
    try {
        versionId = (await workflowApi.deployment(workflowId, 'PRODUCTION')).versionId
    } catch (e) {
        return e instanceof ApiError && e.status === 404 ? { kind: 'unpublished' } : null
    }
    try {
        const version = (await workflowApi.versions(workflowId)).find((v) => v.id === versionId)
        return version ? { kind: 'published', number: version.number } : { kind: 'unpublished' }
    } catch {
        return null
    }
}
