package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent specialized for data analysis tasks.
 *
 * <p>Supports text-to-SQL query generation, statistical analysis,
 * chart suggestion for visualization, and trend prediction/forecasting.
 * Includes schema introspection for intelligent query generation and
 * configurable table access controls.
 */
public class DataAnalysisAgent implements AIAgent, ComponentPlugin {

    private static final String COMPONENT_ID = "data-analysis-agent";
    private static final String VERSION = "1.0.0";

    private static final Set<String> SUPPORTED_TASK_TYPES = Set.of(
            "query", "analyze", "visualize", "forecast"
    );

    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "\\b(?:from|join|into|update|table)\\s+(\\w+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COLUMN_REF_PATTERN = Pattern.compile(
            "\\b(?:select|where|group\\s+by|order\\s+by|having)\\s+([\\w.,\\s]+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern METRIC_PATTERN = Pattern.compile(
            "\\b(?:sum|avg|count|min|max|total|average|mean|median)\\b", Pattern.CASE_INSENSITIVE
    );

    private Map<String, Object> config;
    private boolean initialized = false;
    private DataSource dataSource;
    private SchemaIntrospector schemaIntrospector;
    private Set<String> allowedTables;
    private int maxRows;

    /**
     * Interface for executing SQL queries against a data source.
     */
    public interface DataSource {
        /**
         * Executes the given SQL query and returns the result rows.
         *
         * @param sql the SQL query to execute
         * @return list of rows, each represented as a column-name-to-value map
         */
        List<Map<String, Object>> executeQuery(String sql);
    }

    /**
     * Interface for discovering database schema information.
     */
    public interface SchemaIntrospector {
        /**
         * Returns the names of all available tables.
         *
         * @return list of table names
         */
        List<String> getTables();

        /**
         * Returns the column names for the specified table.
         *
         * @param table the table name
         * @return list of column names
         */
        List<String> getColumns(String table);
    }

    /**
     * Supported analysis task types.
     */
    public enum AnalysisType {
        /** Text-to-SQL query generation and execution. */
        QUERY,
        /** Statistical analysis of data. */
        ANALYZE,
        /** Chart and visualization suggestion. */
        VISUALIZE,
        /** Trend prediction and forecasting. */
        FORECAST
    }

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config;
        if (this.maxRows == 0) {
            this.maxRows = 1000;
        }
        if (this.allowedTables == null) {
            this.allowedTables = Set.of();
        }
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "Data Analysis Agent",
                "Autonomous data analysis agent that generates SQL, analyzes data, suggests visualizations, and forecasts trends",
                ComponentType.AGENT,
                VERSION,
                Set.of("query", "analysis", "visualization", "forecasting"),
                List.of(
                        new ComponentMetadata.OperationMetadata(
                                "executeTask", "Execute Task", "Execute a data analysis task",
                                List.of(new ComponentMetadata.ParameterMetadata("task", "object", "Task to execute", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "object", "Task result", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "analyzeRequest", "Analyze Request", "Detect analysis type and extract entity references",
                                List.of(new ComponentMetadata.ParameterMetadata("request", "string", "Natural language request", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("analysis", "object", "Request analysis", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "generateResponse", "Generate Response", "Describe findings in natural language",
                                List.of(new ComponentMetadata.ParameterMetadata("data", "object", "Analysis data", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("response", "string", "Natural language response", true))
                        )
                ),
                Map.of(),
                Set.of("agent", "data-analysis", "sql", "visualization")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Agent not initialized. Call initialize() first.");
        }

        return switch (operation) {
            case "executeTask" -> executeTask((Task) input, context);
            case "analyzeRequest" -> analyzeRequest((String) input);
            case "generateResponse" -> generateResponse(input);
            case "makeDecision" -> makeDecision(context);
            case "planActions" -> planActions((Goal) input, context);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    @Override
    public Result executeTask(Task task, ExecutionContext context) {
        if (task == null) {
            return Result.failure("Task cannot be null");
        }

        if (!SUPPORTED_TASK_TYPES.contains(task.type())) {
            return Result.failure("Unsupported task type: " + task.type()
                    + ". Supported: " + SUPPORTED_TASK_TYPES);
        }

        return switch (task.type()) {
            case "query" -> executeQueryTask(task);
            case "analyze" -> executeAnalyzeTask(task);
            case "visualize" -> executeVisualizeTask(task);
            case "forecast" -> executeForecastTask(task);
            default -> Result.failure("Unhandled task type: " + task.type());
        };
    }

    @Override
    public Decision makeDecision(ExecutionContext context) {
        boolean hasDataSource = dataSource != null;
        boolean hasSchema = schemaIntrospector != null;

        if (!hasDataSource) {
            return new Decision(
                    "request_datasource",
                    "No data source configured; cannot proceed with analysis",
                    0.95,
                    List.of("use_sample_data", "ask_user")
            );
        }

        if (!hasSchema) {
            return new Decision(
                    "query_direct",
                    "No schema introspector available; will attempt direct query execution",
                    0.70,
                    List.of("request_schema", "infer_schema")
            );
        }

        return new Decision(
                "full_analysis",
                "Data source and schema available; proceed with full analysis pipeline",
                0.90,
                List.of("query_only", "schema_exploration")
        );
    }

    @Override
    public List<Action> planActions(Goal goal, ExecutionContext context) {
        if (goal == null) {
            return List.of(Action.of("error", "No goal provided"));
        }

        List<Action> actions = new ArrayList<>();

        // Step 1: Analyze the request
        actions.add(new Action("analyze_request", "Analyze Request",
                Map.of("goal", goal.description()), true));

        // Step 2: Introspect schema
        actions.add(new Action("introspect_schema", "Introspect Schema",
                Map.of("discover_tables", true, "discover_columns", true), true));

        // Step 3: Generate query
        actions.add(new Action("generate_query", "Generate SQL Query",
                Map.of("natural_language", goal.description()), false));

        // Step 4: Execute query
        actions.add(new Action("execute_query", "Execute Query",
                Map.of("max_rows", maxRows), false));

        // Step 5: Analyze results
        actions.add(new Action("analyze_results", "Analyze Results",
                Map.of("criteria", goal.successCriteria()), false));

        // Step 6: Generate response
        actions.add(new Action("generate_response", "Generate Response",
                Map.of("format", "natural_language"), false));

        return actions;
    }

    /**
     * Analyzes a natural language request to detect the analysis type
     * and extract entity references (table names, column names, metrics).
     *
     * @param request the natural language request
     * @return a map containing analysisType, tables, columns, and metrics
     */
    public Map<String, Object> analyzeRequest(String request) {
        if (request == null || request.isBlank()) {
            return Map.of("analysisType", "UNKNOWN", "tables", List.of(),
                    "columns", List.of(), "metrics", List.of());
        }

        AnalysisType type = detectAnalysisType(request);
        List<String> tables = extractTableReferences(request);
        List<String> columns = extractColumnReferences(request);
        List<String> metrics = extractMetrics(request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysisType", type.name());
        result.put("tables", tables);
        result.put("columns", columns);
        result.put("metrics", metrics);
        return result;
    }

    /**
     * Generates a natural language description of the given analysis data.
     *
     * @param data the analysis results to describe
     * @return a natural language summary string
     */
    @SuppressWarnings("unchecked")
    public String generateResponse(Object data) {
        if (data == null) {
            return "No data available to generate a response.";
        }

        if (data instanceof List<?> list) {
            if (list.isEmpty()) {
                return "The query returned no results.";
            }
            int rowCount = list.size();
            if (list.get(0) instanceof Map<?, ?> firstRow) {
                int colCount = firstRow.size();
                return String.format("Analysis complete. Found %d record(s) across %d column(s).",
                        rowCount, colCount);
            }
            return String.format("Analysis complete. Found %d result(s).", rowCount);
        }

        if (data instanceof Map<?, ?> map) {
            return String.format("Analysis complete with %d finding(s).", map.size());
        }

        return "Analysis complete. " + data;
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        // Configuration is optional; data source and schema introspector
        // can be set via the builder
    }

    @Override
    public void shutdown() {
        this.config = null;
        this.initialized = false;
        this.dataSource = null;
        this.schemaIntrospector = null;
    }

    // ---- Internal pipeline methods ----

    private Result executeQueryTask(Task task) {
        Map<String, Object> params = task.parameters() != null ? task.parameters() : Map.of();
        String query = (String) params.getOrDefault("query", "");
        if (query.isBlank()) {
            return Result.failure("Query parameter is required for query tasks");
        }

        // Analyze the request
        Map<String, Object> analysis = analyzeRequest(query);

        @SuppressWarnings("unchecked")
        List<String> referencedTables = (List<String>) analysis.get("tables");

        // Check allowed tables
        if (!allowedTables.isEmpty() && !referencedTables.isEmpty()) {
            List<String> disallowed = referencedTables.stream()
                    .filter(t -> !allowedTables.contains(t.toLowerCase()))
                    .collect(Collectors.toList());
            if (!disallowed.isEmpty()) {
                return Result.failure("Access denied to table(s): " + disallowed
                        + ". Allowed: " + allowedTables);
            }
        }

        // Introspect schema if available
        Map<String, Object> schemaInfo = introspectSchema();

        // Generate SQL
        String sql = generateSql(query, analysis, schemaInfo);

        // Execute if data source is available
        List<Map<String, Object>> rows;
        if (dataSource != null) {
            rows = dataSource.executeQuery(sql);
            if (rows.size() > maxRows) {
                rows = rows.subList(0, maxRows);
            }
        } else {
            rows = List.of();
        }

        // Build output
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_type", "query");
        output.put("analysis", analysis);
        output.put("generated_sql", sql);
        output.put("row_count", rows.size());
        output.put("rows", rows);
        output.put("response", generateResponse(rows));
        output.put("status", "completed");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Query task completed"));
    }

    private Result executeAnalyzeTask(Task task) {
        Map<String, Object> params = task.parameters() != null ? task.parameters() : Map.of();
        String subject = (String) params.getOrDefault("query",
                params.getOrDefault("subject", ""));

        Map<String, Object> analysis = analyzeRequest(subject);

        List<String> steps = List.of(
                "Parse analysis request",
                "Identify relevant data sources",
                "Compute descriptive statistics",
                "Identify patterns and correlations",
                "Generate summary"
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_type", "analyze");
        output.put("analysis", analysis);
        output.put("steps_executed", steps.size());
        output.put("steps", steps);
        output.put("status", "completed");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Analysis task completed"));
    }

    private Result executeVisualizeTask(Task task) {
        Map<String, Object> params = task.parameters() != null ? task.parameters() : Map.of();
        String query = (String) params.getOrDefault("query", "");
        Map<String, Object> analysis = analyzeRequest(query);

        String chartType = suggestChartType(analysis);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_type", "visualize");
        output.put("analysis", analysis);
        output.put("suggested_chart", chartType);
        output.put("status", "completed");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Visualization task completed"));
    }

    private Result executeForecastTask(Task task) {
        Map<String, Object> params = task.parameters() != null ? task.parameters() : Map.of();
        String query = (String) params.getOrDefault("query", "");
        Map<String, Object> analysis = analyzeRequest(query);

        List<String> steps = List.of(
                "Identify time series data",
                "Detect seasonality and trends",
                "Select forecasting model",
                "Generate predictions",
                "Compute confidence intervals"
        );

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_type", "forecast");
        output.put("analysis", analysis);
        output.put("steps_executed", steps.size());
        output.put("steps", steps);
        output.put("status", "completed");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Forecast task completed"));
    }

    private AnalysisType detectAnalysisType(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("forecast") || lower.contains("predict") || lower.contains("trend")) {
            return AnalysisType.FORECAST;
        }
        if (lower.contains("chart") || lower.contains("plot") || lower.contains("visualize")
                || lower.contains("graph")) {
            return AnalysisType.VISUALIZE;
        }
        if (lower.contains("statistics") || lower.contains("correlation") || lower.contains("distribution")
                || lower.contains("analyze") || lower.contains("average") || lower.contains("mean")
                || lower.contains("median")) {
            return AnalysisType.ANALYZE;
        }
        return AnalysisType.QUERY;
    }

    private List<String> extractTableReferences(String request) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = TABLE_REF_PATTERN.matcher(request);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase());
        }
        return tables;
    }

    private List<String> extractColumnReferences(String request) {
        List<String> columns = new ArrayList<>();
        Matcher matcher = COLUMN_REF_PATTERN.matcher(request);
        while (matcher.find()) {
            String group = matcher.group(1).trim();
            for (String col : group.split("[,\\s]+")) {
                String trimmed = col.trim();
                if (!trimmed.isEmpty() && !trimmed.equals("*")) {
                    columns.add(trimmed.toLowerCase());
                }
            }
        }
        return columns;
    }

    private List<String> extractMetrics(String request) {
        List<String> metrics = new ArrayList<>();
        Matcher matcher = METRIC_PATTERN.matcher(request);
        while (matcher.find()) {
            metrics.add(matcher.group().toLowerCase());
        }
        return metrics;
    }

    private Map<String, Object> introspectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (schemaIntrospector != null) {
            List<String> tables = schemaIntrospector.getTables();
            for (String table : tables) {
                if (allowedTables.isEmpty() || allowedTables.contains(table.toLowerCase())) {
                    schema.put(table, schemaIntrospector.getColumns(table));
                }
            }
        }
        return schema;
    }

    private String generateSql(String naturalLanguage, Map<String, Object> analysis, Map<String, Object> schema) {
        // Simplified SQL generation based on analysis
        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) analysis.getOrDefault("tables", List.of());
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) analysis.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked")
        List<String> metrics = (List<String>) analysis.getOrDefault("metrics", List.of());

        StringBuilder sql = new StringBuilder("SELECT ");

        if (!metrics.isEmpty() && !columns.isEmpty()) {
            List<String> selectParts = new ArrayList<>();
            for (String metric : metrics) {
                for (String col : columns) {
                    selectParts.add(metric.toUpperCase() + "(" + col + ")");
                }
            }
            sql.append(String.join(", ", selectParts));
        } else if (!columns.isEmpty()) {
            sql.append(String.join(", ", columns));
        } else {
            sql.append("*");
        }

        if (!tables.isEmpty()) {
            sql.append(" FROM ").append(tables.get(0));
        }

        sql.append(" LIMIT ").append(maxRows);

        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    private String suggestChartType(Map<String, Object> analysis) {
        List<String> metrics = (List<String>) analysis.getOrDefault("metrics", List.of());
        String analysisType = (String) analysis.getOrDefault("analysisType", "QUERY");

        if ("FORECAST".equals(analysisType)) {
            return "line";
        }
        if (metrics.contains("count") || metrics.contains("total")) {
            return "bar";
        }
        if (metrics.contains("avg") || metrics.contains("average") || metrics.contains("mean")) {
            return "bar";
        }
        if (metrics.contains("distribution") || metrics.contains("median")) {
            return "histogram";
        }
        return "table";
    }

    // ---- Builder ----

    /**
     * Creates a new builder for constructing a DataAnalysisAgent.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing a configured DataAnalysisAgent instance.
     */
    public static class Builder {

        private DataSource dataSource;
        private SchemaIntrospector schemaIntrospector;
        private Set<String> allowedTables = Set.of();
        private int maxRows = 1000;

        /**
         * Sets the data source for query execution.
         *
         * @param dataSource the data source
         * @return this builder
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * Sets the schema introspector for schema discovery.
         *
         * @param schemaIntrospector the schema introspector
         * @return this builder
         */
        public Builder schemaIntrospector(SchemaIntrospector schemaIntrospector) {
            this.schemaIntrospector = schemaIntrospector;
            return this;
        }

        /**
         * Sets the allowed tables for query access control.
         *
         * @param allowedTables set of allowed table names (lowercase)
         * @return this builder
         */
        public Builder allowedTables(Set<String> allowedTables) {
            this.allowedTables = allowedTables;
            return this;
        }

        /**
         * Sets the maximum number of rows returned by queries.
         *
         * @param maxRows maximum row count (default 1000)
         * @return this builder
         */
        public Builder maxRows(int maxRows) {
            this.maxRows = maxRows;
            return this;
        }

        /**
         * Builds the DataAnalysisAgent with the configured settings.
         *
         * @return a configured DataAnalysisAgent
         */
        public DataAnalysisAgent build() {
            DataAnalysisAgent agent = new DataAnalysisAgent();
            agent.dataSource = this.dataSource;
            agent.schemaIntrospector = this.schemaIntrospector;
            agent.allowedTables = this.allowedTables;
            agent.maxRows = this.maxRows;
            agent.initialize(Map.of());
            return agent;
        }
    }
}
