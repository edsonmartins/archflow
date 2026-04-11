package br.com.archflow.conversation.guardrail.builtin;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailResult;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Built-in PII redaction guardrail. Detects Brazilian CPF, CNPJ, email and
 * credit card patterns and masks them in place.
 *
 * <p>This guardrail does NOT block — it returns {@link GuardrailResult#redacted}
 * so the chain continues with the masked text.
 *
 * <p>Applies to both input and output by default. Use {@link #inputOnly()} or
 * {@link #outputOnly()} to restrict the direction.
 *
 * <p>Patterns: CPF, CNPJ, credit card, email.
 */
public class PiiRedactionGuardrail implements AgentGuardrail {

    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b");
    private static final Pattern CNPJ = Pattern.compile("\\b\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d{4}[\\s-]?){3}\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@([\\w-]+\\.)+[\\w-]{2,}\\b");

    private final boolean applyToInput;
    private final boolean applyToOutput;

    public PiiRedactionGuardrail() {
        this(true, true);
    }

    private PiiRedactionGuardrail(boolean applyToInput, boolean applyToOutput) {
        this.applyToInput = applyToInput;
        this.applyToOutput = applyToOutput;
    }

    public static PiiRedactionGuardrail inputOnly() {
        return new PiiRedactionGuardrail(true, false);
    }

    public static PiiRedactionGuardrail outputOnly() {
        return new PiiRedactionGuardrail(false, true);
    }

    @Override
    public String getName() {
        return "pii-redaction";
    }

    @Override
    public GuardrailResult evaluateInput(String message, Map<String, Object> context) {
        return applyToInput ? redact(message) : GuardrailResult.ok();
    }

    @Override
    public GuardrailResult evaluateOutput(String response, Map<String, Object> context) {
        return applyToOutput ? redact(response) : GuardrailResult.ok();
    }

    private GuardrailResult redact(String text) {
        if (text == null || text.isEmpty()) return GuardrailResult.ok();

        String redacted = text;
        boolean hasPii = false;

        if (CPF.matcher(redacted).find()) {
            redacted = CPF.matcher(redacted).replaceAll("***.***.***-**");
            hasPii = true;
        }
        if (CNPJ.matcher(redacted).find()) {
            redacted = CNPJ.matcher(redacted).replaceAll("**.***.***/****-**");
            hasPii = true;
        }
        if (CREDIT_CARD.matcher(redacted).find()) {
            redacted = CREDIT_CARD.matcher(redacted).replaceAll("**** **** **** ****");
            hasPii = true;
        }
        if (EMAIL.matcher(redacted).find()) {
            redacted = EMAIL.matcher(redacted).replaceAll(m -> {
                String email = m.group();
                int at = email.indexOf('@');
                return email.charAt(0) + "***" + email.substring(at);
            });
            hasPii = true;
        }

        return hasPii
                ? GuardrailResult.redacted("pii_detected", redacted)
                : GuardrailResult.ok();
    }
}
