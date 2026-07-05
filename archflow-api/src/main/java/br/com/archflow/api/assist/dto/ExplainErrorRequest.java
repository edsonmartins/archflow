package br.com.archflow.api.assist.dto;

/**
 * Contexto estruturado de um erro de execução de integração, enviado para
 * diagnóstico pela IA via {@code POST /archflow/assist/explain-error}.
 *
 * <p>Contrato fixo, compartilhado com o backend Mentors (change #21). Todos os
 * campos exceto {@code mensagemErro} e {@code idioma} são opcionais.
 *
 * @param tipoErro          classe/tipo do erro (ex.: nome da exceção); pode ser {@code null}
 * @param mensagemErro      mensagem do erro (obrigatória)
 * @param stacktrace        stacktrace completo; pode ser {@code null}
 * @param configuracaoStep  configuração do step que falhou (JSON/texto); pode ser {@code null}
 * @param amostraEntrada    amostra do payload de entrada; pode ser {@code null}
 * @param amostraSaida      amostra do payload de saída; pode ser {@code null}
 * @param nomeStep          nome do step que falhou; pode ser {@code null}
 * @param tipoPlugin        tipo do plugin/conector envolvido; pode ser {@code null}
 * @param idioma            idioma da resposta (ex.: "pt-BR"); default "pt-BR"
 */
public record ExplainErrorRequest(
        String tipoErro,
        String mensagemErro,
        String stacktrace,
        String configuracaoStep,
        String amostraEntrada,
        String amostraSaida,
        String nomeStep,
        String tipoPlugin,
        String idioma
) {
    public String idiomaOrDefault() {
        return (idioma == null || idioma.isBlank()) ? "pt-BR" : idioma;
    }
}
