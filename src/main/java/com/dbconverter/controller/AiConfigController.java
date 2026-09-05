package com.dbconverter.controller;

import com.dbconverter.common.AiConfig;
import com.dbconverter.common.Result;
import com.dbconverter.service.AiConfigService;
import com.dbconverter.service.AiService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 配置管理接口
 * 所有厂商信息（协议、地址、模型、Key）均可在网页端自定义维护，
 * 支持内网私有化部署的模型服务
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-config")
public class AiConfigController {

    /** API Key 对外展示时的掩码 */
    private static final String MASK = "********";

    private final AiConfigService configService;
    private final AiService aiService;

    public AiConfigController(AiConfigService configService, AiService aiService) {
        this.configService = configService;
        this.aiService = aiService;
    }

    /**
     * 查询全部 AI 配置（API Key 脱敏）
     */
    @GetMapping
    public Result<List<AiConfig>> list() {
        List<AiConfig> configs = configService.list();
        List<AiConfig> masked = new ArrayList<>(configs.size());
        for (AiConfig c : configs) {
            masked.add(maskApiKey(c));
        }
        return Result.success(masked);
    }

    /**
     * 查询当前启用配置的运行状态（供其它页面判断 AI 功能是否可用）
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> status = new HashMap<>();
        Optional<AiConfig> active = configService.getActiveConfig();

        status.put("available", aiService.isAvailable());
        status.put("visionAvailable", aiService.isVisionAvailable());
        status.put("configFile", configService.getConfigFileLocation());

        if (active.isPresent()) {
            AiConfig c = active.get();
            status.put("activeName", c.getName());
            status.put("activeProtocol", c.getProtocol());
            status.put("activeModel", c.getModel());
            status.put("activeVisionModel", c.getVisionModel());
        }

        return Result.success(status);
    }

    /**
     * 新增配置
     */
    @PostMapping
    public Result<AiConfig> create(@RequestBody AiConfig config) {
        String error = validate(config, true);
        if (error != null) {
            return Result.error(400, error);
        }

        try {
            AiConfig created = configService.create(config);
            return Result.success(maskApiKey(created));
        } catch (Exception e) {
            log.error("新增 AI 配置失败", e);
            return Result.error("新增 AI 配置失败: " + e.getMessage());
        }
    }

    /**
     * 更新配置。apiKey 传空或掩码值时保留原 Key
     */
    @PutMapping("/{id}")
    public Result<AiConfig> update(@PathVariable("id") String id, @RequestBody AiConfig config) {
        String error = validate(config, false);
        if (error != null) {
            return Result.error(400, error);
        }

        // 前端回传掩码说明用户没改 Key，置空让 service 保留原值
        if (MASK.equals(config.getApiKey())) {
            config.setApiKey(null);
        }

        try {
            return configService.update(id, config)
                    .map(updated -> Result.success(maskApiKey(updated)))
                    .orElseGet(() -> Result.error(404, "配置不存在: " + id));
        } catch (Exception e) {
            log.error("更新 AI 配置失败", e);
            return Result.error("更新 AI 配置失败: " + e.getMessage());
        }
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable("id") String id) {
        if (configService.delete(id)) {
            return Result.success();
        }
        return Result.error(404, "配置不存在: " + id);
    }

    /**
     * 启用指定配置
     */
    @PostMapping("/{id}/activate")
    public Result<?> activate(@PathVariable("id") String id) {
        if (configService.activate(id)) {
            return Result.success();
        }
        return Result.error(404, "配置不存在: " + id);
    }

    /**
     * 连通性测试。
     * 传入 id 时测试已保存的配置；也可直接传入完整配置测试尚未保存的参数。
     */
    @PostMapping("/test")
    public Result<TestResponse> test(@RequestBody TestRequest request) {
        AiConfig config;

        if (request.getId() != null && !request.getId().trim().isEmpty()) {
            Optional<AiConfig> saved = configService.findById(request.getId());
            if (saved.isEmpty()) {
                return Result.error(404, "配置不存在: " + request.getId());
            }
            config = saved.get();
            // 允许用未保存的表单值覆盖已存配置进行测试
            applyOverrides(config, request);
        } else {
            config = new AiConfig();
            config.setProtocol(request.getProtocol());
            config.setBaseUrl(request.getBaseUrl());
            config.setApiKey(request.getApiKey());
            config.setModel(request.getModel());
            config.setMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 1024);
            config.setTimeout(request.getTimeout() != null ? request.getTimeout() : 60);
        }

        // 测试请求用较小的 token 上限，加快返回
        config.setMaxTokens(Math.min(config.getMaxTokens() == null ? 1024 : config.getMaxTokens(), 1024));

        long start = System.currentTimeMillis();
        TestResponse response = new TestResponse();
        try {
            String reply = aiService.testConnection(config);
            response.setSuccess(true);
            response.setReply(reply);
            response.setElapsedMs(System.currentTimeMillis() - start);
            response.setEndpoint(resolveEndpoint(config));
            return Result.success(response);
        } catch (Exception e) {
            log.warn("AI 配置测试失败: {}", e.getMessage());
            response.setSuccess(false);
            response.setMessage(rootMessage(e));
            response.setElapsedMs(System.currentTimeMillis() - start);
            response.setEndpoint(resolveEndpoint(config));
            // 业务上属于「测试完成但结果失败」，仍返回 200 由前端展示原因
            return Result.success(response);
        }
    }

    /**
     * 用表单中的临时值覆盖已保存配置（仅覆盖非空字段）
     */
    private void applyOverrides(AiConfig config, TestRequest request) {
        if (notBlank(request.getProtocol())) {
            config.setProtocol(request.getProtocol());
        }
        if (notBlank(request.getBaseUrl())) {
            config.setBaseUrl(request.getBaseUrl());
        }
        if (notBlank(request.getModel())) {
            config.setModel(request.getModel());
        }
        // 掩码表示沿用已保存的 Key
        if (notBlank(request.getApiKey()) && !MASK.equals(request.getApiKey())) {
            config.setApiKey(request.getApiKey());
        }
        if (request.getTimeout() != null && request.getTimeout() > 0) {
            config.setTimeout(request.getTimeout());
        }
    }

    /**
     * 校验必填项。isCreate 为 false 时允许 apiKey 为空（表示不修改）
     */
    private String validate(AiConfig config, boolean isCreate) {
        if (config == null) {
            return "配置内容不能为空";
        }
        if (!notBlank(config.getName())) {
            return "厂商名称不能为空";
        }
        if (!notBlank(config.getProtocol())) {
            return "协议类型不能为空";
        }
        String protocol = config.getProtocol().trim().toLowerCase();
        if (!"openai".equals(protocol) && !"anthropic".equals(protocol)) {
            return "协议类型只支持 openai 或 anthropic";
        }
        if (!notBlank(config.getBaseUrl())) {
            return "API 地址不能为空";
        }
        if (!config.getBaseUrl().trim().startsWith("http://")
                && !config.getBaseUrl().trim().startsWith("https://")) {
            return "API 地址必须以 http:// 或 https:// 开头";
        }
        if (!notBlank(config.getModel())) {
            return "文本模型不能为空";
        }
        if (isCreate && !notBlank(config.getApiKey())) {
            return "API Key 不能为空";
        }
        if (config.getMaxTokens() != null && (config.getMaxTokens() < 1 || config.getMaxTokens() > 200000)) {
            return "最大 Token 数需在 1 ~ 200000 之间";
        }
        if (config.getTemperature() != null
                && (config.getTemperature() < 0 || config.getTemperature() > 2)) {
            return "温度参数需在 0 ~ 2 之间";
        }
        if (config.getTimeout() != null && (config.getTimeout() < 5 || config.getTimeout() > 600)) {
            return "超时时间需在 5 ~ 600 秒之间";
        }
        return null;
    }

    /**
     * API Key 脱敏：有值时统一返回固定掩码，避免泄露长度等信息
     */
    private AiConfig maskApiKey(AiConfig config) {
        AiConfig copy = config.copy();
        copy.setApiKey(notBlank(copy.getApiKey()) ? MASK : "");
        return copy;
    }

    /**
     * 展示实际请求地址，便于用户确认路径拼接是否符合预期
     */
    private String resolveEndpoint(AiConfig config) {
        if (!notBlank(config.getBaseUrl())) {
            return "";
        }
        try {
            return aiService.buildEndpoint(config);
        } catch (Exception e) {
            return config.getBaseUrl();
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = cause.getClass().getSimpleName();
        }
        return msg;
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Data
    public static class TestRequest {
        private String id;
        private String protocol;
        private String baseUrl;
        private String apiKey;
        private String model;
        private Integer maxTokens;
        private Integer timeout;
    }

    @Data
    public static class TestResponse {
        private boolean success;
        private String reply;
        private String message;
        private String endpoint;
        private long elapsedMs;
    }
}
