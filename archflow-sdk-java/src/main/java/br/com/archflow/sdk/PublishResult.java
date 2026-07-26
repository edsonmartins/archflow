package br.com.archflow.sdk;

import java.util.Map;

/**
 * O que aconteceu ao publicar um fluxo.
 *
 * <p><b>Por que isto não é só um id.</b> {@code POST /api/workflows} <b>descarta
 * o id do corpo</b> e gera um {@code wf-<aleatório>}; {@code PUT /api/workflows/{id}}
 * respeita o id, mas devolve 404 se o fluxo ainda não existir. A API não tem,
 * hoje, um upsert por id escolhido pelo cliente.
 *
 * <p>A consequência é real e não dá para esconder atrás de um retorno
 * {@code String}: o id com que você <i>definiu</i> o fluxo na DSL pode não ser
 * o id com que ele <i>ficou</i> no servidor. Quem publica precisa guardar o
 * {@link #assignedId()} em algum lugar para conseguir atualizar o mesmo fluxo
 * depois — senão cada publicação cria uma cópia nova.
 *
 * @param requestedId id que a DSL declarou
 * @param assignedId  id com que o servidor de fato gravou; igual ao
 *                    {@code requestedId} numa atualização, diferente numa criação
 * @param created     {@code true} se um fluxo novo foi criado, {@code false} se
 *                    um existente foi atualizado
 * @param body        a resposta do servidor, como veio
 * @since 1.1.0
 */
public record PublishResult(String requestedId, String assignedId, boolean created,
                            Map<String, Object> body) {

    /**
     * {@code true} quando o servidor gravou o fluxo com um id diferente do
     * declarado — o caso em que anotar o {@link #assignedId()} deixa de ser
     * opcional.
     */
    public boolean idChanged() {
        return !java.util.Objects.equals(requestedId, assignedId);
    }
}
