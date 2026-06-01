package br.com.archflow.conversation.agent;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailChain;
import br.com.archflow.conversation.guardrail.GuardrailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationalAgent — tool-calling loop")
class ConversationalAgentTest {

    /** Scripted chat: returns queued replies in order, recording prompts seen. */
    static class ScriptedChat implements ConversationalAgent.ChatFunction {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        ScriptedChat(String... scripted) {
            for (String s : scripted) replies.add(s);
        }
        @Override public String reply(String prompt) {
            prompts.add(prompt);
            return replies.isEmpty() ? "(no more replies)" : replies.poll();
        }
    }

    private ToolRegistry registryWithEcho() {
        DefaultToolRegistry reg = new DefaultToolRegistry();
        reg.register("echo", "echoes its text param", params -> "echoed:" + params.getOrDefault("text", ""));
        return reg;
    }

    @Test
    @DisplayName("direct answer (no tool) returns in one iteration")
    void directAnswer() {
        var agent = ConversationalAgent.builder()
                .chat(new ScriptedChat("Hello there!"))
                .build();

        var r = agent.chat("hi");

        assertThat(r.reply()).isEqualTo("Hello there!");
        assertThat(r.blocked()).isFalse();
        assertThat(r.iterations()).isEqualTo(1);
        assertThat(r.toolsUsed()).isEmpty();
    }

    @Test
    @DisplayName("executes a tool call then feeds the result back for the final answer")
    void toolCallThenFinal() {
        ScriptedChat chat = new ScriptedChat(
                "[TOOL: echo]\n[PARAMS: text=hi]",
                "Final: done");
        var agent = ConversationalAgent.builder()
                .systemPrompt("You are helpful.")
                .tools(registryWithEcho())
                .chat(chat)
                .build();

        var r = agent.chat("please echo hi");

        assertThat(r.toolsUsed()).containsExactly("echo");
        assertThat(r.iterations()).isEqualTo(2);
        assertThat(r.reply()).isEqualTo("Final: done");
        // the tool result was fed back into the transcript for the 2nd turn
        assertThat(chat.prompts.get(1)).contains("ToolResult(echo): echoed:hi");
    }

    @Test
    @DisplayName("unknown tool yields an error fed back, loop still terminates")
    void unknownTool() {
        var agent = ConversationalAgent.builder()
                .tools(new DefaultToolRegistry())
                .chat(new ScriptedChat("[TOOL: nope]", "recovered"))
                .build();

        var r = agent.chat("do something");

        assertThat(r.reply()).isEqualTo("recovered");
        assertThat(r.iterations()).isEqualTo(2);
    }

    @Test
    @DisplayName("guardrail-in block short-circuits before any LLM call")
    void guardrailInBlocks() {
        ScriptedChat chat = new ScriptedChat("should never be returned");
        GuardrailChain chain = new GuardrailChain(List.of(new AgentGuardrail() {
            @Override public String getName() { return "secret-blocker"; }
            @Override public GuardrailResult evaluateInput(String m, Map<String, Object> c) {
                return m.contains("secret")
                        ? GuardrailResult.blocked("contains-secret", "I can't help with that.")
                        : GuardrailResult.ok();
            }
        }));
        var agent = ConversationalAgent.builder().guardrails(chain).chat(chat).build();

        var r = agent.chat("here is my secret");

        assertThat(r.blocked()).isTrue();
        assertThat(r.blockReason()).isEqualTo("contains-secret");
        assertThat(r.reply()).isEqualTo("I can't help with that.");
        assertThat(r.iterations()).isZero();
        assertThat(chat.prompts).isEmpty();   // LLM never called
    }

    @Test
    @DisplayName("guardrail-out redaction rewrites the final answer")
    void guardrailOutRedacts() {
        GuardrailChain chain = new GuardrailChain(List.of(new AgentGuardrail() {
            @Override public String getName() { return "redactor"; }
            @Override public GuardrailResult evaluateOutput(String m, Map<String, Object> c) {
                return GuardrailResult.redacted("pii", m.replace("12345", "[redacted]"));
            }
        }));
        var agent = ConversationalAgent.builder()
                .guardrails(chain)
                .chat(new ScriptedChat("Your code is 12345"))
                .build();

        var r = agent.chat("what is my code");

        assertThat(r.blocked()).isFalse();
        assertThat(r.reply()).isEqualTo("Your code is [redacted]");
    }

    @Test
    @DisplayName("loop is bounded by maxIterations")
    void boundedByMaxIterations() {
        // chat always asks for a tool → would loop forever without the bound
        ConversationalAgent.ChatFunction alwaysTool = prompt -> "[TOOL: echo]\n[PARAMS: text=x]";
        var agent = ConversationalAgent.builder()
                .maxIterations(3)
                .tools(registryWithEcho())
                .chat(alwaysTool)
                .build();

        var r = agent.chat("go");

        assertThat(r.iterations()).isEqualTo(3);
        assertThat(r.toolsUsed()).hasSize(3);
    }
}
