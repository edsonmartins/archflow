package br.com.archflow.plugin.loader;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIAssistant;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.plugin.api.spi.ComponentPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ArchflowPluginManager")
class ArchflowPluginManagerTest {

    private ArchflowPluginManager manager;

    @BeforeEach
    void setUp() {
        manager = new ArchflowPluginManager();
    }

    @Nested
    @DisplayName("getComponentsByType")
    class GetComponentsByTypeTest {

        @Test
        @DisplayName("should return empty list when no plugins loaded")
        void shouldReturnEmptyWhenNoPlugins() {
            List<AIAgent> agents = manager.getComponentsByType(ComponentType.AGENT);
            assertThat(agents).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAssistants")
    class GetAssistantsTest {

        @Test
        @DisplayName("should return empty list when no assistants loaded")
        void shouldReturnEmptyList() {
            assertThat(manager.getAssistants()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAgents")
    class GetAgentsTest {

        @Test
        @DisplayName("should return empty list when no agents loaded")
        void shouldReturnEmptyList() {
            assertThat(manager.getAgents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTools")
    class GetToolsTest {

        @Test
        @DisplayName("should return empty list when no tools loaded")
        void shouldReturnEmptyList() {
            assertThat(manager.getTools()).isEmpty();
        }
    }
}
