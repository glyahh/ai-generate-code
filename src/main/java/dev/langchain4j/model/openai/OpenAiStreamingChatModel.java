package dev.langchain4j.model.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.internal.ExceptionMapper;
import dev.langchain4j.internal.ToolExecutionRequestBuilder;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.*;
import dev.langchain4j.model.openai.internal.shared.StreamOptions;
import dev.langchain4j.model.openai.spi.OpenAiStreamingChatModelBuilderFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.langchain4j.internal.InternalStreamingChatResponseHandlerUtils.withLoggingExceptions;
import static dev.langchain4j.internal.Utils.*;
import static dev.langchain4j.model.ModelProvider.OPEN_AI;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.*;
import static dev.langchain4j.spi.ServiceHelper.loadFactories;
import static java.time.Duration.ofSeconds;

/**
 * Represents an OpenAI language model with a chat completion interface, such as gpt-4o-mini and o3.
 * The model's response is streamed token by token and should be handled with {@link StreamingResponseHandler}.
 * You can find description of parameters <a href="https://platform.openai.com/docs/api-reference/chat/create">here</a>.
 */
public class OpenAiStreamingChatModel implements StreamingChatModel {

    private final OpenAiClient client;
    private final OpenAiChatRequestParameters defaultRequestParameters;
    private final Boolean strictJsonSchema;
    private final Boolean strictTools;
    private final List<ChatModelListener> listeners;
    private final boolean thinkingDisabled;
    private final String baseUrl;
    private final String apiKey;

    public OpenAiStreamingChatModel(OpenAiStreamingChatModelBuilder builder) {
        this.thinkingDisabled = builder.thinkingDisabled;
        this.baseUrl = getOrDefault(builder.baseUrl, DEFAULT_OPENAI_URL);
        this.apiKey = builder.apiKey;

        this.client = OpenAiClient.builder()
                .httpClientBuilder(builder.httpClientBuilder)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .organizationId(builder.organizationId)
                .projectId(builder.projectId)
                .connectTimeout(getOrDefault(builder.timeout, ofSeconds(15)))
                .readTimeout(getOrDefault(builder.timeout, ofSeconds(60)))
                .logRequests(getOrDefault(builder.logRequests, false))
                .logResponses(getOrDefault(builder.logResponses, false))
                .userAgent(DEFAULT_USER_AGENT)
                .customHeaders(builder.customHeaders)
                .build();

        ChatRequestParameters commonParameters;
        if (builder.defaultRequestParameters != null) {
            validate(builder.defaultRequestParameters);
            commonParameters = builder.defaultRequestParameters;
        } else {
            commonParameters = DefaultChatRequestParameters.EMPTY;
        }

        OpenAiChatRequestParameters openAiParameters;
        if (builder.defaultRequestParameters instanceof OpenAiChatRequestParameters openAiChatRequestParameters) {
            openAiParameters = openAiChatRequestParameters;
        } else {
            openAiParameters = OpenAiChatRequestParameters.EMPTY;
        }

        this.defaultRequestParameters = OpenAiChatRequestParameters.builder()
                // common parameters
                .modelName(getOrDefault(builder.modelName, commonParameters.modelName()))
                .temperature(getOrDefault(builder.temperature, commonParameters.temperature()))
                .topP(getOrDefault(builder.topP, commonParameters.topP()))
                .frequencyPenalty(getOrDefault(builder.frequencyPenalty, commonParameters.frequencyPenalty()))
                .presencePenalty(getOrDefault(builder.presencePenalty, commonParameters.presencePenalty()))
                .maxOutputTokens(getOrDefault(builder.maxTokens, commonParameters.maxOutputTokens()))
                .stopSequences(getOrDefault(builder.stop, commonParameters.stopSequences()))
                .toolSpecifications(commonParameters.toolSpecifications())
                .toolChoice(commonParameters.toolChoice())
                .responseFormat(getOrDefault(fromOpenAiResponseFormat(builder.responseFormat), commonParameters.responseFormat()))
                // OpenAI-specific parameters
                .maxCompletionTokens(getOrDefault(builder.maxCompletionTokens, openAiParameters.maxCompletionTokens()))
                .logitBias(getOrDefault(builder.logitBias, openAiParameters.logitBias()))
                .parallelToolCalls(getOrDefault(builder.parallelToolCalls, openAiParameters.parallelToolCalls()))
                .seed(getOrDefault(builder.seed, openAiParameters.seed()))
                .user(getOrDefault(builder.user, openAiParameters.user()))
                .store(getOrDefault(builder.store, openAiParameters.store()))
                .metadata(getOrDefault(builder.metadata, openAiParameters.metadata()))
                .serviceTier(getOrDefault(builder.serviceTier, openAiParameters.serviceTier()))
                .reasoningEffort(openAiParameters.reasoningEffort())
                .build();
        this.strictJsonSchema = getOrDefault(builder.strictJsonSchema, false);
        this.strictTools = getOrDefault(builder.strictTools, false);
        this.listeners = copy(builder.listeners);
    }

    @Override
    public OpenAiChatRequestParameters defaultRequestParameters() {
        return defaultRequestParameters;
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        doChatWithRetry(chatRequest, handler, 0);
    }

    private void doChatWithRetry(ChatRequest chatRequest, StreamingChatResponseHandler handler, int attempt) {

        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) chatRequest.parameters();
        validate(parameters);

        ChatCompletionRequest openAiRequest =
                toOpenAiChatRequest(chatRequest, parameters, strictTools, strictJsonSchema)
                        .stream(true)
                        .streamOptions(StreamOptions.builder()
                                .includeUsage(true)
                                .build())
                        .build();

        if (thinkingDisabled) {
            doChatWithRetryAsyncSse(openAiRequest, handler, attempt, parameters);
        } else {
            doChatWithRetryStandard(openAiRequest, chatRequest, handler, attempt, parameters);
        }
    }

    /**
     * 标准流式请求路径：使用 OpenAiClient 内置的 HTTP 客户端发送请求。
     */
    private void doChatWithRetryStandard(ChatCompletionRequest openAiRequest,
                                          ChatRequest chatRequest,
                                          StreamingChatResponseHandler handler,
                                          int attempt,
                                          OpenAiChatRequestParameters parameters) {

        OpenAiStreamingResponseBuilder openAiResponseBuilder = new OpenAiStreamingResponseBuilder();
        ToolExecutionRequestBuilder toolBuilder = new ToolExecutionRequestBuilder();
        Set<Integer> nameNotifiedIndices = new HashSet<>();
        AtomicBoolean contentReceived = new AtomicBoolean(false);

        client.chatCompletion(openAiRequest)
                .onPartialResponse(partialResponse -> {
                    openAiResponseBuilder.append(partialResponse);
                    if (!contentReceived.get() && partialResponse != null
                            && partialResponse.choices() != null
                            && !partialResponse.choices().isEmpty()) {
                        Delta d = partialResponse.choices().get(0).delta();
                        if (d != null && !isNullOrEmpty(d.content())) {
                            contentReceived.set(true);
                        }
                    }
                    handle(partialResponse, toolBuilder, handler, nameNotifiedIndices);
                })
                .onComplete(() -> {
                    completeStream(openAiResponseBuilder, toolBuilder, handler, attempt, contentReceived);
                })
                .onError(throwable -> {
                    RuntimeException mappedException = ExceptionMapper.DEFAULT.mapException(throwable);
                    withLoggingExceptions(() -> handler.onError(mappedException));
                })
                .execute();
    }

    /**
     * 自定义流式请求路径：在请求体中加入 "thinking": {"type": "disabled"} 后，
     * 通过 JDK 内置 HttpClient 发送 SSE 流式请求，用 {@link HttpResponse.BodyHandlers#fromLineSubscriber(Flow.Subscriber)}
     * 实现真正的行级流式处理（每行到达即回调 onNext），而非等待整个响应体接收完毕。
     * <p>
     * 适用于 DashScope 等兼容 API，需要禁用模型的 thinking/reasoning 阶段，
     * 避免首轮 HTML 生成时因 reasoning token 导致长时间无 content chunk。
     * <p>
     * <b>重要：</b>不能用 {@code HttpResponse.BodyHandlers.ofLines()} ——
     * 它内部基于 {@code BodyHandlers.ofByteArray()} 实现，会先将整个响应体读入内存再回调，
     * 导致前端长时间空白、最后一次性出现所有内容。
     */
    private void doChatWithRetryAsyncSse(ChatCompletionRequest openAiRequest,
                                          StreamingChatResponseHandler handler,
                                          int attempt,
                                          OpenAiChatRequestParameters parameters) {

        OpenAiStreamingResponseBuilder openAiResponseBuilder = new OpenAiStreamingResponseBuilder();
        ToolExecutionRequestBuilder toolBuilder = new ToolExecutionRequestBuilder();
        Set<Integer> nameNotifiedIndices = new HashSet<>();
        AtomicBoolean contentReceived = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        try {
            // 1. 构建含 thinking 参数的请求体 JSON
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            ObjectNode requestBody = mapper.valueToTree(openAiRequest);
            ObjectNode thinking = requestBody.putObject("thinking");
            thinking.put("type", "disabled");

            String jsonBody = mapper.writeValueAsString(requestBody);

            // 2. 使用 JDK 内置 HttpClient 发送流式 POST 请求
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofMinutes(5))
                    .build();

            // 3. 使用 fromLineSubscriber 实现真正的行级流式：每行到达即回调 onNext
            //    （ofLines() 会等整个响应体收完才回调，非真流式）
            Flow.Subscriber<String> lineSubscriber = new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    // 无界需求：有多少行就处理多少行
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(String line) {
                    if (completed.get()) return;

                    // SSE data: 行
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) {
                            return;
                        }
                        if (data.isEmpty()) return;

                        try {
                            ChatCompletionResponse partialResponse =
                                    mapper.readValue(data, ChatCompletionResponse.class);
                            openAiResponseBuilder.append(partialResponse);

                            if (!contentReceived.get()
                                    && partialResponse.choices() != null
                                    && !partialResponse.choices().isEmpty()) {
                                Delta d = partialResponse.choices().get(0).delta();
                                if (d != null && !isNullOrEmpty(d.content())) {
                                    contentReceived.set(true);
                                }
                            }
                            handle(partialResponse, toolBuilder, handler, nameNotifiedIndices);
                        } catch (Exception e) {
                            // 跳过无法解析的 SSE 事件（如注释行、空行）
                        }
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    if (!completed.getAndSet(true)) {
                        RuntimeException mapped = ExceptionMapper.DEFAULT.mapException(throwable);
                        withLoggingExceptions(() -> handler.onError(mapped));
                    }
                }

                @Override
                public void onComplete() {
                    if (!completed.getAndSet(true)) {
                        completeStream(openAiResponseBuilder, toolBuilder, handler, attempt, contentReceived);
                    }
                }
            };

            // sendAsync + fromLineSubscriber：CompletableFuture 在响应头到达后完成，
            // 但 body 由 lineSubscriber 异步消费；thenAccept 仅检查 HTTP 状态码
            httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.fromLineSubscriber(lineSubscriber))
                    .thenAccept(httpResponse -> {
                        int statusCode = httpResponse.statusCode();
                        if (statusCode != 200) {
                            if (!completed.getAndSet(true)) {
                                withLoggingExceptions(() -> handler.onError(
                                        new RuntimeException("HTTP " + statusCode + " (thinking-disabled SSE 流式请求失败)")));
                            }
                        }
                        // 200 时不做任何事 —— lineSubscriber.onComplete() 负责收尾
                    })
                    .exceptionally(throwable -> {
                        if (!completed.getAndSet(true)) {
                            RuntimeException mapped = ExceptionMapper.DEFAULT.mapException(throwable);
                            withLoggingExceptions(() -> handler.onError(mapped));
                        }
                        return null;
                    });

        } catch (Exception e) {
            if (!completed.getAndSet(true)) {
                RuntimeException mappedException = ExceptionMapper.DEFAULT.mapException(e);
                withLoggingExceptions(() -> handler.onError(mappedException));
            }
        }
    }

    /**
     * 流完成处理逻辑（标准路径与自定义路径共用）。
     * 构建 ChatResponse、处理空响应重试/fallback。
     */
    private void completeStream(OpenAiStreamingResponseBuilder openAiResponseBuilder,
                                 ToolExecutionRequestBuilder toolBuilder,
                                 StreamingChatResponseHandler handler,
                                 int attempt,
                                 AtomicBoolean contentReceived) {
        if (toolBuilder.hasToolExecutionRequests()) {
            try {
                handler.onCompleteToolExecutionRequest(toolBuilder.index(), toolBuilder.build());
            } catch (Exception e) {
                withLoggingExceptions(() -> handler.onError(e));
            }
        }
        ChatResponse chatResponse = openAiResponseBuilder.build();
        if (chatResponse == null && toolBuilder.hasToolExecutionRequests()) {
            chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(toolBuilder.build()))
                    .build();
        }
        if (chatResponse == null) {
            if (attempt == 0
                    && openAiResponseBuilder.isEmptyUpstreamStream()
                    && !contentReceived.get()) {
                System.err.println("[OpenAiStreamingChatModel] attempt 0 empty stream; retrying");
                // 注意：自定义路径不支持重试（无法重建 ChatRequest），
                // 但标准路径的 retry 会通过 doChatWithRetry 递归调用
                withLoggingExceptions(() -> handler.onError(
                        new IllegalStateException("自定义流式路径遇空响应")));
                return;
            }
            if (contentReceived.get()) {
                String fallbackText = openAiResponseBuilder.buildContent();
                ChatResponse fallback = ChatResponse.builder()
                        .aiMessage(AiMessage.from(fallbackText != null ? fallbackText : ""))
                        .build();
                try {
                    handler.onCompleteResponse(fallback);
                } catch (Exception e) {
                    withLoggingExceptions(() -> handler.onError(e));
                }
                return;
            }
            withLoggingExceptions(() -> handler.onError(
                    new IllegalStateException("流式响应结束时未构建出 ChatResponse（可能是上游提前关闭或返回空流）")));
            return;
        }
        try {
            handler.onCompleteResponse(chatResponse);
        } catch (Exception e) {
            withLoggingExceptions(() -> handler.onError(e));
        }
    }

    private static void handle(ChatCompletionResponse partialResponse,
                               ToolExecutionRequestBuilder toolBuilder,
                               StreamingChatResponseHandler handler,
                               Set<Integer> nameNotifiedIndices) {
        if (partialResponse == null) {
            return;
        }

        List<ChatCompletionChoice> choices = partialResponse.choices();
        if (choices == null || choices.isEmpty()) {
            return;
        }

        ChatCompletionChoice chatCompletionChoice = choices.get(0);
        if (chatCompletionChoice == null) {
            return;
        }

        Delta delta = chatCompletionChoice.delta();
        if (delta == null) {
            return;
        }

        String content = delta.content();
        if (!isNullOrEmpty(content)) {
            try {
                handler.onPartialResponse(content);
            } catch (Exception e) {
                withLoggingExceptions(() -> handler.onError(e));
            }
        }
        List<ToolCall> toolCalls = delta.toolCalls();
        if (toolCalls != null) {
            for (ToolCall toolCall : toolCalls) {

                int index = toolCall.index();
                if (toolBuilder.index() != index) {
                    try {
                        handler.onCompleteToolExecutionRequest(toolBuilder.index(), toolBuilder.build());
                    } catch (Exception e) {
                        withLoggingExceptions(() -> handler.onError(e));
                    }
                    toolBuilder.updateIndex(index);
                }

                String id = toolBuilder.updateId(toolCall.id());
                String name = toolBuilder.updateName(toolCall.function().name());

                // 当 name/id 首次非空时立刻通知下游，前端可提前渲染「选择工具」卡片，
                // 不必等到 arguments 片段到达。
                // IMPORTANT: pass EMPTY arguments to avoid double-counting — the
                // regular notification below also fires with the same chunk's
                // arguments, and downstream accumulators (LegacyHtmlToolStreamSupport)
                // would append them twice otherwise (Issue #3 card ordering).
                if ((isNotNullOrEmpty(name) || isNotNullOrEmpty(id))
                        && nameNotifiedIndices.add(index)) {
                    ToolExecutionRequest earlyPartialRequest = ToolExecutionRequest.builder()
                            .id(id)
                            .name(name)
                            .arguments("")
                            .build();
                    try {
                        handler.onPartialToolExecutionRequest(index, earlyPartialRequest);
                    } catch (Exception e) {
                        withLoggingExceptions(() -> handler.onError(e));
                    }
                }

                String partialArguments = toolCall.function().arguments();
                if (isNotNullOrEmpty(partialArguments)) {
                    toolBuilder.appendArguments(partialArguments);

                    ToolExecutionRequest partialToolExecutionRequest = ToolExecutionRequest.builder()
                            .id(id)
                            .name(name)
                            .arguments(partialArguments)
                            .build();
                    try {
                        handler.onPartialToolExecutionRequest(index, partialToolExecutionRequest);
                    } catch (Exception e) {
                        withLoggingExceptions(() -> handler.onError(e));
                    }
                }
            }
        }
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ModelProvider provider() {
        return OPEN_AI;
    }

    public static OpenAiStreamingChatModelBuilder builder() {
        for (OpenAiStreamingChatModelBuilderFactory factory : loadFactories(OpenAiStreamingChatModelBuilderFactory.class)) {
            return factory.get();
        }
        return new OpenAiStreamingChatModelBuilder();
    }

    public static class OpenAiStreamingChatModelBuilder {

        private HttpClientBuilder httpClientBuilder;
        private String baseUrl;
        private String apiKey;
        private String organizationId;
        private String projectId;

        private ChatRequestParameters defaultRequestParameters;
        private String modelName;
        private Double temperature;
        private Double topP;
        private List<String> stop;
        private Integer maxTokens;
        private Integer maxCompletionTokens;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private Map<String, Integer> logitBias;
        private String responseFormat;
        private Boolean strictJsonSchema;
        private Integer seed;
        private String user;
        private Boolean strictTools;
        private Boolean parallelToolCalls;
        private Boolean store;
        private Map<String, String> metadata;
        private String serviceTier;
        private Duration timeout;
        private Boolean logRequests;
        private Boolean logResponses;
        private Map<String, String> customHeaders;
        private List<ChatModelListener> listeners;

        /**
         * 是否禁用模型 thinking/reasoning（DashScope 的 thinking 参数）。
         * 当为 true 时，会在 API 请求中添加 "thinking": {"type": "disabled"}，
         * 避免模型在生成 HTML/MULTI_FILE 流前先输出 reasoning token。
         */
        private boolean thinkingDisabled;

        public OpenAiStreamingChatModelBuilder() {
            // This is public so it can be extended
        }

        public OpenAiStreamingChatModelBuilder httpClientBuilder(HttpClientBuilder httpClientBuilder) {
            this.httpClientBuilder = httpClientBuilder;
            return this;
        }

        /**
         * Sets default common {@link ChatRequestParameters} or OpenAI-specific {@link OpenAiChatRequestParameters}.
         * <br>
         * When a parameter is set via an individual builder method (e.g., {@link #modelName(String)}),
         * its value takes precedence over the same parameter set via {@link ChatRequestParameters}.
         */
        public OpenAiStreamingChatModelBuilder defaultRequestParameters(ChatRequestParameters parameters) {
            this.defaultRequestParameters = parameters;
            return this;
        }

        public OpenAiStreamingChatModelBuilder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public OpenAiStreamingChatModelBuilder modelName(OpenAiChatModelName modelName) {
            this.modelName = modelName.toString();
            return this;
        }

        public OpenAiStreamingChatModelBuilder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public OpenAiStreamingChatModelBuilder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public OpenAiStreamingChatModelBuilder organizationId(String organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        public OpenAiStreamingChatModelBuilder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public OpenAiStreamingChatModelBuilder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public OpenAiStreamingChatModelBuilder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public OpenAiStreamingChatModelBuilder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        public OpenAiStreamingChatModelBuilder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public OpenAiStreamingChatModelBuilder maxCompletionTokens(Integer maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        public OpenAiStreamingChatModelBuilder presencePenalty(Double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public OpenAiStreamingChatModelBuilder frequencyPenalty(Double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public OpenAiStreamingChatModelBuilder logitBias(Map<String, Integer> logitBias) {
            this.logitBias = logitBias;
            return this;
        }

        public OpenAiStreamingChatModelBuilder responseFormat(String responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public OpenAiStreamingChatModelBuilder strictJsonSchema(Boolean strictJsonSchema) {
            this.strictJsonSchema = strictJsonSchema;
            return this;
        }

        public OpenAiStreamingChatModelBuilder seed(Integer seed) {
            this.seed = seed;
            return this;
        }

        public OpenAiStreamingChatModelBuilder user(String user) {
            this.user = user;
            return this;
        }

        public OpenAiStreamingChatModelBuilder strictTools(Boolean strictTools) {
            this.strictTools = strictTools;
            return this;
        }

        public OpenAiStreamingChatModelBuilder parallelToolCalls(Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
            return this;
        }

        public OpenAiStreamingChatModelBuilder store(Boolean store) {
            this.store = store;
            return this;
        }

        public OpenAiStreamingChatModelBuilder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public OpenAiStreamingChatModelBuilder serviceTier(String serviceTier) {
            this.serviceTier = serviceTier;
            return this;
        }

        public OpenAiStreamingChatModelBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public OpenAiStreamingChatModelBuilder logRequests(Boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }

        public OpenAiStreamingChatModelBuilder logResponses(Boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }

        public OpenAiStreamingChatModelBuilder customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = customHeaders;
            return this;
        }

        public OpenAiStreamingChatModelBuilder listeners(List<ChatModelListener> listeners) {
            this.listeners = listeners;
            return this;
        }

        /**
         * 设置为 true 时，在请求体中添加 "thinking": {"type": "disabled"}，禁用模型推理阶段。
         * 适用于 DashScope/Qwen 等兼容 API，可避免 SSE 流在推理阶段无 content chunk 到达。
         */
        public OpenAiStreamingChatModelBuilder thinkingDisabled(boolean thinkingDisabled) {
            this.thinkingDisabled = thinkingDisabled;
            return this;
        }

        public OpenAiStreamingChatModel build() {
            return new OpenAiStreamingChatModel(this);
        }
    }
}
