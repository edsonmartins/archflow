package br.com.archflow.api.assist.impl;

import br.com.archflow.api.assist.AssistService;
import br.com.archflow.api.assist.AssistUnavailableException;
import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.ExplainErrorResponse;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementação de {@link AssistService}.
 *
 * <p>Resolve o modelo LLM padrão (apenas o {@code platformDefault}, sem patches
 * de tenant/flow/agent/step), monta um prompt focado em troubleshooting de
 * integração, faz a chamada síncrona {@code ChatModel.chat(String)} e tenta
 * parsear um objeto JSON estrito. Qualquer exceção do modelo vira
 * {@link AssistUnavailableException} (503), nunca 500.
 */
public class AssistServiceImpl implements AssistService {

    private static final Logger log = LoggerFactory.getLogger(AssistServiceImpl.class);

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ObjectMapper objectMapper;

    public AssistServiceImpl(LLMConfigResolver llmConfigResolver,
                             ResolvedLLMConfig platformDefault,
                             ObjectMapper objectMapper) {
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefault;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public ExplainErrorResponse explainError(ExplainErrorRequest request) {
        ResolvedLLMConfig resolved = llmConfigResolver.resolve(defaultRequest());
        String modelo = resolved.model() != null ? resolved.model() : "default";

        String prompt = buildPrompt(request);

        String raw;
        try {
            ChatModel model = llmConfigResolver.resolveModel(defaultRequest());
            raw = model.chat(prompt);
        } catch (Exception e) {
            // Qualquer falha do modelo (incl. timeout) → 503, nunca 500.
            log.warn("Assist LLM call failed (model={}): {}", modelo, e.toString());
            throw new AssistUnavailableException(
                    "Falha ao consultar o modelo de IA: " + e.getMessage(), e);
        }

        return parse(raw, modelo);
    }

    /** Requisição de resolução usando somente o default da plataforma. */
    private LLMResolutionRequest defaultRequest() {
        return LLMResolutionRequest.builder(platformDefault).build();
    }

    private ExplainErrorResponse parse(String raw, String modelo) {
        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode node = objectMapper.readTree(json);
                if (node.isObject()) {
                    String diagnostico = text(node, "diagnostico");
                    String sugestao = text(node, "sugestaoCorrecao");
                    if (diagnostico != null || sugestao != null) {
                        return new ExplainErrorResponse(
                                diagnostico != null ? diagnostico : "",
                                text(node, "causaProvavel"),
                                sugestao != null ? sugestao : "",
                                normalizeConfianca(text(node, "confianca")),
                                modelo);
                    }
                }
            } catch (Exception e) {
                log.debug("Resposta da IA não é JSON parseável, usando fallback: {}", e.toString());
            }
        }
        // Fallback: texto cru no diagnóstico, ainda 200.
        return new ExplainErrorResponse(raw != null ? raw.trim() : "", null, "", null, modelo);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String normalizeConfianca(String c) {
        if (c == null) {
            return null;
        }
        String up = c.trim().toUpperCase();
        return switch (up) {
            case "ALTA", "MEDIA", "BAIXA" -> up;
            default -> null;
        };
    }

    /**
     * Extrai o primeiro objeto JSON do texto do modelo (tolera cercas de código
     * e texto ao redor). Retorna {@code null} se não houver chaves balanceadas.
     */
    private static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String buildPrompt(ExplainErrorRequest r) {
        String idioma = r.idiomaOrDefault();
        StringBuilder sb = new StringBuilder();
        sb.append("Você é um assistente especialista em troubleshooting de integrações de dados (iPaaS). ")
          .append("Analise o erro abaixo e responda no idioma '").append(idioma).append("'.\n\n")
          .append("Responda EXCLUSIVAMENTE com um objeto JSON válido, sem cercas de código e sem texto adicional, ")
          .append("com EXATAMENTE estas chaves:\n")
          .append("{\n")
          .append("  \"diagnostico\": string (explicação clara do que aconteceu),\n")
          .append("  \"causaProvavel\": string ou null (causa raiz mais provável),\n")
          .append("  \"sugestaoCorrecao\": string (passos acionáveis para corrigir),\n")
          .append("  \"confianca\": \"ALTA\" | \"MEDIA\" | \"BAIXA\" ou null\n")
          .append("}\n\n")
          .append("Contexto do erro (apenas os campos fornecidos):\n");

        appendField(sb, "Tipo do erro", r.tipoErro());
        appendField(sb, "Mensagem do erro", r.mensagemErro());
        appendField(sb, "Nome do step", r.nomeStep());
        appendField(sb, "Tipo do plugin/conector", r.tipoPlugin());
        appendField(sb, "Configuração do step", r.configuracaoStep());
        appendField(sb, "Amostra de entrada", r.amostraEntrada());
        appendField(sb, "Amostra de saída", r.amostraSaida());
        appendField(sb, "Stacktrace", r.stacktrace());

        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }
}
