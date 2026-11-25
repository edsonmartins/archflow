package br.com.archflow.api.flow;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentInputs;
import br.com.archflow.plugin.api.spi.ComponentPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O ciclo de vida de um componente do catálogo quando alguém o executa: um nó de fluxo
 * ({@link ComponentStep}) ou uma invocação direta do agente (a fila de invocações).
 *
 * <p>Antes, nada dirigia o contrato do {@link AIComponent} ({@code initialize} → {@code execute} →
 * {@code shutdown}) para os componentes do catálogo: o catálogo guardava instâncias cruas, o passo as
 * executava com a operação fixa {@code "execute"} — que nenhum dos embutidos declara — e entregava o
 * mapa da entrada a plugins que esperam texto. Três defeitos em fila; corrigir só o primeiro trocava
 * "not initialized" por "Unsupported operation: execute" e esse por {@code ClassCastException}.</p>
 *
 * <p>As três regras moram aqui, e não no passo, para que o nó e a invocação não divirjam:</p>
 * <ol>
 *   <li><b>Instância isolada.</b> Um plugin ({@link ComponentPlugin}) com construtor público sem
 *       argumentos ganha uma instância nova por execução, com o ciclo de vida inteiro de plugin:
 *       {@code onLoad} → {@code initialize(config do nó)} → execução → {@code shutdown} →
 *       {@code onUnload}. O singleton do catálogo guarda estado mutável (histórico de conversa do
 *       {@code conversational-agent}, histórico de métricas do {@code monitoring-agent}); executá-lo
 *       direto vazaria esse estado entre execuções e tenants — o mesmo motivo pelo qual o
 *       {@code mcp-agent} e o {@code decision} já são instâncias por passo. O resto (componentes
 *       registrados à mão, mocks, classes anônimas) usa a instância registrada, como antes. O custo é
 *       um {@code initialize} por execução — o mesmo preço que o {@code mcp-agent} e o {@code decision}
 *       pagam; um plugin com setup caro (cliente, pool) deve guardar o recurso fora da instância.</li>
 *   <li><b>Operação padrão.</b> Sem operação explícita, vale a que o componente declara como padrão
 *       na dica {@value #DEFAULT_OPERATION_HINT} das propriedades; sem a dica, {@code "execute"} (o
 *       padrão histórico). Não se adivinha uma operação entre as declaradas: um plugin sem padrão e sem
 *       {@code execute} falha dizendo que nenhuma operação foi informada e listando as que existem. Uma
 *       operação explícita nunca é trocada. Num plugin, a operação que ele não declara é recusada
 *       <i>antes</i> de criar a instância e de passar pelos interceptors: a lista de operações é o
 *       contrato do plugin.</li>
 *   <li><b>Entrada adaptada.</b> Se a operação declara um primeiro parâmetro {@code string}, o mapa da
 *       entrada vira texto ({@link ComponentInputs#text}); os demais tipos o próprio plugin adapta.</li>
 * </ol>
 *
 * <p>E uma regra de saída: um {@link Result} com {@code success=false} é falha. Os agentes do catálogo
 * respondem "tarefa não suportada" assim, sem exceção; tratado como saída comum, o passo (e a invocação)
 * terminava COMPLETED carregando um fracasso — o "responde sucesso e nada acontece" da homologação.</p>
 */
public final class CatalogComponents {

    private static final Logger log = LoggerFactory.getLogger(CatalogComponents.class);

    /** Propriedade de metadados com a operação a usar quando o nó/invocação não escolhe nenhuma. */
    public static final String DEFAULT_OPERATION_HINT = "defaultOperation";

    /** A operação que o factory usava para todo nó sem operação — mantida para quem a declara. */
    static final String LEGACY_OPERATION = "execute";

    /**
     * Chaves da config do nó que pertencem ao motor e ao designer, não ao plugin: identidade e roteamento
     * do nó, e a escolha de modelo que o painel de agente escreve. Ficam fora do {@code initialize} para
     * que um plugin cujo {@code validateConfig} recusa chaves desconhecidas (ou as converte com cast) não
     * falhe por um campo que o usuário nem digitou. O resto passa: é a config que o plugin declara.
     */
    static final Set<String> RESERVED_CONFIG_KEYS = Set.of(
            "operation", "label", "title", "description", "componentId",
            "agentTypeId", "assistantTypeId", "toolId",
            "agentPattern", "personaId", "provider", "llmProvider", "model", "temperature", "maxTokens");

    private CatalogComponents() {
    }

    /** Como o componente é chamado; é o ponto onde o {@link ComponentStep} encaixa os interceptors. */
    @FunctionalInterface
    public interface Invoker {
        Object call(AIComponent component, String operation, Object input) throws Exception;
    }

    /**
     * Inicializa um componente no momento em que entra no catálogo (embutidos e plugins do diretório).
     * Falhar não impede o registro nem a subida: o componente continua listado, e a execução de um nó
     * usa a instância isolada, inicializada com a config do nó — que pode ser justamente o que faltava.
     *
     * @return {@code true} se inicializou
     */
    public static boolean initializeOnRegistration(AIComponent component) {
        String id = idOf(component);
        try {
            component.initialize(Map.of());
            return true;
        } catch (Throwable e) {
            log.warn("Componente '{}' não inicializou sem config ao entrar no catálogo ({}); fica registrado "
                    + "e cada execução o inicializa com a config do nó", id, e.getMessage());
            return false;
        }
    }

    /** A operação padrão do componente (regra 2 da classe). */
    public static String defaultOperation(ComponentMetadata metadata) {
        if (metadata == null) {
            return LEGACY_OPERATION;
        }
        Object hint = metadata.properties() == null ? null : metadata.properties().get(DEFAULT_OPERATION_HINT);
        if (hint != null && !hint.toString().isBlank()) {
            return hint.toString();
        }
        return LEGACY_OPERATION;
    }

    /** A operação pedida, ou a padrão quando nenhuma foi pedida ({@code null}/em branco). */
    public static String resolveOperation(ComponentMetadata metadata, String requested) {
        return requested == null || requested.isBlank() ? defaultOperation(metadata) : requested;
    }

    /** Ids das operações declaradas nos metadados, na ordem declarada. */
    public static List<String> declaredOperations(ComponentMetadata metadata) {
        if (metadata == null || metadata.operations() == null) {
            return List.of();
        }
        return metadata.operations().stream()
                .map(ComponentMetadata.OperationMetadata::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    /** A entrada no formato que a operação declara (regra 3 da classe). */
    public static Object adaptInput(ComponentMetadata metadata, String operation, Object input) {
        if (input == null || input instanceof CharSequence || metadata == null || metadata.operations() == null) {
            return input;
        }
        for (ComponentMetadata.OperationMetadata op : metadata.operations()) {
            if (operation.equals(op.id()) && op.inputs() != null && !op.inputs().isEmpty()
                    && "string".equalsIgnoreCase(op.inputs().get(0).type())) {
                return ComponentInputs.text(input);
            }
        }
        return input;
    }

    /** Executa o componente do catálogo chamando {@link AIComponent#execute} direto. */
    public static Object invoke(AIComponent registered, String requestedOperation, Map<String, Object> config,
                                Object input, ExecutionContext context) throws Exception {
        return invoke(registered, requestedOperation, config, input,
                (component, operation, in) -> component.execute(operation, in, context));
    }

    /**
     * Executa o componente do catálogo aplicando as três regras da classe.
     *
     * @param registered         a instância do catálogo (já resolvida no escopo do fluxo)
     * @param requestedOperation a operação do nó/invocação; {@code null} usa a padrão
     * @param config             a config do nó, entregue ao {@code initialize} da instância isolada
     */
    public static Object invoke(AIComponent registered, String requestedOperation, Map<String, Object> config,
                                Object input, Invoker invoker) throws Exception {
        ComponentMetadata metadata = registered.getMetadata();
        String operation = resolveOperation(metadata, requestedOperation);
        requireDeclared(registered, metadata, operation, requestedOperation == null || requestedOperation.isBlank());
        Object adapted = adaptInput(metadata, operation, input);
        AIComponent instance = isolatedInstance(registered, config);
        try {
            return requireSuccess(registered, invoker.call(instance, operation, adapted));
        } finally {
            if (instance != registered) {
                release(instance);
            }
        }
    }

    /**
     * Recusa, antes de qualquer efeito, a operação que um plugin não declara. Só para plugins: um
     * componente registrado à mão pode atender operações que não lista nos metadados, e recusá-las
     * quebraria quem já funcionava. Por isso também não se reescreve depois uma
     * {@code IllegalArgumentException} do componente — ela pode ser "entrada inválida", não "operação
     * inexistente".
     */
    private static void requireDeclared(AIComponent registered, ComponentMetadata metadata, String operation,
                                        boolean implicit) {
        if (!(registered instanceof ComponentPlugin)) {
            return;
        }
        List<String> declared = declaredOperations(metadata);
        if (!declared.isEmpty() && !declared.contains(operation)) {
            // "Unsupported operation: x" do plugin não diz o que existe; quem montou o nó precisa disso.
            if (implicit) {
                throw new IllegalArgumentException("nenhuma operação informada e " + idOf(registered)
                        + " não declara uma operação padrão; informe a operação: " + String.join(", ", declared));
            }
            throw new IllegalArgumentException("operação '" + operation + "' não existe em "
                    + idOf(registered) + "; operações: " + String.join(", ", declared));
        }
    }

    /** {@link Result} sem sucesso vira exceção com as mensagens dele (regra de saída da classe). */
    private static Object requireSuccess(AIComponent registered, Object output) {
        if (output instanceof Result r && !r.success()) {
            List<String> messages = r.messages() == null ? List.of() : r.messages();
            String why = messages.isEmpty() ? "sem mensagem" : String.join("; ", messages);
            throw new IllegalStateException(idOf(registered) + " falhou: " + why);
        }
        return output;
    }

    /** A config do nó sem as chaves do motor/designer ({@link #RESERVED_CONFIG_KEYS}). */
    static Map<String, Object> pluginConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> own = new LinkedHashMap<>(config);
        own.keySet().removeAll(RESERVED_CONFIG_KEYS);
        return own;
    }

    /**
     * Uma instância nova, carregada ({@code onLoad}) e inicializada com a config do nó, ou a registrada
     * quando o componente não é um plugin instanciável (regra 1 da classe). O {@code onLoad} vem antes do
     * {@code initialize}, na ordem em que o singleton os recebe (o manager chama {@code onLoad} ao instalar;
     * o catálogo do app, {@code initialize} ao registrar): um jar externo que prepara recursos no
     * {@code onLoad} encontraria a cópia por execução sem eles.
     *
     * @throws IllegalStateException "configuração inválida para &lt;id&gt;" quando o {@code initialize} rejeita
     *                               a config do nó — falha da execução, nunca de abrir/salvar o fluxo
     */
    static AIComponent isolatedInstance(AIComponent registered, Map<String, Object> config) {
        Constructor<? extends AIComponent> constructor = prototypeConstructor(registered);
        if (constructor == null) {
            return registered;
        }
        AIComponent instance;
        try {
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Componente '{}' não pôde ser instanciado por execução; usando a instância do catálogo",
                    idOf(registered), e);
            return registered;
        }
        ComponentPlugin plugin = (ComponentPlugin) instance;
        try {
            plugin.onLoad(null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("plugin " + idOf(registered) + " falhou ao carregar: "
                    + messageOf(e), e);
        }
        try {
            instance.initialize(pluginConfig(config));
        } catch (RuntimeException e) {
            unloadQuietly(plugin);
            throw new IllegalStateException("configuração inválida para " + idOf(registered) + ": "
                    + messageOf(e), e);
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static Constructor<? extends AIComponent> prototypeConstructor(AIComponent registered) {
        // Só plugins: é o contrato do ServiceLoader (construtor público sem argumentos) e é como os
        // embutidos e os jars do diretório entram no catálogo — recriar dá o mesmo componente, sem o
        // estado acumulado. Componentes que não são plugins (registrados à mão, mocks) seguem como
        // estavam. Limite conhecido: um plugin montado por builder e registrado à mão é recriado sem o
        // que o builder configurou; a config que vale para ele é a do nó.
        if (!(registered instanceof ComponentPlugin)) {
            return null;
        }
        Class<? extends AIComponent> type = registered.getClass();
        if (!Modifier.isPublic(type.getModifiers()) || type.isAnonymousClass() || type.isSynthetic()) {
            return null;
        }
        try {
            Constructor<?> c = type.getConstructor();
            return (Constructor<? extends AIComponent>) c;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Fim da instância por execução: {@code shutdown} e depois {@code onUnload}, o inverso da criação. */
    private static void release(AIComponent instance) {
        try {
            instance.shutdown();
        } catch (RuntimeException e) {
            log.debug("shutdown de '{}' falhou", idOf(instance), e);
        }
        if (instance instanceof ComponentPlugin plugin) {
            unloadQuietly(plugin);
        }
    }

    private static void unloadQuietly(ComponentPlugin plugin) {
        try {
            plugin.onUnload();
        } catch (RuntimeException e) {
            log.debug("onUnload de '{}' falhou", plugin.getClass().getName(), e);
        }
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String idOf(AIComponent component) {
        try {
            ComponentMetadata m = component.getMetadata();
            return m == null ? component.getClass().getName() : m.id();
        } catch (RuntimeException e) {
            return component.getClass().getName();
        }
    }
}
