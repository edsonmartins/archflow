package br.com.archflow.conversation.guardrail;

import br.com.archflow.conversation.guardrail.builtin.IdentificationGuardrail;
import br.com.archflow.conversation.guardrail.builtin.PiiRedactionGuardrail;
import br.com.archflow.conversation.guardrail.builtin.ProfanityGuardrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailChainTest {

    @Nested
    @DisplayName("GuardrailResult semantics")
    class ResultSemantics {

        @Test
        void okIsAllowed() {
            GuardrailResult r = GuardrailResult.ok();
            assertThat(r.isAllowed()).isTrue();
            assertThat(r.isBlocked()).isFalse();
            assertThat(r.isRedacted()).isFalse();
            assertThat(r.action()).isEqualTo(GuardrailResult.Action.ALLOW);
        }

        @Test
        void blockRequiresReplacement() {
            assertThatThrownBy(() -> new GuardrailResult(GuardrailResult.Action.BLOCK, "x", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void redactRequiresReplacement() {
            assertThatThrownBy(() -> new GuardrailResult(GuardrailResult.Action.REDACT, "x", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void blockedFactoryProducesBlockAction() {
            GuardrailResult r = GuardrailResult.blocked("reason", "sorry");
            assertThat(r.isBlocked()).isTrue();
            assertThat(r.replacement()).isEqualTo("sorry");
        }

        @Test
        void redactedFactoryProducesRedactAction() {
            GuardrailResult r = GuardrailResult.redacted("pii", "safe");
            assertThat(r.isRedacted()).isTrue();
            assertThat(r.replacement()).isEqualTo("safe");
        }
    }

    @Nested
    @DisplayName("ProfanityGuardrail")
    class Profanity {

        @Test
        void blocksInputWithProfanity() {
            var g = new ProfanityGuardrail();
            GuardrailResult r = g.evaluateInput("você é um idiota", Map.of());
            assertThat(r.isBlocked()).isTrue();
            assertThat(r.reason()).isEqualTo("profanity");
            assertThat(r.replacement()).contains("respeitoso");
        }

        @Test
        void blocksOutputWithProfanity() {
            var g = new ProfanityGuardrail();
            GuardrailResult r = g.evaluateOutput("isso é lixo", Map.of());
            assertThat(r.isBlocked()).isTrue();
            assertThat(r.replacement()).contains("Desculpe");
        }

        @Test
        void allowsCleanInput() {
            var g = new ProfanityGuardrail();
            assertThat(g.evaluateInput("quero rastrear meu pedido", Map.of()).isAllowed()).isTrue();
        }

        @Test
        void respectsCustomBlacklist() {
            var g = new ProfanityGuardrail(List.of("bobão"));
            assertThat(g.evaluateInput("bobão", Map.of()).isBlocked()).isTrue();
            assertThat(g.evaluateInput("idiota", Map.of()).isAllowed()).isTrue();
        }

        @Test
        void handlesNullText() {
            assertThat(new ProfanityGuardrail().evaluateInput(null, Map.of()).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("IdentificationGuardrail")
    class Identification {

        @Test
        void blocksBusinessOperationWithoutIdentification() {
            var g = new IdentificationGuardrail();
            GuardrailResult r = g.evaluateInput("quero rastrear meu pedido", Map.of("identified", false));
            assertThat(r.isBlocked()).isTrue();
            assertThat(r.reason()).isEqualTo("missing_identification");
            assertThat(r.replacement()).contains("CNPJ");
        }

        @Test
        void allowsBusinessOperationWhenIdentified() {
            var g = new IdentificationGuardrail();
            assertThat(g.evaluateInput("rastrear pedido", Map.of("identified", true)).isAllowed()).isTrue();
        }

        @Test
        void bypassesGreetings() {
            var g = new IdentificationGuardrail();
            assertThat(g.evaluateInput("oi", Map.of("identified", false)).isAllowed()).isTrue();
            assertThat(g.evaluateInput("bom dia", Map.of("identified", false)).isAllowed()).isTrue();
            assertThat(g.evaluateInput("Olá", Map.of("identified", false)).isAllowed()).isTrue();
        }

        @Test
        void bypassesConfirmations() {
            var g = new IdentificationGuardrail();
            assertThat(g.evaluateInput("ok", Map.of("identified", false)).isAllowed()).isTrue();
            assertThat(g.evaluateInput("obrigado", Map.of("identified", false)).isAllowed()).isTrue();
            assertThat(g.evaluateInput("tchau", Map.of("identified", false)).isAllowed()).isTrue();
        }

        @Test
        void bypassesShortMessages() {
            var g = new IdentificationGuardrail();
            assertThat(g.evaluateInput("si", Map.of("identified", false)).isAllowed()).isTrue();
        }

        @Test
        void allowsNonBusinessMessages() {
            var g = new IdentificationGuardrail();
            assertThat(g.evaluateInput("como você está hoje", Map.of("identified", false)).isAllowed()).isTrue();
        }

        @Test
        void handlesMissingIdentifiedKey() {
            var g = new IdentificationGuardrail();
            GuardrailResult r = g.evaluateInput("rastrear pedido 123", Map.of());
            assertThat(r.isBlocked()).isTrue();
        }
    }

    @Nested
    @DisplayName("PiiRedactionGuardrail")
    class Pii {

        @Test
        void redactsCpfInOutput() {
            var g = new PiiRedactionGuardrail();
            GuardrailResult r = g.evaluateOutput("Seu CPF é 123.456.789-00", Map.of());
            assertThat(r.isRedacted()).isTrue();
            assertThat(r.reason()).isEqualTo("pii_detected");
            assertThat(r.replacement()).doesNotContain("123.456.789-00").contains("***.***.***-**");
        }

        @Test
        void redactsCpfInInputToo() {
            var g = new PiiRedactionGuardrail();
            GuardrailResult r = g.evaluateInput("meu CPF é 111.222.333-44", Map.of());
            assertThat(r.isRedacted()).isTrue();
        }

        @Test
        void outputOnlyDoesNotRedactInput() {
            var g = PiiRedactionGuardrail.outputOnly();
            assertThat(g.evaluateInput("CPF 111.222.333-44", Map.of()).isAllowed()).isTrue();
            assertThat(g.evaluateOutput("CPF 111.222.333-44", Map.of()).isRedacted()).isTrue();
        }

        @Test
        void inputOnlyDoesNotRedactOutput() {
            var g = PiiRedactionGuardrail.inputOnly();
            assertThat(g.evaluateInput("CPF 111.222.333-44", Map.of()).isRedacted()).isTrue();
            assertThat(g.evaluateOutput("CPF 111.222.333-44", Map.of()).isAllowed()).isTrue();
        }

        @Test
        void redactsCnpj() {
            var g = new PiiRedactionGuardrail();
            GuardrailResult r = g.evaluateOutput("CNPJ 12.345.678/0001-99", Map.of());
            assertThat(r.isRedacted()).isTrue();
            assertThat(r.replacement()).doesNotContain("12.345.678/0001-99");
        }

        @Test
        void redactsCreditCard() {
            var g = new PiiRedactionGuardrail();
            GuardrailResult r = g.evaluateOutput("Cartão 1234 5678 9012 3456 aprovado", Map.of());
            assertThat(r.isRedacted()).isTrue();
            assertThat(r.replacement()).doesNotContain("1234 5678 9012 3456");
        }

        @Test
        void redactsEmail() {
            var g = new PiiRedactionGuardrail();
            GuardrailResult r = g.evaluateOutput("Envie para edson@empresa.com.br", Map.of());
            assertThat(r.isRedacted()).isTrue();
            assertThat(r.replacement()).doesNotContain("edson@empresa.com.br");
            assertThat(r.replacement()).contains("@empresa.com.br");
        }

        @Test
        void allowsCleanOutput() {
            var g = new PiiRedactionGuardrail();
            assertThat(g.evaluateOutput("seu pedido está em trânsito", Map.of()).isAllowed()).isTrue();
        }

        @Test
        void handlesNullAndEmpty() {
            var g = new PiiRedactionGuardrail();
            assertThat(g.evaluateOutput(null, Map.of()).isAllowed()).isTrue();
            assertThat(g.evaluateOutput("", Map.of()).isAllowed()).isTrue();
        }

        @Test
        void redactsMultiplePiiTypesAtOnce() {
            var g = new PiiRedactionGuardrail();
            GuardrailResult r = g.evaluateOutput(
                    "CPF 111.222.333-44 email a@b.com",
                    Map.of()
            );
            assertThat(r.isRedacted()).isTrue();
            assertThat(r.replacement()).doesNotContain("111.222.333-44");
            assertThat(r.replacement()).doesNotContain("a@b.com");
        }
    }

    @Nested
    @DisplayName("Chain composition")
    class Chain {

        @Test
        void firstBlockingGuardrailShortCircuits() {
            var chain = new GuardrailChain(List.of(
                    new ProfanityGuardrail(),
                    new IdentificationGuardrail()
            ));

            GuardrailChain.ChainResult r = chain.evaluateInput(
                    "seu sistema é lixo, rastrear pedido",
                    Map.of("identified", false)
            );

            assertThat(r.blocked()).isTrue();
            assertThat(r.blockReason()).isEqualTo("profanity");
        }

        @Test
        void chainAllowsWhenAllPass() {
            var chain = new GuardrailChain(List.of(
                    new ProfanityGuardrail(),
                    new IdentificationGuardrail()
            ));

            GuardrailChain.ChainResult r = chain.evaluateInput(
                    "rastrear pedido 123",
                    Map.of("identified", true)
            );

            assertThat(r.blocked()).isFalse();
            assertThat(r.wasRedacted()).isFalse();
            assertThat(r.finalText()).isEqualTo("rastrear pedido 123");
        }

        @Test
        void redactionContinuesThroughChain() {
            var chain = new GuardrailChain(List.of(
                    new PiiRedactionGuardrail(),
                    new ProfanityGuardrail()
            ));

            GuardrailChain.ChainResult r = chain.evaluateOutput("CPF 111.222.333-44 limpo", Map.of());

            assertThat(r.blocked()).isFalse();
            assertThat(r.wasRedacted()).isTrue();
            assertThat(r.redactionReasons()).containsExactly("pii_detected");
            assertThat(r.finalText()).doesNotContain("111.222.333-44").contains("***.***.***-**");
        }

        @Test
        void blockAfterRedactStillBlocks() {
            var chain = new GuardrailChain(List.of(
                    new PiiRedactionGuardrail(),
                    new ProfanityGuardrail()
            ));

            GuardrailChain.ChainResult r = chain.evaluateOutput(
                    "CPF 111.222.333-44 é lixo", Map.of());

            assertThat(r.blocked()).isTrue();
            assertThat(r.blockReason()).isEqualTo("profanity");
            // finalText keeps the original (not redacted) because block won
            assertThat(r.finalText()).isEqualTo("CPF 111.222.333-44 é lixo");
        }

        @Test
        void multipleRedactionsAreTracked() {
            AgentGuardrail first = new AgentGuardrail() {
                @Override public String getName() { return "A"; }
                @Override public GuardrailResult evaluateInput(String m, Map<String, Object> c) {
                    return GuardrailResult.redacted("first", m.replace("foo", "***"));
                }
            };
            AgentGuardrail second = new AgentGuardrail() {
                @Override public String getName() { return "B"; }
                @Override public GuardrailResult evaluateInput(String m, Map<String, Object> c) {
                    return GuardrailResult.redacted("second", m.replace("bar", "***"));
                }
            };

            var chain = new GuardrailChain(List.of(first, second));
            GuardrailChain.ChainResult r = chain.evaluateInput("foo bar baz", Map.of());

            assertThat(r.blocked()).isFalse();
            assertThat(r.wasRedacted()).isTrue();
            assertThat(r.redactionReasons()).containsExactly("first", "second");
            assertThat(r.finalText()).isEqualTo("*** *** baz");
        }

        @Test
        void emptyChainAllowsEverything() {
            var chain = new GuardrailChain(List.of());
            assertThat(chain.evaluateInput("anything", Map.of()).blocked()).isFalse();
            assertThat(chain.evaluateOutput("anything", Map.of()).blocked()).isFalse();
        }

        @Test
        void exposesGuardrailList() {
            var chain = new GuardrailChain(List.of(new ProfanityGuardrail()));
            assertThat(chain.getGuardrails()).hasSize(1);
        }
    }
}
