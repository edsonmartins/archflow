import { ApiError, workflowApi, type WorkflowDetail, type WorkflowVersion } from '../services/api'

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

/**
 * O que o caminho de produção vai rodar e o documento dele: a versão implantada em PRODUCTION, ou o rascunho salvo
 * quando não há implantação (ou ela aponta para uma versão que não existe) — a regra do
 * {@code SpringWorkflowCrudController.execute}, como em {@link productionRunTarget}.
 *
 * <p>É o que o Rodar da lista confere antes de executar (RCHFLW-133): a checagem do editor olha o canvas, mas a
 * lista roda outro documento quando há versão publicada. Qualquer falha devolve {@code null}.</p>
 */
export async function productionRunDocument(
    workflowId: string,
): Promise<{ target: RunTarget; document: WorkflowDetail } | null> {
    try {
        let versionId: string | null = null
        try {
            versionId = (await workflowApi.deployment(workflowId, 'PRODUCTION')).versionId
        } catch (e) {
            if (!(e instanceof ApiError && e.status === 404)) return null
        }
        if (versionId) {
            // Só a versão implantada, e não a lista com o documento de todas: é um clique de Rodar.
            let version: WorkflowVersion | null = null
            try {
                version = await workflowApi.version(workflowId, versionId)
            } catch (e) {
                // Implantação que aponta para versão que não existe: o servidor roda o rascunho.
                if (!(e instanceof ApiError && e.status === 404)) return null
            }
            if (version) return { target: { kind: 'published', number: version.number }, document: version.document }
        }
        return { target: { kind: 'unpublished' }, document: await workflowApi.get(workflowId) }
    } catch {
        return null
    }
}
