package com.dbconverter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼（OpenAI 兼容接口）AI 服务
 * 支持 qwen-max（文本）+ qwen-vl-max（图片识别）
 */
@Slf4j
@Service
public class AnthropicApiService {

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String baseUrl;

    @Value("${anthropic.api.model:qwen-max-latest}")
    private String model;

    @Value("${anthropic.api.vision-model:qwen-vl-max-latest}")
    private String visionModel;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicApiService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 图片 OCR 识别（使用 qwen-vl-max 多模态模型）
     */
    public String recognizeImage(byte[] imageData, String mimeType) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            String dataUrl = "data:" + mimeType + ";base64," + base64Image;

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", visionModel);
            requestBody.put("max_tokens", 4096);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "user");

            ArrayNode content = objectMapper.createArrayNode();

            // 图片内容
            ObjectNode imageContent = objectMapper.createObjectNode();
            imageContent.put("type", "image_url");
            ObjectNode imageUrl = objectMapper.createObjectNode();
            imageUrl.put("url", dataUrl);
            imageContent.set("image_url", imageUrl);
            content.add(imageContent);

            // 文本提示
            ObjectNode textContent = objectMapper.createObjectNode();
            textContent.put("type", "text");
            textContent.put("text", "请识别图片中的SQL语句，只返回SQL代码，不要有其他说明文字。如果图片中没有SQL语句，请返回空字符串。");
            content.add(textContent);

            message.set("content", content);
            messages.add(message);
            requestBody.set("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            return callApi(jsonBody);
        } catch (Exception e) {
            log.error("图片OCR识别失败", e);
            throw new RuntimeException("图片OCR识别失败: " + e.getMessage());
        }
    }

    /**
     * AI 优化 SQL（使用 qwen-max 文本模型）
     */
    public String optimizeSql(String sourceSql, String targetDb) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 4096);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "user");

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

            message.put("content", prompt);
            messages.add(message);
            requestBody.set("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            return callApi(jsonBody);
        } catch (Exception e) {
            log.error("AI优化SQL失败", e);
            throw new RuntimeException("AI优化SQL失败: " + e.getMessage());
        }
    }

    /**
     * 调用百炼 OpenAI 兼容接口
     */
    private String callApi(String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("百炼 API 调用失败: {} - {}", response.code(), responseBody);
                throw new IOException("百炼 API 调用失败: " + response.code() + " - " + responseBody);
            }

            JsonNode responseJson = objectMapper.readTree(responseBody);

            // OpenAI 兼容格式：choices[0].message.content
            JsonNode choices = responseJson.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode messageNode = firstChoice.get("message");
                if (messageNode != null && messageNode.has("content")) {
                    return messageNode.get("content").asText();
                }
            }

            throw new IOException("百炼 API 返回格式异常: " + responseBody);
        }
    }
}
