package com.dbconverter.service;

import com.dbconverter.common.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
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

        String prompt = String.format("""
                你是一个数据库SQL专家，精通各种数据库方言。请将以下SQL语句优化并转换为 %s 数据库的最佳兼容SQL。

                要求：
                1. 保持原有业务逻辑不变
                2. 使用目标数据库的最佳实践和函数
                3. 优化SQL性能
                4. 只返回转换后的SQL代码，不要有其他说明文字

                原始SQL：
                %s
                """, targetDb, sourceSql);

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
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", config.getMaxTokens());
        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
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
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", config.getMaxTokens());
        if (config.getTemperature() != null) {
            requestBody.put("temperature", config.getTemperature());
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
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

        Request.Builder builder = new Request.Builder()
                .url(buildEndpoint(config))
                .addHeader("Content-Type", "application/json")
                .post(body);

        // 两种协议的认证头不同
        if (config.isAnthropicProtocol()) {
            builder.addHeader("x-api-key", config.getApiKey());
            builder.addHeader("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.addHeader("Authorization", "Bearer " + config.getApiKey());
        }

        OkHttpClient client = getHttpClient(config.getTimeout());

        try (Response response = client.newCall(builder.build()).execute()) {
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
            JsonNode messageNode = choices.get(0).get("message");
            if (messageNode != null && messageNode.has("content")) {
                return messageNode.get("content").asText();
            }
        }
        throw new IOException("AI 返回格式异常（OpenAI 协议）: " + truncate(responseBody));
    }

    private String parseAnthropicResponse(String responseBody) throws IOException {
        JsonNode responseJson = objectMapper.readTree(responseBody);
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
        int timeout = (timeoutSeconds == null || timeoutSeconds <= 0) ? 120 : timeoutSeconds;
        return clientCache.computeIfAbsent(timeout, t -> new OkHttpClient.Builder()
                .connectTimeout(Math.min(t, 60), TimeUnit.SECONDS)
                .readTimeout(t, TimeUnit.SECONDS)
                .writeTimeout(Math.min(t, 60), TimeUnit.SECONDS)
                .build());
    }
}
