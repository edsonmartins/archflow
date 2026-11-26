package br.com.archflow.api.assist.impl;

import br.com.archflow.api.assist.AssistService;
import br.com.archflow.api.assist.AssistUnavailableException;
import br.com.archflow.api.assist.ServerFlowCatalog;
import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.ExplainErrorResponse;
import br.com.archflow.api.assist.dto.NlToFlowRequest;
import br.com.archflow.api.assist.dto.NlToFlowResponse;
import br.com.archflow.api.assist.dto.SuggestMappingRequest;
import br.com.archflow.api.assist.dto.SuggestMappingResponse;
import br.com.archflow.api.catalog.dto.CatalogItemDto;
import br.com.archflow.api.flow.adapter.LangChainAdapterComponent;
import br.com.archflow.decision.DecisionComponent;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.decision.HttpDecisionProvider;
import br.com.archflow.engine.execution.ControlExpressionRules;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /** O que este servidor executa; {@code null} = sem catálogo do servidor (só o do corpo). */
    private final ServerFlowCatalog serverCatalog;

    public AssistServiceImpl(LLMConfigResolver llmConfigResolver,
                             ResolvedLLMConfig platformDefault,
                             ObjectMapper objectMapper) {
        this(llmConfigResolver, platformDefault, objectMapper, null);
    }

    /**
     * @param serverCatalog o catálogo executável do servidor: o {@code nl-to-flow} o usa quando o
     *                      corpo não traz catálogo, e o {@code explain-error} o descreve ao modelo
     *                      (RCHFLW-107); {@code null} mantém só o catálogo do corpo
     */
    public AssistServiceImpl(LLMConfigResolver llmConfigResolver,
                             ResolvedLLMConfig platformDefault,
                             ObjectMapper objectMapper,
                             ServerFlowCatalog serverCatalog) {
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefault;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.serverCatalog = serverCatalog;
    }

    @Override
    public ExplainErrorResponse explainError(ExplainErrorRequest request) {
        String prompt = buildPrompt(request, serverCatalog);
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
        String prompt = buildNlToFlowPrompt(request, serverCatalog);
        String raw = chat(prompt);
        return comAvisosDeExpressao(parseNlToFlow(raw));
    }

    /**
     * O rascunho com cada expressão de condition/switch recusada pela regra do motor dita em
     * {@code observacoes} (RCHFLW-116). O modelo pode errar a expressão, e o rascunho que chega a quem o
     * converte não pode carregar em silêncio uma condição que o {@code ExecutableNodesValidator} vai recusar
     * só ao executar. Vai em {@code observacoes}, e não num campo novo, porque o formato é o contrato do
     * Mentors (ver {@link NlToFlowResponse}); {@code lacunas} é outra coisa (capacidade sem plugin). O mesmo vale
     * para um step decision que não roda ({@link #avisosDeDecisao}).
     */
    static NlToFlowResponse comAvisosDeExpressao(NlToFlowResponse r) {
        List<String> avisos = new ArrayList<>(avisosDeExpressao(r.workflow().steps()));
        avisos.addAll(avisosDeDecisao(r.workflow().steps()));
        avisos.addAll(avisosDeMensagemDoLlm(r.workflow()));
        if (avisos.isEmpty()) {
            return r;
        }
        String modelo = r.observacoes() == null || r.observacoes().isBlank() ? "" : r.observacoes().trim() + "\n";
        return new NlToFlowResponse(r.workflow(), r.lacunas(), modelo + String.join("\n", avisos));
    }

    /** Os provedores de decisão que recusam a chamada sem o {@code model} do nó. */
    private static final Set<String> DECISAO_EXIGE_MODELO = Set.of(HttpDecisionProvider.ID,
            br.com.archflow.api.decision.LangChain4jDecisionProvider.ID);

    /**
     * Um aviso por step decision que o motor não executaria (RCHFLW-116, 2ª rodada). O "Gerar com IA" montou um
     * decision sem provider nem model: o padrão é o http-decisions, que não decide sem model, e com
     * {@code onError: ESCALATE} todo pedido — urgente ou comum — saiu pela escalada, com o fluxo COMPLETED. O que o
     * nó recusa ao inicializar vem do próprio {@link DecisionComponent} (nenhuma cópia da regra); o model, dos
     * provedores que o exigem.
     */
    static List<String> avisosDeDecisao(List<NlToFlowResponse.Step> steps) {
        List<String> avisos = new ArrayList<>();
        for (NlToFlowResponse.Step st : steps) {
            String tipo = st.nodeType() == null ? "" : st.nodeType().trim().toLowerCase(Locale.ROOT);
            if (!DecisionComponent.COMPONENT_ID.equals(tipo)) {
                continue;
            }
            Map<String, Object> p = st.parameters() == null ? Map.of() : st.parameters();
            try {
                // Sem provedores: initialize só lê a configuração; nenhum provedor é chamado aqui.
                new DecisionComponent(DecisionProviders.empty(), null).initialize(p);
            } catch (RuntimeException e) {
                avisos.add("Aviso do archflow: o step \"" + st.name() + "\" (decision) será recusado ao executar: "
                        + e.getMessage());
                continue;
            }
            String provider = p.get("provider") instanceof String s && !s.isBlank() ? s : HttpDecisionProvider.ID;
            boolean semModelo = !(p.get("model") instanceof String m && !m.isBlank());
            if (DECISAO_EXIGE_MODELO.contains(provider) && semModelo) {
                avisos.add("Aviso do archflow: o step \"" + st.name() + "\" (decision) usa o provedor " + provider
                        + ", que não decide sem 'model': todo pedido sairia pelo route ESCALATE. Use o provider "
                        + "'llm' (o LLM da plataforma) ou informe o model de decisão.");
            }
        }
        return avisos;
    }

    /**
     * Um aviso por step de controle com expressão recusada. No rascunho o id do passo é o {@code name}
     * (é o que vira {@code id} na conversão, ver {@link NlToFlowResponse}); os campos esperados da Entrada
     * vêm de {@code parameters.ioFields}, como no documento.
     */
    static List<String> avisosDeExpressao(List<NlToFlowResponse.Step> steps) {
        Set<String> ids = new HashSet<>();
        Set<String> texto = new HashSet<>();
        Set<String> campos = new HashSet<>();
        for (NlToFlowResponse.Step st : steps) {
            if (st.name() == null) {
                continue;
            }
            ids.add(st.name());
            String tipo = st.nodeType() == null ? "" : st.nodeType().trim().toLowerCase(Locale.ROOT);
            if (ControlExpressionRules.TEXT_OUTPUT_NODE_TYPES.contains(tipo)) {
                texto.add(st.name());
            }
            if ("input".equals(tipo) && st.parameters() != null
                    && st.parameters().get("ioFields") instanceof List<?> lista) {
                lista.forEach(c -> {
                    if (c instanceof String campo && !campo.isBlank()) {
                        campos.add(campo.trim());
                    }
                });
            }
        }
        ControlExpressionRules.FlowRefs refs = new ControlExpressionRules.FlowRefs(ids, texto, campos);
        List<String> avisos = new ArrayList<>();
        for (NlToFlowResponse.Step st : steps) {
            String tipo = st.nodeType() == null ? "" : st.nodeType().trim().toLowerCase(Locale.ROOT);
            // A "Mensagem do usuário" do llm-chat (RCHFLW-120) segue as regras de caminho das condições.
            String campo = switch (tipo) {
                case "condition" -> "conditionExpression";
                case "switch" -> "switchExpression";
                default -> ControlExpressionRules.TEXT_OUTPUT_NODE_TYPES.contains(tipo)
                        ? LangChainAdapterComponent.USER_MESSAGE_KEY : null;
            };
            if (campo == null || st.parameters() == null || !(st.parameters().get(campo) instanceof String expr)) {
                continue;
            }
            List<ControlExpressionRules.Finding> achados = switch (tipo) {
                case "condition" -> ControlExpressionRules.checkCondition(expr, refs);
                case "switch" -> ControlExpressionRules.checkSwitch(expr, refs);
                default -> ControlExpressionRules.checkTemplate(expr, refs);
            };
            if (!achados.isEmpty()) {
                avisos.add("Aviso do archflow: o step \"" + st.name() + "\", campo " + campo
                        + ", será recusado ao executar: " + String.join("; ",
                        achados.stream().map(ControlExpressionRules.Finding::message).toList()));
            }
        }
        return avisos;
    }

    /** Os steps que classificam ou ramificam: a saída (ou a entrada repassada) deles não é a mensagem do cliente. */
    private static final Set<String> CLASSIFICA_OU_RAMIFICA = Set.of(DecisionComponent.COMPONENT_ID, "condition",
            "switch");

    /**
     * Um aviso por llm-chat sem {@code userMessage} logo depois de um decision, condition ou switch (RCHFLW-120). O
     * motor passa a saída de cada passo ao seguinte, e a condição repassa o que recebeu: esse LLM recebe o JSON da
     * classificação como mensagem do usuário e responde a ele, não ao cliente — foi o que se viu no Chrome. O aviso
     * diz o campo e a referência à Entrada, quando o rascunho tem uma.
     */
    static List<String> avisosDeMensagemDoLlm(NlToFlowResponse.Workflow wf) {
        Map<String, String> tipos = new java.util.HashMap<>();
        String entrada = null;
        for (NlToFlowResponse.Step st : wf.steps()) {
            if (st.name() == null) {
                continue;
            }
            String tipo = st.nodeType() == null ? "" : st.nodeType().trim().toLowerCase(Locale.ROOT);
            tipos.put(st.name(), tipo);
            if (entrada == null && "input".equals(tipo)) {
                entrada = st.name();
            }
        }
        String referencia = entrada != null ? "${" + entrada + "}" : "${<name do step input>}";
        List<String> avisos = new ArrayList<>();
        for (NlToFlowResponse.Step st : wf.steps()) {
            String tipo = tipos.getOrDefault(st.name(), "");
            boolean semMensagem = st.parameters() == null
                    || !(st.parameters().get(LangChainAdapterComponent.USER_MESSAGE_KEY) instanceof String m
                    && !m.isBlank());
            if (!ControlExpressionRules.TEXT_OUTPUT_NODE_TYPES.contains(tipo) || !semMensagem) {
                continue;
            }
            List<String> antes = wf.links() == null ? List.of() : wf.links().stream()
                    .filter(l -> st.name().equals(l.target()) && l.source() != null
                            && CLASSIFICA_OU_RAMIFICA.contains(tipos.getOrDefault(l.source(), "")))
                    .map(NlToFlowResponse.Link::source).distinct().toList();
            if (!antes.isEmpty()) {
                avisos.add("Aviso do archflow: o step \"" + st.name() + "\" (llm-chat) vem depois de \""
                        + String.join("\", \"", antes) + "\" e, sem 'userMessage', recebe como mensagem do usuário a "
                        + "saída do passo anterior (o JSON da classificação), não a mensagem do cliente. Preencha "
                        + "parameters.userMessage com " + referencia + ".");
            }
        }
        return avisos;
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

    /**
     * O prompt do explain-error descreve o archflow (RCHFLW-107). Antes ele pedia um "especialista em
     * integrações (iPaaS)" e nada mais: para "component not found: transform" o modelo explicava uma
     * plataforma genérica e sugeria {@code data-transform}, {@code mapper} e {@code jq}, que não existem
     * aqui. Agora diz como o motor executa um nó, o que a mensagem significa, quais tipos não têm
     * execução neste servidor e quais componentes existem — e proíbe sugerir nomes fora dessas listas.
     */
    static String buildPrompt(ExplainErrorRequest r, ServerFlowCatalog serverCatalog) {
        String idioma = r.idiomaOrDefault();
        StringBuilder sb = new StringBuilder();
        sb.append("Você é o assistente de diagnóstico do archflow, um framework Java de automação com ")
          .append("agentes de IA construído sobre LangChain4j. Analise o erro de execução abaixo e responda ")
          .append("no idioma '").append(idioma).append("'.\n\n")
          .append("Como o archflow executa um fluxo: o fluxo é um documento JSON com steps[]; cada passo ")
          .append("tem type, componentId e configuration, e as arestas ficam em steps[].connections. O motor ")
          .append("executa cada passo por um de três caminhos: (1) um passo próprio do motor (controle, ")
          .append("aprovação, decisão, agente MCP, orquestração); (2) um adapter LangChain4j, para nós de ")
          .append("chat, embedding, memória, vector store e RAG cujo provider está registrado no servidor; ")
          .append("(3) um componente do catálogo (agent, assistant, tool) nomeado em componentId.\n\n")
          .append("O que \"component not found: X\" significa: o motor não encontrou execução para o nó X ")
          .append("por nenhum dos três caminhos — X não é um passo do motor, não é um provider de adapter ")
          .append("registrado para aquele tipo e não é um componente do catálogo deste servidor. Não é falha ")
          .append("de rede nem de dados. Quando o texto depois de \"—\" diz o motivo (por exemplo, ")
          .append("configuration.allowedComponents ou archflow.components.require-allowlist), a causa é ")
          .append("esse motivo. A correção é trocar o nó por um tipo ou componente que executa, ou instalar ")
          .append("um plugin (fat-jar em archflow.plugins.directory) que forneça o componente.\n\n");
        appendServerCatalogForDiagnosis(sb, serverCatalog);
        sb.append("REGRA: sugira SOMENTE tipos de nó e componentes que aparecem nas listas acima. NUNCA ")
          .append("invente nomes de componente, plugin, conector ou ferramenta. Se nada nas listas resolve, ")
          .append("diga que este servidor não tem execução para o que o nó pede.\n\n")
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

    /**
     * As listas que o explain-error pode citar, todas tiradas do servidor. Sem catálogo do servidor não
     * há lista, e a regra de não inventar nomes continua valendo.
     */
    private static void appendServerCatalogForDiagnosis(StringBuilder sb, ServerFlowCatalog serverCatalog) {
        if (serverCatalog == null) {
            sb.append("(Este servidor não informou o próprio catálogo; não cite componentes por nome.)\n\n");
            return;
        }
        sb.append("Tipos de nó que executam neste servidor sem precisar de componente do catálogo: ")
          .append(joinOrNone(serverCatalog.executableNodeTypes())).append("\n")
          .append("Tipos de nó do designer SEM execução neste servidor (o passo falha com ")
          .append("\"component not found\"; agent, assistant e tool só executam apontando em componentId ")
          .append("para um componente do catálogo): ")
          .append(joinOrNone(serverCatalog.nonExecutableNodeTypes())).append("\n")
          .append("Componentes executáveis deste servidor (type do nó → componente):\n");
        List<ServerFlowCatalog.ExecutableComponent> componentes = serverCatalog.executableComponents();
        if (componentes.isEmpty()) {
            sb.append("(nenhum)\n");
        }
        for (ServerFlowCatalog.ExecutableComponent c : componentes) {
            sb.append("- type=").append(c.nodeType())
              .append(c.adapter() ? ", configuration.provider=" : ", componentId=").append(c.component())
              .append(" (").append(nullToEmpty(c.kind())).append(")\n");
        }
        sb.append('\n');
    }

    private static String joinOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "(nenhum)" : String.join(", ", values);
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

    /**
     * Sem catálogo no corpo, o catálogo é o do servidor, só com o que o motor executa (RCHFLW-107).
     * Antes o prompt dizia "catálogo vazio" e a resposta vinha sem nenhum passo, embora o servidor
     * conheça os próprios componentes. O catálogo do corpo, quando vem, continua mandando: é o contrato
     * de quem chama (ADR-0001).
     */
    static String buildNlToFlowPrompt(NlToFlowRequest r, ServerFlowCatalog serverCatalog) {
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

        if ((r.catalogo() == null || r.catalogo().isEmpty()) && serverCatalog != null) {
            appendServerCatalogForFlow(sb, serverCatalog);
            appendRegrasDeExpressao(sb, serverCatalog.executableNodeTypes());
            appendRegrasDeDecisao(sb, serverCatalog.executableNodeTypes());
            appendRegrasDaMensagemDoLlm(sb, serverCatalog);
        } else {
            sb.append("CATÁLOGO de plugins disponíveis:\n");
            appendCatalogo(sb, r.catalogo());
        }

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

    /**
     * O catálogo do servidor no formato do prompt, com o {@code nodeType} de cada item: é o
     * {@code type} que o nó grava no documento, e o que decide por onde o motor o executa.
     */
    private static void appendServerCatalogForFlow(StringBuilder sb, ServerFlowCatalog serverCatalog) {
        sb.append("CATÁLOGO deste servidor (somente o que o motor executa). Para cada step, ")
          .append("'nodeType' é o nodeType do item e 'component' é o seu plugin; 'componentVersion' é null. ")
          .append("Nunca preencha chaves de API nem segredos em 'parameters': eles vêm da configuração ")
          .append("do servidor.\n");
        List<ServerFlowCatalog.ExecutableComponent> componentes = serverCatalog.executableComponents();
        for (ServerFlowCatalog.ExecutableComponent c : componentes) {
            sb.append("- plugin=").append(c.component())
              .append(", componentName=").append(nullToEmpty(c.name()))
              .append(", pluginKind=").append(nullToEmpty(c.kind()))
              .append(", nodeType=").append(c.nodeType());
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(", descricao=").append(c.description());
            }
            sb.append('\n');
            for (CatalogItemDto.OperationDto op : c.operations()) {
                if (op == null) {
                    continue;
                }
                sb.append("    operacao=").append(nullToEmpty(op.id()));
                appendProps(sb, op.inputs() == null ? List.of() : op.inputs().stream()
                        .map(p -> p.name() + ':' + nullToEmpty(p.type())
                                + (p.required() ? "(obrigatório)" : "(opcional)"))
                        .toList());
                sb.append('\n');
            }
            if (c.operations().isEmpty() && !c.config().isEmpty()) {
                sb.append("    propriedades de configuração");
                appendProps(sb, c.config().stream()
                        .map(k -> k.name() + ':' + nullToEmpty(k.type())
                                + (k.required() ? "(obrigatório)" : "(opcional)"))
                        .toList());
                sb.append('\n');
            }
        }
        List<String> tipos = serverCatalog.executableNodeTypes();
        sb.append("Tipos de nó do motor que executam sem plugin (plugin=nodeType=o tipo, pluginKind=node): ")
          .append(joinOrNone(tipos)).append('\n');
        if (componentes.isEmpty() && tipos.isEmpty()) {
            sb.append("(catálogo vazio — nenhum plugin disponível)\n");
        }
    }

    /**
     * A gramática das expressões dos nós condition/switch, quando o catálogo os oferece (RCHFLW-116). Sem ela o
     * modelo escrevia {@code ticket.urgente == true}: sem {@code ${}}, lendo um campo que nenhum passo produz, e
     * o ramo nunca mudava. As regras são as do {@code ConditionEvaluator} e do {@code ControlExpressionRules}.
     */
    private static void appendRegrasDeExpressao(StringBuilder sb, List<String> tipos) {
        if (!tipos.contains("condition") && !tipos.contains("switch")) {
            return;
        }
        sb.append("Expressões dos nós condition e switch (recusadas antes de executar se fugirem disto): ")
          .append("'parameters.conditionExpression' é operando op operando, com op ==, !=, >, >=, <, <= ou ")
          .append("contains, unidos por && ou ||, sem parênteses; cada operando é ${caminho}, texto entre aspas ")
          .append("('urgente'), número ou true/false — nunca palavra solta. 'parameters.switchExpression' é um ")
          .append("texto com ao menos um ${caminho}. O caminho começa pelo 'name' de um step anterior: ${<name>} ")
          .append("é a saída dele, ${<name>.<campo>} um campo dela. A saída de um llm-chat é o texto da resposta, ")
          .append("sem campos. Para classificar e ramificar, use um step decision e ramifique pela RESPOSTA dele ")
          .append("(ver as regras do decision). Uma referência ${caminho} sem valor na execução FALHA o passo da ")
          .append("condition/switch, que nunca escolhe um ramo por ela. A entrada do run costuma ser o texto do ")
          .append("cliente, e aí ${<name do step input>.valor} não existe: para comparar um valor que o cliente ")
          .append("escreve no texto (um valor em reais, uma data, uma quantidade), extraia-o antes — um step ")
          .append("decision com uma pergunta choice (uma opção por faixa, mais uma para \"não diz\") ou score sobre ")
          .append("ele, e um switch em ${<name do decision>.decision} cujo default vai ao approval (ver o exemplo ")
          .append("do reembolso nas regras do decision). Nunca peça a um llm-chat o número para compará-lo com >, ")
          .append(">=, < ou <=: ele responde 1.500, 1.500,00 ou R$ 900 — os dois últimos falham o passo, e 1.500 é ")
          .append("lido como 1,5 e escolhe o ramo errado. Compare um campo da entrada direto só ")
          .append("quando ela é um JSON que o traz, com o campo declarado nos ioFields do step input.\n");
    }

    /**
     * A "Mensagem do usuário" do llm-chat (RCHFLW-120), quando o catálogo oferece o llm-chat. O motor passa a saída
     * de cada passo ao seguinte: o llm-chat depois de um decision/condition/switch recebia o JSON da classificação e
     * respondia a ele. As regras de caminho são as das condições ({@code ControlExpressionRules.checkTemplate}).
     */
    private static void appendRegrasDaMensagemDoLlm(StringBuilder sb, ServerFlowCatalog serverCatalog) {
        boolean temLlmChat = serverCatalog.executableNodeTypes().contains("llm-chat")
                || serverCatalog.executableComponents().stream().anyMatch(c -> "llm-chat".equals(c.nodeType()));
        if (!temLlmChat) {
            return;
        }
        sb.append("Mensagem do usuário do llm-chat: sem 'parameters.userMessage', o llm-chat recebe como mensagem ")
          .append("a saída do passo anterior. Depois de um decision, condition ou switch essa saída é o JSON da ")
          .append("classificação (a condition e o switch repassam o que receberam), e o LLM responde ao JSON, não ")
          .append("ao cliente: nesse caso preencha 'parameters.userMessage' com \"${<name do step input>}\" (a ")
          .append("mensagem que chegou à execução). É um texto com referências ${caminho}, pelas mesmas regras de ")
          .append("caminho das condições: texto fora de ${} vai como está (\"Cliente: ${receber}\"), ${input} é a ")
          .append("saída do passo anterior, e um valor que não é texto vai como JSON. Uma referência sem valor ")
          .append("falha o passo. Use ${<name do step input>} sozinho (nunca ${<name do step input>.message}): como ")
          .append("referência única ele já dá a mensagem, tanto do texto digitado no Executar quanto de uma API que ")
          .append("manda {\"message\": \"…\"}.\n");
    }

    /**
     * Como classificar e ramificar com o step decision, quando o catálogo o oferece (RCHFLW-116, 2ª rodada). O
     * modelo montava um decision sem provider nem model (o http-decisions padrão não decide sem model) e
     * ramificava pelo {@code route} — que é a confiança da resposta, não a categoria. Os campos são os que o
     * {@code DecisionComponent} grava; a regra das expressões, a do {@code ConditionEvaluator}. O provedor é o
     * {@code llm}, o único que roda sem um model que ninguém tem como escolher (não há catálogo de modelos de
     * decisão).
     */
    private static void appendRegrasDeDecisao(StringBuilder sb, List<String> tipos) {
        if (!tipos.contains(DecisionComponent.COMPONENT_ID)) {
            return;
        }
        sb.append("Step decision (classificar e ramificar): o 'route' dele (AUTO, REVIEW ou ESCALATE) mede a ")
          .append("CONFIANÇA da resposta, não diz a categoria — um pedido urgente e um comum respondidos com a ")
          .append("mesma confiança têm o mesmo route. Ramifique pela RESPOSTA, num step condition ou switch logo ")
          .append("depois do decision. 'parameters' do decision: provider 'llm' (o LLM da plataforma; sem 'model', ")
          .append("herda o modelo da plataforma). Não use http-decisions nem langchain4j-decisions sem um model de ")
          .append("decisão dado na descrição: eles não decidem sem 'model', e nunca invente um model. Com o provider ")
          .append("llm a confiança fica limitada a 0,89, abaixo do AUTO padrão (0,9): o route AUTO nunca sai. ")
          .append("questions: objeto id → {type: choice|score|noul, instructions, criteria} (choice: opção → ")
          .append("descrição; score: lista de níveis, do menor ao maior; noul: {true, false}); primary: a pergunta ")
          .append("cujo valor vira 'decision'; gate: as perguntas que decidem o route. A saída, pelo 'name' do ")
          .append("step: ${<name>.answers.<pergunta>.result} (noul: true/false), ${<name>.answers.<pergunta>.choice} ")
          .append("(choice: a opção), ${<name>.decision} (o valor da primary — a opção de um choice, mas a ")
          .append("PROBABILIDADE de um noul), ${<name>.route}; uma decisão que falhou tem route ESCALATE e nenhuma ")
          .append("resposta. Exemplo para \"mandar ao humano quando for urgente; senão, responder com um LLM\": step ")
          .append("'triagem' (decision) com parameters {\"provider\":\"llm\",\"questions\":{\"urgente\":")
          .append("{\"type\":\"noul\",\"instructions\":\"O pedido é urgente?\",\"criteria\":{\"true\":")
          .append("\"algo parado ou fora do ar\",\"false\":\"pode esperar\"}}},\"gate\":[\"urgente\"]}; depois ")
          .append("um step 'e_urgente' (condition) com conditionExpression \"${triagem.answers.urgente.result} == ")
          .append("true || ${triagem.route} == 'ESCALATE'\" (a falha também vai ao humano), ligado ao approval ")
          .append("(ramo true) e ao llm-chat (ramo false), que leva parameters {\"userMessage\":\"${receber}\"} ")
          .append("('receber' é o step input: o llm-chat responde à mensagem do cliente, não ao JSON da ")
          .append("triagem). Os links não levam o ramo: diga em 'observacoes' qual ")
          .append("link sai pelo true e qual pelo false. Por categoria: switch com switchExpression ")
          .append("\"${<name>.decision}\" (primary = um choice) e um caso por opção. Exemplo para \"reembolso ")
          .append("acima de 500 reais pede aprovação do gerente; abaixo, responder automaticamente\" (o valor vem ")
          .append("no texto do cliente): step 'faixa' (decision) com parameters {\"provider\":\"llm\",")
          .append("\"questions\":{\"valor\":{\"type\":\"choice\",\"instructions\":\"Qual o valor do reembolso ")
          .append("pedido?\",\"criteria\":{\"acima_de_500\":\"mais de R$ 500\",\"ate_500\":\"R$ 500 ou menos\",")
          .append("\"sem_valor\":\"o pedido não diz o valor\"}}},\"primary\":\"valor\"}; depois um step ")
          .append("'por_valor' (switch) com switchExpression \"${faixa.decision}\" e switchCases \"ate_500\": ")
          .append("o caso ate_500 vai ao llm-chat e o default (onde caem acima_de_500, sem_valor e a decisão que ")
          .append("falhou) ao approval — nunca ${<name do step input>.valor} > 500 sobre texto livre. Os links do ")
          .append("switch também não levam o caso: diga em 'observacoes' qual link sai pelo caso ate_500 e qual ")
          .append("pelo default.\n");
    }

    private static void appendProps(StringBuilder sb, List<String> props) {
        if (!props.isEmpty()) {
            sb.append(" propriedades=[").append(String.join(", ", props)).append(']');
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
