package br.com.archflow.api.assist.dto;

import java.util.List;

/**
 * Sugestões de mapeamento campo-a-campo produzidas pela IA (change #23).
 *
 * <p>Contrato fixo, compartilhado com o backend/frontend Mentors. Em caso de
 * falha de parsing da resposta do modelo, retorna {@code sugestoes} vazio
 * (ainda HTTP 200).
 *
 * @param sugestoes lista de sugestões origem→destino (nunca {@code null})
 */
public record SuggestMappingResponse(
        List<Sugestao> sugestoes
) {
    /**
     * Uma sugestão de mapeamento de um campo de origem para um de destino.
     *
     * @param sourcePath            caminho do campo de origem
     * @param targetPath            caminho do campo de destino
     * @param confianca             confiança da sugestão no intervalo 0..1
     * @param transformacaoSugerida nome da transformação sugerida quando os
     *                              tipos diferem (ex.: "date-format",
     *                              "decimal/cents"); pode ser {@code null}
     */
    public record Sugestao(
            String sourcePath,
            String targetPath,
            Double confianca,
            String transformacaoSugerida
    ) {
    }
}
