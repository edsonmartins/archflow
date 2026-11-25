package br.com.archflow.api.apikey.dto;

import br.com.archflow.model.security.ApiKeyScope;

/**
 * Um escopo que uma chave de API pode receber (RCHFLW-83).
 *
 * <p>{@code name} é o valor que {@code POST /api/apikeys} aceita e que as respostas devolvem em
 * {@code scopes} — o nome da constante de {@link ApiKeyScope}. É o único vocabulário: a UI monta o
 * seletor a partir desta lista em vez de manter a sua, que divergiu ({@code workflow.read}) e fazia
 * toda criação falhar com 400.
 *
 * @param name        valor a enviar/receber (ex.: {@code WORKFLOW_EXECUTE})
 * @param permission  permissão que o escopo concede (ex.: {@code workflow:execute})
 * @param description    descrição curta, em inglês, para clientes de API
 * @param grantable      o papel atual de quem pergunta concede este escopo — a criação recusa
 *                       ({@code 403}) o que não for; a UI desabilita a opção em vez de deixar falhar
 * @param opensEndpoints alguma rota aceita uma chave com este escopo; {@code false} quer dizer que uma
 *                       chave só com ele recebe {@code 403} em toda chamada, por enquanto
 */
public record ApiKeyScopeResponse(String name, String permission, String description, boolean grantable,
                                  boolean opensEndpoints) {

    public static ApiKeyScopeResponse of(ApiKeyScope scope, boolean grantable, boolean opensEndpoints) {
        return new ApiKeyScopeResponse(scope.name(), scope.getPermission(), scope.getDescription(), grantable,
                opensEndpoints);
    }
}
