/**
 * Multi-LLM Provider Hub for unified access to 15+ LLM providers.
 *
 * <h2>Overview</h2>
 * <p>The Provider Hub provides a unified interface for accessing multiple
 * LLM providers (OpenAI, Anthropic, Google, Azure, AWS, etc.) with
 * runtime switching capabilities.</p>
 *
 * <h2>Main Components</h2>
 * <ul>
 *   <li>{@link br.com.archflow.langchain4j.provider.LLMProvider} - Enum of all supported providers</li>
 *   <li>{@link br.com.archflow.langchain4j.provider.LLMProviderConfig} - Configuration for providers</li>
 *   <li>{@link br.com.archflow.langchain4j.provider.LLMProviderHub} - Central hub for accessing providers</li>
 *   <li>{@link br.com.archflow.langchain4j.provider.ProviderSwitcher} - Runtime switching with fallback</li>
 * </ul>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Get the hub singleton
 * LLMProviderHub hub = LLMProviderHub.getInstance();
 *
 * // Register a configuration
 * hub.registerConfig("default", LLMProviderConfig.builder()
 *     .provider(LLMProvider.OPENAI)
 *     .modelId("gpt-4o")
 *     .apiKey("sk-...")
 *     .build());
 *
 * // Get a model
 * ChatLanguageModel model = hub.getModel("default");
 * String response = model.generate("Hello!");
 *
 * // Switch provider at runtime
 * hub.switchProvider("default", LLMProviderConfig.builder()
 *     .provider(LLMProvider.ANTHROPIC)
 *     .modelId("claude-3-5-sonnet-20241022")
 *     .apiKey("sk-ant-...")
 *     .build());
 * }</pre>
 *
 * <h2>Supported Providers</h2>
 * <ul>
 *   <li>OpenAI (GPT-4, GPT-4o, o1, o3-mini)</li>
 *   <li>Anthropic (Claude 3.5/3.7 Sonnet)</li>
 *   <li>Azure OpenAI</li>
 *   <li>Google Gemini</li>
 *   <li>AWS Bedrock</li>
 *   <li>Hugging Face</li>
 *   <li>Ollama (local)</li>
 *   <li>Mistral AI</li>
 *   <li>Cohere</li>
 *   <li>DeepSeek</li>
 *   <li>Alibaba Tongyi (Qwen)</li>
 *   <li>Baidu Qianfan (Ernie)</li>
 *   <li>Tencent Hunyuan</li>
 *   <li>IBM Watsonx</li>
 *   <li>Google Vertex AI</li>
 * </ul>
 *
 * @since 1.0.0
 */
package br.com.archflow.langchain4j.provider;
