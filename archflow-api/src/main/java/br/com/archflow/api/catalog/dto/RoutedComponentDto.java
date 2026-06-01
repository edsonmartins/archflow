package br.com.archflow.api.catalog.dto;

import java.util.List;

/**
 * Resultado de roteamento por query: um componente do catálogo pontuado pelo
 * {@code ComponentQueryRouter}.
 *
 * @param componentId  id estável (resolve o componente vivo no catálogo)
 * @param displayName  nome legível
 * @param kind         categoria ("agent", "assistant", "tool")
 * @param score        confiança em [0.0, 1.0]
 * @param capabilities capabilities declaradas
 * @param keywords     keywords declaradas
 */
public record RoutedComponentDto(
        String componentId,
        String displayName,
        String kind,
        double score,
        List<String> capabilities,
        List<String> keywords) {}
