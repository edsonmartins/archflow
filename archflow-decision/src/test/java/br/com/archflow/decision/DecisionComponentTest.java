package br.com.archflow.decision;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("componente de decisão")
class DecisionComponentTest {

    /** Provedor controlável: devolve o que o teste manda, e guarda o que recebeu. */
    private static final class Falso implements DecisionProvider {
        final String id;
        final boolean exigeChave;
        final List<DecisionRequest> pedidos = new ArrayList<>();
        final List<DecisionCall> chamadas = new ArrayList<>();
        Function<DecisionRequest, DecisionResult> resposta;
        DecisionException falha;

        Falso(String id, boolean exigeChave) {
            this.id = id;
            this.exigeChave = exigeChave;
        }

        @Override public String id() { return id; }
        @Override public boolean requiresKey() { return exigeChave; }
        @Override public Optional<String> keyRef(Map<String, Object> o) { return Optional.of("ref-" + id); }

        @Override
        public DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException {
            pedidos.add(request);
            chamadas.add(call);
            if (falha != null) {
                throw falha;
            }
            return resposta.apply(request);
        }
    }

    private static DecisionResult resultado(Map<String, Answer> answers) {
        return new DecisionResult(answers, "m-1", "falso", true,
                new DecisionResult.Usage(10L, 2L, 0.00001));
    }

    private static Answer.Choice escolha(String opcao, double confianca) {
        return new Answer.Choice(opcao, Map.of(opcao, 1.0), confianca);
    }

    private static Map<String, Object> perguntas() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("time", Map.of("type", "choice", "instructions", "Quem cuida?",
                "criteria", Map.of("pagamento", "cobrança", "conta", "login")));
        q.put("urgencia", Map.of("type", "score", "criteria", List.of("baixa", "alta")));
        return q;
    }

    private static Map<String, Object> config(Object... extras) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("provider", "falso");
        c.put("model", "m-1");
        c.put("questions", perguntas());
        for (int i = 0; i < extras.length; i += 2) {
            c.put((String) extras[i], extras[i + 1]);
        }
        return c;
    }

    private static DecisionComponent componente(Falso p, DecisionKeys keys, Map<String, Object> cfg) {
        DecisionComponent c = new DecisionComponent(DecisionProviders.empty().register(p), keys);
        c.initialize(cfg);
        return c;
    }

    private static ExecutionContext ctx() {
        return new DefaultExecutionContext("tenant-7", null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rodar(DecisionComponent c, Object input, ExecutionContext ctx)
            throws Exception {
        return (Map<String, Object>) c.execute("decide", input, ctx);
    }

    private static Falso respondendo(Answer time, Answer urgencia) {
        Falso f = new Falso("falso", false);
        f.resposta = r -> resultado(Map.of("time", time, "urgencia", urgencia));
        return f;
    }

    @Test
    @DisplayName("a rota vem da MENOR confiança entre as perguntas: uma incerta rebaixa a decisão toda")
    void rotaPelaMenorConfianca() throws Exception {
        Falso f = respondendo(escolha("pagamento", 0.95),
                new Answer.Score(1.1, Map.of(), Map.of(), 0.6));
        Map<String, Object> saida = rodar(componente(f, null, config()), "texto", ctx());

        assertThat(saida.get("route")).isEqualTo("REVIEW");
        assertThat(saida.get("confidence")).isEqualTo(0.6);
        assertThat(saida.get("decision")).isEqualTo("pagamento");
    }

    @Test
    @DisplayName("limiares do nó mudam a rota; 'gate' restringe quais perguntas decidem")
    void limiaresEGate() throws Exception {
        Falso f = respondendo(escolha("pagamento", 0.95),
                new Answer.Score(0.2, Map.of(), Map.of(), 0.1));

        var comGate = componente(f, null, config("gate", List.of("time")));
        assertThat(rodar(comGate, "x", ctx()).get("route")).isEqualTo("AUTO");

        var rigoroso = componente(f, null, config("gate", List.of("time"),
                "thresholds", Map.of("auto", 0.99, "review", 0.9)));
        assertThat(rodar(rigoroso, "x", ctx()).get("route")).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("a saída traz o valor, a confiança e as probabilidades por pergunta, lidos pelas arestas")
    @SuppressWarnings("unchecked")
    void formaDaSaida() throws Exception {
        Falso f = respondendo(escolha("conta", 0.92), new Answer.Score(1.5, Map.of("1", 1.0),
                Map.of("1", "alta"), 0.8));
        Map<String, Object> saida = rodar(componente(f, null, config()), "x", ctx());

        Map<String, Object> time = (Map<String, Object>) ((Map<String, Object>) saida.get("answers")).get("time");
        assertThat(time).containsEntry("value", "conta").containsEntry("choice", "conta")
                .containsEntry("confidence", 0.92).containsEntry("type", "choice");
        assertThat(saida).containsEntry("provider", "falso").containsEntry("model", "m-1");
        assertThat((Map<String, Object>) saida.get("usage")).containsEntry("costUsd", 0.00001);
    }

    @Test
    @DisplayName("sim/não: a confiança é derivada da probabilidade (|2p-1|)")
    void confiancaDoSimNao() {
        assertThat(new Answer.YesNo(0.5).confidence()).isEqualTo(0.0);
        assertThat(new Answer.YesNo(1.0).confidence()).isEqualTo(1.0);
        assertThat(new Answer.YesNo(0.0).confidence()).isEqualTo(1.0);
        assertThat(new Answer.YesNo(0.9).confidence()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("o estado vem do modelo ${...} resolvido no contexto; sem modelo, é a entrada")
    void estado() throws Exception {
        Falso f = respondendo(escolha("conta", 1), new Answer.Score(0, Map.of(), Map.of(), 1));
        ExecutionContext c = ctx();
        c.set("cliente", Map.of("plano", "enterprise"));

        rodar(componente(f, null, config("state",
                Map.of("ticket", "${input}", "plano", "${cliente.plano}", "msg", "oi ${cliente.plano}"))),
                "tela em branco", c);
        rodar(componente(f, null, config()), "so a entrada", c);

        assertThat(f.pedidos.get(0).state()).isEqualTo(Map.of(
                "ticket", "tela em branco", "plano", "enterprise", "msg", "oi enterprise"));
        assertThat(f.pedidos.get(1).state()).isEqualTo("so a entrada");
    }

    @Test
    @DisplayName("falha do provedor escala em vez de palpitar: route ESCALATE, erro e nenhuma resposta")
    void falhaEscala() throws Exception {
        Falso f = new Falso("falso", false);
        f.falha = new DecisionException("HTTP 529", true);

        Map<String, Object> saida = rodar(componente(f, null, config()), "x", ctx());

        assertThat(saida.get("route")).isEqualTo("ESCALATE");
        assertThat(saida.get("confidence")).isEqualTo(0.0);
        assertThat(saida.get("error").toString()).contains("falso").contains("529");
        assertThat((Map<?, ?>) saida.get("answers")).isEmpty();
    }

    @Test
    @DisplayName("onError FAIL lança, e o motor decide")
    void falhaLanca() {
        Falso f = new Falso("falso", false);
        f.falha = new DecisionException("HTTP 529", true);

        assertThatThrownBy(() -> rodar(componente(f, null, config("onError", "FAIL")), "x", ctx()))
                .hasMessageContaining("529");
    }

    @Test
    @DisplayName("a cascata tenta o próximo provedor quando o anterior falha, e diz quem respondeu")
    void cascata() throws Exception {
        Falso primeiro = new Falso("falso", false);
        primeiro.falha = new DecisionException("fora do ar", true);
        Falso segundo = new Falso("reserva", false);
        segundo.resposta = r -> new DecisionResult(Map.of("time", escolha("conta", 0.93),
                "urgencia", new Answer.Score(1, Map.of(), Map.of(), 0.95)), "r-1", "reserva", true, null);
        var providers = DecisionProviders.empty().register(primeiro).register(segundo);
        var c = new DecisionComponent(providers, null);
        c.initialize(config("fallbacks", List.of(Map.of("provider", "reserva", "model", "r-1"))));

        Map<String, Object> saida = rodar(c, "x", ctx());

        assertThat(saida.get("provider")).isEqualTo("reserva");
        assertThat(saida.get("route")).isEqualTo("AUTO");
        assertThat(segundo.chamadas.get(0).model()).isEqualTo("r-1");
    }

    @Test
    @DisplayName("a chave vem do resolvedor do tenant, pela referência do provedor; a do nó é ignorada")
    void chaveDoResolvedor() throws Exception {
        Falso f = new Falso("falso", true);
        f.resposta = r -> resultado(Map.of("time", escolha("conta", 1),
                "urgencia", new Answer.Score(0, Map.of(), Map.of(), 1)));
        List<String> pedidas = new ArrayList<>();
        DecisionKeys keys = (tenant, ref) -> {
            pedidas.add(tenant + "/" + ref);
            return Optional.of("chave-do-tenant");
        };

        rodar(componente(f, keys, config("apiKey", "chave-esquecida-no-no")), "x", ctx());

        assertThat(pedidas).containsExactly("tenant-7/ref-falso");
        assertThat(f.chamadas.get(0).apiKey()).isEqualTo("chave-do-tenant");
    }

    @Test
    @DisplayName("provedor que exige chave e nenhuma resolvida: nem chama, escala")
    void semChave() throws Exception {
        Falso f = new Falso("falso", true);

        Map<String, Object> saida = rodar(componente(f, DecisionKeys.NONE, config()), "x", ctx());

        assertThat(saida.get("route")).isEqualTo("ESCALATE");
        assertThat(saida.get("error").toString()).contains("sem chave");
        assertThat(f.pedidos).isEmpty();
    }

    @Test
    @DisplayName("provedor desconhecido escala com o motivo")
    void provedorDesconhecido() throws Exception {
        var c = new DecisionComponent(DecisionProviders.empty(), null);
        c.initialize(config());

        assertThat(rodar(c, "x", ctx()).get("error").toString()).contains("desconhecido");
    }

    @Test
    @DisplayName("configuração inválida é recusada na criação, com o motivo")
    void configuracaoInvalida() {
        var c = new DecisionComponent(DecisionProviders.empty(), null);
        assertThatThrownBy(() -> c.initialize(Map.of("model", "m"))).hasMessageContaining("questions");
        assertThatThrownBy(() -> c.initialize(config("questions",
                Map.of("q", Map.of("type", "choice", "criteria", List.of("a")))))).hasMessageContaining("choice");
        assertThatThrownBy(() -> c.initialize(config("questions",
                Map.of("q", Map.of("type", "score", "criteria", List.of("so-um")))))).hasMessageContaining("dois níveis");
        assertThatThrownBy(() -> c.initialize(config("questions",
                Map.of("q", Map.of("type", "magia"))))).hasMessageContaining("magia");
        assertThatThrownBy(() -> c.initialize(config("gate", List.of("inexistente"))))
                .hasMessageContaining("inexistente");
        assertThatThrownBy(() -> c.initialize(config("onError", "TALVEZ"))).hasMessageContaining("onError");
        assertThatThrownBy(() -> c.initialize(config("thresholds", Map.of("auto", 0.4, "review", 0.8))))
                .hasMessageContaining("limiares");
    }

    @Test
    @DisplayName("'primary' escolhe a pergunta cujo valor vira `decision`; padrão é a primeira declarada")
    void primary() throws Exception {
        Falso f = respondendo(escolha("pagamento", 0.95), new Answer.Score(1.1, Map.of(), Map.of(), 0.95));

        assertThat(rodar(componente(f, null, config()), "x", ctx()).get("decision")).isEqualTo("pagamento");
        assertThat(rodar(componente(f, null, config("primary", "urgencia")), "x", ctx()).get("decision"))
                .isEqualTo(1.1);
        assertThatThrownBy(() -> componente(f, null, config("primary", "nada")))
                .hasMessageContaining("primary");
    }

    @Test
    @DisplayName("sem inicializar, não executa")
    void naoInicializado() {
        var c = new DecisionComponent(DecisionProviders.empty(), null);
        assertThatThrownBy(() -> c.execute("decide", "x", ctx())).isInstanceOf(IllegalStateException.class);
        assertThat(c.getMetadata().id()).isEqualTo("decision");
    }

    // ── Cascata, consenso e consumo ─────────────────────────────────

    /** Provedor que responde sempre a mesma opção/confiança, e conta as chamadas. */
    private static Falso respondendoComo(String id, String opcao, double confianca) {
        Falso f = new Falso(id, false);
        f.resposta = r -> new DecisionResult(
                Map.of("time", escolha(opcao, confianca),
                        "urgencia", new Answer.Score(1.0, Map.of(), Map.of(), confianca)),
                "modelo-" + id, id, true, new DecisionResult.Usage(10L, 2L, 0.00001));
        return f;
    }

    private static DecisionComponent compondo(Map<String, Object> cfg, Falso... provedores) {
        DecisionProviders catalogo = DecisionProviders.empty();
        for (Falso p : provedores) {
            catalogo.register(p);
        }
        DecisionComponent c = new DecisionComponent(catalogo, null);
        c.initialize(cfg);
        return c;
    }

    @Test
    @DisplayName("cascata: para no primeiro degrau que atinge a confiança, e o seguinte nem é chamado")
    @SuppressWarnings("unchecked")
    void cascataParaNoPrimeiroSuficiente() throws Exception {
        Falso a = respondendoComo("a", "conta", 0.6);
        Falso b = respondendoComo("b", "pagamento", 0.95);
        Falso c = respondendoComo("c", "conta", 0.99);

        Map<String, Object> saida = rodar(compondo(config("provider", "a",
                "cascade", List.of(Map.of("provider", "b"), Map.of("provider", "c"))), a, b, c), "x", ctx());

        assertThat(saida.get("provider")).isEqualTo("b");
        assertThat(saida.get("route")).isEqualTo("AUTO");
        assertThat(c.pedidos).as("o terceiro degrau não foi necessário").isEmpty();
        List<Map<String, Object>> trilha = (List<Map<String, Object>>) saida.get("cascade");
        assertThat(trilha).extracting(t -> t.get("status")).containsExactly("LOW_CONFIDENCE", "ACCEPTED");
    }

    @Test
    @DisplayName("cascata sem nenhum degrau suficiente: vale o mais confiante, e a rota diz que não é seguro")
    @SuppressWarnings("unchecked")
    void cascataSemSuficiente() throws Exception {
        Map<String, Object> saida = rodar(compondo(config("provider", "a",
                        "cascade", List.of(Map.of("provider", "b"))),
                respondendoComo("a", "conta", 0.4), respondendoComo("b", "pagamento", 0.7)), "x", ctx());

        assertThat(saida.get("provider")).isEqualTo("b");
        assertThat(saida.get("route")).isEqualTo("REVIEW");
        assertThat((List<Map<String, Object>>) saida.get("cascade"))
                .extracting(t -> t.get("status")).containsExactly("LOW_CONFIDENCE", "LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("degrau que falha é pulado e registrado; sem cascata, o resultado não traz a trilha")
    @SuppressWarnings("unchecked")
    void cascataComFalha() throws Exception {
        Falso a = new Falso("a", false);
        a.falha = new DecisionException("fora do ar", true);

        Map<String, Object> saida = rodar(compondo(config("provider", "a",
                "cascade", List.of(Map.of("provider", "b"))), a, respondendoComo("b", "conta", 0.97)), "x", ctx());

        assertThat((List<Map<String, Object>>) saida.get("cascade"))
                .extracting(t -> t.get("status")).containsExactly("FAILED", "ACCEPTED");
        assertThat(rodar(compondo(config(), respondendoComo("falso", "conta", 0.97)), "x", ctx()))
                .doesNotContainKey("cascade");
    }

    @Test
    @DisplayName("consenso: modelos que concordam mantêm a rota; o consumo soma todos os votos")
    @SuppressWarnings("unchecked")
    void consensoConcorda() throws Exception {
        Map<String, Object> saida = rodar(compondo(config("provider", "a",
                        "consensus", Map.of("models", List.of(Map.of("provider", "b")))),
                respondendoComo("a", "conta", 0.95), respondendoComo("b", "conta", 0.92)), "x", ctx());

        Map<String, Object> consenso = (Map<String, Object>) saida.get("consensus");
        assertThat(consenso.get("agree")).isEqualTo(true);
        assertThat((List<?>) consenso.get("votes")).hasSize(2);
        assertThat(saida.get("route")).isEqualTo("AUTO");
        assertThat(((Map<String, Object>) saida.get("usage")).get("inputTokens")).isEqualTo(20L);
    }

    @Test
    @DisplayName("consenso: discordância rebaixa a rota (REVIEW por padrão, ESCALATE se o nó pedir)")
    void consensoDiscorda() throws Exception {
        Falso a = respondendoComo("a", "conta", 0.95);
        Falso b = respondendoComo("b", "pagamento", 0.95);

        Map<String, Object> revisao = rodar(compondo(config("provider", "a",
                "consensus", Map.of("models", List.of(Map.of("provider", "b")))), a, b), "x", ctx());
        assertThat(revisao.get("route")).isEqualTo("REVIEW");
        assertThat(revisao.get("routeReason")).isEqualTo("consensus_disagreement");
        assertThat(revisao.get("decision")).as("a decisão segue sendo a do principal").isEqualTo("conta");

        Map<String, Object> escala = rodar(compondo(config("provider", "a",
                "consensus", Map.of("models", List.of(Map.of("provider", "b")), "onDisagree", "ESCALATE")),
                a, b), "x", ctx());
        assertThat(escala.get("route")).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("consenso: modelo que falha não conta como discordância, mas fica registrado")
    @SuppressWarnings("unchecked")
    void consensoComFalha() throws Exception {
        Falso b = new Falso("b", false);
        b.falha = new DecisionException("HTTP 529", true);

        Map<String, Object> saida = rodar(compondo(config("provider", "a",
                        "consensus", Map.of("models", List.of(Map.of("provider", "b", "model", "mb")))),
                respondendoComo("a", "conta", 0.95), b), "x", ctx());

        Map<String, Object> consenso = (Map<String, Object>) saida.get("consensus");
        assertThat(consenso.get("agree")).isEqualTo(true);
        assertThat((List<String>) consenso.get("failures")).singleElement().asString().contains("529");
        assertThat(saida.get("route")).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("cada chamada feita é reportada ao ouvinte de consumo — degraus e votos incluídos")
    void consumoReportado() throws Exception {
        List<String> vistas = new ArrayList<>();
        ExecutionContext c = ctx();
        c.set(DecisionUsageListener.CONTEXT_KEY, (DecisionUsageListener) (p, m, in, out, custo) ->
                vistas.add(p + "/" + m + ":" + in + ":" + custo));

        rodar(compondo(config("provider", "a",
                        "cascade", List.of(Map.of("provider", "b")),
                        "consensus", Map.of("models", List.of(Map.of("provider", "c")))),
                respondendoComo("a", "conta", 0.5), respondendoComo("b", "conta", 0.97),
                respondendoComo("c", "conta", 0.9)), "x", c);

        assertThat(vistas).containsExactlyInAnyOrder(
                "a/modelo-a:10:1.0E-5", "b/modelo-b:10:1.0E-5", "c/modelo-c:10:1.0E-5");
    }

    @Test
    @DisplayName("configuração de cascata e consenso inválida é recusada")
    void configuracaoDeCascataInvalida() {
        var c = new DecisionComponent(DecisionProviders.empty(), null);
        assertThatThrownBy(() -> c.initialize(config("acceptAt", 1.5))).hasMessageContaining("acceptAt");
        assertThatThrownBy(() -> c.initialize(config("consensus", Map.of("onDisagree", "AUTO"))))
                .hasMessageContaining("onDisagree");
    }
}
