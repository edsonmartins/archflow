package br.com.archflow.plugin.loader;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin fixture used by loader tests. Discovered via a META-INF/services
 * entry written into a jar at test runtime (the class itself resolves from
 * the test classpath through parent delegation).
 */
public class TestComponentPlugin implements ComponentPlugin, AIComponent {

    public static final String ID = "test-component-plugin";
    public static final AtomicBoolean LOADED = new AtomicBoolean(false);
    public static final AtomicBoolean UNLOADED = new AtomicBoolean(false);

    @Override
    public void validateConfig(Map<String, Object> config) {
    }

    @Override
    public void onLoad(ExecutionContext context) {
        LOADED.set(true);
    }

    @Override
    public void onUnload() {
        UNLOADED.set(true);
    }

    @Override
    public void initialize(Map<String, Object> config) {
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                ID,
                "Test Component Plugin",
                "Fixture for loader tests",
                ComponentType.TOOL,
                "1.0.0",
                Set.of(),
                List.of(),
                Map.of(),
                Set.of());
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) {
        return input;
    }

    @Override
    public void shutdown() {
    }
}
