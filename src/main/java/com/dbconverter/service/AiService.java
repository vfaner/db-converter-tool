package com.dbconverter.service;

import com.dbconverter.common.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 服务，支持 OpenAI 兼容协议与 Anthropic 原生协议
 * 运行时从 AiConfigService 读取当前启用的配置，无需重启即可切换厂商/模型
 */
@Slf4j
@Service
public class AiService {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * 截断提示。截断的 SQL 比没有结果更危险——它看起来像一条正常语句，
     * 拿去执行才发现少了半截，所以一律丢弃并显式报错。
     */
    private static final String TRUNCATED_MESSAGE =
            "AI 输出被「最大 Token 数」限制截断，结果不完整（已丢弃）。"
                    + "请在「AI 配置」里加大该值，或把 SQL 拆短后分次转换";
    private static final String OCR_PROMPT =
            "请识别图片中的SQL语句，只返回SQL代码，不要有其他说明文字。如果图片中没有SQL语句，请返回空字符串。";
    /** 匹配 ```sql ... ``` 或 ``` ... ``` 围栏（含语言标注） */
    private static final java.util.regex.Pattern CODE_FENCE_PATTERN =
            java.util.regex.Pattern.compile(
                    "```(?:\\w+)?\\s*\\n?([\\s\\S]*?)\\n?```",
                    java.util.regex.Pattern.DOTALL
            );

    private final AiConfigService configService;
    private final ObjectMapper objectMapper;

    /** 按超时时间缓存 HttpClient，避免每次请求都新建连接池 */
    private final ConcurrentHashMap<Integer, OkHttpClient> clientCache = new ConcurrentHashMap<>();

    public AiService(AiConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 剥离模型输出里的 Markdown 代码围栏。
     * 模型即使被要求"只返回SQL"，也常常回复 ```sql ... ``` ——
     * 这些反引号如果原样写进源码文件会直接破坏文件，必须在入口处清掉。
     *
     * @return 去掉围栏后的内容；入参为 null 时返回 null
     */
    public static String stripCodeFence(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!s.startsWith("```")) return s;

        java.util.regex.Matcher m = CODE_FENCE_PATTERN.matcher(s);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 只有开头围栏、没有闭合围栏（例如被 maxTokens 截断）
        int nl = s.indexOf('\n');
        if (nl < 0) return "";
        return s.substring(nl + 1).replaceAll("```\\s*$", "").trim();
    }

    /**
     * 图片 OCR 识别（需要配置视觉模型）
     */
    public String recognizeImage(byte[] imageData, String mimeType) {
        AiConfig config = requireActiveConfig();
        if (!config.hasVisionModel()) {
            throw new IllegalStateException(
                    "当前 AI 配置「" + config.getName() + "」未设置视觉模型，无法进行图片识别，请在「AI 配置」中补充视觉模型");
        }

        try {
            String jsonBody = config.isAnthropicProtocol()
                    ? buildAnthropicVisionRequest(config, imageData, mimeType)
                    : buildOpenAiVisionRequest(config, imageData, mimeType);
            return stripCodeFence(callApi(config, jsonBody));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片OCR识别失败", e);
            throw new RuntimeException("图片OCR识别失败: " + e.getMessage());
        }
    }

    /**
     * AI 优化 SQL
     */
    public String optimizeSql(String sourceSql, String targetDb) {
        AiConfig config = requireActiveConfig();
        String prompt = buildOptimizePrompt(sourceSql, targetDb);

        try {
            String jsonBody = config.isAnthropicProtocol()
                    ? buildAnthropicTextRequest(config, config.getModel(), prompt)
                    : buildOpenAiTextRequest(config, config.getModel(), prompt);
            return stripCodeFence(callApi(config, jsonBody));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI优化SQL失败", e);
            throw new RuntimeException("AI优化SQL失败: " + e.getMessage());
        }
    }

    /**
     * AI 优化 SQL —— 流式版本。
     *
     * <p>非流式调用有个协议层面的坑：服务端在整段生成完成之前<b>连响应头都不会发</b>，
     * 于是 OkHttp 的 readTimeout 语义被偷换成了"整段生成不许超过 N 秒"，
     * 前端也只能干等，既看不到进度，也分不清"模型慢"和"连接死了"——两者的报错一模一样，
     * 都是卡在 {@code Http2Stream.takeHeaders} 上精确超时。
     *
     * <p>改成流式后：响应头一两秒就到，readTimeout 变成"多久没有新数据才算断"，
     * 慢但活着的生成不会再被误杀，前端也能边收边显示。
     *
     * @param listener 每收到一段增量文本就回调一次；抛 IOException 可用于中断（例如前端已断开）
     * @return 完整输出（已剥离 Markdown 代码围栏）
     */
    public String optimizeSqlStreaming(String sourceSql, String targetDb, StreamListener listener) {
        AiConfig config = requireActiveConfig();
        String prompt = buildOptimizePrompt(sourceSql, targetDb);

        try {
            String jsonBody = config.isAnthropicProtocol()
                    ? buildAnthropicTextRequest(config, config.getModel(), prompt, true)
                    : buildOpenAiTextRequest(config, config.getModel(), prompt, true);
            return stripCodeFence(callApiStreaming(config, jsonBody, listener));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI流式优化SQL失败", e);
            throw new RuntimeException("AI优化SQL失败: " + describeFailure(e));
        }
    }

    /**
     * 增量文本回调
     */
    public interface StreamListener {
        void onDelta(String text) throws IOException;

        /**
         * 模型正在思考、还没开始输出正文时的进度通知（累计思考字数）。
         * 只有网关无视 thinking:disabled 时才会触发，默认忽略。
         */
        default void onThinking(int totalChars) throws IOException {
        }
    }

    /**
     * 拼接转换用的 prompt。
     * <p>
     * 这里的措辞是踩过坑改出来的。原来写的是"优化并转换为最佳兼容 SQL / 使用最佳实践 / 优化SQL性能"，
     * 结果模型把它当成了重写授权：一条 10 行的 CONNECT BY 查询被改成上百行、
     * 用 {@code @path := ...} 这类 MySQL 会话变量模拟递归（该写法在 8.0.13+ 已废弃，
     * 且赋值求值顺序未定义，结果不可靠），还自己往 {@code SELECT NVL(a,0) FROM t} 后面加 {@code LIMIT 1}。
     * <p>
     * 用户要的不是重写，是方言适配：函数替换、语法调整，其余原样。所以现在给的是最小改动约束。
     */
    String buildOptimizePrompt(String sourceSql, String targetDb) {
        return String.format("""
                你是数据库方言迁移专家。下面这条 SQL 已经过规则引擎初步转换，目标数据库是 %s。
                请检查并修正其中目标数据库无法正确执行的部分。

                最小改动原则（重要）：
                1. 只改目标数据库确实不支持的写法，其余一律原样保留
                2. 保留原有的缩进、换行、大小写、列顺序、别名和注释
                3. 不要增删列，不要添加 LIMIT / ORDER BY / WHERE 条件，不要调整表连接顺序
                4. 不要做性能优化、不要加索引提示、不要重构查询结构
                5. 如果整条语句已经可以在目标数据库正确执行，就原样返回，不要改动

                禁止事项：
                6. 禁止使用会话变量赋值（如 @x := ...）模拟逻辑——求值顺序未定义，结果不可靠
                7. 禁止拆成多条语句、禁止使用临时表、存储过程、游标
                8. MyBatis 占位符 #{...} 和 ${...} 必须逐字保留，不要替换成字面值或问号

                层次查询等无法直接翻译的语法，优先使用目标数据库支持的标准写法
                （例如 MySQL 8 / PostgreSQL 系用 WITH RECURSIVE），保持列名和输出结构与原语句一致。

                只返回 SQL 本身，不要解释、不要 Markdown 围栏。

                待处理 SQL：
                %s
                """, targetDb, sourceSql);
    }

    /**
     * 把底层异常翻成用户看得懂的话。裸 {@code timeout} 这种信息量为零的消息
     * 正是这次排查绕远路的原因，不能再原样丢给前端。
     */
    private String describeFailure(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "等待模型响应超时。可在「AI 配置」里加大超时时间，或缩短 SQL 后重试";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "域名解析失败，请检查 API 地址与网络: " + e.getMessage();
        }
        if (e instanceof java.net.ConnectException) {
            return "无法连接到 API 地址: " + e.getMessage();
        }
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    /**
     * 连通性测试：用指定配置发起一次最小请求
     *
     * @param config 待测试的配置（可以是尚未保存的临时配置）
     * @return 模型返回的文本内容
     */
    public String testConnection(AiConfig config) {
        validateConfig(config);
        try {
            String prompt = "请回复两个字：连接成功";
            String jsonBody = config.isAnthropicProtocol()
                    ? buildAnthropicTextRequest(config, config.getModel(), prompt)
                    : buildOpenAiTextRequest(config, config.getModel(), prompt);
            return callApi(config, jsonBody);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("测试失败: " + e.getMessage(), e);
        }
    }

    /**
     * 当前是否有可用的 AI 配置
     */
    public boolean isAvailable() {
        return configService.getActiveConfig()
                .filter(c -> c.getApiKey() != null && !c.getApiKey().trim().isEmpty())
                .filter(c -> c.getModel() != null && !c.getModel().trim().isEmpty())
                .isPresent();
    }

    /**
     * 当前启用的配置是否支持图片识别（需同时具备可用的 Key 与视觉模型）
     */
    public boolean isVisionAvailable() {
        return isAvailable() && configService.getActiveConfig()
                .filter(AiConfig::hasVisionModel)
                .isPresent();
    }

    private AiConfig requireActiveConfig() {
        Optional<AiConfig> active = configService.getActiveConfig();
        if (active.isEmpty()) {
            throw new IllegalStateException("尚未配置 AI 模型，请先在「AI 配置」页面添加并启用一个配置");
        }
        AiConfig config = active.get();
        validateConfig(config);
        return config;
    }

    private void validateConfig(AiConfig config) {
        if (config == null) {
            throw new IllegalStateException("AI 配置不能为空");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().trim().isEmpty()) {
            throw new IllegalStateException("AI 配置缺少 API 地址");
        }
        if (config.getModel() == null || config.getModel().trim().isEmpty()) {
            throw new IllegalStateException("AI 配置缺少模型名称");
        }
        if (config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            throw new IllegalStateException("AI 配置缺少 API Key");
        }
    }

    // ==================== 请求体构造 ====================

    /**
     * OpenAI 兼容协议 - 纯文本请求
     */
    private String buildOpenAiTextRequest(AiConfig config, String model, String prompt) throws IOException {
        return buildOpenAiTextRequest(config, model, prompt, false);
    }

    private String buildOpenAiTextRequest(AiConfig config, String model, String prompt, boolean stream)
            throws IOException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", config.getMaxTokens());
        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
        }
        if (stream) {
            requestBody.put("stream", true);
        }

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);

        return objectMapper.writeValueAsString(requestBody);
    }

    /**
     * OpenAI 兼容协议 - 图片识别请求
     */
    private String buildOpenAiVisionRequest(AiConfig config, byte[] imageData, String mimeType) throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(imageData);
        String dataUrl = "data:" + mimeType + ";base64," + base64Image;

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.getVisionModel());
        requestBody.put("max_tokens", config.getMaxTokens());
        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
        }

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        ObjectNode imageContent = objectMapper.createObjectNode();
        imageContent.put("type", "image_url");
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", dataUrl);
        imageContent.set("image_url", imageUrl);
        content.add(imageContent);

        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", OCR_PROMPT);
        content.add(textContent);

        message.set("content", content);
        messages.add(message);
        requestBody.set("messages", messages);

        return objectMapper.writeValueAsString(requestBody);
    }

    /**
     * Anthropic 原生协议 - 纯文本请求
     */
    private String buildAnthropicTextRequest(AiConfig config, String model, String prompt) throws IOException {
        return buildAnthropicTextRequest(config, model, prompt, false);
    }

    String buildAnthropicTextRequest(AiConfig config, String model, String prompt, boolean stream)
            throws IOException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", config.getMaxTokens());
        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
        }
        if (stream) {
            requestBody.put("stream", true);
        }
        // 显式关掉扩展思考。这不是可选的调优，是"页面转圈好几分钟"的根因：
        // 服务端默认开思考，实测同一条 SQL 会先流 2368 条 thinking_delta、思考 4371 字，
        // 315 秒之后才吐出第一个字的正文；关掉之后首字 1.87 秒、全程 8.7 秒。
        // SQL 方言转换是规则性改写，不需要模型长链推理。
        requestBody.set("thinking", objectMapper.createObjectNode().put("type", "disabled"));

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);

        return objectMapper.writeValueAsString(requestBody);
    }

    /**
     * Anthropic 原生协议 - 图片识别请求
     */
    private String buildAnthropicVisionRequest(AiConfig config, byte[] imageData, String mimeType) throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(imageData);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.getVisionModel());
        requestBody.put("max_tokens", config.getMaxTokens());
        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
        }

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        // Anthropic 图片格式：source.type=base64
        ObjectNode imageContent = objectMapper.createObjectNode();
        imageContent.put("type", "image");
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", mimeType);
        source.put("data", base64Image);
        imageContent.set("source", source);
        content.add(imageContent);

        ObjectNode textContent = objectMapper.createObjectNode();
        textContent.put("type", "text");
        textContent.put("text", OCR_PROMPT);
        content.add(textContent);

        message.set("content", content);
        messages.add(message);
        requestBody.set("messages", messages);

        return objectMapper.writeValueAsString(requestBody);
    }

    // ==================== 请求执行 ====================

    /**
     * 发起 API 调用并解析响应
     */
    private String callApi(AiConfig config, String jsonBody) throws IOException {
        Request request = buildRequest(config, jsonBody, false);
        OkHttpClient client = getHttpClient(config.getTimeout());

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("AI API 调用失败: {} - {}", response.code(), responseBody);
                throw new IOException("AI API 调用失败 (HTTP " + response.code() + "): " + extractErrorMessage(responseBody));
            }

            return config.isAnthropicProtocol()
                    ? parseAnthropicResponse(responseBody)
                    : parseOpenAiResponse(responseBody);
        }
    }

    /**
     * 发起流式调用，边收边回调，返回拼好的完整文本。
     *
     * <p>两种协议的 SSE 事件流不同，但都是「一行 {@code data:} 一个 JSON」的形状：
     * OpenAI 以 {@code data: [DONE]} 收尾，Anthropic 则靠 JSON 里的 {@code type} 字段区分事件。
     */
    private String callApiStreaming(AiConfig config, String jsonBody, StreamListener listener) throws IOException {
        Request request = buildRequest(config, jsonBody, true);
        OkHttpClient client = getHttpClient(config.getTimeout());

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("AI API 流式调用失败: {} - {}", response.code(), errorBody);
                throw new IOException("AI API 调用失败 (HTTP " + response.code() + "): " + extractErrorMessage(errorBody));
            }
            if (response.body() == null) {
                throw new IOException("AI 返回了空响应体");
            }

            BufferedSource source = response.body().source();
            StringBuilder full = new StringBuilder();
            String currentEvent = null;
            String line;
            int thinkingChars = 0;
            Boolean truncated = null;   // 是否因 max_tokens 被截断，null = 还没收到结束原因

            // "多久没有任何进展"的死线。
            //
            // 这里原来写的是"第一个字必须在 N 秒内到"，那是个会误杀的实现：模型开启扩展思考时，
            // 流上持续跑 thinking_delta（实测 2368 条、315 秒后才出正文），正文迟迟不来是正常的，
            // 旧逻辑却把这种请求当成服务端过载给砍了——用户看到的"120 秒超时"就是这么来的。
            // 现在思考增量同样算进展：只有连一个 thinking/text 增量都收不到才判超时。
            long deadlineNanos = TimeUnit.SECONDS.toNanos(resolveTimeout(config.getTimeout()));
            long lastProgressNanos = System.nanoTime();

            while ((line = source.readUtf8Line()) != null) {
                if (System.nanoTime() - lastProgressNanos > deadlineNanos) {
                    throw new IOException(String.format(
                            "已连上 AI 服务，但 %d 秒内没有收到任何增量内容。可稍后重试，"
                                    + "或在「AI 配置」里加大超时时间",
                            resolveTimeout(config.getTimeout())));
                }
                if (line.isEmpty()) {
                    currentEvent = null;   // 空行 = 一个 SSE 事件结束
                    continue;
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;              // 注释行（": ping"）之类，忽略
                }

                String data = line.substring("data:".length()).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                if ("error".equals(currentEvent)) {
                    throw new IOException("AI 返回错误: " + extractErrorMessage(data));
                }

                SseDelta delta = config.isAnthropicProtocol()
                        ? extractAnthropicDelta(data)
                        : extractOpenAiDelta(data);
                if (truncated == null) {
                    truncated = detectTruncation(config, data);
                }
                if (delta == null || delta.text() == null || delta.text().isEmpty()) {
                    continue;
                }
                lastProgressNanos = System.nanoTime();
                if (delta.thinking()) {
                    // 正常情况下请求里已带 thinking:disabled，走不到这儿。
                    // 但网关未必认这个字段（这个网关连 model 都是忽略的），所以留一条兜底：
                    // 把思考进度报给前端，让页面显示"思考中"而不是空白转圈。
                    thinkingChars += delta.text().length();
                    listener.onThinking(thinkingChars);
                    continue;
                }
                full.append(delta.text());
                listener.onDelta(delta.text());
            }

            if (full.length() == 0) {
                if (thinkingChars > 0) {
                    throw new IOException("AI 只输出了思考内容（" + thinkingChars + " 字）就结束了，没有给出正文；请重试");
                }
                throw new IOException("AI 未返回任何内容（流已结束但没有文本增量）");
            }
            // 截断的 SQL 比没有结果更危险：它看起来像一条正常语句，拿去执行才发现少了半截。
            // 必须显式报错，不能静默交付。
            if (Boolean.TRUE.equals(truncated)) {
                throw new IOException(String.format(
                        "AI 输出在 %d token 处被截断，结果不完整（已丢弃）。"
                                + "请在「AI 配置」里加大「最大 Token 数」，或把 SQL 拆短后分次转换",
                        config.getMaxTokens() == null ? 0 : config.getMaxTokens()));
            }
            return full.toString();
        }
    }

    /**
     * 从一条 SSE data 里判断生成是否被 max_tokens 截断。
     * 返回 null 表示这条数据里没有结束原因信息。
     */
    Boolean detectTruncation(AiConfig config, String data) {
        JsonNode node = readSseJson(data);
        if (node == null) {
            return null;
        }
        if (config.isAnthropicProtocol()) {
            // message_delta 里带 stop_reason；也兼容个别网关把它放在 message_stop 上
            JsonNode reason = node.path("delta").path("stop_reason");
            if (!reason.isTextual()) {
                reason = node.path("message").path("stop_reason");
            }
            return reason.isTextual() ? "max_tokens".equals(reason.asText()) : null;
        }
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode reason = choices.get(0).path("finish_reason");
        return reason.isTextual() ? "length".equals(reason.asText()) : null;
    }

    /**
     * 一条 SSE 增量。{@code thinking=true} 表示这是模型的思考过程而非最终正文，
     * 不能计入结果，但要算作"有进展"。
     */
    record SseDelta(String text, boolean thinking) {
    }

    /**
     * 从 Anthropic SSE 的一条 data 里取增量。
     * text_delta 是正文；thinking_delta 是思考过程，单独标记出来（既不能计入结果，也不能当成"没进展"）。
     */
    SseDelta extractAnthropicDelta(String data) throws IOException {
        JsonNode node = readSseJson(data);
        if (node == null) {
            return null;
        }
        String type = node.path("type").asText("");
        if ("error".equals(type)) {
            throw new IOException("AI 返回错误: " + extractErrorMessage(data));
        }
        if (!"content_block_delta".equals(type)) {
            return null;
        }
        JsonNode delta = node.path("delta");
        if (delta.hasNonNull("thinking")) {
            return new SseDelta(delta.get("thinking").asText(), true);
        }
        if (delta.hasNonNull("text")) {
            return new SseDelta(delta.get("text").asText(), false);
        }
        return null;               // signature_delta / tool_use 等，跳过
    }

    /**
     * 从 OpenAI SSE 的一条 data 里取增量。
     * 国产推理模型（DeepSeek-R1 一类）把思考放在 {@code reasoning_content} 里，一并识别。
     */
    SseDelta extractOpenAiDelta(String data) throws IOException {
        JsonNode node = readSseJson(data);
        if (node == null) {
            return null;
        }
        if (node.has("error")) {
            throw new IOException("AI 返回错误: " + extractErrorMessage(data));
        }
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode delta = choices.get(0).path("delta");
        JsonNode reasoning = delta.path("reasoning_content");
        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
            return new SseDelta(reasoning.asText(), true);
        }
        JsonNode content = delta.path("content");
        return content.isTextual() ? new SseDelta(content.asText(), false) : null;
    }

    /**
     * 单条 SSE data 解析。畸形 JSON 只跳过不中断整条流：
     * 网关插入的心跳/自定义行不该让一次已经生成到一半的请求整体失败。
     */
    private JsonNode readSseJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception e) {
            log.debug("跳过无法解析的 SSE 数据行: {}", truncate(data));
            return null;
        }
    }

    private Request buildRequest(AiConfig config, String jsonBody, boolean streaming) {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

        Request.Builder builder = new Request.Builder()
                .url(buildEndpoint(config))
                .post(body);

        if (streaming) {
            builder.addHeader("Accept", "text/event-stream");
        }

        // 两种协议的认证头不同
        if (config.isAnthropicProtocol()) {
            builder.addHeader("x-api-key", config.getApiKey());
            builder.addHeader("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.addHeader("Authorization", "Bearer " + config.getApiKey());
        }

        return builder.build();
    }

    /**
     * 拼接请求地址。兼容用户填写的多种形式：
     * - https://host                    → https://host/v1/chat/completions
     * - https://host/v1                 → https://host/v1/chat/completions
     * - https://host/v1/chat/completions → 原样使用
     */
    public String buildEndpoint(AiConfig config) {
        String baseUrl = config.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String path = config.isAnthropicProtocol() ? "/messages" : "/chat/completions";

        // 已包含完整路径，原样使用
        if (baseUrl.endsWith(path)) {
            return baseUrl;
        }
        // 已带版本号，只补接口路径
        if (baseUrl.endsWith("/v1") || baseUrl.matches(".*/v\\d+")) {
            return baseUrl + path;
        }
        return baseUrl + "/v1" + path;
    }

    private String parseOpenAiResponse(String responseBody) throws IOException {
        JsonNode responseJson = objectMapper.readTree(responseBody);
        JsonNode choices = responseJson.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            if ("length".equals(choices.get(0).path("finish_reason").asText(""))) {
                throw new IOException(TRUNCATED_MESSAGE);
            }
            JsonNode messageNode = choices.get(0).get("message");
            if (messageNode != null && messageNode.has("content")) {
                return messageNode.get("content").asText();
            }
        }
        throw new IOException("AI 返回格式异常（OpenAI 协议）: " + truncate(responseBody));
    }

    private String parseAnthropicResponse(String responseBody) throws IOException {
        JsonNode responseJson = objectMapper.readTree(responseBody);
        if ("max_tokens".equals(responseJson.path("stop_reason").asText(""))) {
            throw new IOException(TRUNCATED_MESSAGE);
        }
        JsonNode content = responseJson.get("content");
        if (content != null && content.isArray() && !content.isEmpty()) {
            // 取第一个 text 类型的内容块
            for (JsonNode block : content) {
                if (block.has("text")) {
                    return block.get("text").asText();
                }
            }
        }
        throw new IOException("AI 返回格式异常（Anthropic 协议）: " + truncate(responseBody));
    }

    /**
     * 从错误响应中提取可读信息
     */
    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode error = json.get("error");
            if (error != null) {
                if (error.has("message")) {
                    return error.get("message").asText();
                }
                return error.toString();
            }
            if (json.has("message")) {
                return json.get("message").asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，直接返回原文
        }
        return truncate(responseBody);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private OkHttpClient getHttpClient(Integer timeoutSeconds) {
        int timeout = resolveTimeout(timeoutSeconds);
        return clientCache.computeIfAbsent(timeout, t -> new OkHttpClient.Builder()
                .connectTimeout(Math.min(t, 60), TimeUnit.SECONDS)
                .readTimeout(t, TimeUnit.SECONDS)
                .writeTimeout(Math.min(t, 60), TimeUnit.SECONDS)
                // 连接池默认会把 HTTP/2 连接留 5 分钟复用。若这条连接被中间设备静默丢掉
                // （空闲久了、网络切换、机器休眠），复用时请求就进了黑洞，一直等到 readTimeout 才报错，
                // 报出来还是个信息量为零的 timeout。开 PING 让 OkHttp 自己发现连接已死并快速失败。
                .pingInterval(30, TimeUnit.SECONDS)
                .build());
    }

    /** 未配置或配错时回落到 120 秒 */
    private static int resolveTimeout(Integer timeoutSeconds) {
        return (timeoutSeconds == null || timeoutSeconds <= 0) ? 120 : timeoutSeconds;
    }
}
