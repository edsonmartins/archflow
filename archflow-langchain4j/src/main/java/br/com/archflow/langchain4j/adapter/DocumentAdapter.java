package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.document.DocumentLoader;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentTransformer;

import java.util.Map;

// Document Adapter
public class DocumentAdapter implements LangChainAdapter {
    private DocumentLoader loader;
    private DocumentParser parser;
    private DocumentTransformer transformer;

    @Override
    public void configure(Map<String, Object> properties) {
        String type = properties.get("type").toString();
        switch(type) {
            case "pdf":
                loader = new PdfDocumentLoader();
                break;
            case "text":
                loader = new TextDocumentLoader();
                break;
            case "web":
                loader = new WebDocumentLoader();
                break;
        }
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        return switch (operation) {
            case "load" -> loader.load(input.toString());
            case "loadBatch" -> loader.loadBatch((List<String>) input);
            case "parse" -> parser.parse((Document) input);
            case "transform" -> transformer.transform((Document) input);
            default -> throw new IllegalArgumentException("Invalid operation: " + operation);
        };
    }
}

