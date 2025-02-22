package br.com.archflow.langchain4j.embedding.local;

import ai.djl.sentencepiece.SpProcessor;
import ai.djl.sentencepiece.SpTokenizer;
import ai.onnxruntime.*;
import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter para geração local de embeddings usando um modelo ONNX com tokenização SentencePiece via DJL.
 * Executa modelos pré-treinados localmente (ex.: MiniLM-L6-v2) sem dependência de APIs externas.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "local.model.path", "/path/to/minilm-l6-v2.onnx",           // Caminho para o modelo ONNX
 *     "local.vocab.path", "/path/to/sentencepiece.bpe.model",     // Caminho para o vocabulário SentencePiece
 *     "local.dimension", 384,                                     // Dimensão dos embeddings (ex.: 384 para MiniLM-L6)
 *     "local.maxLength", 128                                      // Comprimento máximo da sequência (opcional)
 * );
 * }</pre>
 *
 * <p>Operações suportadas:
 * <ul>
 *   <li>{@code embed} - Gera embedding de um texto</li>
 *   <li>{@code embedBatch} - Gera embeddings de múltiplos textos</li>
 * </ul>
 */
public class LocalEmbeddingAdapter implements LangChainAdapter, EmbeddingModel {
    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile SpTokenizer tokenizer;
    private int dimension;
    private int maxLength;
    private Map<String, Object> config;

    /**
     * Valida as configurações fornecidas para o adapter.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String modelPath = (String) properties.get("local.model.path");
        if (modelPath == null || modelPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Local model path is required");
        }

        String vocabPath = (String) properties.get("local.vocab.path");
        if (vocabPath == null || vocabPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Local vocabulary path is required");
        }

        Object dimension = properties.get("local.dimension");
        if (dimension == null || !(dimension instanceof Number) || ((Number) dimension).intValue() <= 0) {
            throw new IllegalArgumentException("Vector dimension is required and must be a positive number");
        }

        Object maxLength = properties.get("local.maxLength");
        if (maxLength != null && (!(maxLength instanceof Number) || ((Number) maxLength).intValue() <= 0)) {
            throw new IllegalArgumentException("Max length must be a positive number");
        }
    }

    /**
     * Configura o adapter com as propriedades especificadas.
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se as configurações forem inválidas
     */
    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String modelPath = (String) properties.get("local.model.path");
        String vocabPath = (String) properties.get("local.vocab.path");
        this.dimension = (Integer) properties.get("local.dimension");
        this.maxLength = (Integer) properties.getOrDefault("local.maxLength", 128);

        try {
            // Inicializa o ONNX Runtime
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            this.session = env.createSession(modelPath, options);

            // Inicializa o SentencePiece via DJL
            this.tokenizer = new SpTokenizer(Paths.get(vocabPath));
        } catch (OrtException e) {
            throw new RuntimeException("Failed to initialize ONNX model", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SentencePiece tokenizer via DJL", e);
        }
    }

    /**
     * Executa operações no modelo de embeddings local.
     *
     * @param operation Nome da operação ("embed" ou "embedBatch")
     * @param input     Para "embed": String ou {@link TextSegment}<br>Para "embedBatch": List de String ou {@link TextSegment}
     * @param context   Contexto de execução (não utilizado atualmente)
     * @return Para "embed": {@link Response}<{@link Embedding}><br>Para "embedBatch": {@link Response}<List<{@link Embedding}>>
     * @throws IllegalArgumentException se a operação ou o input for inválido
     * @throws IllegalStateException    se o adapter não estiver configurado
     * @throws RuntimeException         se ocorrer um erro durante a execução
     */
    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (session == null || env == null || tokenizer == null) {
            throw new IllegalStateException("Embedding model not configured. Call configure() first.");
        }

        if ("embed".equals(operation)) {
            if (input instanceof String) {
                return embed(TextSegment.from((String) input));
            }
            if (input instanceof TextSegment) {
                return embed((TextSegment) input);
            }
            throw new IllegalArgumentException("Input must be a String or TextSegment for embed operation");
        }

        if ("embedBatch".equals(operation)) {
            if (!(input instanceof List)) {
                throw new IllegalArgumentException("Input must be a List for embedBatch operation");
            }
            List<?> inputs = (List<?>) input;
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Input list cannot be empty");
            }

            if (inputs.get(0) instanceof String) {
                List<TextSegment> segments = ((List<String>) input).stream()
                        .map(TextSegment::from)
                        .toList();
                return embedAll(segments);
            }
            if (inputs.get(0) instanceof TextSegment) {
                return embedAll((List<TextSegment>) input);
            }
            throw new IllegalArgumentException("Input must be a List of String or TextSegment for embedBatch operation");
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    @Override
    public Response<Embedding> embed(TextSegment text) {
        try {
            float[] embedding = generateEmbedding(text.text());
            return Response.from(new Embedding(embedding));
        } catch (OrtException e) {
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> texts) {
        try {
            List<Embedding> embeddings = texts.stream()
                    .map(text -> {
                        try {
                            return new Embedding(generateEmbedding(text.text()));
                        } catch (OrtException e) {
                            throw new RuntimeException("Failed to generate embedding for batch", e);
                        }
                    })
                    .collect(Collectors.toList());
            return Response.from(embeddings);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private float[] generateEmbedding(String text) throws OrtException {
        // Tokenização com SentencePiece via DJL
        SpProcessor processor = tokenizer.getProcessor();
        int[] tokenIds = processor.encode(text); // Usar encode diretamente para obter IDs

        // Truncar ou preencher até maxLength
        long[] inputIds = new long[maxLength];
        long[] attentionMask = new long[maxLength];
        int padId = processor.getId("[PAD]");
        int clsId = processor.getId("[CLS]");
        int sepId = processor.getId("[SEP]");

        Arrays.fill(inputIds, padId); // Preencher com [PAD]
        Arrays.fill(attentionMask, 0);

        int length = Math.min(tokenIds.length, maxLength - 2); // Reservar espaço para [CLS] e [SEP]
        inputIds[0] = clsId;
        attentionMask[0] = 1;

        for (int i = 0; i < length; i++) {
            inputIds[i + 1] = tokenIds[i];
            attentionMask[i + 1] = 1;
        }
        inputIds[length + 1] = sepId;
        attentionMask[length + 1] = 1;

        // Preparar entradas para o modelo ONNX
        LongBuffer inputIdsBuffer = LongBuffer.wrap(inputIds);
        LongBuffer attentionMaskBuffer = LongBuffer.wrap(attentionMask);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(env, inputIdsBuffer, new long[]{1, maxLength}));
        inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMaskBuffer, new long[]{1, maxLength}));

        // Executar inferência
        try (OrtSession.Result result = session.run(inputs)) {
            OnnxTensor outputTensor = (OnnxTensor) result.get(0); // Saída típica é [batch_size, seq_length, hidden_size]
            FloatBuffer buffer = outputTensor.getFloatBuffer();

            // Extrair o embedding do token [CLS] (posição 0)
            float[] embedding = new float[dimension];
            buffer.position(0); // Primeiro token ([CLS])
            buffer.get(embedding, 0, dimension);
            return embedding;
        }
    }

    /**
     * Libera recursos utilizados pelo adapter, fechando a sessão ONNX e o ambiente.
     */
    @Override
    public void shutdown() {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                // Logar erro se houver sistema de log
            }
            session = null;
        }
        if (env != null) {
            env.close();
            env = null;
        }
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        this.config = null;
    }

    public static class Factory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "local";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            LocalEmbeddingAdapter adapter = new LocalEmbeddingAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "embedding".equals(type);
        }
    }
}