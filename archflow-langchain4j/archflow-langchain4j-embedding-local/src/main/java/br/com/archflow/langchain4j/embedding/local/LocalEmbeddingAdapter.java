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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Adapter para geração local de embeddings usando um modelo ONNX com tokenização SentencePiece via DJL.
 * Suporta GPU (multi-GPU), batching, pooling configurável e cache de tokenização.
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "local.model.path", "/path/to/minilm-l6-v2.onnx",           // Caminho para o modelo ONNX
 *     "local.vocab.path", "/path/to/sentencepiece.bpe.model",     // Caminho para o vocabulário SentencePiece
 *     "local.dimension", 384,                                     // Dimensão dos embeddings (ex.: 384 para MiniLM-L6)
 *     "local.maxLength", 128,                                     // Comprimento máximo da sequência (opcional)
 *     "local.batchSize", 32,                                      // Tamanho máximo do batch (opcional)
 *     "local.useGpu", true,                                       // Habilitar GPU (opcional)
 *     "local.gpuDeviceId", 0,                                     // ID do dispositivo GPU (opcional, padrão 0)
 *     "local.usePooling", false,                                  // Usar mean pooling em vez de [CLS] (opcional)
 *     "local.useCache", true                                      // Habilitar cache de tokenização (opcional)
 * );
 * }</pre>
 *
 * <p>Operações suportadas:
 * <ul>
 *   <li>{@code embed} - Gera embedding de um texto</li>
 *   <li>{@code embedBatch} - Gera embeddings de múltiplos textos em lote</li>
 * </ul>
 */
public class LocalEmbeddingAdapter implements LangChainAdapter, EmbeddingModel {
    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile SpTokenizer tokenizer;
    private int dimension;
    private int maxLength;
    private int batchSize;
    private boolean useGpu;
    private int gpuDeviceId;
    private boolean usePooling;
    private boolean useCache;
    private Map<String, int[]> tokenCache; // Cache de tokenização
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

        Object batchSize = properties.get("local.batchSize");
        if (batchSize != null && (!(batchSize instanceof Number) || ((Number) batchSize).intValue() <= 0)) {
            throw new IllegalArgumentException("Batch size must be a positive number");
        }

        Object useGpu = properties.get("local.useGpu");
        if (useGpu != null && !(useGpu instanceof Boolean)) {
            throw new IllegalArgumentException("useGpu must be a boolean");
        }

        Object gpuDeviceId = properties.get("local.gpuDeviceId");
        if (gpuDeviceId != null && (!(gpuDeviceId instanceof Number) || ((Number) gpuDeviceId).intValue() < 0)) {
            throw new IllegalArgumentException("gpuDeviceId must be a non-negative number");
        }

        Object usePooling = properties.get("local.usePooling");
        if (usePooling != null && !(usePooling instanceof Boolean)) {
            throw new IllegalArgumentException("usePooling must be a boolean");
        }

        Object useCache = properties.get("local.useCache");
        if (useCache != null && !(useCache instanceof Boolean)) {
            throw new IllegalArgumentException("useCache must be a boolean");
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
        this.batchSize = (Integer) properties.getOrDefault("local.batchSize", 32);
        this.useGpu = (Boolean) properties.getOrDefault("local.useGpu", false);
        this.gpuDeviceId = (Integer) properties.getOrDefault("local.gpuDeviceId", 0);
        this.usePooling = (Boolean) properties.getOrDefault("local.usePooling", false);
        this.useCache = (Boolean) properties.getOrDefault("local.useCache", true);
        this.tokenCache = useCache ? new ConcurrentHashMap<>() : null;

        try {
            // Inicializa o ONNX Runtime
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();

            if (useGpu) {
                // Adicionar suporte a CUDA (GPU) com dispositivo específico
                options.addCUDA(gpuDeviceId);
            }

            this.session = env.createSession(modelPath, options);

            // Inicializa o SentencePiece via DJL
            this.tokenizer = new SpTokenizer(Paths.get(vocabPath));
        } catch (OrtException e) {
            throw new RuntimeException("Failed to initialize ONNX model" + (useGpu ? " with GPU support" : ""), e);
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
            List<TextSegment> singleText = Collections.singletonList(text);
            float[][] embeddings = generateEmbeddings(singleText);
            return Response.from(new Embedding(embeddings[0]));
        } catch (OrtException e) {
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> texts) {
        try {
            List<Embedding> allEmbeddings = new ArrayList<>();
            for (int i = 0; i < texts.size(); i += batchSize) {
                List<TextSegment> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
                float[][] batchEmbeddings = generateEmbeddings(batch);
                for (float[] embedding : batchEmbeddings) {
                    allEmbeddings.add(new Embedding(embedding));
                }
            }
            return Response.from(allEmbeddings);
        } catch (OrtException e) {
            throw new RuntimeException("Failed to generate embeddings for batch", e);
        }
    }

    private float[][] generateEmbeddings(List<TextSegment> texts) throws OrtException {
        SpProcessor processor = tokenizer.getProcessor();
        int batchSize = texts.size();
        long[][] inputIds = new long[batchSize][maxLength];
        long[][] attentionMask = new long[batchSize][maxLength];
        int padId = processor.getId("[PAD]");
        int clsId = processor.getId("[CLS]");
        int sepId = processor.getId("[SEP]");

        // Tokenização com cache, se habilitado
        for (int b = 0; b < batchSize; b++) {
            String text = texts.get(b).text();
            int[] tokenIds;
            if (useCache && tokenCache.containsKey(text)) {
                tokenIds = tokenCache.get(text);
            } else {
                tokenIds = processor.encode(text);
                if (useCache) {
                    tokenCache.put(text, tokenIds);
                }
            }

            Arrays.fill(inputIds[b], padId);
            Arrays.fill(attentionMask[b], 0);

            int length = Math.min(tokenIds.length, maxLength - 2); // Reservar espaço para [CLS] e [SEP]
            inputIds[b][0] = clsId;
            attentionMask[b][0] = 1;

            for (int i = 0; i < length; i++) {
                inputIds[b][i + 1] = tokenIds[i];
                attentionMask[b][i + 1] = 1;
            }
            inputIds[b][length + 1] = sepId;
            attentionMask[b][length + 1] = 1;
        }

        // Criar tensores para batch
        LongBuffer inputIdsBuffer = LongBuffer.allocate(batchSize * maxLength);
        LongBuffer attentionMaskBuffer = LongBuffer.allocate(batchSize * maxLength);
        for (int b = 0; b < batchSize; b++) {
            inputIdsBuffer.put(inputIds[b]);
            attentionMaskBuffer.put(attentionMask[b]);
        }
        inputIdsBuffer.rewind();
        attentionMaskBuffer.rewind();

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(env, inputIdsBuffer, new long[]{batchSize, maxLength}));
        inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMaskBuffer, new long[]{batchSize, maxLength}));

        // Executar inferência em batch
        try (OrtSession.Result result = session.run(inputs)) {
            OnnxTensor outputTensor = (OnnxTensor) result.get(0); // [batch_size, seq_length, hidden_size]
            FloatBuffer buffer = outputTensor.getFloatBuffer();
            float[][] embeddings = new float[batchSize][dimension];

            if (usePooling) {
                // Mean pooling: calcular a média dos embeddings ao longo da sequência
                int seqLength = maxLength;
                for (int b = 0; b < batchSize; b++) {
                    float[] sum = new float[dimension];
                    int validTokens = 0;
                    for (int s = 0; s < seqLength; s++) {
                        if (attentionMask[b][s] == 1) {
                            buffer.position((b * seqLength + s) * dimension);
                            float[] tokenEmbedding = new float[dimension];
                            buffer.get(tokenEmbedding, 0, dimension);
                            for (int d = 0; d < dimension; d++) {
                                sum[d] += tokenEmbedding[d];
                            }
                            validTokens++;
                        }
                    }
                    for (int d = 0; d < dimension; d++) {
                        embeddings[b][d] = sum[d] / validTokens;
                    }
                }
            } else {
                // Usar [CLS] token (padrão)
                for (int b = 0; b < batchSize; b++) {
                    buffer.position(b * maxLength * dimension); // Posição do [CLS]
                    buffer.get(embeddings[b], 0, dimension);
                }
            }
            return embeddings;
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
        if (tokenCache != null) {
            tokenCache.clear();
            tokenCache = null;
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