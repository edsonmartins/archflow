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
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import dev.langchain4j.model.chat.ChatModel;
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
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolveModel(any())).thenReturn(model);
        when(resolver.resolve(any())).thenReturn(ResolvedLLMConfig.builder().build());
        assist = new AssistServiceImpl(resolver, ResolvedLLMConfig.builder().build(), null, serverCatalog);
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
}
