package com.dbconverter.service;

import com.dbconverter.common.AiConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * AI 配置管理服务
 * 配置持久化到外部 JSON 文件（默认 ./config/ai-config.json），
 * 与 jar 包分离，重新部署不会丢失配置；首次启动时从 application.yml 读取默认值做初始化。
 */
@Slf4j
@Service
public class AiConfigService {

    /** 配置文件路径，可通过 app.ai.config-file 覆盖 */
    @Value("${app.ai.config-file:config/ai-config.json}")
    private String configFilePath;

    /** 以下为首次启动时的初始化默认值（兼容原有环境变量部署方式） */
    @Value("${anthropic.api.key:}")
    private String defaultApiKey;

    @Value("${anthropic.api.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String defaultBaseUrl;

    @Value("${anthropic.api.model:qwen-max-latest}")
    private String defaultModel;

    @Value("${anthropic.api.vision-model:qwen-vl-max-latest}")
    private String defaultVisionModel;

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 内存中的配置列表 */
    private List<AiConfig> configs = new ArrayList<>();

    public AiConfigService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadFromFile();
        if (configs.isEmpty()) {
            seedDefaultConfig();
        }
    }

    /**
     * 从文件加载配置
     */
    private void loadFromFile() {
        File file = resolveConfigFile();
        if (!file.exists()) {
            log.info("AI 配置文件不存在，将使用默认配置初始化: {}", file.getAbsolutePath());
            return;
        }
        try {
            List<AiConfig> loaded = objectMapper.readValue(file, new TypeReference<List<AiConfig>>() {});
            if (loaded != null) {
                configs = new ArrayList<>(loaded);
                log.info("已加载 {} 个 AI 配置: {}", configs.size(), file.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("读取 AI 配置文件失败，将忽略该文件: {}", file.getAbsolutePath(), e);
        }
    }

    /**
     * 首次启动时，用 application.yml 中的配置生成一条默认记录
     */
    private void seedDefaultConfig() {
        AiConfig config = new AiConfig();
        config.setId(UUID.randomUUID().toString());
        config.setName("阿里云百炼");
        config.setProtocol("openai");
        config.setBaseUrl(defaultBaseUrl);
        // 占位符视为未配置
        config.setApiKey(isPlaceholderKey(defaultApiKey) ? "" : defaultApiKey);
        config.setModel(defaultModel);
        config.setVisionModel(defaultVisionModel);
        config.setActive(true);
        config.setRemark("首次启动时根据 application.yml 自动生成，可自由修改或删除");

        configs.add(config);
        persist();
        log.info("已初始化默认 AI 配置: {}", config.getName());
    }

    private boolean isPlaceholderKey(String key) {
        return key == null || key.trim().isEmpty() || key.startsWith("YOUR_") || key.contains("YOUR_BAILIAN");
    }

    /**
     * 查询全部配置
     */
    public List<AiConfig> list() {
        lock.readLock().lock();
        try {
            List<AiConfig> result = new ArrayList<>(configs.size());
            for (AiConfig c : configs) {
                result.add(c.copy());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按 id 查询配置
     */
    public Optional<AiConfig> findById(String id) {
        lock.readLock().lock();
        try {
            return configs.stream()
                    .filter(c -> c.getId() != null && c.getId().equals(id))
                    .findFirst()
                    .map(AiConfig::copy);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取当前启用的配置
     */
    public Optional<AiConfig> getActiveConfig() {
        lock.readLock().lock();
        try {
            return configs.stream()
                    .filter(AiConfig::isActive)
                    .findFirst()
                    .map(AiConfig::copy);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 新增配置
     */
    public AiConfig create(AiConfig config) {
        lock.writeLock().lock();
        try {
            config.setId(UUID.randomUUID().toString());
            normalize(config);

            // 第一条配置或显式指定启用时，设为当前启用配置
            if (configs.isEmpty() || config.isActive()) {
                clearActiveFlag();
                config.setActive(true);
            }

            configs.add(config);
            persist();
            return config.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新配置。apiKey 为空时保留原有 Key（前端展示为掩码，不回传真实值）
     */
    public Optional<AiConfig> update(String id, AiConfig update) {
        lock.writeLock().lock();
        try {
            AiConfig existing = configs.stream()
                    .filter(c -> c.getId() != null && c.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                return Optional.empty();
            }

            existing.setName(update.getName());
            existing.setProtocol(update.getProtocol());
            existing.setBaseUrl(update.getBaseUrl());
            existing.setModel(update.getModel());
            existing.setVisionModel(update.getVisionModel());
            existing.setMaxTokens(update.getMaxTokens());
            existing.setTemperature(update.getTemperature());
            existing.setTimeout(update.getTimeout());
            existing.setRemark(update.getRemark());

            // 只有传入了新 Key 才覆盖，避免掩码值把真实 Key 冲掉
            if (update.getApiKey() != null && !update.getApiKey().trim().isEmpty()) {
                existing.setApiKey(update.getApiKey().trim());
            }

            if (update.isActive() && !existing.isActive()) {
                clearActiveFlag();
                existing.setActive(true);
            }

            normalize(existing);
            persist();
            return Optional.of(existing.copy());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除配置。若删除的是当前启用配置，则自动将第一条设为启用
     */
    public boolean delete(String id) {
        lock.writeLock().lock();
        try {
            AiConfig target = configs.stream()
                    .filter(c -> c.getId() != null && c.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return false;
            }

            boolean wasActive = target.isActive();
            configs.remove(target);

            if (wasActive && !configs.isEmpty()) {
                configs.get(0).setActive(true);
            }

            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 设置指定配置为当前启用
     */
    public boolean activate(String id) {
        lock.writeLock().lock();
        try {
            AiConfig target = configs.stream()
                    .filter(c -> c.getId() != null && c.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return false;
            }

            clearActiveFlag();
            target.setActive(true);
            persist();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void clearActiveFlag() {
        configs.forEach(c -> c.setActive(false));
    }

    /**
     * 规范化字段：去除首尾空格、补齐默认值
     */
    private void normalize(AiConfig config) {
        if (config.getName() != null) {
            config.setName(config.getName().trim());
        }
        if (config.getBaseUrl() != null) {
            // 去掉结尾斜杠，避免拼接出双斜杠
            String url = config.getBaseUrl().trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            config.setBaseUrl(url);
        }
        if (config.getModel() != null) {
            config.setModel(config.getModel().trim());
        }
        if (config.getVisionModel() != null) {
            config.setVisionModel(config.getVisionModel().trim());
        }
        if (config.getApiKey() != null) {
            config.setApiKey(config.getApiKey().trim());
        }
        if (config.getProtocol() == null || config.getProtocol().trim().isEmpty()) {
            config.setProtocol("openai");
        } else {
            config.setProtocol(config.getProtocol().trim().toLowerCase());
        }
        if (config.getMaxTokens() == null || config.getMaxTokens() <= 0) {
            config.setMaxTokens(4096);
        }
        if (config.getTimeout() == null || config.getTimeout() <= 0) {
            config.setTimeout(120);
        }
    }

    /**
     * 写入磁盘（先写临时文件再原子替换，避免写入中断导致配置损坏）
     */
    private void persist() {
        File file = resolveConfigFile();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.error("创建 AI 配置目录失败: {}", parent.getAbsolutePath());
                return;
            }

            Path target = file.toPath();
            Path tmp = Paths.get(file.getAbsolutePath() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), configs);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

            // 配置内含 API Key，尽量收紧文件权限（非 POSIX 文件系统会静默失败）
            restrictPermissions(file);
        } catch (IOException e) {
            log.error("保存 AI 配置失败: {}", file.getAbsolutePath(), e);
        }
    }

    private void restrictPermissions(File file) {
        try {
            if (!file.setReadable(false, false) || !file.setWritable(false, false)) {
                log.debug("收紧配置文件权限未完全生效: {}", file.getAbsolutePath());
            }
            file.setReadable(true, true);
            file.setWritable(true, true);
        } catch (Exception e) {
            log.debug("设置配置文件权限失败: {}", file.getAbsolutePath(), e);
        }
    }

    private File resolveConfigFile() {
        File file = new File(configFilePath);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(System.getProperty("user.dir"), configFilePath);
    }

    /**
     * 返回配置文件的绝对路径（供前端展示，便于运维排查）
     */
    public String getConfigFileLocation() {
        return resolveConfigFile().getAbsolutePath();
    }
}
