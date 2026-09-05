package com.dbconverter.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * AI 模型厂商配置
 * 支持 OpenAI 兼容协议与 Anthropic 原生协议，所有字段均可自定义，
 * 便于在内网环境下对接私有化部署的模型服务（如 Ollama / vLLM / one-api 等）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiConfig {

    /** 配置唯一标识 */
    private String id;

    /** 厂商/配置名称，如「阿里云百炼」「内网 Ollama」 */
    private String name;

    /** 协议类型：openai / anthropic */
    private String protocol = "openai";

    /**
     * API 基础地址，例如：
     * - https://dashscope.aliyuncs.com/compatible-mode
     * - http://192.168.1.10:11434/v1
     * - https://api.anthropic.com
     */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 文本模型，用于 SQL 优化 */
    private String model;

    /** 多模态视觉模型，用于图片 OCR 识别（留空表示不支持图片识别） */
    private String visionModel;

    /** 最大输出 token 数 */
    private Integer maxTokens = 4096;

    /** 采样温度，null 表示不传该参数（部分私有化服务不支持） */
    private Double temperature;

    /** 请求超时时间（秒） */
    private Integer timeout = 120;

    /** 是否为当前启用的配置 */
    private boolean active = false;

    /** 备注说明 */
    private String remark;

    /**
     * 是否配置了可用的视觉模型
     */
    @JsonIgnore
    public boolean hasVisionModel() {
        return visionModel != null && !visionModel.trim().isEmpty();
    }

    /**
     * 是否为 Anthropic 原生协议
     */
    @JsonIgnore
    public boolean isAnthropicProtocol() {
        return "anthropic".equalsIgnoreCase(protocol);
    }

    /**
     * 创建一份副本（用于对外输出时脱敏，避免污染内存中的原始配置）
     */
    public AiConfig copy() {
        AiConfig c = new AiConfig();
        c.id = this.id;
        c.name = this.name;
        c.protocol = this.protocol;
        c.baseUrl = this.baseUrl;
        c.apiKey = this.apiKey;
        c.model = this.model;
        c.visionModel = this.visionModel;
        c.maxTokens = this.maxTokens;
        c.temperature = this.temperature;
        c.timeout = this.timeout;
        c.active = this.active;
        c.remark = this.remark;
        return c;
    }
}
