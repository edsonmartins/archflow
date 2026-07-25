package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz um {@link LangChainAdapter} executar como {@link AIComponent} num nó de
 * workflow.
 *
 * <p>Existia uma camada inteira de adapters (~25k LOC, 15 submódulos) que o
 * designer <b>listava</b> e o runtime <b>nunca instanciava</b>: a única
 * referência de produção ao {@link LangChainRegistry} fora daqueles módulos era
 * a listagem do catálogo. Um nó apontando para "openai" falhava em execução com
 * "component not found", porque adapter não é {@code AIComponent}. As duas
 * interfaces são quase idênticas — a ponte que faltava é fina.
 *
 * <h2>Por que a criação é preguiçosa</h2>
 * A factory do adapter chama {@code configure()}, que chama {@code validate()},
 * que <b>exige a chave de API</b>. Criar no momento em que o fluxo é
 * desserializado faria abrir um workflow no designer explodir por falta de
 * chave. O adapter nasce na primeira execução.
 *
 * <h2>Por que o cache é por tenant</h2>
 * A chave vem do {@link TenantKeyResolver}, e o tenant só é conhecido em
 * execução ({@link ExecutionContext#getTenantId()}). Um mesmo objeto de fluxo
 * pode servir tenants diferentes, então guardar um único adapter configurado
 * entregaria a chave de um tenant para outro.
 *
 * <h2>Segredo não mora no workflow</h2>
 * A chave é injetada a partir do resolver do tenant; o JSON do workflow não
 * precisa (e não deve) carregá-la. Uma chave inline no nó continua funcionando
 * para desenvolvimento, mas o resolver tem precedência.
 */
public final class LangChainAdapterComponent implements AIComponent {

    private static final Logger log = LoggerFactory.getLogger(LangChainAdapterComponent.class);

    /** Chave que os adapters esperam para o segredo. */
    static final String API_KEY_PROPERTY = "api.key";
    /** Chave que os adapters esperam para o modelo. */
    static final String MODEL_PROPERTY = "model.name";

    /** Aliases amigáveis do designer → nomes que os adapters leem. */
    private static final Map<String, String> ALIASES = Map.of(
            "apiKey", API_KEY_PROPERTY,
            "model", MODEL_PROPERTY,
            "modelName", MODEL_PROPERTY);

    private final String provider;
    private final String adapterType;
    private final Map<String, Object> nodeConfig;
    private final TenantKeyResolver keyResolver;
    private final Map<String, LangChainAdapter> byTenant = new ConcurrentHashMap<>();

    public LangChainAdapterComponent(String provider, String adapterType,
                                     Map<String, Object> nodeConfig,
                                     TenantKeyResolver keyResolver) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.adapterType = Objects.requireNonNull(adapterType, "adapterType");
        this.nodeConfig = nodeConfig == null ? Map.of() : Map.copyOf(nodeConfig);
        this.keyResolver = keyResolver != null ? keyResolver : TenantKeyResolver.NOOP;
    }

    /** Id estável deste nó adapter, no formato {@code provider:tipo}. */
    public String componentId() {
        return provider + ":" + adapterType;
    }

    @Override
    public void initialize(Map<String, Object> config) {
        // A configuração chega pelo construtor (é do nó, não do catálogo).
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                componentId(),
                provider + " (" + adapterType + ")",
                "Adapter LangChain4j " + provider + " para " + adapterType,
                componentTypeFor(adapterType),
                "1.0.0",
                Set.of(adapterType),
                List.of(),
                Map.of(),
                Set.of("langchain4j", provider, adapterType));
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        String tenantId = context != null && context.getTenantId() != null
                ? context.getTenantId()
                : "__global__";
        return adapterFor(tenantId).execute(operation, input, context);
    }

    @Override
    public void shutdown() {
        byTenant.values().forEach(adapter -> {
            try {
                adapter.shutdown();
            } catch (RuntimeException e) {
                log.debug("Falha ao encerrar adapter {}: {}", componentId(), e.getMessage());
            }
        });
        byTenant.clear();
    }

    private LangChainAdapter adapterFor(String tenantId) {
        return byTenant.computeIfAbsent(tenantId, this::createAdapter);
    }

    private LangChainAdapter createAdapter(String tenantId) {
        Map<String, Object> properties = effectiveProperties(tenantId);
        log.debug("Criando adapter {} para tenant {}", componentId(), tenantId);
        return LangChainRegistry.createAdapter(provider, adapterType, properties);
    }

    /**
     * Config do nó normalizada, com a chave do tenant injetada.
     *
     * <p>O resolver tem precedência sobre a chave inline: um segredo esquecido
     * no JSON do workflow não pode sobrepor o que a governança do tenant diz.
     */
    private Map<String, Object> effectiveProperties(String tenantId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        nodeConfig.forEach((key, value) -> properties.put(ALIASES.getOrDefault(key, key), value));
        keyResolver.resolveApiKey(tenantId, provider)
                .filter(key -> !key.isBlank())
                .ifPresent(key -> properties.put(API_KEY_PROPERTY, key));
        return properties;
    }

    private static ComponentType componentTypeFor(String adapterType) {
        return switch (adapterType) {
            case "chat" -> ComponentType.ASSISTANT;
            default -> ComponentType.TOOL;
        };
    }
}
