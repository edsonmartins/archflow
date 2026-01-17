package br.com.archflow.template.processing;

import br.com.archflow.model.Workflow;
import br.com.archflow.template.AbstractWorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition;

import java.util.*;

/**
 * Template for document processing workflows with extraction and summarization.
 *
 * <p>This template provides automated document processing with:
 * <ul>
 *   <li><b>Document Parsing:</b> Extract text from PDF, DOCX, images</li>
 *   <li><b>Text Extraction:</b> OCR for scanned documents</li>
 *   <li><b>Entity Extraction:</b> Extract key information (dates, amounts, names)</li>
 *   <li><b>Summarization:</b> Generate document summaries</li>
 *   <li><b>Classification:</b> Categorize documents by type</li>
 *   <li><b>Validation:</b> Verify document completeness</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * DocumentProcessingTemplate template = new DocumentProcessingTemplate();
 *
 * Map<String, Object> params = Map.of(
 *     "documentTypes", List.of("invoice", "contract", "receipt"),
 *     "extractEntities", true,
 *     "generateSummary", true
 * );
 *
 * Workflow workflow = template.createInstance("doc-processor", params);
 * }</pre>
 */
public class DocumentProcessingTemplate extends AbstractWorkflowTemplate {

    private static final String ID = "document-processing";
    private static final String VERSION = "1.0.0";

    public DocumentProcessingTemplate() {
        super(
                ID,
                "Document Processing with Extraction",
                "Automated document processing with text extraction, entity recognition, and summarization",
                "processing",
                Set.of("document", "extraction", "ocr", "summarization", "rag"),
                buildParameters()
        );
    }

    private static Map<String, ParameterDefinition> buildParameters() {
        Map<String, ParameterDefinition> params = new LinkedHashMap<>();

        params.put("supportedFormats", ParameterDefinition.optional(
                "supportedFormats",
                "List of supported document formats",
                List.class,
                List.of("pdf", "docx", "txt", "png", "jpg")
        ));

        params.put("enableOcr", ParameterDefinition.optional(
                "enableOcr",
                "Enable OCR for scanned documents and images",
                Boolean.class,
                true
        ));

        params.put("extractEntities", ParameterDefinition.optional(
                "extractEntities",
                "Extract entities (dates, amounts, names, etc.)",
                Boolean.class,
                true
        ));

        params.put("entityTypes", ParameterDefinition.optional(
                "entityTypes",
                "Types of entities to extract",
                List.class,
                List.of("date", "amount", "person", "organization", "email", "phone")
        ));

        params.put("generateSummary", ParameterDefinition.optional(
                "generateSummary",
                "Generate document summary",
                Boolean.class,
                true
        ));

        params.put("summaryMaxLength", ParameterDefinition.optional(
                "summaryMaxLength",
                "Maximum length of summary in words",
                Integer.class,
                200
        ));

        params.put("classifyDocument", ParameterDefinition.optional(
                "classifyDocument",
                "Classify document by type",
                Boolean.class,
                true
        ));

        params.put("documentTypes", ParameterDefinition.optional(
                "documentTypes",
                "Document types for classification",
                List.class,
                List.of("invoice", "contract", "receipt", "report", "letter", "form")
        ));

        params.put("outputFormat", ParameterDefinition.enumParameter(
                "outputFormat",
                "Output format for extracted data",
                "json", "csv", "xml"
        ));

        return params;
    }

    @Override
    public WorkflowTemplateDefinition getDefinition() {
        Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new LinkedHashMap<>();

        // Document input node
        nodes.put("input", new WorkflowTemplateDefinition.TemplateNode(
                "input",
                "document-input",
                "Document Input",
                Map.of(
                        "accept", "{{supportedFormats}}",
                        "maxSize", 10485760 // 10MB
                )
        ));

        // Document parser node
        nodes.put("parser", new WorkflowTemplateDefinition.TemplateNode(
                "parser",
                "document-parser",
                "Document Parser",
                Map.of(
                        "formats", List.of("pdf", "docx", "txt"),
                        "extractMetadata", true
                )
        ));

        // OCR node
        nodes.put("ocr", new WorkflowTemplateDefinition.TemplateNode(
                "ocr",
                "ocr-processor",
                "OCR Text Extraction",
                Map.of(
                        "engine", "tesseract",
                        "languages", List.of("eng", "por"),
                        "preprocess", true
                )
        ));

        // Entity extraction node
        nodes.put("entity-extractor", new WorkflowTemplateDefinition.TemplateNode(
                "entity-extractor",
                "ner",
                "Entity Extraction",
                Map.of(
                        "entityTypes", "{{entityTypes}}",
                        "model", "ner-v1",
                        "confidence", 0.7
                )
        ));

        // Document classifier node
        nodes.put("classifier", new WorkflowTemplateDefinition.TemplateNode(
                "classifier",
                "document-classifier",
                "Document Classifier",
                Map.of(
                        "categories", "{{documentTypes}}",
                        "threshold", 0.8
                )
        ));

        // Summarizer node
        nodes.put("summarizer", new WorkflowTemplateDefinition.TemplateNode(
                "summarizer",
                "summarization",
                "Document Summarizer",
                Map.of(
                        "maxLength", "{{summaryMaxLength}}",
                        "style", "executive"
                )
        ));

        // Validator node
        nodes.put("validator", new WorkflowTemplateDefinition.TemplateNode(
                "validator",
                "validation",
                "Document Validator",
                Map.of(
                        "checkCompleteness", true,
                        "requiredFields", List.of("title", "date", "content")
                )
        ));

        // Output formatter node
        nodes.put("formatter", new WorkflowTemplateDefinition.TemplateNode(
                "formatter",
                "output-formatter",
                "Output Formatter",
                Map.of(
                        "format", "{{outputFormat}}",
                        "includeMetadata", true
                )
        ));

        // Output node
        nodes.put("output", new WorkflowTemplateDefinition.TemplateNode(
                "output",
                "output",
                "Processed Output",
                Map.of()
        ));

        // Connections
        Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new LinkedHashMap<>();

        connections.put("c1", new WorkflowTemplateDefinition.TemplateConnection(
                "c1", "input", "parser", null, null
        ));

        connections.put("c2", new WorkflowTemplateDefinition.TemplateConnection(
                "c2", "parser", "ocr",
                "{{hasImages}} == true && {{enableOcr}} == true", null
        ));

        connections.put("c3", new WorkflowTemplateDefinition.TemplateConnection(
                "c3", "parser", "classifier",
                "{{hasImages}} == false || {{enableOcr}} == false", null
        ));

        connections.put("c4", new WorkflowTemplateDefinition.TemplateConnection(
                "c4", "ocr", "classifier", null, null
        ));

        connections.put("c5", new WorkflowTemplateDefinition.TemplateConnection(
                "c5", "classifier", "entity-extractor",
                "{{classifyDocument}} == true", null
        ));

        connections.put("c6", new WorkflowTemplateDefinition.TemplateConnection(
                "c6", "classifier", "summarizer",
                "{{classifyDocument}} == false", null
        ));

        connections.put("c7", new WorkflowTemplateDefinition.TemplateConnection(
                "c7", "entity-extractor", "summarizer",
                "{{extractEntities}} == true", null
        ));

        connections.put("c8", new WorkflowTemplateDefinition.TemplateConnection(
                "c8", "entity-extractor", "summarizer",
                "{{extractEntities}} == false", null
        ));

        connections.put("c9", new WorkflowTemplateDefinition.TemplateConnection(
                "c9", "summarizer", "validator",
                "{{generateSummary}} == true", null
        ));

        connections.put("c10", new WorkflowTemplateDefinition.TemplateConnection(
                "c10", "summarizer", "validator",
                "{{generateSummary}} == false", null
        ));

        connections.put("c11", new WorkflowTemplateDefinition.TemplateConnection(
                "c11", "validator", "formatter", null, null
        ));

        connections.put("c12", new WorkflowTemplateDefinition.TemplateConnection(
                "c12", "formatter", "output", null, null
        ));

        WorkflowTemplateDefinition.TemplateStructure structure =
                new WorkflowTemplateDefinition.TemplateStructure("input", nodes, connections);

        return new WorkflowTemplateDefinition(
                ID,
                VERSION,
                getDisplayName(),
                getDescription(),
                getCategory(),
                getTags(),
                Map.of(
                        "supportedFormats", List.of("pdf", "docx", "txt", "png", "jpg"),
                        "enableOcr", true,
                        "extractEntities", true,
                        "entityTypes", List.of("date", "amount", "person", "organization", "email", "phone"),
                        "generateSummary", true,
                        "summaryMaxLength", 200,
                        "classifyDocument", true,
                        "documentTypes", List.of("invoice", "contract", "receipt", "report", "letter", "form"),
                        "outputFormat", "json"
                ),
                structure
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
        List<String> supportedFormats = (List<String>) parameters.getOrDefault("supportedFormats",
                List.of("pdf", "docx", "txt", "png", "jpg"));
        boolean enableOcr = getBoolean(parameters, "enableOcr", true);
        boolean extractEntities = getBoolean(parameters, "extractEntities", true);
        List<String> entityTypes = (List<String>) parameters.getOrDefault("entityTypes",
                List.of("date", "amount", "person", "organization", "email", "phone"));
        boolean generateSummary = getBoolean(parameters, "generateSummary", true);
        int summaryMaxLength = getInt(parameters, "summaryMaxLength", 200);
        boolean classifyDocument = getBoolean(parameters, "classifyDocument", true);
        List<String> documentTypes = (List<String>) parameters.getOrDefault("documentTypes",
                List.of("invoice", "contract", "receipt", "report", "letter", "form"));
        String outputFormat = getString(parameters, "outputFormat", "json");

        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        config.put("template", ID);
        config.put("version", VERSION);
        config.put("parameters", Map.of(
                "supportedFormats", supportedFormats,
                "enableOcr", enableOcr,
                "extractEntities", extractEntities,
                "entityTypes", entityTypes,
                "generateSummary", generateSummary,
                "summaryMaxLength", summaryMaxLength,
                "classifyDocument", classifyDocument,
                "documentTypes", documentTypes,
                "outputFormat", outputFormat
        ));

        return createWorkflowFromConfig(name, config);
    }

    private Workflow createWorkflowFromConfig(String name, Map<String, Object> config) {
        return new Workflow() {
            private final String workflowName = name;
            private final Map<String, Object> workflowConfig = config;

            @Override
            public String getId() {
                return workflowName;
            }

            @Override
            public String getName() {
                return workflowName;
            }

            @Override
            public String getDescription() {
                return "Document processing workflow created from template";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of(
                        "template", ID,
                        "templateVersion", VERSION,
                        "createdAt", java.time.Instant.now()
                );
            }
        };
    }
}
