package br.com.archflow.langchain4j.provider;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@link LLMProviderHub} é um singleton estático com cache de modelos
 * compartilhado, e o {@code DefaultLLMConfigResolver} deriva o id de cache do
 * tenant. A auditoria registrou isso como <b>incógnita</b>: a estrutura foi
 * lida, o comportamento sob concorrência multi-tenant não foi exercitado.
 *
 * <p>O que precisa valer: nenhuma requisição pode receber o modelo de outro
 * tenant, e um override temporário de um thread não pode vazar para os demais.
 *
 * <p>Usa {@code OLLAMA} porque a construção do modelo não exige chave e não
 * abre conexão — só a chamada de chat abriria, e nenhum teste aqui chama.
 */
@DisplayName("LLMProviderHub — concorrência multi-tenant")
class LLMProviderHubConcurrencyTest {

    private static final int TENANTS = 12;
    private static final int ROUNDS = 40;

    private LLMProviderHub hub;

    @BeforeEach
    void setUp() {
        LLMProviderHub.reset();
        hub = LLMProviderHub.getInstance();
    }

    @AfterEach
    void tearDown() {
        LLMProviderHub.reset();
    }

    private static LLMProviderConfig configFor(String tenant) {
        return LLMProviderConfig.builder()
                .provider(LLMProvider.OLLAMA)
                .modelId("modelo-de-" + tenant)
                .build();
    }

    private static String configId(String tenant) {
        return tenant + ":ollama:modelo-de-" + tenant;
    }

    @Test
    @DisplayName("cada tenant recebe o seu modelo, sob acesso concorrente")
    void modelsDoNotBleedAcrossTenants() throws Exception {
        for (int i = 0; i < TENANTS; i++) {
            String tenant = "tenant-" + i;
            hub.registerConfig(configId(tenant), configFor(tenant));
        }

        Map<String, ChatModel> firstSeen = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(TENANTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TENANTS);

        for (int i = 0; i < TENANTS; i++) {
            String tenant = "tenant-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < ROUNDS; r++) {
                        ChatModel model = hub.getModel(configId(tenant));
                        ChatModel previous = firstSeen.putIfAbsent(tenant, model);
                        if (previous != null && previous != model) {
                            throw new AssertionError(
                                    "instância trocou para o mesmo tenant " + tenant);
                        }
                        // A config do slot tem que continuar sendo a do tenant.
                        String modelId = hub.getConfig(configId(tenant)).orElseThrow().getModelId();
                        if (!modelId.equals("modelo-de-" + tenant)) {
                            throw new AssertionError("config de outro tenant vazou: " + modelId);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(failure.get()).isNull();
        assertThat(firstSeen).hasSize(TENANTS);
        assertThat(firstSeen.values().stream().distinct().count())
                .as("cada tenant precisa ter a sua propria instancia")
                .isEqualTo(TENANTS);
        assertThat(hub.getCacheSize()).isEqualTo(TENANTS);
    }

    @Test
    @DisplayName("withProvider é por thread — o override de um não afeta o outro")
    void overrideDoesNotLeakAcrossThreads() throws Exception {
        String id = configId("tenant-a");
        hub.registerConfig(id, configFor("tenant-a"));
        ChatModel shared = hub.getModel(id);

        LLMProviderConfig temporario = LLMProviderConfig.builder()
                .provider(LLMProvider.OLLAMA)
                .modelId("modelo-temporario")
                .build();

        CountDownLatch insideOverride = new CountDownLatch(1);
        CountDownLatch otherThreadChecked = new CountDownLatch(1);
        AtomicReference<ChatModel> fromOtherThread = new AtomicReference<>();
        AtomicReference<ChatModel> fromOverride = new AtomicReference<>();

        Thread overriding = new Thread(() -> hub.withProvider(id, temporario, () -> {
            fromOverride.set(hub.getModel(id));
            insideOverride.countDown();
            try {
                otherThreadChecked.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        overriding.start();

        assertThat(insideOverride.await(10, TimeUnit.SECONDS)).isTrue();
        // Enquanto o outro thread está sob override, este ainda vê o compartilhado.
        fromOtherThread.set(hub.getModel(id));
        otherThreadChecked.countDown();
        overriding.join(10_000);

        assertThat(fromOtherThread.get())
                .as("override e por thread; nao pode vazar para quem nao pediu")
                .isSameAs(shared);
        assertThat(fromOverride.get())
                .as("dentro do override o modelo e efemero, nao o do cache")
                .isNotSameAs(shared);
        assertThat(hub.getModel(id))
                .as("terminado o override, o cache compartilhado segue intacto")
                .isSameAs(shared);
    }

    @Test
    @DisplayName("trocar o provider de um slot invalida o cache daquele slot apenas")
    void switchingProviderInvalidatesOnlyItsOwnSlot() {
        String a = configId("tenant-a");
        String b = configId("tenant-b");
        hub.registerConfig(a, configFor("tenant-a"));
        hub.registerConfig(b, configFor("tenant-b"));
        ChatModel modeloA = hub.getModel(a);
        ChatModel modeloB = hub.getModel(b);

        hub.registerConfig(a, LLMProviderConfig.builder()
                .provider(LLMProvider.OPENAI)
                .apiKey("k")
                .modelId("gpt-4o-mini")
                .build());

        assertThat(hub.getModel(a)).isNotSameAs(modeloA);
        assertThat(hub.getModel(b))
                .as("a troca de um tenant nao pode derrubar o modelo de outro")
                .isSameAs(modeloB);
    }
}
