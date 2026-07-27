package br.com.archflow.events.proto;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Perda de evento tem que ser <b>visível</b>.
 *
 * <p>Os dois caminhos de perda deste módulo eram silenciosos de maneiras
 * diferentes, e ambos produziam o mesmo resultado prático: um consumidor
 * recebendo menos — ou pior — do que o produtor mandou, sem nada no log.
 *
 * <ul>
 *   <li>a fila cheia descartava eventos incrementando um contador que ninguém
 *       lê. Um número que exige alguém perguntar não avisa nada;</li>
 *   <li>o mapeador convertia qualquer valor não escalar com {@code toString()}.
 *       Aqui não se perde o evento — perde-se a <i>estrutura</i> dele, e o
 *       consumidor recebe algo que parece um valor legítimo e não é.</li>
 * </ul>
 *
 * <p>Este teste captura o {@code java.util.logging} de verdade em vez de
 * verificar o contador: o contador já existia quando o defeito existia, então
 * afirmar sobre ele não provaria nada sobre a correção.
 */
@DisplayName("perda de evento é visível no log")
class EventLossIsVisibleTest {

    /** Handler que guarda os registros emitidos, para asserção. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }

        List<String> warnings() {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    private CapturingHandler handler;
    private Logger logger;
    private Level originalLevel;

    private void capture(Class<?> target) {
        logger = Logger.getLogger(target.getName());
        handler = new CapturingHandler();
        originalLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void detach() {
        if (logger != null && handler != null) {
            logger.removeHandler(handler);
            logger.setLevel(originalLevel);
        }
    }

    /**
     * A regra de frequência do aviso de descarte.
     *
     * <p>Testada pela regra e não pela fila: forçar o transbordo de verdade não
     * é confiável, porque o {@code flushNow} drena em paralelo enquanto a fila
     * é preenchida — na primeira versão deste teste, 4000 submissões numa fila
     * de 2048 produziram <b>zero</b> descartes. Um teste que depende de ganhar
     * essa corrida passa às vezes, e um teste que passa às vezes é pior que
     * nenhum.
     */
    @Nested
    @DisplayName("frequência do aviso de descarte")
    class DropWarningCadence {

        @Test
        @DisplayName("o primeiro descarte avisa — não espera acumular")
        void warnsOnTheVeryFirstDrop() {
            assertThat(ProtobufEventPublisher.shouldWarnAboutDrops(1))
                    .as("esperar acumular esconde justamente o comeco da perda")
                    .isTrue();
        }

        @Test
        @DisplayName("depois, só nas potências de 10")
        void thenOnlyPowersOfTen() {
            assertThat(ProtobufEventPublisher.shouldWarnAboutDrops(10)).isTrue();
            assertThat(ProtobufEventPublisher.shouldWarnAboutDrops(100)).isTrue();
            assertThat(ProtobufEventPublisher.shouldWarnAboutDrops(1_000)).isTrue();
            assertThat(ProtobufEventPublisher.shouldWarnAboutDrops(1_000_000)).isTrue();
        }

        /**
         * A fila enche justamente sob rajada: um aviso por descarte inundaria o
         * log no pior momento possível, e no caminho quente.
         */
        @Test
        @DisplayName("não avisa a cada descarte")
        void staysQuietInBetween() {
            long avisos = java.util.stream.LongStream.rangeClosed(1, 10_000)
                    .filter(ProtobufEventPublisher::shouldWarnAboutDrops)
                    .count();

            assertThat(avisos)
                    .as("em 10.000 descartes: 1, 10, 100, 1.000 e 10.000")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("zero não avisa — nada foi descartado")
        void zeroIsSilent() {
            assertThat(ProtobufEventPublisher.shouldWarnAboutDrops(0)).isFalse();
        }
    }

    /** O aviso sai de fato quando a fila transborda, se transbordar. */
    @Nested
    @DisplayName("descarte real")
    class RealOverflow {

        private ProtobufEventPublisher publisher;

        @BeforeEach
        void setUp() {
            capture(ProtobufEventPublisher.class);
            publisher = new ProtobufEventPublisher(
                    new EventStreamRegistry(), URI.create("http://127.0.0.1:1/ingest"), "teste");
        }

        @AfterEach
        void tearDown() throws Exception {
            publisher.close();
        }

        /**
         * Condicional de propósito: ver a nota de {@link DropWarningCadence}. O
         * que se afirma aqui é a implicação — <i>se</i> houve descarte, <i>então</i>
         * houve aviso —, que é falsificável e não depende da corrida.
         */
        @Test
        @DisplayName("havendo descarte, há aviso")
        void dropImpliesWarning() {
            for (int i = 0; i < 20_000; i++) {
                publisher.submit(evento("e" + i));
            }

            if (publisher.dropped() > 0) {
                assertThat(handler.warnings())
                        .as("descartados=%d e nenhum aviso — era este o silencio", publisher.dropped())
                        .anySatisfy(m -> assertThat(m).contains("Fila de eventos cheia"));
            }
        }
    }

    @Nested
    @DisplayName("valor não escalar")
    class NonScalarFlattening {

        @BeforeEach
        void setUp() {
            capture(ProtobufEventMapper.class);
        }

        /**
         * <b>Um tipo por teste, e não um compartilhado.</b> O aviso sai uma vez
         * por classe e vale para o processo inteiro, então dois testes usando o
         * mesmo tipo disputam o único aviso: quem roda primeiro o consome e o
         * outro falha. A ordem dos métodos no JUnit não é a de declaração, então
         * isso não seria nem consistente — a primeira versão deste teste
         * quebrou exatamente assim.
         */
        private record SoDesteTeste(String a, int b) { }

        private record SoDoTesteDeRepeticao(String a, int b) { }

        @Test
        @DisplayName("avisa que a estrutura foi perdida no toString()")
        void warnsWhenFlattening() {
            ProtobufEventMapper.toProto(ArchflowEvent.builder()
                    .domain(ArchflowDomain.FLOW)
                    .type(ArchflowEventType.START)
                    .data(Map.of("payload", new SoDesteTeste("x", 1)))
                    .build());

            assertThat(handler.warnings())
                    .as("a conversao para texto era completamente silenciosa")
                    .anySatisfy(m -> assertThat(m)
                            .contains(SoDesteTeste.class.getName())
                            .contains("toString()"));
        }

        @Test
        @DisplayName("o mesmo tipo não avisa duas vezes")
        void warnsOncePerType() {
            for (int i = 0; i < 5; i++) {
                ProtobufEventMapper.toProto(ArchflowEvent.builder()
                        .domain(ArchflowDomain.FLOW)
                        .type(ArchflowEventType.START)
                        .data(Map.of("payload", new SoDoTesteDeRepeticao("x", i)))
                        .build());
            }

            assertThat(handler.warnings().stream()
                    .filter(m -> m.contains(SoDoTesteDeRepeticao.class.getName()))
                    .count())
                    .as("o achatamento acontece por chave, em todo evento: avisar sempre "
                            + "inundaria o caminho quente")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("escalar não gera aviso nenhum")
        void scalarsAreSilent() {
            ProtobufEventMapper.toProto(ArchflowEvent.builder()
                    .domain(ArchflowDomain.FLOW)
                    .type(ArchflowEventType.START)
                    .data(Map.of("texto", "a", "numero", 1, "decimal", 1.5, "flag", true))
                    .build());

            assertThat(handler.warnings())
                    .as("avisar sobre o caminho normal treina todo mundo a ignorar o aviso")
                    .isEmpty();
        }
    }

    private static ArchflowEvent evento(String id) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_STARTED)
                .data(Map.of("stepId", id))
                .build();
    }
}
