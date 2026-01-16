# Fase 4: Ecosystem

**Duração Estimada**: 4-6 semanas (4 sprints)
**Objetivo**: Construir ecossistema com templates, marketplace e composição avançada

---

## Visão Geral

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         archflow Ecosystem                              │
│                                                                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │ Workflow    │  │ Suspend/    │  │ Extension   │  │ Workflow-   │   │
│  │ Templates   │  │ Resume      │  │ Marketplace │  │ as-Tool     │   │
│  │             │  │             │  │             │  │             │   │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘   │
│         │                │                │                │           │
│         └────────────────┴────────────────┴────────────────┘           │
│                                   │                                     │
│                                   ▼                                     │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │                    Template Gallery                             │   │
│  │   + One-click Install + Versioning + Community Contributions   │   │
│  └────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Sprint 13: Workflow Templates

### Objetivo
Criar sistema de templates para workflows comuns, instaláveis com um clique.

### Arquitetura de Templates

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Template System                                  │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                     Template Registry                           │    │
│  │                                                                  │    │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐  │    │
│  │  │ Built-in   │ │ Community  │ │ Custom     │ │ Enterprise │  │    │
│  │  │ Templates  │ │ Templates  │ │ Templates  │ │ Templates  │  │    │
│  │  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘  │    │
│  └────────┼───────────────┼───────────────┼───────────────┼────────┘    │
│           │               │               │               │             │
│           └───────────────┴───────────────┴───────────────┘             │
│                           │                                             │
│                           ▼                                             │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                    Template Engine                             │    │
│  │    - Parameter Substitution                                    │    │
│  │    - Validation                                                │    │
│  │    - One-click Install                                         │    │
│  └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 13.1 Template Metadata

```java
package org.archflow.template.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Metadados de um template
 */
@Entity
@Table(name = "af_templates")
public class TemplateMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    private String displayName;
    private String description;

    /**
     * Categoria do template
     */
    @Enumerated(EnumType.STRING)
    private TemplateCategory category;

    /**
     * Tags para busca
     */
    @ElementCollection
    @CollectionTable(name = "af_template_tags", joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "tag")
    private Set<String> tags;

    /**
     * Autor do template
     */
    private String author;

    /**
     * Versão do template
     */
    private String version;

    /**
     * Versão mínima do archflow compatível
     */
    private String minArchflowVersion;

    /**
     * Template built-in (vem com o sistema)?
     */
    private Boolean builtIn = false;

    /**
     * Template verificado pela equipe archflow?
     */
    private Boolean verified = false;

    /**
     * URL da fonte (para templates comunitários)
     */
    private String sourceUrl;

    /**
     * Estatísticas
     */
    private Long installCount = 0L;
    private Double rating = 0.0;
    private Integer ratingCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Parâmetros configuráveis do template
     */
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL)
    private List<TemplateParameter> parameters;

    /**
     * Dependencies (outros templates, tools, etc.)
     */
    @ElementCollection
    @CollectionTable(name = "af_template_dependencies", joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "dependency")
    private Set<String> dependencies;

    public enum TemplateCategory {
        CUSTOMER_SERVICE("Customer Service", "Support, chatbots, triagem"),
        DOCUMENT_PROCESSING("Document Processing", "PDF, OCR, extração de dados"),
        KNOWLEDGE_BASE("Knowledge Base", "RAG, busca semântica"),
        DATA_ANALYSIS("Data Analysis", "Analytics, relatórios"),
        AUTOMATION("Automation", "ETL, workflows automatizados"),
        AGENTIC("Agentic", "Agentes autônomos"),
        INTEGRATION("Integration", "APIs, conectores"),
        DEVELOPER_TOOLS("Developer Tools", "Code assistant, debugging"),
        MARKETING("Marketing", "Content generation, campaigns"),
        HR("HR", "Recrutamento, onboarding");

        private final String displayName;
        private final String description;

        TemplateCategory(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
    }

    // Getters, setters
}
```

```java
package org.archflow.template.model;

import java.util.List;

/**
 * Parâmetro configurável de um template
 */
@Entity
@Table(name = "af_template_parameters")
public class TemplateParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private TemplateMetadata template;

    /**
     * Nome do parâmetro
     */
    private String name;

    /**
     * Label para exibição
     */
    private String label;

    /**
     * Descrição/help text
     */
    private String description;

    /**
     * Tipo do parâmetro
     */
    @Enumerated(EnumType.STRING)
    private ParameterType type;

    /**
     * Valor padrão
     */
    private String defaultValue;

    /**
     * Valores permitidos (para select/multi-select)
     */
    @ElementCollection
    @CollectionTable(name = "af_template_param_options", joinColumns = @JoinColumn(name = "parameter_id"))
    @Column(name = "option_value")
    private List<String> options;

    /**
     * Se obrigatório
     */
    private Boolean required = false;

    /**
     * Validação (regex, min, max, etc.)
     */
    private String validation;

    /**
     * Placeholder para exibição
     */
    private String placeholder;

    public enum ParameterType {
        TEXT,           // Texto simples
        TEXTAREA,       // Texto longo
        NUMBER,         // Numérico
        BOOLEAN,        // Checkbox
        SELECT,         // Dropdown
        MULTI_SELECT,   // Múltipla seleção
        PASSWORD,       // Senha
        API_KEY,        // API key
        URL,            // URL
        JSON,           // JSON editor
        CODE,           // Code editor
        FILE,           // Upload de arquivo
        MODEL_SELECTOR, // Seletor de modelo LLM
        TOOL_SELECTOR   // Seletor de tools
    }

    // Getters, setters
}
```

### 13.2 Template Definition (YAML)

```yaml
# template.yaml
name: customer-support-rag
displayName: "Customer Support with RAG"
description: "Complete customer support workflow with knowledge base retrieval"
category: CUSTOMER_SERVICE
version: "1.0.0"
minArchflowVersion: "2.0.0"
author: "archflow"
verified: true

tags:
  - support
  - rag
  - customer-service
  - knowledge-base

parameters:
  - name: knowledgeBaseId
    label: "Knowledge Base ID"
    description: "Select the knowledge base to use for retrieval"
    type: KNOWLEDGE_BASE_SELECTOR
    required: true

  - name: llmModel
    label: "LLM Model"
    description: "Select the language model to use"
    type: MODEL_SELECTOR
    defaultValue: "openai/gpt-4o"
    required: true

  - name: temperature
    label: "Temperature"
    description: "Temperature for response generation"
    type: NUMBER
    defaultValue: "0.7"
    validation: "gte=0,lte=2"

  - name: maxHistory
    label: "Max Conversation History"
    description: "Maximum number of messages to keep in context"
    type: NUMBER
    defaultValue: "10"
    validation: "gte=1,lte=50"

  - name: enableEscalation
    label: "Enable Human Escalation"
    description: "Allow escalation to human agent"
    type: BOOLEAN
    defaultValue: "true"

  - name: escalationEmail
    label: "Escalation Email"
    description: "Email to notify when escalation is triggered"
    type: EMAIL
    requiredWhen: "enableEscalation=true"

dependencies:
  - tool: vector-store
  - tool: document-loader
  - minVersion: "2.0.0"

# Workflow definition (pode referenciar arquivo externo)
workflow: workflow.yaml

# Example configuration
examples:
  - name: "E-commerce Support"
    description: "Example configuration for e-commerce customer support"
    config:
      knowledgeBaseId: "kb-ecommerce-001"
      llmModel: "anthropic/claude-3-5-sonnet"
      temperature: 0.5
      enableEscalation: true
```

### 13.3 Template Engine

```java
package org.archflow.template.engine;

import org.archflow.template.model.*;
import org.archflow.template.repository.TemplateRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TemplateEngine {

    private final TemplateRepository templateRepository;
    private final WorkflowInstaller workflowInstaller;

    /**
     * Instala template a partir dos metadados
     */
    public InstalledWorkflow install(String templateId, Map<String, Object> parameters) {
        TemplateMetadata template = templateRepository.findById(templateId)
            .orElseThrow(() -> new TemplateNotFoundException(templateId));

        // Valida parâmetros
        validateParameters(template, parameters);

        // Resolve parâmetros com defaults
        Map<String, Object> resolvedParams = resolveParameters(template, parameters);

        // Carrega workflow do template
        WorkflowDefinition workflow = loadWorkflow(template);

        // Substitui variáveis
        WorkflowDefinition instantiated = instantiate(workflow, resolvedParams);

        // Instala workflow
        String workflowId = workflowInstaller.install(instantiated);

        // Atualiza estatísticas
        template.setInstallCount(template.getInstallCount() + 1);
        templateRepository.save(template);

        return InstalledWorkflow.builder()
            .workflowId(workflowId)
            .templateId(templateId)
            .templateVersion(template.getVersion())
            .parameters(resolvedParams)
            .build();
    }

    /**
     * Valida parâmetros de entrada
     */
    private void validateParameters(TemplateMetadata template, Map<String, Object> parameters) {
        for (TemplateParameter param : template.getParameters()) {
            Object value = parameters.get(param.getName());

            // Required check
            if (param.getRequired() && value == null) {
                throw new TemplateValidationException(
                    "Required parameter missing: " + param.getName()
                );
            }

            // Type validation
            if (value != null) {
                validateType(param, value);
                validateConstraints(param, value);
            }
        }
    }

    private void validateType(TemplateParameter param, Object value) {
        switch (param.getType()) {
            case NUMBER -> {
                if (!(value instanceof Number)) {
                    throw new TemplateValidationException(
                        "Parameter " + param.getName() + " must be a number"
                    );
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw new TemplateValidationException(
                        "Parameter " + param.getName() + " must be a boolean"
                    );
                }
            }
            // ... other types
        }
    }

    private void validateConstraints(TemplateParameter param, Object value) {
        if (param.getValidation() != null) {
            ValidationRules rules = ValidationRules.parse(param.getValidation());
            if (!rules.validate(value)) {
                throw new TemplateValidationException(
                    "Parameter " + param.getName() + " failed validation: " + param.getValidation()
                );
            }
        }
    }

    /**
     * Resolve parâmetros com defaults
     */
    private Map<String, Object> resolveParameters(TemplateMetadata template,
                                                    Map<String, Object> input) {
        return template.getParameters().stream()
            .collect(Collectors.toMap(
                TemplateParameter::getName,
                param -> input.getOrDefault(param.getName(), param.getDefaultValue())
            ));
    }

    /**
     * Substitui variáveis no workflow
     */
    private WorkflowDefinition instantiate(WorkflowDefinition workflow,
                                           Map<String, Object> parameters) {
        String yaml = workflow.toYaml();

        // Substitui ${param.name} pelo valor
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = String.valueOf(entry.getValue());
            yaml = yaml.replace(placeholder, value);
        }

        return WorkflowDefinition.fromYaml(yaml);
    }

    /**
     * Preview do workflow sem instalar
     */
    public WorkflowDefinition preview(String templateId, Map<String, Object> parameters) {
        TemplateMetadata template = templateRepository.findById(templateId)
            .orElseThrow(() -> new TemplateNotFoundException(templateId));

        Map<String, Object> resolvedParams = resolveParameters(template, parameters);
        WorkflowDefinition workflow = loadWorkflow(template);
        return instantiate(workflow, resolvedParams);
    }
}
```

### 13.4 Template Registry

```java
package org.archflow.template.registry;

import org.archflow.template.model.TemplateMetadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de templates built-in
 */
@Component
public class BuiltinTemplateRegistry {

    private final Map<String, TemplateMetadata> templates = new ConcurrentHashMap<>();

    public BuiltinTemplateRegistry() {
        registerCustomerSupportTemplate();
        registerDocumentProcessingTemplate();
        registerKnowledgeBaseTemplate();
        registerAgentSupervisorTemplate();
    }

    private void registerCustomerSupportTemplate() {
        TemplateMetadata template = new TemplateMetadata();
        template.setName("customer-support-rag");
        template.setDisplayName("Customer Support with RAG");
        template.setDescription("Complete customer support workflow with knowledge base");
        template.setCategory(TemplateMetadata.TemplateCategory.CUSTOMER_SERVICE);
        template.setVersion("1.0.0");
        template.setBuiltIn(true);
        template.setVerified(true);
        template.setAuthor("archflow");

        templates.put(template.getName(), template);
    }

    private void registerDocumentProcessingTemplate() {
        TemplateMetadata template = new TemplateMetadata();
        template.setName("document-processor");
        template.setDisplayName("Document Processor");
        template.setDescription("Extract structured data from documents using OCR and LLM");
        template.setCategory(TemplateMetadata.TemplateCategory.DOCUMENT_PROCESSING);
        template.setVersion("1.0.0");
        template.setBuiltIn(true);
        template.setVerified(true);
        template.setAuthor("archflow");

        templates.put(template.getName(), template);
    }

    private void registerKnowledgeBaseTemplate() {
        TemplateMetadata template = new TemplateMetadata();
        template.setName("knowledge-base-rag");
        template.setDisplayName("Knowledge Base RAG");
        template.setDescription("Build a searchable knowledge base with RAG");
        template.setCategory(TemplateMetadata.TemplateCategory.KNOWLEDGE_BASE);
        template.setVersion("1.0.0");
        template.setBuiltIn(true);
        template.setVerified(true);
        template.setAuthor("archflow");

        templates.put(template.getName(), template);
    }

    private void registerAgentSupervisorTemplate() {
        TemplateMetadata template = new TemplateMetadata();
        template.setName("agent-supervisor");
        template.setDisplayName("Agent Supervisor");
        template.setDescription("Multi-agent system with supervisor pattern");
        template.setCategory(TemplateMetadata.TemplateCategory.AGENTIC);
        template.setVersion("1.0.0");
        template.setBuiltIn(true);
        template.setVerified(true);
        template.setAuthor("archflow");

        templates.put(template.getName(), template);
    }

    public List<TemplateMetadata> getAllTemplates() {
        return List.copyOf(templates.values());
    }

    public TemplateMetadata getTemplate(String name) {
        return templates.get(name);
    }
}
```

### 13.5 Template Examples

#### Customer Support RAG Template

```yaml
# templates/customer-support/workflow.yaml
workflow:
  id: ${workflowId}
  name: ${name}
  description: "Customer support with RAG knowledge base"

  nodes:
    - id: input
      type: InputNode
      config:
        schema:
          type: object
          properties:
            message:
              type: string
            conversationId:
              type: string

    - id: retrieve
      type: VectorSearchNode
      config:
        knowledgeBaseId: ${knowledgeBaseId}
        topK: 5
        threshold: 0.7

    - id: augment
      type: PromptTemplateNode
      config:
        template: |
          You are a customer support agent. Use the following context to answer:

          Context:
          {{context}}

          User message: {{message}}

          Conversation history:
          {{history}}

          Provide a helpful, friendly response.

    - id: llm
      type: LLMNode
      config:
        model: ${llmModel}
        temperature: ${temperature}
        maxTokens: 500

    - id: checkEscalation
      type: ConditionNode
      config:
        condition: |
          {{output}} includes "escalate" OR {{output}} includes "human agent"

    - id: escalate
      type: EmailNode
      config:
        to: ${escalationEmail}
        subject: "Support Escalation Required"
        enabledWhen: ${enableEscalation}

    - id: output
      type: OutputNode
      config:
        schema:
          response: string
          escalated: boolean

  edges:
    - from: input
      to: retrieve
    - from: retrieve
      to: augment
    - from: augment
      to: llm
    - from: llm
      to: checkEscalation
    - from: checkEscalation
      to: escalate
      condition: true
    - from: checkEscalation
      to: output
      condition: false
```

#### Document Processor Template

```yaml
# templates/document-processor/workflow.yaml
workflow:
  id: ${workflowId}
  name: ${name}
  description: "Extract structured data from documents"

  nodes:
    - id: input
      type: InputNode
      config:
        schema:
          documentUrl: string
          extractionSchema: object

    - id: load
      type: DocumentLoaderNode
      config:
        supportedFormats:
          - pdf
          - docx
          - txt
          - png
          - jpg

    - id: ocr
      type: OCRNode
      config:
        engine: tesseract
        language: por+eng

    - id: extract
      type: LLMNode
      config:
        model: ${llmModel}
        system: |
          You are a document extraction expert. Extract structured data
          from the document following the provided schema. Return ONLY valid JSON.

    - id: validate
      type: SchemaValidationNode
      config:
        schemaRef: ${extractionSchema}

    - id: output
      type: OutputNode
      config:
        schema:
          extractedData: object
          confidence: number
          errors: array

  edges:
    - from: input
      to: load
    - from: load
      to: ocr
    - from: ocr
      to: extract
    - from: extract
      to: validate
    - from: validate
      to: output
```

---

## Sprint 14: Suspend/Resume

### Objetivo
Implementar mecanismo de suspensão de conversas para input humano.

### Arquitetura Suspend/Resume

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      Suspend/Resume Flow                                │
│                                                                          │
│  1. USER MESSAGE                                                        │
│     │                                                                    │
│     ▼                                                                    │
│  ┌────────────────┐                                                     │
│  │   LLM Call     │                                                     │
│  └────────┬───────┘                                                     │
│           │                                                             │
│           ▼                                                             │
│  ┌────────────────┐     ┌──────────────────────────────────────┐      │
│  │  Tool Call     │────▶│ Tool requires user input?             │      │
│  └────────────────┘     │  (e.g., file upload, confirmation)    │      │
│                         └──────────────┬───────────────────────┘      │
│                                        │ YES                           │
│                                        ▼                               │
│                         ┌──────────────────────────────────────┐      │
│                         │  SUSPEND Conversation                │      │
│                         │  - Generate resumeToken              │      │
│                         │  - Create FormData                   │      │
│                         │  - State → SUSPENDED                 │      │
│                         └──────────────┬───────────────────────┘      │
│                                        │                               │
│                                        ▼                               │
│                         ┌──────────────────────────────────────┐      │
│                         │  Send Interaction Event              │      │
│                         │  domain: "interaction"               │      │
│                         │  type: "form"                        │      │
│                         │  data: {formId, fields}              │      │
│                         └──────────────┬───────────────────────┘      │
│                                        │                               │
│  ┌─────────────────────────────────────┼───────────────────────┐      │
│  │                                     │                       │      │
│  ▼                                     ▼                       ▼      │
│ ┌────────┐                        ┌─────────┐            ┌─────────┐  │
│ │  WAIT  │                        │ USER    │            │ TIMEOUT │  │
│ │        │                        │ FILLS   │            │         │  │
│ │        │                        │ FORM    │            │         │  │
│ └────┬───┘                        └────┬────┘            └────┬────┘  │
│      │                                  │                      │       │
│      └──────────────────────────────────┴──────────────────────┘       │
│                                        │                               │
│                                        ▼                               │
│                         ┌──────────────────────────────────────┐      │
│                         │  RESUME Conversation                │      │
│                         │  - Validate resumeToken             │      │
│                         │  - Restore state                    │      │
│                         │  - Continue from suspended point    │      │
│                         └──────────────┬───────────────────────┘      │
│                                        │                               │
│                                        ▼                               │
│                         ┌──────────────────────────────────────┐      │
│                         │   Continue Workflow                  │      │
│                         └──────────────────────────────────────┘      │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 14.1 Conversation State

```java
package org.archflow.conversation.model;

import java.time.Instant;
import java.util.Map;

/**
 * Estado de uma conversa
 */
@Entity
@Table(name = "af_conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String workflowId;

    @Column(nullable = false)
    private String userId;

    /**
     * Estado da conversa
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationState state = ConversationState.ACTIVE;

    /**
     * Token para resumir conversa suspensa
     */
    @Column(unique = true)
    private String resumeToken;

    /**
     * Dados do formulário quando suspenso
     */
    @Lob
    private String formDataJson;

    /**
     * Estado de execução serializado
     */
    @Lob
    private byte[] executionState;

    /**
     * Timestamps
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "resumed_at")
    private Instant resumedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Última interação
     */
    @Column(name = "last_interaction_at")
    private Instant lastInteractionAt;

    /**
     * Histórico de mensagens
     */
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL)
    private List<ConversationMessage> messages;

    /**
     * Metadados da conversa
     */
    @ElementCollection
    @MapKeyColumn("name")
    @Column(name = "value")
    @CollectionTable(name = "af_conversation_metadata", joinColumns = @JoinColumn(name = "conversation_id"))
    private Map<String, String> metadata;

    public enum ConversationState {
        ACTIVE,      // Em andamento
        SUSPENDED,   // Aguardando input do usuário
        COMPLETED,   // Finalizada
        FAILED,      // Falhou
        TIMED_OUT    // Timeout
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public void suspend(String resumeToken, FormData formData, byte[] executionState) {
        this.state = ConversationState.SUSPENDED;
        this.resumeToken = resumeToken;
        this.formDataJson = formData.toJson();
        this.executionState = executionState;
        this.suspendedAt = Instant.now();
        // Default: expira em 24 horas
        this.expiresAt = Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS);
    }

    public void resume() {
        if (this.state != ConversationState.SUSPENDED) {
            throw new IllegalStateException("Cannot resume conversation in state: " + this.state);
        }
        if (isExpired()) {
            throw new IllegalStateException("Conversation has expired");
        }
        this.state = ConversationState.ACTIVE;
        this.resumedAt = Instant.now();
        this.lastInteractionAt = Instant.now();
    }

    // Getters, setters
}
```

```java
package org.archflow.conversation.model;

import java.time.Instant;

/**
 * Mensagem na conversa
 */
@Entity
@Table(name = "af_conversation_messages")
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /**
     * Remetente da mensagem
     */
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    /**
     * Conteúdo da mensagem
     */
    @Lob
    private String content;

    /**
     * Metadados da mensagem
     */
    @Lob
    private String metadataJson;

    /**
     * Timestamp
     */
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    /**
     * Tool calls associados
     */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL)
    private List<ToolCall> toolCalls;

    public enum MessageRole {
        USER,       // Mensagem do usuário
        ASSISTANT,  // Resposta do assistente
        SYSTEM,     // Mensagem do sistema
        TOOL        // Resultado de tool
    }

    // Getters, setters
}
```

### 14.2 Form Definition

```java
package org.archflow.interaction.model;

import java.util.List;

/**
 * Formulário para interação com usuário
 */
public class FormData {

    private String formId;
    private String title;
    private String description;
    private List<FormField> fields;
    private FormSubmitAction submitAction;

    /**
     * Cria novo formulário
     */
    public static FormDataBuilder builder() {
        return new FormDataBuilder();
    }

    public static class FormDataBuilder {
        private final FormData form = new FormData();

        public FormDataBuilder id(String id) {
            form.formId = id;
            return this;
        }

        public FormDataBuilder title(String title) {
            form.title = title;
            return this;
        }

        public FormDataBuilder description(String description) {
            form.description = description;
            return this;
        }

        public FormDataBuilder field(FormField field) {
            if (form.fields == null) {
                form.fields = new ArrayList<>();
            }
            form.fields.add(field);
            return this;
        }

        public FormDataBuilder submitAction(FormSubmitAction action) {
            form.submitAction = action;
            return this;
        }

        public FormData build() {
            return form;
        }
    }

    public String toJson() {
        // Serializa para JSON
        return "{}"; // Simplificado
    }
}
```

```java
package org.archflow.interaction.model;

import java.util.List;

/**
 * Campo de formulário
 */
public class FormField {

    private String name;
    private String label;
    private String description;
    private FieldType type;
    private boolean required = false;
    private Object defaultValue;
    private List<String> options; // Para select/radio
    private String placeholder;
    private ValidationRule validation;

    public enum FieldType {
        TEXT,
        TEXTAREA,
        NUMBER,
        EMAIL,
        PASSWORD,
        DATE,
        TIME,
        DATETIME,
        SELECT,
        MULTI_SELECT,
        CHECKBOX,
        RADIO,
        FILE_UPLOAD,
        SLIDER,
        TOGGLE
    }

    public static FormField text(String name) {
        FormField field = new FormField();
        field.name = name;
        field.type = FieldType.TEXT;
        return field;
    }

    public static FormField number(String name) {
        FormField field = new FormField();
        field.name = name;
        field.type = FieldType.NUMBER;
        return field;
    }

    public static FormField select(String name, List<String> options) {
        FormField field = new FormField();
        field.name = name;
        field.type = FieldType.SELECT;
        field.options = options;
        return field;
    }

    public FormField label(String label) {
        this.label = label;
        return this;
    }

    public FormField required(boolean required) {
        this.required = required;
        return this;
    }

    public FormField placeholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }
}
```

### 14.3 Suspend/Resume Service

```java
package org.archflow.conversation.service;

import org.archflow.conversation.model.*;
import org.archflow.conversation.repository.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ExecutionStateManager stateManager;

    /**
     * Suspende conversa aguardando input do usuário
     */
    public SuspendResult suspend(String conversationId, FormData formData,
                                  byte[] executionState) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        // Gera token único para resume
        String resumeToken = generateResumeToken();

        // Suspende conversa
        conversation.suspend(resumeToken, formData, executionState);
        conversationRepository.save(conversation);

        return new SuspendResult(
            conversationId,
            resumeToken,
            formData,
            conversation.getExpiresAt()
        );
    }

    /**
     * Retoma conversa suspensa
     */
    public ResumeResult resume(String resumeToken, FormSubmission submission) {
        Conversation conversation = conversationRepository.findByResumeToken(resumeToken)
            .orElseThrow(() -> new InvalidResumeTokenException());

        // Valida expiração
        if (conversation.isExpired()) {
            throw new ConversationExpiredException();
        }

        // Valida formulário
        validateSubmission(conversation, submission);

        // Resume conversa
        conversation.resume();
        conversationRepository.save(conversation);

        // Restaura estado de execução
        byte[] executionState = conversation.getExecutionState();

        return new ResumeResult(
            conversation.getId(),
            executionState,
            submission.getData()
        );
    }

    /**
     * Processa mensagem com suporte a suspend/resume
     */
    public ConversationResponse processMessage(String conversationId, String message) {
        Conversation conversation = getOrCreateConversation(conversationId);

        // Adiciona mensagem do usuário
        addMessage(conversation, MessageRole.USER, message);

        // Executa workflow
        try {
            return executeWorkflow(conversation);

        } catch (SuspendException e) {
            // Workflow solicitou suspensão
            return handleSuspension(conversation, e.getFormData(), e.getExecutionState());

        } catch (Exception e) {
            conversation.setState(ConversationState.FAILED);
            conversationRepository.save(conversation);
            throw e;
        }
    }

    private ConversationResponse executeWorkflow(Conversation conversation) {
        // Workflow executa normalmente
        // Se precisar de input, lança SuspendException
        return null;
    }

    private ConversationResponse handleSuspension(Conversation conversation,
                                                     FormData formData,
                                                     byte[] executionState) {
        SuspendResult suspendResult = suspend(
            conversation.getId(),
            formData,
            executionState
        );

        return ConversationResponse.suspended(formData, suspendResult.resumeToken());
    }

    private String generateResumeToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void validateSubmission(Conversation conversation, FormSubmission submission) {
        // Valida campos obrigatórios
        // Valida tipos
        // Valida regras customizadas
    }

    public record SuspendResult(
        String conversationId,
        String resumeToken,
        FormData form,
        Instant expiresAt
    ) {}

    public record ResumeResult(
        String conversationId,
        byte[] executionState,
        Map<String, Object> data
    ) {}
}
```

### 14.4 SSE Events para Suspend/Resume

```typescript
// Interaction domain events

interface SuspendEvent {
  envelope: {
    domain: "interaction";
    type: "suspend";
    id: string;
    timestamp: number;
  };
  data: {
    conversationId: string;
    resumeToken: string;
    form: {
      id: string;
      title: string;
      description?: string;
      fields: FormField[];
    };
    expiresAt: number;
  };
}

interface ResumeEvent {
  envelope: {
    domain: "interaction";
    type: "resume";
    id: string;
    timestamp: number;
  };
  data: {
    conversationId: string;
    submittedData: Record<string, unknown>;
  };
}

// Web Component handling
class ArchflowChat extends HTMLElement {
  private handleInteractionEvent(event: ArchflowEvent) {
    if (event.envelope.domain === "interaction") {
      switch (event.envelope.type) {
        case "suspend":
          this.showForm(event.data);
          break;
        case "resume":
          this.hideForm();
          break;
      }
    }
  }

  private showForm(data: SuspendEvent["data"]) {
    const formContainer = this.shadowRoot.querySelector("#form-container");
    formContainer.innerHTML = `
      <div class="suspend-form">
        <h3>${data.form.title}</h3>
        ${data.form.description ? `<p>${data.form.description}</p>` : ""}

        <form data-resume-token="${data.resumeToken}">
          ${data.form.fields.map(field => this.renderField(field)).join("")}

          <button type="submit">Continue</button>
          <button type="button" class="cancel">Cancel</button>
        </form>

        <p class="expires">
          Expires: ${new Date(data.expiresAt).toLocaleString()}
        </p>
      </div>
    `;

    formContainer.querySelector("form").addEventListener("submit", (e) => {
      e.preventDefault();
      this.submitForm(data.resumeToken, new FormData(e.target));
    });
  }

  private renderField(field: FormField): string {
    switch (field.type) {
      case "text":
        return `
          <label>${field.label}</label>
          <input type="text" name="${field.name}"
            ${field.required ? "required" : ""}
            placeholder="${field.placeholder || ""}">
        `;
      case "textarea":
        return `
          <label>${field.label}</label>
          <textarea name="${field.name}"
            placeholder="${field.placeholder || ""}"></textarea>
        `;
      case "select":
        return `
          <label>${field.label}</label>
          <select name="${field.name}" ${field.required ? "required" : ""}>
            <option value="">Select...</option>
            ${field.options.map(opt => `<option value="${opt}">${opt}</option>`).join("")}
          </select>
        `;
      default:
        return "";
    }
  }

  private async submitForm(resumeToken: string, formData: FormData) {
    const data = Object.fromEntries(formData.entries());

    await fetch(`${this.apiBase}/api/conversations/resume`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ resumeToken, data })
    });

    // Continue stream...
  }
}
```

### 14.5 Example: File Upload Suspend

```java
/**
 * Tool que solicita upload de arquivo
 */
public class FileUploadTool implements Tool {

    @Override
    public ToolResult execute(ToolInput input) {
        throw SuspendException.builder()
            .reason("File upload required")
            .form(FormData.builder()
                .id("file-upload")
                .title("Upload Document")
                .description("Please upload the document to process")
                .field(FormField.builder()
                    .name("file")
                    .label("Document")
                    .type(FormField.Type.FILE_UPLOAD)
                    .required(true)
                    .accept("application/pdf,image/*")
                    .build())
                .field(FormField.builder()
                    .name("description")
                    .label("Description")
                    .type(FormField.Type.TEXT)
                    .build())
                .build())
            .build());
    }

    @Override
    public String getName() {
        return "file_upload";
    }

    @Override
    public String getDescription() {
        return "Request user to upload a file";
    }
}
```

---

## Sprint 15: Extension Marketplace

### Objetivo
Criar marketplace de extensões com segurança e validação.

### Arquitetura do Marketplace

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Extension Marketplace                              │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                     Marketplace Repository                      │    │
│  │                                                                  │    │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐  │    │
│  │  │   Official │  │ Community  │  │   Verified│  │  Enterprise│  │    │
│  │  │ Extensions │  │ Extensions │  │ Extensions│  │ Extensions│  │    │
│  │  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘  │    │
│  └────────┼───────────────┼───────────────┼───────────────┼────────┘    │
│           │               │               │               │             │
│           ▼               ▼               ▼               ▼             │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                    Extension Index                             │    │
│  │   - Search, Filter, Sort                                       │    │
│  │   - Categories, Tags                                           │    │
│  │   - Ratings, Reviews                                           │    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                   │                                     │
│                                   ▼                                     │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                  Extension Installer                           │    │
│  │   - Manifest Validation                                        │    │
│  │   - Signature Verification                                     │    │
│  │   - Permission Check                                           │    │
│  │   - Dependency Resolution                                      │    │
│  └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 15.1 Extension Manifest

```typescript
// Extension manifest schema

interface ExtensionManifest {
  // Identificação
  id: string;                    // ex: "archflow-pinecone"
  name: string;                  // ex: "Pinecone Vector Store"
  version: string;               // ex: "1.0.0"
  description: string;
  author: string;
  license: string;

  // Compatibilidade
  archflowVersion: string;       // ex: ">=2.0.0,<3.0.0"
  langchain4jVersion?: string;   // ex: ">=1.10.0"

  // Tipo de extensão
  type: ExtensionType;

  // Permissões requeridas
  permissions: ExtensionPermission[];

  // Dependências
  dependencies?: ExtensionDependency[];

  // Arquivos
  files: ExtensionFiles;

  // Assinatura digital (para verificação)
  signature?: string;

  // Checksum
  checksum: {
    algorithm: "sha256" | "sha512";
    value: string;
  };

  // Homepage, docs, repository
  links?: {
    homepage?: string;
    documentation?: string;
    repository?: string;
    issues?: string;
  };

  // Keywords para busca
  keywords?: string[];

  // Categorias
  category: ExtensionCategory[];

  // Icone (emoji ou URL)
  icon?: string;
}

type ExtensionType =
  | "tool"              // Tool customizada
  | "vector-store"      // Vector store
  | "llm-provider"      // Provider LLM
  | "embedding-model"   // Model de embedding
  | "memory-type"       // Tipo de memória
  | "node-type"         // Node customizado para designer
  | "template"          // Template de workflow
  | "middleware"        // Middleware/interceptor
  | "ui-theme"          // Tema para UI
  | "integration";      // Integração externa

type ExtensionPermission =
  | "network:read"      // Acesso de rede
  | "network:write"     // Upload/download
  | "file:read"         // Leitura de arquivos
  | "file:write"        // Escrita de arquivos
  | "database:read"     // Acesso a DB
  | "database:write"
  | "secrets:read"      // Acesso a secrets
  | "secrets:write"
  | "workflow:execute"  // Executar workflows
  | "workflow:modify";  // Modificar workflows

type ExtensionCategory =
  | "database"
  | "cloud"
  | "ai"
  | "integration"
  | "monitoring"
  | "security"
  | "productivity"
  | "developer-tools";

interface ExtensionDependency {
  type: "maven" | "npm" | "python" | "extension";
  id: string;
  version?: string;
}

interface ExtensionFiles {
  // Backend (Java/Maven)
  backend?: {
    groupId: string;
    artifactId: string;
    version: string;
    jarUrl: string;
    sourcesUrl?: string;
  };

  // Frontend (JS/TS)
  frontend?: {
    packageName: string;
    version: string;
    tarballUrl: string;
  };

  // Recursos
  resources?: {
    icon?: string;
    screenshots?: string[];
    readme?: string;
  };
}
```

### 15.2 Extension Registry

```java
package org.archflow.extension.model;

import java.time.Instant;
import java.util.List;

/**
 * Metadados de uma extensão no marketplace
 */
@Entity
@Table(name = "af_extensions")
public class Extension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String extensionId;  // ex: "archflow-pinecone"

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    private String author;

    @Column(nullable = false)
    private String version;

    private String license;

    /**
     * Tipo de extensão
     */
    @Enumerated(EnumType.STRING)
    private ExtensionType type;

    /**
     * Categorias
     */
    @ElementCollection
    @CollectionTable(name = "af_extension_categories", joinColumns = @JoinColumn(name = "extension_id"))
    @Column(name = "category")
    private List<ExtensionCategory> categories;

    /**
     * Palavras-chave
     */
    @ElementCollection
    @CollectionTable(name = "af_extension_keywords", joinColumns = @JoinColumn(name = "extension_id"))
    @Column(name = "keyword")
    private List<String> keywords;

    /**
     * Permissões
     */
    @ElementCollection
    @CollectionTable(name = "af_extension_permissions", joinColumns = @JoinColumn(name = "extension_id"))
    @Column(name = "permission")
    private List<String> permissions;

    /**
     * Versão do archflow compatível
     */
    private String archflowVersion;

    /**
     * Official, Verified, Community
     */
    @Enumerated(EnumType.STRING)
    private ExtensionStatus status = ExtensionStatus.COMMUNITY;

    /**
     * Oficial da equipe archflow?
     */
    private Boolean official = false;

    /**
     * Verificado pela equipe?
     */
    private Boolean verified = false;

    /**
     * Estatísticas
     */
    private Long downloads = 0L;
    private Double rating = 0.0;
    private Integer ratingCount = 0;
    private Integer starCount = 0;

    /**
     * Links
     */
    private String homepageUrl;
    private String documentationUrl;
    private String repositoryUrl;
    private String issuesUrl;

    /**
     * Icone
     */
    private String iconUrl;

    /**
     * Checksum para validação
     */
    private String checksumAlgorithm;
    private String checksumValue;

    /**
     * Assinatura digital
     */
    private String signature;

    /**
     * Timestamps
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Dependencies (JSON)
     */
    @Lob
    private String dependenciesJson;

    /**
     * Manifest completo (JSON)
     */
    @Lob
    private String manifestJson;

    /**
     * Arquivos para download
     */
    @Lob
    private String filesJson;

    public enum ExtensionType {
        TOOL, VECTOR_STORE, LLM_PROVIDER, EMBEDDING_MODEL,
        MEMORY_TYPE, NODE_TYPE, TEMPLATE, MIDDLEWARE,
        UI_THEME, INTEGRATION
    }

    public enum ExtensionCategory {
        DATABASE, CLOUD, AI, INTEGRATION, MONITORING,
        SECURITY, PRODUCTIVITY, DEVELOPER_TOOLS
    }

    public enum ExtensionStatus {
        OFFICIAL,    // Mantido pela equipe archflow
        VERIFIED,    // Verificado pela equipe
        COMMUNITY,   // Comunitário
        DEPRECATED   // Deprecado
    }

    // Getters, setters
}
```

### 15.3 Extension Installer

```java
package org.archflow.extension.service;

import org.archflow.extension.model.*;
import org.archflow.extension.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtensionInstaller {

    private final ExtensionRepository extensionRepository;
    private final ExtensionSignatureValidator signatureValidator;
    private final PermissionValidator permissionValidator;
    private final DependencyResolver dependencyResolver;

    /**
     * Instala extensão
     */
    @Transactional
    public InstalledExtension install(String extensionId, String version) {
        Extension extension = extensionRepository.findByExtensionIdAndVersion(extensionId, version)
            .orElseThrow(() -> new ExtensionNotFoundException(extensionId, version));

        // Valida assinatura
        if (!signatureValidator.validate(extension)) {
            throw new ExtensionSignatureInvalidException(extensionId);
        }

        // Valida permissões
        PermissionValidationResult permResult = permissionValidator.validate(extension);
        if (!permResult.allowed()) {
            throw new ExtensionPermissionDeniedException(permResult.reason());
        }

        // Resolve dependências
        List<ResolvedDependency> dependencies = dependencyResolver.resolve(extension);

        // Download arquivos
        ExtensionArtifacts artifacts = downloadArtifacts(extension);

        // Instala dependências primeiro
        for (ResolvedDependency dep : dependencies) {
            if (dep.needsInstallation()) {
                install(dep.extensionId(), dep.version());
            }
        }

        // Instala extensão
        installBackend(artifacts.backend());
        installFrontend(artifacts.frontend());

        // Registra instalação
        ExtensionInstallation installation = new ExtensionInstallation();
        installation.setExtensionId(extension.getId());
        installation.setVersion(version);
        installation.setInstalledAt(Instant.now());
        extensionInstallationRepository.save(installation);

        // Incrementa contador de downloads
        extension.setDownloads(extension.getDownloads() + 1);
        extensionRepository.save(extension);

        return new InstalledExtension(extension, artifacts);
    }

    /**
     * Desinstala extensão
     */
    @Transactional
    public void uninstall(String extensionId) {
        ExtensionInstallation installation = extensionInstallationRepository
            .findByExtensionId(extensionId)
            .orElseThrow(() -> new ExtensionNotInstalledException(extensionId));

        // Verifica se há outras extensões dependentes
        List<Extension> dependents = extensionRepository.findDependents(extensionId);
        if (!dependents.isEmpty()) {
            throw new ExtensionHasDependentsException(
                "Cannot uninstall: " + dependents.stream()
                    .map(Extension::getName)
                    .toList()
            );
        }

        // Remove artefatos
        uninstallBackend(extensionId);
        uninstallFrontend(extensionId);

        // Remove registro
        extensionInstallationRepository.delete(installation);
    }

    /**
     * Atualiza extensão
     */
    @Transactional
    public InstalledExtension update(String extensionId, String toVersion) {
        // Desinstala versão atual
        uninstall(extensionId);
        // Instala nova versão
        return install(extensionId, toVersion);
    }

    /**
     * Lista extensões instaladas
     */
    public List<InstalledExtensionInfo> listInstalled() {
        return extensionInstallationRepository.findAll().stream()
            .map(inst -> {
                Extension ext = extensionRepository.findById(inst.getExtensionId()).orElse(null);
                return new InstalledExtensionInfo(ext, inst);
            })
            .toList();
    }

    /**
     * Busca extensões no marketplace
     */
    public List<Extension> search(String query, ExtensionCategory category,
                                   ExtensionStatus status, int page, int size) {
        return extensionRepository.search(query, category, status, page, size);
    }

    private ExtensionArtifacts downloadArtifacts(Extension extension) {
        // Download dos arquivos JAR, JS, etc.
        return null;
    }

    private void installBackend(BackendArtifact artifact) {
        // Adiciona JAR ao classpath
        // Registra beans
    }

    private void installFrontend(FrontendArtifact artifact) {
        // Instala pacote npm
        // Registra Web Components
    }

    private void uninstallBackend(String extensionId) {
        // Remove do classpath
    }

    private void uninstallFrontend(String extensionId) {
        // Desinstala pacote npm
    }

    public record InstalledExtension(Extension extension, ExtensionArtifacts artifacts) {}
}
```

### 15.4 Extension API

```java
package org.archflow.api.controller;

import org.archflow.extension.service.ExtensionInstaller;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/extensions")
public class ExtensionController {

    private final ExtensionInstaller installer;

    /**
     * Busca extensões
     */
    @GetMapping("/marketplace")
    public Page<Extension> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ExtensionCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return installer.search(q, category, null, page, size);
    }

    /**
     * Detalhes da extensão
     */
    @GetMapping("/marketplace/{extensionId}")
    public ExtensionDetails getDetails(@PathVariable String extensionId) {
        return installer.getDetails(extensionId);
    }

    /**
     * Instala extensão
     */
    @PostMapping("/install")
    @RequiresPermission("extension:install")
    public InstalledExtensionInfo install(@RequestBody InstallRequest request) {
        return installer.install(request.extensionId(), request.version());
    }

    /**
     * Desinstala extensão
     */
    @DeleteMapping("/{extensionId}")
    @RequiresPermission("extension:uninstall")
    public void uninstall(@PathVariable String extensionId) {
        installer.uninstall(extensionId);
    }

    /**
     * Lista extensões instaladas
     */
    @GetMapping("/installed")
    public List<InstalledExtensionInfo> listInstalled() {
        return installer.listInstalled();
    }

    /**
     * Atualiza extensão
     */
    @PostMapping("/{extensionId}/update")
    @RequiresPermission("extension:update")
    public InstalledExtensionInfo update(
            @PathVariable String extensionId,
            @RequestBody UpdateRequest request) {
        return installer.update(extensionId, request.toVersion());
    }

    public record InstallRequest(String extensionId, String version) {}
    public record UpdateRequest(String toVersion) {}
}
```

---

## Sprint 16: Workflow-as-Tool

### Objetivo
Permitir que workflows sejam invocados como tools, possibilitando composição.

### Arquitetura Workflow-as-Tool

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Workflow-as-Tool Composition                          │
│                                                                          │
│  Parent Workflow                                                         │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │                                                                  │    │
│  │  ┌─────────┐    ┌──────────────────────────────────────────┐   │    │
│  │  │  Input  │───▶│           LLM Agent                      │   │    │
│  │  └─────────┘    │  "Extract customer data from order..."   │   │    │
│  │                └──────────┬─────────────────────────────────┘   │    │
│  │                           │                                      │    │
│  │                           ▼                                      │    │
│  │                ┌────────────────────────────────────┐          │    │
│  │                │   Tool: validate-order-workflow    │◀──────┐   │    │
│  │                │   (Workflow invocado como Tool)    │       │   │    │
│  │                └────────────┬───────────────────────┘       │   │    │
│  │                             │                                │   │    │
│  │                             ▼                                │   │    │
│  │              ┌──────────────────────────────────────┐       │   │    │
│  │              │      Child Workflow (Inline)         │       │   │    │
│  │              │  ┌────────┐  ┌────────┐  ┌────────┐ │       │   │    │
│  │              │  │ Check  │──▶│ Validate│──▶│ Return ││       │   │    │
│  │              │  │ Stock  │  │ Rules  │  │ Result ││       │   │    │
│  │              │  └────────┘  └────────┘  └────────┘ │       │   │    │
│  │              └──────────────┬───────────────────────┘       │   │    │
│  │                             │                                │   │    │
│  │                             ▼                                │   │    │
│  │                ┌────────────────────────────────────┐       │   │    │
│  │                │   Result → Parent continues        │───────┘   │    │
│  │                └────────────────────────────────────┘           │    │
│  │                                                                  │    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  Composição:                                                            │
│  - Workflows podem chamar outros workflows                             │
│  - Input/output mapeados automaticamente                               │
│  - Execução com tracing hierárquico (toolCallId)                       │
│  - Reutilização e abstração progressiva                                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 16.1 Workflow Tool Wrapper

```java
package org.archflow.workflow.tool;

import dev.langchain4j.agent.tool.Tool;
import org.archflow.execution.*;
import org.archflow.model.*;
import org.springframework.stereotype.Component;

/**
 * Wrapper que executa workflow como tool
 */
public class WorkflowTool implements Tool {

    private final String workflowId;
    private final String toolName;
    private final String description;
    private final WorkflowEngine workflowEngine;
    private final ToolSchema inputSchema;

    public WorkflowTool(WorkflowDefinition workflow,
                        WorkflowEngine workflowEngine) {
        this.workflowId = workflow.getId();
        this.toolName = workflow.getName().replaceAll("\\s+", "-").toLowerCase();
        this.description = workflow.getDescription() != null
            ? workflow.getDescription()
            : "Executes workflow: " + workflow.getName();
        this.workflowEngine = workflowEngine;

        // Extrai schema de entrada a partir do input node do workflow
        this.inputSchema = extractInputSchema(workflow);
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ToolSchema getInputSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        // Cria contexto de execução
        ExecutionContext context = ExecutionContext.builder()
            .id(ExecutionId.generate())
            .type(ExecutionType.WORKFLOW)
            .workflowId(workflowId)
            .build();

        // Executa workflow
        WorkflowExecutionResult result = workflowEngine.execute(
            workflowId,
            input.getParameters(),
            context
        );

        // Converte resultado para ToolResult
        if (result.isSuccess()) {
            return ToolResult.success(result.getOutput());
        } else {
            return ToolResult.failure(result.getError());
        }
    }

    /**
     * Extrai schema de entrada do workflow
     */
    private ToolSchema extractInputSchema(WorkflowDefinition workflow) {
        WorkflowNode inputNode = workflow.getNodes().stream()
            .filter(n -> n.getType() == NodeType.INPUT)
            .findFirst()
            .orElseThrow();

        return ToolSchema.builder()
            .name(inputNode.getName())
            .properties(extractProperties(inputNode))
            .required(extractRequiredProperties(inputNode))
            .build();
    }

    private Map<String, PropertySchema> extractProperties(WorkflowNode node) {
        // Extrai propriedades do schema do input node
        return Map.of();
    }

    private List<String> extractRequiredProperties(WorkflowNode node) {
        return List.of();
    }
}
```

### 16.2 Workflow Tool Registry

```java
package org.archflow.workflow.tool;

import org.archflow.workflow.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de workflows expostos como tools
 */
@Service
public class WorkflowToolRegistry {

    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine workflowEngine;

    private final Map<String, WorkflowTool> tools = new ConcurrentHashMap<>();

    /**
     * Registra workflow como tool
     */
    public void registerAsTool(String workflowId) {
        WorkflowDefinition workflow = workflowRepository.findById(workflowId)
            .orElseThrow(() -> new WorkflowNotFoundException(workflowId));

        // Verifica se workflow pode ser usado como tool
        validateAsTool(workflow);

        WorkflowTool tool = new WorkflowTool(workflow, workflowEngine);
        tools.put(tool.getName(), tool);
    }

    /**
     * Remove workflow do registry
     */
    public void unregister(String workflowId) {
        WorkflowDefinition workflow = workflowRepository.findById(workflowId)
            .orElseThrow();

        String toolName = workflow.getName().replaceAll("\\s+", "-").toLowerCase();
        tools.remove(toolName);
    }

    /**
     * Obtém tool por nome
     */
    public WorkflowTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Lista todas as workflow-tools
     */
    public Collection<WorkflowTool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * Sincroniza workflows marcados como "exportable" com o registry
     */
    public void syncExportableWorkflows() {
        List<WorkflowDefinition> exportable = workflowRepository
            .findAllExportable();

        // Limpa registry
        tools.clear();

        // Registra workflows exportable
        for (WorkflowDefinition workflow : exportable) {
            registerAsTool(workflow.getId());
        }
    }

    /**
     * Valida se workflow pode ser usado como tool
     */
    private void validateAsTool(WorkflowDefinition workflow) {
        // Deve ter exatamente um input node
        long inputCount = workflow.getNodes().stream()
            .filter(n -> n.getType() == NodeType.INPUT)
            .count();

        if (inputCount != 1) {
            throw new InvalidWorkflowToolException(
                "Workflow must have exactly one input node to be used as tool"
            );
        }

        // Deve ter exatamente um output node
        long outputCount = workflow.getNodes().stream()
            .filter(n -> n.getType() == NodeType.OUTPUT)
            .count();

        if (outputCount != 1) {
            throw new InvalidWorkflowToolException(
                "Workflow must have exactly one output node to be used as tool"
            );
        }

        // Não deve ter ciclos (para tools)
        if (hasCycles(workflow)) {
            throw new InvalidWorkflowToolException(
                "Workflow tools cannot contain cycles"
            );
        }
    }

    private boolean hasCycles(WorkflowDefinition workflow) {
        // Detecta ciclos no grafo
        return false;
    }
}
```

### 16.3 Tool Integration com LangChain4j

```java
package org.archflow.workflow.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Integração de workflow tools com LangChain4j
 */
@Component
public class LangChain4jToolAdapter implements ToolExecutor {

    private final WorkflowToolRegistry toolRegistry;

    @Override
    public ToolExecutionResultMessage execute(ToolSpecification toolSpecification,
                                              String arguments) {
        String toolName = toolSpecification.name();

        // Obtém workflow tool
        WorkflowTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return new ToolExecutionResultMessage(
                toolName,
                "Error: Tool not found: " + toolName
            );
        }

        // Parse argumentos
        Map<String, Object> parsedArgs = parseArguments(arguments);

        // Cria ToolInput
        ToolInput input = ToolInput.builder()
            .parameters(parsedArgs)
            .build();

        // Executa
        try {
            ToolResult result = tool.execute(input);
            return new ToolExecutionResultMessage(
                toolName,
                serializeResult(result)
            );
        } catch (Exception e) {
            return new ToolExecutionResultMessage(
                toolName,
                "Error: " + e.getMessage()
            );
        }
    }

    /**
     * Gera ToolSpecifications para LangChain4j
     */
    public List<ToolSpecification> getToolSpecifications() {
        return toolRegistry.getAllTools().stream()
            .map(this::toSpecification)
            .toList();
    }

    private ToolSpecification toSpecification(WorkflowTool tool) {
        return ToolSpecification.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .parameters(toParametersSchema(tool.getInputSchema()))
            .build();
    }

    private dev.langchain4j.service.tool.ToolParametersSchema toParametersSchema(ToolSchema schema) {
        // Converte schema para formato LangChain4j
        return null;
    }

    private Map<String, Object> parseArguments(String arguments) {
        // Parse JSON
        return Map.of();
    }

    private String serializeResult(ToolResult result) {
        // Serializa resultado para JSON
        return result.toJson();
    }
}
```

### 16.4 Composition Examples

#### Exemplo 1: Order Processing Workflow

```yaml
# parent-workflow.yaml - Processa pedidos chamando sub-workflows
workflow:
  id: order-processing
  name: "Order Processing Pipeline"

  nodes:
    - id: input
      type: InputNode
      config:
        schema:
          orderId: string
          customerId: string

    - id: validateOrder
      type: ToolNode
      config:
        tool: validate-order-workflow  # Workflow como tool!
        description: "Validates order details"

    - id: checkInventory
      type: ToolNode
      config:
        tool: check-inventory-workflow  # Workflow como tool!
        description: "Checks item availability"

    - id: processPayment
      type: ToolNode
      config:
        tool: process-payment-workflow  # Workflow como tool!
        description: "Processes payment"

    - id: confirmOrder
      type: ToolNode
      config:
        tool: confirm-order-workflow  # Workflow como tool!
        description: "Confirms and ships order"

    - id: output
      type: OutputNode

  edges:
    - from: input
      to: validateOrder
    - from: validateOrder
      to: checkInventory
    - from: checkInventory
      to: processPayment
    - from: processPayment
      to: confirmOrder
    - from: confirmOrder
      to: output
```

```yaml
# validate-order-workflow.yaml - Sub-workflow reutilizável
workflow:
  id: validate-order
  name: "Validate Order"
  exportable: true  # Permite uso como tool

  nodes:
    - id: input
      type: InputNode
      config:
        schema:
          orderId: string
          items: array

    - id: checkRules
      type: RuleEngineNode
      config:
        rules:
          - name: "Min items"
            condition: "{{items.length}} >= 1"
          - name: "Max items"
            condition: "{{items.length}} <= 100"
          - name: "No prohibited items"
            condition: "!{{items}}.any(i => i.prohibited)"

    - id: output
      type: OutputNode
      config:
        schema:
          valid: boolean
          errors: array

  edges:
    - from: input
      to: checkRules
    - from: checkRules
      to: output
```

#### Exemplo 2: Hierarquia de Workflows

```
┌─────────────────────────────────────────────────────────────────┐
│                    Customer Support System                       │
│                                                                   │
│  Main Workflow: customer-support-orchestrator                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Input: customer inquiry                                 │    │
│  │                                                          │    │
│  │  ┌────────────────────────────────────────────────┐    │    │
│  │  │ Tool: classify-inquiry-workflow                 │    │    │
│  │  │ → Returns: category (technical, billing, etc)   │    │    │
│  │  └────────────────────────────────────────────────┘    │    │
│  │                                                          │    │
│  │  ┌────────────────────────────────────────────────┐    │    │
│  │  │ Conditional routing based on category           │    │    │
│  │  └────────────────────────────────────────────────┘    │    │
│  │                                                          │    │
│  │  If technical:                                          │    │
│  │  ┌────────────────────────────────────────────────┐    │    │
│  │  │ Tool: technical-troubleshooting-workflow       │    │    │
│  │  │   └─ Tool: search-knowledge-base               │    │    │
│  │  │   └─ Tool: diagnose-issue                     │    │    │
│  │  └────────────────────────────────────────────────┘    │    │
│  │                                                          │    │
│  │  If billing:                                            │    │
│  │  ┌────────────────────────────────────────────────┐    │    │
│  │  │ Tool: billing-inquiry-workflow                 │    │    │
│  │  │   └─ Tool: get-customer-balance                │    │    │
│  │  │   └─ Tool: generate-invoice                    │    │    │
│  │  └────────────────────────────────────────────────┘    │    │
│  │                                                          │    │
│  │  ┌────────────────────────────────────────────────┐    │    │
│  │  │ Output: resolution or escalation                │    │    │
│  │  └────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### 16.5 Hierarchical Execution Tracking

```java
/**
 * Executa workflow como tool com tracking hierárquico
 */
public ToolResult execute(ToolInput input, ExecutionId parentExecutionId) {
    // Cria execution ID filho
    ExecutionId executionId = ExecutionId.childOf(
        parentExecutionId,
        ExecutionType.WORKFLOW_TOOL
    );

    // Registra hierarquia
    executionTracker.registerHierarchy(
        parentExecutionId,
        executionId,
        "workflow-tool:" + workflowId
    );

    // Executa com contexto filho
    ExecutionContext context = ExecutionContext.builder()
        .id(executionId)
        .parent(parentExecutionId)
        .workflowId(workflowId)
        .build();

    // Span filho para tracing
    io.opentelemetry.api.trace.Span parentSpan = tracer.getCurrentSpan();
    io.opentelemetry.api.trace.Span span = tracer.startWorkflowSpan(
        workflowId,
        executionId.getValue(),
        parentSpan.getContext()
    );

    try {
        WorkflowExecutionResult result = workflowEngine.execute(workflowId, input, context);
        return ToolResult.success(result.getOutput());

    } finally {
        span.end();
    }
}
```

---

## Critérios de Sucesso da Fase 4

- [ ] 4+ templates built-in instaláveis
- [ ] Sistema de parâmetros configurável
- [ ] Preview de workflow antes da instalação
- [ ] Conversas podem ser suspensas e retomadas
- [ ] Formulários interativos renderizados no chat
- [ ] Marketplace de extensões funcional
- [ ] Extensões podem ser instaladas/removidas
- [ ] Validação de assinatura de extensões
- [ ] Workflows podem ser invocados como tools
- [ ] Hierarquia de execução visível no tracing
- [ ] Templates comunitários podem ser publicados

---

## Próximos Passos

Após completar Fase 4, o sistema terá:
- Sistema de templates robusto
- Conversações interativas com suspend/resume
- Marketplace de extensões com segurança
- Composição avançada de workflows

Próximo: **Fase 5 - Polish & Launch** (Performance, Documentação, Examples, Release)
