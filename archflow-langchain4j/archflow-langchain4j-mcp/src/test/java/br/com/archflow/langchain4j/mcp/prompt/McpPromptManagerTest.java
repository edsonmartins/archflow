package br.com.archflow.langchain4j.mcp.prompt;

import br.com.archflow.langchain4j.mcp.McpModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for McpPromptManager.
 */
class McpPromptManagerTest {

    private McpPromptManager manager;

    @BeforeEach
    void setUp() {
        manager = new McpPromptManager();
    }

    @Test
    void testEmptyManager() {
        assertEquals(0, manager.size());
        assertFalse(manager.hasPrompt("test"));
        assertTrue(manager.getTemplates().isEmpty());
    }

    @Test
    void testRegisterTextPrompt() {
        manager.registerTextPrompt(
                "greet",
                "Greeting prompt",
                "Hello, {name}!"
        );

        assertEquals(1, manager.size());
        assertTrue(manager.hasPrompt("greet"));

        McpPromptManager.PromptTemplate template = manager.getTemplate("greet");
        assertEquals("greet", template.name());
        assertEquals("Greeting prompt", template.description());
    }

    @Test
    void testGetTextPrompt() throws Exception {
        manager.registerTextPrompt(
                "greet",
                "Greeting prompt",
                "Hello, {name}!"
        );

        McpModel.PromptResult result = manager.getPrompt("greet", Map.of("name", "World")).get();

        assertEquals("Generated prompt", result.description());
        assertEquals(1, result.messages().size());
        assertEquals("Hello, World!", result.messages().get(0).content());
    }

    @Test
    void testRegisterWithBuilder() {
        manager.register("test", "Test prompt")
                .addArgument("input", "Input text")
                .asFixed("This is a fixed prompt");

        assertEquals(1, manager.size());
        assertTrue(manager.hasPrompt("test"));
    }

    @Test
    void testFixedPrompt() throws Exception {
        manager.register("fixed", "Fixed prompt")
                .asFixed("This is always the same");

        McpModel.PromptResult result = manager.getPrompt("fixed", Map.of()).get();

        assertEquals("Fixed prompt", result.description());
        assertEquals("This is always the same", result.messages().get(0).content());
    }

    @Test
    void testSystemPrompt() throws Exception {
        manager.register("system", "System prompt")
                .asSystem("You are a helpful assistant.");

        McpModel.PromptResult result = manager.getPrompt("system", Map.of("input", "Hi")).get();

        assertEquals(2, result.messages().size());
        assertEquals(McpModel.PromptMessage.ROLE_SYSTEM, result.messages().get(0).role());
        assertEquals("You are a helpful assistant.", result.messages().get(0).content());
        assertEquals(McpModel.PromptMessage.ROLE_USER, result.messages().get(1).role());
        assertEquals("Hi", result.messages().get(1).content());
    }

    @Test
    void testPromptWithMultipleArguments() throws Exception {
        manager.registerTextPrompt(
                "template",
                "Template with multiple args",
                "Translate {text} from {source} to {target}"
        );

        McpModel.PromptResult result = manager.getPrompt("template",
                Map.of(
                        "text", "Hello",
                        "source", "English",
                        "target", "Spanish"
                )
        ).get();

        String content = (String) result.messages().get(0).content();
        assertTrue(content.contains("Hello"));
        assertTrue(content.contains("English"));
        assertTrue(content.contains("Spanish"));
    }

    @Test
    void testPromptWithCustomExecutor() throws Exception {
        manager.register("custom", "Custom prompt")
                .withExecutor(args -> new McpModel.PromptResult(
                        "Custom result",
                        List.of(new McpModel.PromptMessage(
                                McpModel.PromptMessage.ROLE_USER,
                                "Custom: " + args.get("input")
                        ))
                ));

        McpModel.PromptResult result = manager.getPrompt("custom", Map.of("input", "test")).get();

        assertEquals("Custom: test", result.messages().get(0).content());
    }

    @Test
    void testListPrompts() {
        manager.register("p1", "Prompt 1").asFixed("One");
        manager.register("p2", "Prompt 2").asFixed("Two");

        List<McpModel.Prompt> prompts = manager.listPrompts();
        assertEquals(2, prompts.size());
        assertEquals("p1", prompts.get(0).name());
        assertEquals("p2", prompts.get(1).name());
    }

    @Test
    void testUnregisterPrompt() {
        manager.register("test", "Test").asFixed("Test");
        assertTrue(manager.hasPrompt("test"));

        manager.unregister("test");
        assertFalse(manager.hasPrompt("test"));
        assertEquals(0, manager.size());
    }

    @Test
    void testPromptNotFound() {
        assertThrows(Exception.class, () -> {
            manager.getPrompt("nonexistent", Map.of()).get();
        });
    }

    @Test
    void testPredefinedPrompts() {
        manager.registerPredefinedPrompts();

        assertTrue(manager.hasPrompt("summarize"));
        assertTrue(manager.hasPrompt("explain"));
        assertTrue(manager.hasPrompt("translate"));
        assertTrue(manager.hasPrompt("code_review"));
        assertTrue(manager.hasPrompt("format"));

        assertEquals(5, manager.size());
    }

    @Test
    void testPredefinedPromptUsage() throws Exception {
        manager.registerPredefinedPrompts();

        McpModel.PromptResult result = manager.getPrompt("summarize",
                Map.of(
                        "text", "This is a long text that needs summary.",
                        "style", "brief"
                )
        ).get();

        String content = (String) result.messages().get(0).content();
        assertTrue(content.contains("summarize"));
        assertTrue(content.contains("brief"));
    }

    @Test
    void testTemplateWithMissingArgument() throws Exception {
        manager.registerTextPrompt(
                "test",
                "Test template",
                "Hello {name} from {city}"
        );

        // Missing {city} - should remain as placeholder
        McpModel.PromptResult result = manager.getPrompt("test", Map.of("name", "John")).get();

        String content = (String) result.messages().get(0).content();
        assertTrue(content.contains("John"));
        assertTrue(content.contains("{city}"));
    }

    @Test
    void testPromptTemplateTags() {
        McpPromptManager.PromptTemplate template = new McpPromptManager.PromptTemplate(
                "test",
                "Test",
                List.of(),
                List.of("tag1", "tag2")
        );

        assertEquals(2, template.tags().size());
        assertTrue(template.tags().contains("tag1"));
    }
}
