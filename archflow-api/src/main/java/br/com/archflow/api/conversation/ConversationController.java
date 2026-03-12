package br.com.archflow.api.conversation;

import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;

import java.util.List;

/**
 * REST controller for conversation management.
 *
 * <p>This interface defines the contract for conversation endpoints.
 * Implementations can be provided for different web frameworks (Spring Boot, JAX-RS, etc.).</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /api/conversations/suspend - Suspend a conversation awaiting user input</li>
 *   <li>POST /api/conversations/resume - Resume a suspended conversation with form data</li>
 *   <li>GET /api/conversations/{id} - Get conversation details and messages</li>
 *   <li>GET /api/conversations/{id}/messages - Get conversation messages</li>
 *   <li>DELETE /api/conversations/{id} - Cancel a suspended conversation</li>
 * </ul>
 */
public interface ConversationController {

    /**
     * Suspends a conversation awaiting user input.
     *
     * @param request The suspend request with conversation and form details
     * @return Response with the suspended conversation details and resume token
     */
    ConversationResponse suspend(SuspendRequest request);

    /**
     * Resumes a suspended conversation with user-submitted form data.
     *
     * @param request The resume request with token and form data
     * @return Response with the resumed conversation details
     */
    ConversationResponse resume(ResumeRequest request);

    /**
     * Gets conversation details by ID.
     *
     * @param conversationId The conversation ID
     * @return The conversation details including status and form
     * @throws NotFoundException if the conversation doesn't exist
     */
    ConversationResponse getConversation(String conversationId);

    /**
     * Gets conversation messages by conversation ID.
     *
     * @param conversationId The conversation ID
     * @return The conversation response with messages
     */
    ConversationResponse getMessages(String conversationId);

    /**
     * Cancels a suspended conversation.
     *
     * @param conversationId The conversation ID
     * @throws NotFoundException if the conversation doesn't exist
     */
    void cancel(String conversationId);

    /**
     * Exception thrown when a conversation is not found.
     */
    class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
