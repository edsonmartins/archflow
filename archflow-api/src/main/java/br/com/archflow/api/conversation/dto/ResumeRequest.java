package br.com.archflow.api.conversation.dto;

import java.util.Map;

/**
 * Request DTO for resuming a suspended conversation.
 */
public record ResumeRequest(
        String resumeToken,
        Map<String, Object> formData
) {
}
