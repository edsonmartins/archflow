package br.com.archflow.api.assist;

import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.NlToFlowRequest;
import br.com.archflow.api.assist.impl.AssistServiceImpl;
import br.com.archflow.api.catalog.impl.CatalogControllerImpl;
import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.NodeSupport;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A API de assistência conhece o archflow (RCHFLW-107).
 *
 * <p>Na homologação, {@code nl-to-flow} sem catálogo no corpo devolveu um fluxo vazio ("O catálogo está
 * vazio"), e {@code explain-error} para "component not found: transform" falou de "plataforma iPaaS" e
 * sugeriu {@code data-transform}, {@code mapper} e {@code jq}. O teste lê o prompt que chega ao modelo:
 * a resposta do modelo não é do archflow, o que ele recebe é.</p>
 */
@DisplayName("Assistência: prompts com o catálogo do servidor")
class AssistDoArchflowTest {

    private final List<String> prompts = new ArrayList<>();
    private String resposta = "{\"workflow\":{\"steps\":[],\"links\":[]},\"lacunas\":[],\"diagnostico\":\"x\"}";
    /** Como a resposta do modelo falso termina (o nl-to-flow lê o motivo do fim e os tokens de saída). */
    private FinishReason fim = FinishReason.STOP;
    private Integer tokensDeSaida;
    private final List<LLMResolutionRequest> pedidosDeModelo = new ArrayList<>();
    private ServerFlowCatalog serverCatalog;
    private AssistServiceImpl assist;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        ComponentCatalog catalog = new ArchflowBeanConfiguration().componentCatalog(semPlugins);
        NodeSupport nodeSupport = new NodeSupport(new DefaultFlowStepFactory(
                catalog, mock(DynamicWorkflowService.class), null, null, null));
        serverCatalog = new ServerFlowCatalog(new CatalogControllerImpl(catalog), nodeSupport);

        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(inv -> {
            prompts.add(inv.getArgument(0));
            return resposta;
        });
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest pedido = inv.getArgument(0);
            prompts.add(((UserMessage) pedido.messages().getLast()).singleText());
            return ChatResponse.builder().aiMessage(AiMessage.from(resposta)).finishReason(fim)
                    .tokenUsage(tokensDeSaida == null ? null : new TokenUsage(100, tokensDeSaida)).build();
        });
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolveModel(any())).thenAnswer(inv -> {
            pedidosDeModelo.add(inv.getArgument(0));
            return model;
        });
        when(resolver.resolve(any())).thenReturn(ResolvedLLMConfig.builder().build());
        assist = new AssistServiceImpl(resolver, ResolvedLLMConfig.builder().maxTokens(1024).build(), null,
                serverCatalog);
    }

    @Test
    @DisplayName("nl-to-flow sem catálogo no corpo usa o catálogo executável do servidor")
    void nlToFlowUsaOCatalogoDoServidor() {
        assist.nlToFlow(new NlToFlowRequest("triagem de pedidos de suporte", null, "pt-BR"));

        String prompt = prompts.getFirst();
        assertThat(prompt).doesNotContain("catálogo vazio");
        assertThat(serverCatalog.executableComponents()).isNotEmpty();
        assertThat(serverCatalog.executableComponents())
                .allSatisfy(c -> assertThat(prompt).contains("plugin=" + c.component() + ","));
        assertThat(prompt).contains("plugin=text-transform-tool,").contains("nodeType=tool");
        // Só o que executa: um tipo da paleta sem execução não entra como opção de nó.
        assertThat(serverCatalog.nonExecutableNodeTypes()).contains("transform");
        assertThat(prompt).doesNotContain("plugin=transform,");
    }

    @Test
    @DisplayName("nl-to-flow ensina a gramática das expressões e avisa da condição que o motor recusaria")
    void nlToFlowCondicaoGeradaComAviso() {
        resposta = """
                {"workflow":{"steps":[
                  {"name":"receber","component":"input","nodeType":"input","parameters":{}},
                  {"name":"classificar","component":"llm-chat","nodeType":"llm-chat","parameters":{}},
                  {"name":"urgente","component":"condition","nodeType":"condition",
                   "parameters":{"conditionExpression":"ticket.urgente == true"}},
                  {"name":"ok","component":"condition","nodeType":"condition",
                   "parameters":{"conditionExpression":"${classificar} contains 'URGENTE'"}}],
                 "links":[]},"lacunas":[],"observacoes":"rascunho"}
                """;

        var r = assist.nlToFlow(new NlToFlowRequest("rotear urgentes para um humano", null, "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("cada operando é ${caminho}, texto entre aspas")
                .contains("O caminho começa pelo 'name' de um step anterior");
        // O rascunho segue o contrato (os steps como vieram); o aviso diz o step, o campo e o motivo.
        assertThat(r.workflow().steps()).hasSize(4);
        assertThat(r.observacoes()).startsWith("rascunho\n")
                .contains("o step \"urgente\", campo conditionExpression, será recusado ao executar")
                .contains("«ticket.urgente» não é um valor")
                .doesNotContain("\"ok\"");
    }

    /**
     * RCHFLW-116, 2ª rodada: o decision gerado vinha sem provider nem model (o http-decisions padrão não decide sem
     * model) e o ramo era o route, que é a confiança. O prompt ensina a ramificar pela resposta, com o provider llm e
     * os campos que o DecisionComponent grava; o decision que não roda vira aviso, com o motivo do próprio componente.
     */
    @Test
    @DisplayName("nl-to-flow ensina a ramificar pela resposta da decisão e avisa do decision que não roda")
    void nlToFlowDecisaoPelaResposta() {
        resposta = """
                {"workflow":{"steps":[
                  {"name":"semModelo","component":"decision","nodeType":"decision",
                   "parameters":{"questions":{"assunto":{"type":"choice","criteria":{"a":"x","b":"y"}}}}},
                  {"name":"semTipo","component":"decision","nodeType":"decision",
                   "parameters":{"provider":"llm","questions":{"urgente":{"instructions":"É urgente?"}}}},
                  {"name":"triagem","component":"decision","nodeType":"decision",
                   "parameters":{"provider":"llm","questions":{"urgente":{"type":"noul"}},"gate":["urgente"]}}],
                 "links":[]},"lacunas":[],"observacoes":null}
                """;

        var r = assist.nlToFlow(new NlToFlowRequest("triagem: urgente ao humano, o resto ao LLM", null, "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("mede a CONFIANÇA da resposta, não diz a categoria")
                .contains("provider 'llm' (o LLM da plataforma; sem 'model', herda o modelo da plataforma)")
                .contains("o route AUTO nunca sai")
                .contains("${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'")
                .doesNotContain("leia ${<name>.decision} ou ${<name>.route}")
                // RCHFLW-123: a decisão que falhou não tem answers — o switch em answers.<pergunta>.choice falharia
                // o passo em vez de ir ao humano. O caminho do humano lê decision (nulo → default) ou o ESCALATE.
                .contains("O caminho do humano lê ${<name>.decision} ou ${<name>.route}, nunca só")
                .contains("answers.<pergunta>.choice e .result não existem, e um switch ou condition que depende só "
                        + "deles FALHA o passo em vez de ir ao humano")
                .contains("Faça o switch sobre ${<name>.decision} (nulo na falha: cai no default) — nunca sobre "
                        + "${<name>.answers.<pergunta>.choice}")
                .contains("e dê à condition sobre answers o || ${<name>.route} == 'ESCALATE'")
                // A recusa fora do gate não escala: o || ESCALATE só cobre a pergunta que está no gate.
                .contains("a recusada só escala se a pergunta estiver no gate (padrão: todas): ponha no gate as "
                        + "perguntas que a condition lê")
                .contains("um caso por opção e o default ao humano (a decisão que falhou cai nele; sem link pelo "
                        + "default, um valor sem caso falha o passo)")
                .doesNotContain("switchExpression \"${triagem.answers.");
        assertThat(r.observacoes())
                .contains("o step \"semModelo\" (decision) usa o provedor http-decisions, que não decide sem 'model'")
                .contains("o step \"semTipo\" (decision) será recusado ao executar: decision: tipo '' da pergunta "
                        + "'urgente' inválido")
                .doesNotContain("\"triagem\"");
    }

    /**
     * RCHFLW-120: o llm-chat que responde depois de um decision/condition recebia o JSON da classificação. O prompt
     * ensina a "Mensagem do usuário" ({@code userMessage}) e o exemplo da triagem a preenche com a Entrada; o
     * rascunho que esquece o campo ou o escreve sem fechar recebe o aviso — o LLM falso devolve os dois casos.
     */
    @Test
    @DisplayName("nl-to-flow ensina a userMessage do llm-chat e avisa do LLM que responderia ao JSON da decisão")
    void nlToFlowMensagemDoUsuario() {
        resposta = """
                {"workflow":{"steps":[
                  {"name":"receber","component":"input","nodeType":"input","parameters":{}},
                  {"name":"triagem","component":"decision","nodeType":"decision",
                   "parameters":{"provider":"llm","questions":{"urgente":{"type":"noul"}},"gate":["urgente"]}},
                  {"name":"e_urgente","component":"condition","nodeType":"condition",
                   "parameters":{"conditionExpression":"${triagem.answers.urgente.result} == true"}},
                  {"name":"esqueceu","component":"llm-chat","nodeType":"llm-chat","parameters":{}},
                  {"name":"aberta","component":"llm-chat","nodeType":"llm-chat",
                   "parameters":{"userMessage":"Cliente: ${receber"}},
                  {"name":"certo","component":"llm-chat","nodeType":"llm-chat",
                   "parameters":{"userMessage":"${receber}"}},
                  {"name":"resumir","component":"llm-chat","nodeType":"llm-chat","parameters":{}}],
                 "links":[{"source":"receber","target":"triagem"},{"source":"triagem","target":"e_urgente"},
                  {"source":"e_urgente","target":"esqueceu"},{"source":"e_urgente","target":"aberta"},
                  {"source":"triagem","target":"certo"},{"source":"certo","target":"resumir"}]},
                 "lacunas":[],"observacoes":null}
                """;

        var r = assist.nlToFlow(new NlToFlowRequest("triagem: urgente ao humano, o resto ao LLM", null, "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("Mensagem do usuário do llm-chat: sem 'parameters.userMessage'")
                .contains("preencha 'parameters.userMessage' com \"${<name do step input>}\"")
                .contains("${input} é a saída do passo anterior")
                // A referência sozinha à Entrada já dá a mensagem, em texto ou em objeto JSON: nunca ".message".
                .contains("Use ${<name do step input>} sozinho (nunca ${<name do step input>.message})")
                .contains("que leva parameters {\"userMessage\":\"${receber}\"}");
        assertThat(r.observacoes())
                .contains("o step \"esqueceu\" (llm-chat) vem depois de \"e_urgente\" e, sem 'userMessage'")
                .contains("Preencha parameters.userMessage com ${receber}.")
                .contains("o step \"aberta\", campo userMessage, será recusado ao executar: «${receber» não é uma "
                        + "referência")
                // Com o campo, ou depois de um step que não classifica, não há o que avisar.
                .doesNotContain("\"certo\"").doesNotContain("\"resumir\"");
    }

    /**
     * RCHFLW-123: o "Gerar com IA" montou {@code ${entrada.valor} > 500} sobre a entrada em texto livre, e o LLM
     * confirmou um reembolso de R$ 900 sem aprovação. O motor agora falha o passo; o prompt ensina a extrair o valor
     * antes com um decision (nunca um llm-chat que devolva o número para comparar) e traz o exemplo do reembolso —
     * que, devolvido pelo modelo, não gera aviso.
     */
    @Test
    @DisplayName("nl-to-flow ensina que a referência sem valor falha o passo e a extrair o valor do texto antes")
    void nlToFlowExtraiOValorDoTexto() {
        resposta = """
                {"workflow":{"steps":[
                  {"name":"receber","component":"input","nodeType":"input","parameters":{}},
                  {"name":"faixa","component":"decision","nodeType":"decision",
                   "parameters":{"provider":"llm","questions":{"valor":{"type":"choice",
                     "instructions":"Qual o valor do reembolso pedido?","criteria":{"acima_de_500":"mais de R$ 500",
                     "ate_500":"R$ 500 ou menos","sem_valor":"o pedido não diz o valor"}}},"primary":"valor"}},
                  {"name":"por_valor","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${faixa.decision}","switchCases":"ate_500"}},
                  {"name":"gerente","component":"approval","nodeType":"approval","parameters":{}},
                  {"name":"responder","component":"llm-chat","nodeType":"llm-chat",
                   "parameters":{"userMessage":"${receber}"}}],
                 "links":[{"source":"receber","target":"faixa"},{"source":"faixa","target":"por_valor"},
                  {"source":"por_valor","target":"responder"},{"source":"por_valor","target":"gerente"}]},
                 "lacunas":[],"observacoes":null}
                """;

        var r = assist.nlToFlow(new NlToFlowRequest("reembolso acima de 500 reais pede aprovação do gerente; abaixo, "
                + "responder automaticamente", null, "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("Uma referência ${caminho} sem valor na execução FALHA o passo da condition/switch")
                .contains("extraia-o antes — um step decision com uma pergunta choice")
                .contains("e um switch em ${<name do decision>.decision} cujo default vai ao approval")
                // O llm que devolve o número não é caminho: «1.500» seria lido 1,5 e o reembolso de R$ 1.500 iria ao
                // automático; «R$ 900» e «1.500,00» falham o passo. O prompt o proíbe em vez de ensiná-lo.
                .contains("Nunca peça a um llm-chat o número para compará-lo com >, >=, < ou <=")
                .doesNotContain("responder só o número")
                .doesNotContain("${<name do llm>} > 500")
                .contains("switchExpression \"${faixa.decision}\" e switchCases \"ate_500\"")
                .contains("nunca ${<name do step input>.valor} > 500 sobre texto livre")
                // Os links não levam o caso do switch, como não levam o true/false da condition.
                .contains("diga em 'observacoes' qual link sai pelo caso ate_500 e qual pelo default");
        assertThat(String.valueOf(r.observacoes())).doesNotContain("será recusado").doesNotContain("\"por_valor\"");
    }

    @Test
    @DisplayName("nl-to-flow com catálogo no corpo continua usando o do chamador")
    void nlToFlowRespeitaOCatalogoDoCorpo() {
        assist.nlToFlow(new NlToFlowRequest("x", List.of(new NlToFlowRequest.CatalogPlugin(
                "crm-source", "CRM", "1.0", "source", List.of())), "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("plugin=crm-source")
                .doesNotContain("plugin=text-transform-tool");
    }

    @Test
    @DisplayName("explain-error descreve o archflow, o que é 'component not found' e os tipos sem execução")
    void explainErrorDescreveOArchflow() {
        assist.explainError(new ExplainErrorRequest(null, "component not found: transform", null, null,
                null, null, "transformar", "transform", "pt-BR"));

        String prompt = prompts.getFirst();
        assertThat(prompt)
                .contains("archflow")
                .doesNotContain("iPaaS")
                .contains("\"component not found: X\" significa")
                .contains("NUNCA invente nomes de componente");
        assertThat(serverCatalog.nonExecutableNodeTypes())
                .allSatisfy(t -> assertThat(prompt).contains(t));
        assertThat(prompt).contains("componentId=text-transform-tool");
        // Os nomes que o modelo inventou na homologação não aparecem como sugestão possível.
        assertThat(prompt).doesNotContain("data-transform").doesNotContain("mapper").doesNotContain("jq");
    }

    @Test
    @DisplayName("tipos executáveis e sem execução saem do factory do motor, não de uma lista própria")
    void tiposSaemDoMotor() {
        assertThat(serverCatalog.executableNodeTypes())
                .contains("condition", "switch", "approval", "decision")
                .doesNotContain("transform");
        assertThat(serverCatalog.nonExecutableNodeTypes())
                .contains("transform", "map", "filter")
                .doesNotContain("condition");
    }

    /**
     * RCHFLW-154: o rascunho de um roteamento com três ramos passava de 3,5 mil caracteres e, no teto padrão de 1024
     * tokens, saía cortado no meio de uma string — virava rascunho vazio com a lacuna genérica, sem dizer por quê.
     */
    @Test
    @DisplayName("nl-to-flow pede um teto de saída maior que o padrão da plataforma")
    void nlToFlowSobeOTetoDeTokens() {
        assist.nlToFlow(new NlToFlowRequest("roteamento por assunto", null, "pt-BR"));

        assertThat(pedidosDeModelo.getLast().stepPatch().maxTokens())
                .hasValue(AssistServiceImpl.NL_TO_FLOW_MAX_TOKENS_PADRAO);
    }

    @Test
    @DisplayName("nl-to-flow não baixa o teto quando o da plataforma é maior")
    void nlToFlowNaoBaixaOTetoDaPlataforma() {
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(resposta)).build());
        when(resolver.resolveModel(any())).thenAnswer(inv -> {
            pedidosDeModelo.add(inv.getArgument(0));
            return model;
        });
        new AssistServiceImpl(resolver, ResolvedLLMConfig.builder().maxTokens(16000).build(), null, serverCatalog)
                .nlToFlow(new NlToFlowRequest("roteamento por assunto", null, "pt-BR"));

        assertThat(pedidosDeModelo.getLast().stepPatch().maxTokens()).hasValue(16000);
    }

    @Test
    @DisplayName("nl-to-flow cortado no teto diz que foi cortado, em vez da lacuna genérica")
    void nlToFlowCortadoDizOCorte() {
        resposta = "{\"workflow\":{\"steps\":[{\"name\":\"receber\",\"nodeType\":\"input\"},"
                + "{\"name\":\"por_assunto\",\"nodeType\":\"switch\",\"parameters\":{\"switchExpression\":"
                + "\"${triagem.decision}\"}}]},\"observacoes\":\"o caso 'tecnico' vai para 'llm_tecnico', o caso '";
        fim = FinishReason.LENGTH;

        var r = assist.nlToFlow(new NlToFlowRequest("tickets: financeiro, técnico ou comercial", null, "pt-BR"));

        assertThat(r.workflow().steps()).isEmpty();
        assertThat(r.lacunas()).singleElement().asString()
                .startsWith("Não foi possível gerar a partir da descrição")
                .contains("cortada no teto de " + AssistServiceImpl.NL_TO_FLOW_MAX_TOKENS_PADRAO + " tokens")
                .contains("archflow.assist.nl-to-flow.max-tokens");
        assertThat(r.observacoes()).as("o texto cru continua lá").startsWith("{\"workflow\"");
    }

    @Test
    @DisplayName("nl-to-flow lido mas encostado no teto avisa que o rascunho pode estar incompleto")
    void nlToFlowNoTetoAvisaQuePodeEstarIncompleto() {
        tokensDeSaida = AssistServiceImpl.NL_TO_FLOW_MAX_TOKENS_PADRAO;
        fim = null;

        var r = assist.nlToFlow(new NlToFlowRequest("x", null, "pt-BR"));

        assertThat(r.lacunas()).isEmpty();
        assertThat(r.observacoes()).contains("cortada no teto").contains("O rascunho pode estar incompleto.");
    }

    @Test
    @DisplayName("resposta que terminou normalmente não ganha aviso de corte")
    void nlToFlowSemCorteSemAviso() {
        tokensDeSaida = 800;

        var r = assist.nlToFlow(new NlToFlowRequest("x", null, "pt-BR"));

        assertThat(String.valueOf(r.observacoes())).doesNotContain("cortada");
    }

    /**
     * RCHFLW-154: o switch do reembolso vinha só com a switchExpression, sem switchCases, enquanto as observacoes
     * citavam o caso ate_300 — tudo caía no default, sem nenhum aviso. O prompt exige os casos fora do exemplo, e o
     * switch sem casos é avisado.
     */
    @Test
    @DisplayName("nl-to-flow exige switchCases no prompt e avisa do switch que veio sem casos")
    void nlToFlowSwitchSemCasos() {
        resposta = """
                {"workflow":{"steps":[
                  {"name":"receber","component":"input","nodeType":"input","parameters":{}},
                  {"name":"triagem_reembolso","component":"decision","nodeType":"decision",
                   "parameters":{"provider":"llm","questions":{"valor":{"type":"choice","instructions":"Valor?",
                     "criteria":{"ate_300":"até R$ 300","acima":"acima"}}},"primary":"valor"}},
                  {"name":"por_valor","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${triagem_reembolso.decision}"}},
                  {"name":"vazio","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${triagem_reembolso.decision}","switchCases":[" "]}},
                  {"name":"com_casos","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${triagem_reembolso.decision}","switchCases":"ate_300"}}],
                 "links":[]},"lacunas":[],"observacoes":"o caso ate_300 vai ao llm-chat"}
                """;

        var r = assist.nlToFlow(new NlToFlowRequest("reembolso até R$ 300 automático", null, "pt-BR"));

        assertThat(prompts.getFirst()).contains("Todo switch declara 'parameters.switchCases'");
        assertThat(r.observacoes()).startsWith("o caso ate_300 vai ao llm-chat\n")
                .contains("o step \"por_valor\" (switch) não declara 'parameters.switchCases': todo valor cai no "
                        + "ramo default")
                .contains("o step \"vazio\" (switch) não declara")
                .doesNotContain("\"com_casos\"");
    }

    /**
     * RCHFLW-154: o {@code ControlStep.casos()} só lê texto ou lista, e cada item vira texto. Os links do nl-to-flow
     * não levam o caso, e o modelo põe o destino dentro do switch — mapa caso → step ou lista de objetos. O motor lê
     * o mapa como vazio e o objeto como "{case=…, to=…}", que nunca casa: tudo cai no default, e isso é avisado.
     */
    @Test
    @DisplayName("nl-to-flow avisa do switchCases em formato que o motor não casa")
    void nlToFlowSwitchCasesEmFormatoQueNaoCasa() {
        resposta = """
                {"workflow":{"steps":[
                  {"name":"t","component":"decision","nodeType":"decision",
                   "parameters":{"provider":"llm","questions":{"assunto":{"type":"choice","instructions":"Assunto?",
                     "criteria":{"financeiro":"boleto","tecnico":"erro"}}},"primary":"assunto"}},
                  {"name":"mapa","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${t.decision}",
                     "switchCases":{"financeiro":"llm_financeiro","tecnico":"llm_tecnico"}}},
                  {"name":"objetos","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${t.decision}",
                     "switchCases":[{"case":"financeiro","to":"llm_financeiro"}]}},
                  {"name":"numero","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${t.decision}","switchCases":3}},
                  {"name":"lista_de_textos","component":"switch","nodeType":"switch",
                   "parameters":{"switchExpression":"${t.decision}","switchCases":["financeiro","tecnico", 2]}}],
                 "links":[]},"lacunas":[],"observacoes":"financeiro vai ao llm_financeiro"}
                """;

        var r = assist.nlToFlow(new NlToFlowRequest("roteamento por assunto", null, "pt-BR"));

        assertThat(r.observacoes())
                .contains("o step \"mapa\" (switch) tem 'parameters.switchCases' num formato que o motor não casa")
                .contains("o step \"objetos\" (switch) tem 'parameters.switchCases' num formato")
                .contains("o step \"numero\" (switch) tem 'parameters.switchCases' num formato")
                // Texto e número numa lista casam pelo texto (ControlStep faz String.valueOf): não é aviso.
                .doesNotContain("\"lista_de_textos\" (switch)");
    }

    /**
     * RCHFLW-189 (3): os exemplos do nl-to-flow terminavam no approval, e o rascunho também — aprovado, o fluxo
     * concluía com {"decision":"APPROVED"} e o cliente ficava sem resposta. O copiloto do editor já ensina que o
     * approval continua num llm-chat; as duas fontes de orientação têm de dizer a mesma coisa, inclusive quais
     * campos não resolvem ${...}.
     */
    @Test
    @DisplayName("nl-to-flow ensina que o approval continua num llm-chat e que systemPrompt não resolve ${...}")
    void nlToFlowApprovalNaoEOFim() {
        assist.nlToFlow(new NlToFlowRequest("reembolso acima de 300 vai ao supervisor", null, "pt-BR"));

        assertThat(prompts.getFirst())
                .contains("Step approval: não é o fim do fluxo — aprovar RETOMA a execução no step seguinte")
                .contains("Ligue o approval ao step que faz o que foi aprovado")
                .contains("o approval liga a um segundo llm-chat que responde ao pedido aprovado")
                .contains("e o approval a um llm-chat que responde ao reembolso aprovado")
                .contains("O 'systemPrompt' do llm-chat e os textos do approval (approvalTitle, approvalDescription, "
                        + "approvalProposal) nunca resolvem ${...}");
    }
}
