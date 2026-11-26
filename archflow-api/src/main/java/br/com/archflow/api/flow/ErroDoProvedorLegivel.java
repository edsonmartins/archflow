package br.com.archflow.api.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * O erro de um provedor de LLM em frase para quem abre a execução (RCHFLW-130).
 *
 * <p><b>Por que existe.</b> O langchain4j põe o corpo HTTP inteiro da resposta de erro na mensagem da
 * exceção ({@code LangChain4jException(cause)} copia o {@code getMessage()} do {@code HttpException}, que é o
 * corpo). Esse texto ia direto para o registro da execução, e o detalhe mostrava
 * {@code {"error":{"message":"x is not a valid model ID","code":400},"user_id":"user_…"}} — JSON cru e, pior,
 * o {@code user_id} da conta OpenRouter da plataforma, que nenhum usuário do tenant deveria ver.</p>
 *
 * <p><b>O que faz.</b> Só reescreve quando a mensagem <b>é</b> o corpo de erro de um provedor — o envelope
 * {@code {"error": {"message": …}}} da OpenAI/OpenRouter/Anthropic, sozinho ou atrás apenas de nomes de classe de
 * exceção ({@code CompletionException: dev.langchain4j…Exception: {…}}), que é como o langchain4j a entrega.
 * Fica a frase do provedor, com o status HTTP explicado em português, e o resto do envelope — {@code user_id},
 * ids de requisição — sai. Quando o OpenRouter responde só o genérico "Provider returned error", o motivo de
 * verdade vem em {@code metadata.raw} e é acrescentado, também sem identificadores: tirar o identificador não pode
 * esconder a causa.</p>
 *
 * <p><b>O que não reescreve.</b> Um texto na frente do JSON diz que outra peça embrulhou o corpo — o cliente MCP
 * escreve {@code "HTTP 401 do MCP server: {"error":{…}}"}, na mesma forma —, e atribuir isso ao provedor do modelo
 * seria culpar o componente errado; a mensagem passa como está. Também passa como está qualquer mensagem sem esse
 * envelope (validação do motor, recusa no portão). O texto original fica no log do servidor, escrito por
 * {@link StepRecordingListener}, porque é ele que o suporte precisa.</p>
 *
 * <p><b>Onde é aplicado.</b> Em tudo que chega a uma tela: o registro da execução (passos e coluna Erro), o
 * stream de eventos ({@code step_failed}/{@code flow_failed}, que o painel Executar do editor lê via AG-UI) e o
 * {@code RUN_ERROR} do AG-UI. O {@code FlowResult} e o contexto continuam com a mensagem original — o invoke do
 * VendaX ({@code AgentFlowRunner}) devolve ao Core o que o passo disse.</p>
 */
public final class ErroDoProvedorLegivel {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** "dev.langchain4j.exception.InvalidRequestException" no fim do prefixo, antes do corpo. */
    private static final Pattern CLASSE_NO_FIM = Pattern.compile(
            "(?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)\\s*:?\\s*$");

    /**
     * Identificadores de conta e chaves que um provedor às vezes repete dentro da própria frase
     * ({@code "User user_2x… not found"}, {@code "organization org-8Kx…"} da OpenAI, uma chave {@code sk-…}
     * ecoada num 401). Com hífen, o id precisa ter um dígito: sem isso, palavras comuns como
     * {@code user-provided} ou {@code org-wide} viravam "[identificador omitido]" no meio da frase.
     */
    private static final Pattern IDENTIFICADOR_DA_CONTA = Pattern.compile(
            "\\b(?:user|org)_[A-Za-z0-9]{6,}\\b"
                    + "|\\b(?:user|org)-(?=[A-Za-z]*\\d)[A-Za-z0-9]{6,}\\b"
                    + "|\\bsk-[A-Za-z0-9_-]{8,}");

    /** A frase genérica do OpenRouter quando o provedor de baixo recusou; a causa vem em {@code metadata.raw}. */
    private static final String RECUSA_GENERICA = "Provider returned error";

    /** Limite do motivo tirado de {@code metadata.raw}: às vezes é uma página HTML inteira. */
    private static final int MAX_MOTIVO = 300;

    private ErroDoProvedorLegivel() {
    }

    /**
     * A mensagem para a tela: a do provedor traduzida quando a mensagem carrega o envelope de erro dele, ou a
     * própria mensagem quando não carrega.
     *
     * @param mensagem a mensagem da exceção, como chegou (pode ser {@code null})
     * @return a frase legível; {@code null} só quando a entrada é {@code null}
     */
    public static String paraUsuario(String mensagem) {
        if (mensagem == null) {
            return null;
        }
        int inicio = mensagem.indexOf('{');
        int fim = mensagem.lastIndexOf('}');
        if (inicio < 0 || fim <= inicio || !mensagem.substring(fim + 1).isBlank()
                || !semNomesDeClasse(mensagem.substring(0, inicio)).isEmpty()) {
            return mensagem;
        }
        JsonNode corpo;
        try {
            corpo = JSON.readTree(mensagem.substring(inicio, fim + 1));
        } catch (Exception naoEJson) {
            return mensagem;
        }
        JsonNode erro = corpo == null ? null : corpo.get("error");
        JsonNode frase = erro != null && erro.isObject() ? erro.get("message") : null;
        if (frase == null || !frase.isTextual() || frase.asText().isBlank()) {
            return mensagem;
        }
        String texto = frase.asText().trim();
        String motivo = RECUSA_GENERICA.equalsIgnoreCase(texto) ? motivoDoProvedorDeBaixo(erro) : null;
        if (motivo != null) {
            texto = texto + " — " + motivo;
        }
        return traduzir(statusHttp(erro), semIdentificadores(texto));
    }

    /**
     * O que sobra do texto antes do corpo tirando os nomes de classe que o {@code ExecutionException}/
     * {@code CompletionException} e o próprio langchain4j empilham na frente ({@code "pacote.Classe: "}). Vazio
     * quando só havia isso — o corpo é do provedor; não vazio quando alguém escreveu uma frase ali.
     */
    private static String semNomesDeClasse(String antesDoCorpo) {
        String prefixo = antesDoCorpo.trim();
        String anterior;
        do {
            anterior = prefixo;
            while (prefixo.endsWith(":")) {
                prefixo = prefixo.substring(0, prefixo.length() - 1).trim();
            }
            prefixo = CLASSE_NO_FIM.matcher(prefixo).replaceFirst("").trim();
        } while (!prefixo.equals(anterior));
        return prefixo;
    }

    /**
     * O motivo que o OpenRouter repassa do provedor de baixo, em {@code metadata.raw} (texto, muitas vezes o JSON
     * de erro daquele provedor), com o nome dele ({@code metadata.provider_name}) na frente. {@code null} quando
     * não há motivo legível — um JSON sem frase não vai para a tela; fica no log.
     */
    private static String motivoDoProvedorDeBaixo(JsonNode erro) {
        JsonNode metadata = erro.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        String motivo = fraseDe(metadata.get("raw"));
        if (motivo == null) {
            return null;
        }
        if (motivo.length() > MAX_MOTIVO) {
            motivo = motivo.substring(0, MAX_MOTIVO).trim() + "…";
        }
        JsonNode provedor = metadata.get("provider_name");
        return provedor != null && provedor.isTextual() && !provedor.asText().isBlank()
                ? provedor.asText().trim() + ": " + motivo
                : motivo;
    }

    /** A frase de um {@code raw}: o texto como veio, ou o {@code error.message}/{@code message} quando é JSON. */
    private static String fraseDe(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        JsonNode json = raw;
        if (raw.isTextual()) {
            String texto = raw.asText().trim();
            if (texto.isEmpty()) {
                return null;
            }
            if (!texto.startsWith("{")) {
                return texto;
            }
            try {
                json = JSON.readTree(texto);
            } catch (Exception naoEJson) {
                return texto;
            }
        }
        if (!json.isObject()) {
            return null;
        }
        JsonNode erro = json.get("error");
        JsonNode frase = erro != null && erro.isObject() ? erro.get("message")
                : erro != null && erro.isTextual() ? erro : json.get("message");
        return frase != null && frase.isTextual() && !frase.asText().isBlank() ? frase.asText().trim() : null;
    }

    /**
     * O {@code code} numérico do envelope (OpenRouter manda número; a OpenAI às vezes manda texto). Só um status
     * HTTP de verdade (100–599): um {@code -32602} de JSON-RPC na mesma posição não pode virar "HTTP -32602".
     */
    private static Integer statusHttp(JsonNode erro) {
        JsonNode code = erro.get("code");
        if (code == null) {
            return null;
        }
        Integer status = null;
        if (code.isInt()) {
            status = code.asInt();
        } else if (code.isTextual() && code.asText().matches("\\d{3}")) {
            status = Integer.parseInt(code.asText());
        }
        return status != null && status >= 100 && status <= 599 ? status : null;
    }

    private static String semIdentificadores(String frase) {
        return IDENTIFICADOR_DA_CONTA.matcher(frase).replaceAll("[identificador omitido]");
    }

    private static String traduzir(Integer status, String frase) {
        if (status == null) {
            return "O provedor do modelo recusou a chamada: " + frase;
        }
        String verbo = status >= 500 ? "falhou" : "recusou a chamada";
        String motivo = motivo(status);
        String entreParenteses = motivo == null ? "HTTP " + status : "HTTP " + status + ", " + motivo;
        return "O provedor do modelo " + verbo + " (" + entreParenteses + "): " + frase;
    }

    /** O que o status quer dizer para quem montou o fluxo; a frase do provedor diz o resto. */
    private static String motivo(int status) {
        return switch (status) {
            case 400 -> "requisição inválida";
            case 401 -> "a chave de API foi recusada";
            case 402 -> "sem crédito na conta do provedor";
            case 403 -> "acesso negado pelo provedor";
            case 404 -> "modelo ou recurso não encontrado";
            case 408 -> "tempo esgotado no provedor";
            case 413 -> "a requisição passou do tamanho aceito";
            case 429 -> "limite de requisições atingido";
            default -> status >= 500 ? "provedor indisponível no momento" : null;
        };
    }
}
