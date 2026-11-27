package br.com.archflow.template.support;

import br.com.archflow.model.Workflow;
import br.com.archflow.template.AbstractWorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateConnection;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Atendimento ao cliente com base de conhecimento, triagem por assunto e aprovação humana.
 *
 * <p>O grafo, todo com nós que o motor executa (RCHFLW-203):
 * <pre>
 * inquiry (input) → kb-search (vector-search na base escolhida)
 *   → [sentiment (decision noul) → is-frustrated (condition)]      só com enableSentiment
 *   → classify (decision choice) → low-confidence (condition) → by-intent (switch)
 *   → billing-agent | technical-agent | sales-agent | general-agent (llm-chat) → output
 * is-frustrated:true, low-confidence:true e by-intent:default → escalated-draft (llm-chat)
 *   → handoff (approval do rascunho) → output
 * </pre>
 *
 * <p>Por que cada peça é assim:
 * <ul>
 *   <li><b>Busca logo depois da entrada.</b> O nó de base de conhecimento busca o texto da própria entrada
 *       ({@code KnowledgeNodeComponent}); depois do classificador ele buscava a resposta do classificador (RC5).
 *       Sem {@code minScore}: vale o padrão da plataforma (0.0) — com 0.7 os trechos relevantes, que pontuam
 *       0.55–0.67 com os embeddings do deploy, eram todos descartados (RC15).</li>
 *   <li><b>Triagem pela resposta.</b> O {@code decision} com provedor {@code llm} classifica; o {@code switch}
 *       ramifica por {@code ${classify.decision}} (a opção escolhida). O {@code route} do decision é a confiança,
 *       não a categoria — ele só alimenta o portão {@code low-confidence}. A decisão que falha sai com
 *       {@code route} ESCALATE e vai ao humano: a falha nunca vira um palpite. O {@code default} do switch também
 *       vai ao humano, como as regras do copiloto mandam.</li>
 *   <li><b>{@code confidenceThreshold} é o {@code thresholds.review} do classificador</b>: abaixo dele a rota sai
 *       ESCALATE e a pergunta vai à aprovação. O {@code auto} fica em 1.0 só para qualquer valor de 0 a 1 caber na
 *       regra {@code review <= auto} do {@code RoutePolicy}; o grafo não usa o ramo AUTO.</li>
 *   <li><b>O sentimento decide pela resposta.</b> O {@code is-frustrated} manda ao humano o {@code result} true
 *       e a rota ESCALATE; o {@code thresholds.review} do {@code sentiment} é 0.1, para a rota só escalar a
 *       decisão que falhou ou a probabilidade perto de 0.5 — com o 0.5 padrão, um cliente só um pouco chateado
 *       (probabilidade 0.4) ia à aprovação.</li>
 *   <li><b>{@code enableSentiment} muda o grafo</b> ({@link #getDefinition(Map)}): desligado, os dois passos do
 *       sentimento não existem. Antes era um par de arestas cosméticas para o mesmo destino (RC6).</li>
 *   <li><b>O humano aprova o rascunho, não um sim/não às cegas.</b> O {@code escalated-draft} escreve a resposta
 *       antes do {@code handoff}, que não tem {@code approvalProposal}: a proposta na fila é a entrada do passo,
 *       o rascunho ({@code HumanApprovalStep.proposalOf}). Aprovar retoma no {@code output}, que repassa a
 *       entrada — o rascunho; editar (EDITED) faz da versão do revisor a saída do {@code handoff} e a entrada do
 *       {@code output} (RCHFLW-121); recusar encerra a execução sem resposta. Antes a aprovação vinha antes da
 *       resposta: o revisor via só o JSON da triagem, e a edição dele era descartada, porque o chat seguinte
 *       monta a mensagem só da {@code userMessage} (RCHFLW-203).</li>
 *   <li><b>Instrução no {@code systemPrompt}, dado na {@code userMessage}</b> com {@code ${inquiry}} e
 *       {@code ${kb-search}}: são as chaves que o chat lê (RC4). O id da entrada não é {@code input} porque
 *       {@code ${input}} é a saída do passo anterior, não a mensagem do cliente. O {@code systemPrompt} não resolve
 *       {@code ${...}} na execução; o idioma entra nele no install, pelo marcador {@code {{responseLanguage}}}.</li>
 * </ul>
 *
 * <p>{@code maxIterations} e {@code escalationEmail} continuam aceitos para não quebrar quem já instala pela API,
 * mas nada os executa: cada agente é uma chamada de chat sem laço, e o archflow não envia e-mail — a escalada vai
 * à fila de aprovações.</p>
 */
public class CustomerSupportTemplate extends AbstractWorkflowTemplate {

    private static final String ID = "customer-support";
    // 2.0.0: o grafo mudou por inteiro (RCHFLW-203) — ids de passo, tipos e ramos não são os da 1.0.0.
    private static final String VERSION = "2.0.0";

    private static final String DESCRIPTION = "Searches the knowledge base you choose, classifies the inquiry as "
            + "billing, technical, sales or general and answers it with the matching LLM agent. Optionally "
            + "flags frustrated customers. For frustrated customers, and for inquiries the classifier cannot "
            + "classify with enough confidence, an LLM drafts the reply from the knowledge base and the draft "
            + "waits in the archflow approval queue: the reviewer approves it as the answer, edits it, or "
            + "rejects it to end the run without an answer. No e-mail is sent.";

    /** Regras comuns aos agentes que respondem ao cliente; o idioma entra no install. */
    private static final String ANSWER_RULES = """

            Answer only from the knowledge base excerpts in the message and from what the customer wrote. \
            If the excerpts do not cover the question, say that you could not find that information and \
            suggest contacting the support team; never invent prices, policies, deadlines or procedures. \
            Reply to the customer in the language whose ISO 639-1 code is "{{responseLanguage}}".""";

    /** A mensagem do cliente e os trechos da base — a entrada linear de cada agente seria o JSON da triagem. */
    private static final String ANSWER_MESSAGE = """
            Customer message:
            ${inquiry}

            Knowledge base excerpts (JSON):
            ${kb-search}""";

    public CustomerSupportTemplate() {
        super(
                ID,
                "Customer Support with RAG",
                DESCRIPTION,
                "support",
                Set.of("rag", "support", "routing", "decision", "human-approval"),
                buildParameters()
        );
    }

    private static Map<String, ParameterDefinition> buildParameters() {
        Map<String, ParameterDefinition> params = new LinkedHashMap<>();

        params.put("knowledgeBaseId", ParameterDefinition.required(
                "knowledgeBaseId",
                "ID of the knowledge base the flow searches before answering",
                String.class
        ));

        params.put("maxIterations", ParameterDefinition.optional(
                "maxIterations",
                "Not used: each agent answers with a single LLM call, with no iteration loop. "
                        + "Accepted for compatibility",
                Integer.class,
                5
        ));

        params.put("escalationEmail", ParameterDefinition.optional(
                "escalationEmail",
                "Not used: escalation goes to the archflow approval queue and no e-mail is sent. "
                        + "Accepted for compatibility",
                String.class,
                "support@company.com"
        ));

        params.put("enableSentiment", ParameterDefinition.optional(
                "enableSentiment",
                "Adds a step that asks the LLM whether the customer is frustrated; the reply to a frustrated "
                        + "customer is drafted by the LLM and waits for human approval",
                Boolean.class,
                true
        ));

        params.put("responseLanguage", ParameterDefinition.enumParameter(
                "responseLanguage",
                "Language of the answers (ISO 639-1 code)",
                "en", "pt", "es", "fr", "de"
        ));

        params.put("confidenceThreshold", ParameterDefinition.optional(
                "confidenceThreshold",
                "Minimum confidence of the intent classification (0.0-1.0) for an automated answer; below it "
                        + "the LLM drafts the reply and it waits for human approval. The LLM classifier is not calibrated and caps its "
                        + "confidence at 0.89, so a value above 0.89 sends every inquiry to a human",
                Double.class,
                0.8
        ));

        return params;
    }

    /**
     * Além do tipo, a faixa do {@code confidenceThreshold}: fora de 0–1 o {@code RoutePolicy} do classificador
     * recusa a configuração e o fluxo instalado não abriria para executar (RCHFLW-203). Recusar no install diz o
     * motivo a quem instala.
     */
    @Override
    public void validateParameters(Map<String, Object> parameters) {
        super.validateParameters(parameters);
        if (parameters != null && parameters.get("confidenceThreshold") instanceof Number n) {
            double value = n.doubleValue();
            if (value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(
                        "Parameter 'confidenceThreshold' must be between 0.0 and 1.0, got " + value);
            }
        }
    }

    @Override
    public WorkflowTemplateDefinition getDefinition() {
        return getDefinition(Map.of());
    }

    @Override
    public WorkflowTemplateDefinition getDefinition(Map<String, Object> parameters) {
        // Só o enableSentiment muda a forma do grafo; os outros parâmetros entram pelos marcadores {{x}}.
        Object sentiment = parameters == null ? null : parameters.get("enableSentiment");
        boolean enableSentiment = !(sentiment instanceof Boolean b) || b;

        Map<String, TemplateNode> nodes = new LinkedHashMap<>();
        Map<String, TemplateConnection> connections = new LinkedHashMap<>();

        node(nodes, "inquiry", "input", "Customer inquiry", Map.of());
        node(nodes, "kb-search", "vector-search", "Knowledge base search", Map.of(
                "knowledgeBaseId", "{{knowledgeBaseId}}",
                "topK", 5));
        edge(connections, "inquiry", "kb-search");

        if (enableSentiment) {
            node(nodes, "sentiment", "decision", "Is the customer frustrated?", Map.of(
                    "provider", "llm",
                    "state", Map.of("message", "${inquiry}"),
                    "questions", Map.of("frustrated", Map.of(
                            "type", "noul",
                            "instructions", "Is the customer frustrated, angry or upset?",
                            "criteria", Map.of(
                                    "true", "complains, threatens to leave, uses angry or hostile language, "
                                            + "says the problem keeps happening",
                                    "false", "neutral or polite tone, even when reporting a problem"))),
                    "primary", "frustrated",
                    "gate", List.of("frustrated"),
                    // Com os limiares padrão (review 0.5) uma probabilidade entre 0.25 e 0.75 sai ESCALATE: no
                    // servidor, "a fatura veio com uma cobrança que não reconheço" deu 0.4 e foi à aprovação, e quase
                    // todo pedido iria ao humano. Aqui só a moeda ao ar (0.45–0.55) e a decisão que falhou escalam;
                    // fora disso quem decide é a resposta (result). AUTO não é usado (RCHFLW-203).
                    "thresholds", Map.of("auto", 1.0, "review", 0.1)));
            // A decisão que falhou não tem answers.frustrated: o || ESCALATE a manda ao humano em vez de falhar
            // o passo (ConditionEvaluator.evaluateRequiringValues decide pelo lado conhecido).
            node(nodes, "is-frustrated", "condition", "Frustrated or undecided?", Map.of(
                    "conditionExpression",
                    "${sentiment.answers.frustrated.result} == true || ${sentiment.route} == 'ESCALATE'"));
            edge(connections, "kb-search", "sentiment");
            edge(connections, "sentiment", "is-frustrated");
            branch(connections, "is-frustrated", "escalated-draft", "true");
        }

        node(nodes, "classify", "decision", "Classify the inquiry", Map.of(
                "provider", "llm",
                "state", Map.of("message", "${inquiry}"),
                "questions", Map.of("intent", Map.of(
                        "type", "choice",
                        "instructions", "Which team should handle this customer inquiry?",
                        "criteria", Map.of(
                                "billing", "invoices, payments, charges, refunds, pricing",
                                "technical", "errors, bugs, outages, setup or how-to problems with the product",
                                "sales", "product information, plans, upgrades, new purchases",
                                "general", "anything else: general questions and information requests"))),
                "primary", "intent",
                "gate", List.of("intent"),
                "thresholds", Map.of("auto", 1.0, "review", "{{confidenceThreshold}}")));
        if (enableSentiment) {
            branch(connections, "is-frustrated", "classify", "false");
        } else {
            edge(connections, "kb-search", "classify");
        }

        node(nodes, "low-confidence", "condition", "Low confidence or failed?", Map.of(
                "conditionExpression", "${classify.route} == 'ESCALATE'"));
        edge(connections, "classify", "low-confidence");
        branch(connections, "low-confidence", "escalated-draft", "true");

        node(nodes, "by-intent", "switch", "Route by intent", Map.of(
                "switchExpression", "${classify.decision}",
                "switchCases", "billing\ntechnical\nsales\ngeneral"));
        branch(connections, "low-confidence", "by-intent", "false");

        agent(nodes, "billing-agent", "Billing agent", "You are a billing support agent. You help with "
                + "invoices, payments, charges, refunds and pricing questions.");
        agent(nodes, "technical-agent", "Technical support agent", "You are a technical support agent. You "
                + "help with errors, bugs, outages and setup problems, with clear troubleshooting steps.");
        agent(nodes, "sales-agent", "Sales agent", "You are a sales agent. You help with product questions, "
                + "plans, upgrades and new purchases. Be helpful, not pushy.");
        agent(nodes, "general-agent", "General support agent", "You are a customer support agent. You answer "
                + "general questions and information requests.");
        branch(connections, "by-intent", "billing-agent", "billing");
        branch(connections, "by-intent", "technical-agent", "technical");
        branch(connections, "by-intent", "sales-agent", "sales");
        branch(connections, "by-intent", "general-agent", "general");
        // Um valor sem caso não sai de um choice respondido; se sair, vai ao humano em vez de falhar o passo.
        branch(connections, "by-intent", "escalated-draft", "default");

        // O motivo só cita a frustração quando o passo que a detecta existe: com enableSentiment=false nada a
        // verifica, e o revisor e o LLM leriam um motivo que o grafo não produz (RCHFLW-203).
        String reason = enableSentiment
                ? "the customer may be frustrated, or the inquiry could not be classified with enough confidence"
                : "the inquiry could not be classified with enough confidence";
        agent(nodes, "escalated-draft", "Draft reply for review", "You are a senior customer support agent. "
                + "This inquiry was escalated because " + reason + ". Write the reply to the customer: a human "
                + "reviewer reads it, and may edit it, before it becomes the answer, so do not mention the review. "
                + "Acknowledge the customer's situation with empathy and answer what was asked.");
        edge(connections, "escalated-draft", "handoff");
        node(nodes, "handoff", "approval", "Human approval of the draft", Map.of(
                "approvalTitle", "Customer support: escalated inquiry",
                "approvalDescription", "Escalated because " + reason + ". The proposal is a reply drafted by "
                        + "the LLM from the knowledge base; the customer has not received anything yet. Approve to "
                        + "make the draft the flow's answer, edit it to answer with your version instead, or "
                        + "reject to end the run without an answer."));
        edge(connections, "handoff", "output");

        node(nodes, "output", "output", "Answer", Map.of());
        for (String answering : List.of("billing-agent", "technical-agent", "sales-agent", "general-agent")) {
            edge(connections, answering, "output");
        }

        return new WorkflowTemplateDefinition(
                ID,
                VERSION,
                getDisplayName(),
                getDescription(),
                getCategory(),
                getTags(),
                // Sem knowledgeBaseId: é obrigatório e não tem padrão. Com "" a prévia da galeria (GET, com os
                // defaults) montava a busca sem base e a marcava "sem executor", e a tela mandava trocar o nó que o
                // install preenche; sem padrão o marcador {{knowledgeBaseId}} fica na prévia até o install
                // (RCHFLW-203/204).
                Map.of(
                        "maxIterations", 5,
                        "escalationEmail", "support@company.com",
                        "enableSentiment", true,
                        "responseLanguage", "en",
                        "confidenceThreshold", 0.8
                ),
                new WorkflowTemplateDefinition.TemplateStructure("inquiry", nodes, connections)
        );
    }

    private static void node(Map<String, TemplateNode> nodes, String id, String type, String name,
                             Map<String, Object> config) {
        nodes.put(id, new TemplateNode(id, type, name, config));
    }

    /** Um llm-chat que responde ao cliente: persona + regras comuns no prompt, mensagem e trechos na entrada. */
    private static void agent(Map<String, TemplateNode> nodes, String id, String name, String persona) {
        node(nodes, id, "llm-chat", name, Map.of(
                "systemPrompt", persona + ANSWER_RULES,
                "userMessage", ANSWER_MESSAGE));
    }

    private static void edge(Map<String, TemplateConnection> connections, String source, String target) {
        String id = "c" + (connections.size() + 1);
        connections.put(id, new TemplateConnection(id, source, target, null, null));
    }

    private static void branch(Map<String, TemplateConnection> connections, String source, String target,
                               String branch) {
        String id = "c" + (connections.size() + 1);
        connections.put(id, TemplateConnection.branch(id, source, target, branch));
    }

    @Override
    protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
        String knowledgeBaseId = getString(parameters, "knowledgeBaseId", "");
        int maxIterations = getInt(parameters, "maxIterations", 5);
        String escalationEmail = getString(parameters, "escalationEmail", "support@company.com");
        boolean enableSentiment = getBoolean(parameters, "enableSentiment", true);
        String responseLanguage = getString(parameters, "responseLanguage", "en");
        double confidenceThreshold = parameters.get("confidenceThreshold") instanceof Number n
                ? n.doubleValue() : 0.8;

        // Metadados da instância; o grafo executável é o de getDefinition(parameters), que o install grava.
        Map<String, Object> meta = new HashMap<>();
        meta.put("template", ID);
        meta.put("templateVersion", VERSION);
        // Texto ISO, não Instant: a configuração do workflow instalado vai ao store JDBC, que não serializa
        // java.time (RCHFLW-200).
        meta.put("createdAt", java.time.Instant.now().toString());
        meta.put("description", DESCRIPTION);
        meta.put("knowledgeBaseId", knowledgeBaseId);
        meta.put("maxIterations", maxIterations);
        meta.put("escalationEmail", escalationEmail);
        meta.put("enableSentiment", enableSentiment);
        meta.put("responseLanguage", responseLanguage);
        meta.put("confidenceThreshold", confidenceThreshold);

        return new Workflow() {
            @Override
            public String getId() {
                return name;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Customer Support workflow created from template";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return new HashMap<>(meta);
            }
        };
    }
}
