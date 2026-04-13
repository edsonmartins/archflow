package br.com.archflow.api.workflow;

import br.com.archflow.api.workflow.dto.AgentPatternDto;
import br.com.archflow.api.workflow.dto.GovernanceProfileDto;
import br.com.archflow.api.workflow.dto.McpServerDto;
import br.com.archflow.api.workflow.dto.PersonaDto;
import br.com.archflow.api.workflow.dto.ProviderDto;

import java.util.List;

/**
 * REST contract exposing the metadata the visual workflow editor needs
 * to populate its dropdowns and accordions dynamically.
 *
 * <p>The editor used to hardcode its LLM model list, persona catalog
 * and governance profiles in the frontend bundle. This controller
 * centralizes that data on the backend so adding a new provider or
 * persona no longer requires a frontend release.
 *
 * <p>Endpoints (base path {@code /api/workflow}):
 * <ul>
 *   <li>{@code GET /providers}           — LLM providers with full model catalog</li>
 *   <li>{@code GET /agent-patterns}      — available agent reasoning strategies</li>
 *   <li>{@code GET /personas}            — registered personas</li>
 *   <li>{@code GET /governance-profiles} — pre-configured governance profiles</li>
 *   <li>{@code GET /mcp-servers}         — registered MCP servers</li>
 * </ul>
 *
 * <p>All endpoints are read-only and tenant-agnostic — the data is
 * platform-wide configuration, not per-tenant. Implementations should
 * still require authentication so the catalog isn't leaked publicly.
 */
public interface WorkflowConfigController {

    /**
     * Returns the full LLM provider catalog with nested model lists.
     * Used by the PropertyPanel's two-tier provider/model selects.
     */
    List<ProviderDto> listProviders();

    /**
     * Returns the available agent reasoning strategies
     * (ReAct, Plan-Execute, ReWOO, Chain-of-Thought).
     */
    List<AgentPatternDto> listAgentPatterns();

    /**
     * Returns the registered personas the editor can attach to agent nodes.
     */
    List<PersonaDto> listPersonas();

    /**
     * Returns pre-configured governance profiles the editor can use as
     * starting points when configuring an agent node.
     */
    List<GovernanceProfileDto> listGovernanceProfiles();

    /**
     * Returns the list of MCP servers currently registered with the
     * tool registry, with tool counts for status display.
     */
    List<McpServerDto> listMcpServers();
}
