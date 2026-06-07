package br.com.archflow.api.assist.impl;

import br.com.archflow.api.assist.AssistService;
import br.com.archflow.api.assist.AssistUnavailableException;
import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.ExplainErrorResponse;
import br.com.archflow.api.assist.dto.NlToFlowRequest;
import br.com.archflow.api.assist.dto.NlToFlowResponse;
import br.com.archflow.api.assist.dto.SuggestMappingRequest;
import br.com.archflow.api.assist.dto.SuggestMappingResponse;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        String prompt = buildPrompt(request);
        String raw = chat(prompt);
        return parse(raw, currentModelo());
    }

    @Override
    public SuggestMappingResponse suggestMapping(SuggestMappingRequest request) {
        String prompt = buildSuggestMappingPrompt(request);
        String raw = chat(prompt);
        return parseSuggestMapping(raw);
    }

    @Override
    public NlToFlowResponse nlToFlow(NlToFlowRequest request) {
        String prompt = buildNlToFlowPrompt(request);
        String raw = chat(prompt);
        return parseNlToFlow(raw);
    }

    /**
     * Chamada síncrona compartilhada ao LLM padrão da plataforma. Qualquer
     * falha do modelo (incl. timeout) vira {@link AssistUnavailableException}
     * (503), nunca 500.
     */
    private String chat(String prompt) {
        try {
            ChatModel model = llmConfigResolver.resolveModel(defaultRequest());
            return model.chat(prompt);
        } catch (Exception e) {
            log.warn("Assist LLM call failed (model={}): {}", currentModelo(), e.toString());
            throw new AssistUnavailableException(
                    "Falha ao consultar o modelo de IA: " + e.getMessage(), e);
        }
    }

    private String currentModelo() {
        ResolvedLLMConfig resolved = llmConfigResolver.resolve(defaultRequest());
        return resolved.model() != null ? resolved.model() : "default";
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

    // ---------------------------------------------------------------------
    // #23 — suggest-mapping
    // ---------------------------------------------------------------------

    private String buildSuggestMappingPrompt(SuggestMappingRequest r) {
        String idioma = r.idiomaOrDefault();
        StringBuilder sb = new StringBuilder();
        sb.append("Você é um assistente especialista em mapeamento de dados entre esquemas (iPaaS). ")
          .append("Case cada campo de ORIGEM com o campo de DESTINO mais adequado por similaridade ")
          .append("semântica e de tipo (ex.: 'nm_cliente'→'customerName', 'vl_total'→'amount'). ")
          .append("Use os rótulos (label) e tipos como pistas adicionais. ")
          .append("Quando os tipos diferirem, sugira o nome de uma transformação provável em ")
          .append("'transformacaoSugerida' (ex.: 'date-format', 'decimal/cents'); senão use null. ")
          .append("Atribua 'confianca' entre 0 e 1. Inclua apenas pares plausíveis. ")
          .append("Responda no idioma '").append(idioma).append("'.\n\n")
          .append("Responda EXCLUSIVAMENTE com um objeto JSON válido, sem cercas de código e sem texto adicional, ")
          .append("com EXATAMENTE esta forma:\n")
          .append("{\n")
          .append("  \"sugestoes\": [\n")
          .append("    { \"sourcePath\": string, \"targetPath\": string, ")
          .append("\"confianca\": number (0..1), \"transformacaoSugerida\": string | null }\n")
          .append("  ]\n")
          .append("}\n\n");

        sb.append("Campos de ORIGEM (path | label | type):\n");
        appendSchema(sb, r.sourceSchema());
        sb.append("\nCampos de DESTINO (path | label | type):\n");
        appendSchema(sb, r.targetSchema());

        return sb.toString();
    }

    private static void appendSchema(StringBuilder sb, List<SuggestMappingRequest.SchemaField> fields) {
        if (fields == null || fields.isEmpty()) {
            sb.append("(nenhum)\n");
            return;
        }
        for (SuggestMappingRequest.SchemaField f : fields) {
            if (f == null) {
                continue;
            }
            sb.append("- ")
              .append(f.path() != null ? f.path() : "")
              .append(" | ").append(f.label() != null ? f.label() : "")
              .append(" | ").append(f.type() != null ? f.type() : "")
              .append('\n');
        }
    }

    private SuggestMappingResponse parseSuggestMapping(String raw) {
        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode arr = root.get("sugestoes");
                if (arr != null && arr.isArray()) {
                    List<SuggestMappingResponse.Sugestao> out = new ArrayList<>();
                    for (JsonNode n : arr) {
                        String src = text(n, "sourcePath");
                        String tgt = text(n, "targetPath");
                        if (src == null || tgt == null) {
                            continue;
                        }
                        out.add(new SuggestMappingResponse.Sugestao(
                                src, tgt,
                                clampConfianca(n.get("confianca")),
                                text(n, "transformacaoSugerida")));
                    }
                    return new SuggestMappingResponse(out);
                }
            } catch (Exception e) {
                log.debug("Resposta da IA (suggest-mapping) não é JSON parseável, usando fallback: {}", e.toString());
            }
        }
        // Fallback: sem sugestões, ainda 200.
        return new SuggestMappingResponse(List.of());
    }

    private static Double clampConfianca(JsonNode v) {
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        double d = v.asDouble();
        if (d < 0) {
            return 0d;
        }
        if (d > 1) {
            return 1d;
        }
        return d;
    }

    // ---------------------------------------------------------------------
    // #22 — nl-to-flow (restrito ao catálogo, ADR-0001)
    // ---------------------------------------------------------------------

    private String buildNlToFlowPrompt(NlToFlowRequest r) {
        String idioma = r.idiomaOrDefault();
        StringBuilder sb = new StringBuilder();
        sb.append("Você é um assistente que monta rascunhos de workflows de integração (iPaaS). ")
          .append("REGRA CRÍTICA (ADR-0001): use EXCLUSIVAMENTE os plugins, operações e propriedades ")
          .append("presentes no CATÁLOGO abaixo. NUNCA invente conectores, operações ou campos. ")
          .append("Cada step deve referenciar um plugin/componente/versão/operação existentes no catálogo, ")
          .append("e 'parameters' só pode conter propriedades daquela operação. ")
          .append("Use 'step.name' único (ele é usado como source/target dos links). ")
          .append("Ligue os steps em 'links' refletindo o fluxo da descrição. ")
          .append("Se a descrição pedir uma capacidade SEM plugin correspondente no catálogo, ")
          .append("registre-a em 'lacunas' (não invente um step). ")
          .append("Responda no idioma '").append(idioma).append("'.\n\n")
          .append("Responda EXCLUSIVAMENTE com um objeto JSON válido, sem cercas de código e sem texto adicional, ")
          .append("com EXATAMENTE esta forma:\n")
          .append("{\n")
          .append("  \"workflow\": {\n")
          .append("    \"steps\": [ { \"name\": string, \"component\": string, \"componentName\": string, ")
          .append("\"componentVersion\": string, \"pluginKind\": string, \"operation\": string | null, ")
          .append("\"nodeType\": string | null, \"parameters\": object } ],\n")
          .append("    \"links\": [ { \"source\": string, \"target\": string } ]\n")
          .append("  },\n")
          .append("  \"lacunas\": [ string ],\n")
          .append("  \"observacoes\": string | null\n")
          .append("}\n\n");

        sb.append("Descrição em linguagem natural:\n")
          .append(r.descricao() != null ? r.descricao() : "(vazia)").append("\n\n");

        sb.append("CATÁLOGO de plugins disponíveis:\n");
        appendCatalogo(sb, r.catalogo());

        return sb.toString();
    }

    private static void appendCatalogo(StringBuilder sb, List<NlToFlowRequest.CatalogPlugin> catalogo) {
        if (catalogo == null || catalogo.isEmpty()) {
            sb.append("(catálogo vazio — nenhum plugin disponível)\n");
            return;
        }
        for (NlToFlowRequest.CatalogPlugin p : catalogo) {
            if (p == null) {
                continue;
            }
            sb.append("- plugin=").append(nullToEmpty(p.plugin()))
              .append(", componentName=").append(nullToEmpty(p.componentName()))
              .append(", version=").append(nullToEmpty(p.version()))
              .append(", pluginKind=").append(nullToEmpty(p.pluginKind()))
              .append('\n');
            if (p.operacoes() != null) {
                for (NlToFlowRequest.CatalogOperation op : p.operacoes()) {
                    if (op == null) {
                        continue;
                    }
                    sb.append("    operacao=").append(nullToEmpty(op.nome()));
                    if (op.propriedades() != null && !op.propriedades().isEmpty()) {
                        sb.append(" propriedades=[");
                        boolean first = true;
                        for (NlToFlowRequest.CatalogProperty prop : op.propriedades()) {
                            if (prop == null) {
                                continue;
                            }
                            if (!first) {
                                sb.append(", ");
                            }
                            sb.append(nullToEmpty(prop.nome()))
                              .append(':').append(nullToEmpty(prop.tipo()))
                              .append(prop.obrigatorio() ? "(obrigatório)" : "(opcional)");
                            first = false;
                        }
                        sb.append("]");
                    }
                    sb.append('\n');
                }
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private NlToFlowResponse parseNlToFlow(String raw) {
        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode wf = root.get("workflow");
                if (wf != null && wf.isObject()) {
                    List<NlToFlowResponse.Step> steps = parseSteps(wf.get("steps"));
                    List<NlToFlowResponse.Link> links = parseLinks(wf.get("links"));
                    List<String> lacunas = parseStringArray(root.get("lacunas"));
                    String observacoes = text(root, "observacoes");
                    return new NlToFlowResponse(
                            new NlToFlowResponse.Workflow(steps, links),
                            lacunas,
                            observacoes);
                }
            } catch (Exception e) {
                log.debug("Resposta da IA (nl-to-flow) não é JSON parseável, usando fallback: {}", e.toString());
            }
        }
        // Fallback: workflow vazio + lacuna padrão + texto cru em observações, ainda 200.
        return new NlToFlowResponse(
                new NlToFlowResponse.Workflow(List.of(), List.of()),
                List.of("Não foi possível gerar a partir da descrição"),
                raw != null ? raw.trim() : null);
    }

    private List<NlToFlowResponse.Step> parseSteps(JsonNode arr) {
        List<NlToFlowResponse.Step> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                continue;
            }
            out.add(new NlToFlowResponse.Step(
                    text(n, "name"),
                    text(n, "component"),
                    text(n, "componentName"),
                    text(n, "componentVersion"),
                    text(n, "pluginKind"),
                    text(n, "operation"),
                    text(n, "nodeType"),
                    parseObject(n.get("parameters"))));
        }
        return out;
    }

    private List<NlToFlowResponse.Link> parseLinks(JsonNode arr) {
        List<NlToFlowResponse.Link> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                continue;
            }
            String source = text(n, "source");
            String target = text(n, "target");
            if (source == null || target == null) {
                continue;
            }
            out.add(new NlToFlowResponse.Link(source, target));
        }
        return out;
    }

    private Map<String, Object> parseObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(
                    node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return map != null ? map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> parseStringArray(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n != null && !n.isNull()) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
