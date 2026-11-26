package br.com.archflow.api.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RCHFLW-130: o detalhe da execução mostrava o corpo HTTP do OpenRouter como veio —
 * {@code {"error":{…},"user_id":"user_…"}} —, JSON cru e com o identificador da conta da plataforma.
 */
@DisplayName("erro do provedor de LLM: frase traduzida, sem JSON cru nem identificador da conta")
class ErroDoProvedorLegivelTest {

    /** O corpo que o QA viu, com o user_id da conta OpenRouter. */
    private static final String CORPO_OPENROUTER = "{\"error\":{\"message\":\"qa/modelo-que-nao-existe is not a "
            + "valid model ID\",\"code\":400},\"user_id\":\"user_314F4GOabcdefXYZ\"}";

    @Test
    @DisplayName("o corpo do OpenRouter vira a frase do provedor com o status explicado; o user_id some")
    void corpoDoOpenRouter() {
        String legivel = ErroDoProvedorLegivel.paraUsuario(CORPO_OPENROUTER);

        assertThat(legivel).isEqualTo("O provedor do modelo recusou a chamada (HTTP 400, requisição inválida): "
                + "qa/modelo-que-nao-existe is not a valid model ID");
        assertThat(legivel).doesNotContain("user_").doesNotContain("{").doesNotContain("\"error\"");
    }

    @Test
    @DisplayName("os nomes de classe que as exceções empilham na frente do corpo saem")
    void nomeDaClasse() {
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "java.util.concurrent.ExecutionException: dev.langchain4j.exception.InvalidRequestException: "
                        + CORPO_OPENROUTER))
                .isEqualTo("O provedor do modelo recusou a chamada (HTTP 400, requisição inválida): "
                        + "qa/modelo-que-nao-existe is not a valid model ID");
    }

    @Test
    @DisplayName("corpo na mesma forma embrulhado por outra peça (cliente MCP) não vira erro do provedor do modelo")
    void corpoEmbrulhadoPorOutraPeca() {
        String mcp = "HTTP 401 do MCP server: {\"error\":{\"message\":\"unauthorized\",\"code\":401}}";
        assertThat(ErroDoProvedorLegivel.paraUsuario(mcp)).isEqualTo(mcp);
        String depoisDoCorpo = CORPO_OPENROUTER + " (tentativa 2)";
        assertThat(ErroDoProvedorLegivel.paraUsuario(depoisDoCorpo)).isEqualTo(depoisDoCorpo);
    }

    @Test
    @DisplayName("code fora da faixa HTTP (JSON-RPC -32602) não vira 'HTTP -32602'")
    void codeQueNaoEStatusHttp() {
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "{\"error\":{\"message\":\"Invalid params\",\"code\":-32602}}"))
                .isEqualTo("O provedor do modelo recusou a chamada: Invalid params");
    }

    @Test
    @DisplayName("palavras com hífen ficam; id de organização com dígito, não")
    void palavrasComHifen() {
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "{\"error\":{\"message\":\"Invalid user-provided parameter; org-wide limit reached\","
                        + "\"code\":400}}"))
                .endsWith(": Invalid user-provided parameter; org-wide limit reached");
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "{\"error\":{\"message\":\"Rate limit for organization org-8KxAbC2d reached\",\"code\":429}}"))
                .endsWith(": Rate limit for organization [identificador omitido] reached");
    }

    @Test
    @DisplayName("'Provider returned error' do OpenRouter leva o motivo de metadata.raw, sem identificadores")
    void motivoDoProvedorDeBaixo() {
        String corpo = "{\"error\":{\"code\":400,\"message\":\"Provider returned error\",\"metadata\":"
                + "{\"provider_name\":\"Anthropic\",\"raw\":\"{\\\"type\\\":\\\"error\\\",\\\"error\\\":"
                + "{\\\"message\\\":\\\"max_tokens too large for user_9aBcDeFgH\\\"}}\"}},"
                + "\"user_id\":\"user_314F4GOabcdefXYZ\"}";

        assertThat(ErroDoProvedorLegivel.paraUsuario(corpo))
                .isEqualTo("O provedor do modelo recusou a chamada (HTTP 400, requisição inválida): "
                        + "Provider returned error — Anthropic: max_tokens too large for [identificador omitido]");
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "{\"error\":{\"code\":502,\"message\":\"Provider returned error\",\"metadata\":"
                        + "{\"raw\":\"upstream connect error\"}}}"))
                .endsWith(": Provider returned error — upstream connect error");
    }

    @Test
    @DisplayName("forma da Anthropic, 5xx e identificador repetido dentro da frase")
    void outrasFormas() {
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-x\"}}"))
                .isEqualTo("O provedor do modelo recusou a chamada: model: claude-x");
        assertThat(ErroDoProvedorLegivel.paraUsuario("{\"error\":{\"message\":\"Upstream down\",\"code\":503}}"))
                .isEqualTo("O provedor do modelo falhou (HTTP 503, provedor indisponível no momento): Upstream down");
        assertThat(ErroDoProvedorLegivel.paraUsuario(
                "{\"error\":{\"message\":\"User user_2xAbCdEf9 not found. Key sk-or-v1-abcdef123456\",\"code\":401}}"))
                .isEqualTo("O provedor do modelo recusou a chamada (HTTP 401, a chave de API foi recusada): "
                        + "User [identificador omitido] not found. Key [identificador omitido]");
    }

    @Test
    @DisplayName("o que não é erro de provedor passa como está")
    void outrasMensagensIntactas() {
        assertThat(ErroDoProvedorLegivel.paraUsuario("'conditionExpression' é obrigatória"))
                .isEqualTo("'conditionExpression' é obrigatória");
        assertThat(ErroDoProvedorLegivel.paraUsuario("saída inválida: {\"valor\": 1}"))
                .isEqualTo("saída inválida: {\"valor\": 1}");
        assertThat(ErroDoProvedorLegivel.paraUsuario("condição ${x} = {a}")).isEqualTo("condição ${x} = {a}");
        assertThat(ErroDoProvedorLegivel.paraUsuario(null)).isNull();
    }
}
