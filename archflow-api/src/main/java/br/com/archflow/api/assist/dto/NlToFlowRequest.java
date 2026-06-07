package br.com.archflow.api.assist.dto;

import java.util.List;

/**
 * Requisição de geração de rascunho de workflow a partir de linguagem natural
 * (change #22), enviada para {@code POST /archflow/assist/nl-to-flow}.
 *
 * <p>Contrato fixo, compartilhado com o backend/frontend Mentors.
 *
 * <p><b>ADR-0001:</b> o modelo deve usar EXCLUSIVAMENTE os plugins/operações/
 * propriedades presentes em {@code catalogo} — jamais inventar conectores ou
 * campos.
 *
 * @param descricao descrição em linguagem natural do que se deseja construir
 * @param catalogo  catálogo de plugins disponíveis (manifest); pode ser
 *                  {@code null}/vazio
 * @param idioma    idioma da resposta (ex.: "pt-BR"); default "pt-BR"
 */
public record NlToFlowRequest(
        String descricao,
        List<CatalogPlugin> catalogo,
        String idioma
) {
    public String idiomaOrDefault() {
        return (idioma == null || idioma.isBlank()) ? "pt-BR" : idioma;
    }

    /**
     * Plugin disponível no catálogo (manifest).
     *
     * @param plugin        id do plugin
     * @param componentName nome do componente
     * @param version       versão do componente
     * @param pluginKind    tipo do plugin (ex.: "source", "target", "transform")
     * @param operacoes     operações suportadas pelo plugin
     */
    public record CatalogPlugin(
            String plugin,
            String componentName,
            String version,
            String pluginKind,
            List<CatalogOperation> operacoes
    ) {
    }

    /**
     * Operação de um plugin do catálogo.
     *
     * @param nome         nome da operação
     * @param propriedades propriedades configuráveis da operação
     */
    public record CatalogOperation(
            String nome,
            List<CatalogProperty> propriedades
    ) {
    }

    /**
     * Propriedade configurável de uma operação.
     *
     * @param nome        nome da propriedade
     * @param tipo        tipo da propriedade
     * @param obrigatorio se a propriedade é obrigatória
     */
    public record CatalogProperty(
            String nome,
            String tipo,
            boolean obrigatorio
    ) {
    }
}
