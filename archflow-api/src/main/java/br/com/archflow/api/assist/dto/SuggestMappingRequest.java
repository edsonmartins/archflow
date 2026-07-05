package br.com.archflow.api.assist.dto;

import java.util.List;

/**
 * Requisição de sugestão preditiva de mapeamento de campos (change #23),
 * enviada para {@code POST /archflow/assist/suggest-mapping}.
 *
 * <p>Contrato fixo, compartilhado com o backend/frontend Mentors. O modelo
 * deve casar campos de origem com campos de destino por similaridade semântica
 * e de tipo.
 *
 * @param sourceSchema lista de campos de origem; pode ser {@code null}/vazia
 * @param targetSchema lista de campos de destino; pode ser {@code null}/vazia
 * @param idioma       idioma da resposta (ex.: "pt-BR"); default "pt-BR"
 */
public record SuggestMappingRequest(
        List<SchemaField> sourceSchema,
        List<SchemaField> targetSchema,
        String idioma
) {
    public String idiomaOrDefault() {
        return (idioma == null || idioma.isBlank()) ? "pt-BR" : idioma;
    }

    /**
     * Campo de um schema (origem ou destino).
     *
     * @param path  caminho/identificador do campo (ex.: "nm_cliente")
     * @param label rótulo amigável; pode ser {@code null}
     * @param type  tipo do campo (ex.: "string", "date", "decimal"); pode ser {@code null}
     */
    public record SchemaField(String path, String label, String type) {
    }
}
