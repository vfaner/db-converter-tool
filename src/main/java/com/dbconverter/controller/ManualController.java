package com.dbconverter.controller;

import com.dbconverter.common.Result;
import com.dbconverter.service.AiService;
import com.dbconverter.service.SqlConverter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api")
public class ManualController {

    /** SSE 推送时长可达数分钟，不能占用 Tomcat 的请求线程 */
    private static final ExecutorService AI_STREAM_POOL =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ai-stream");
                t.setDaemon(true);
                return t;
            });

    private final SqlConverter sqlConverter;
    private final AiService aiService;

    @Autowired
    public ManualController(SqlConverter sqlConverter, AiService aiService) {
        this.sqlConverter = sqlConverter;
        this.aiService = aiService;
    }

    /**
     * OCR识别图片中的SQL
     */
    @PostMapping("/ocr")
    public Result<String> ocr(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "上传文件不能为空");
        }

        try {
            byte[] imageData = file.getBytes();
            // 不信任客户端声明的 Content-Type，按实际字节头判定真实图片类型
            String mimeType = detectImageMimeType(imageData);
            if (mimeType == null) {
                return Result.error(400, "只支持图片文件（.png/.jpg/.jpeg/.bmp/.gif/.webp）");
            }
            String sql = aiService.recognizeImage(imageData, mimeType);
            return Result.success(sql);
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            return Result.error("读取上传文件失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("OCR识别失败", e);
            return Result.error("OCR识别失败: " + e.getMessage());
        }
    }

    /**
     * 根据文件头魔数识别图片类型，无法识别时返回 null
     */
    static String detectImageMimeType(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (matches(data, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if (matches(data, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        // BMP: 42 4D
        if (matches(data, 0, 0x42, 0x4D)) {
            return "image/bmp";
        }
        // GIF: "GIF8"
        if (matches(data, 0, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        // WEBP: "RIFF" .... "WEBP"
        if (matches(data, 0, 0x52, 0x49, 0x46, 0x46) && matches(data, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean matches(byte[] data, int offset, int... signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 转换SQL
     */
    @PostMapping("/convert")
    public Result<ConvertResponse> convert(@RequestBody ConvertRequest request) {
        if (request.getSourceSql() == null || request.getSourceSql().trim().isEmpty()) {
            return Result.error(400, "源SQL不能为空");
        }
        if (request.getTargetDb() == null || request.getTargetDb().trim().isEmpty()) {
            return Result.error(400, "目标数据库不能为空");
        }

        try {
            String convertedSql = sqlConverter.convert(request.getSourceSql(), request.getTargetDb());
            ConvertResponse response = new ConvertResponse();
            response.setConvertedSql(convertedSql);
            response.setSourceSql(request.getSourceSql());
            response.setTargetDb(request.getTargetDb());
            return Result.success(response);
        } catch (Exception e) {
            log.error("SQL转换失败", e);
            return Result.error("SQL转换失败: " + e.getMessage());
        }
    }

    /**
     * AI优化SQL
     */
    @PostMapping("/optimize")
    public Result<ConvertResponse> optimize(@RequestBody ConvertRequest request) {
        if (request.getSourceSql() == null || request.getSourceSql().trim().isEmpty()) {
            return Result.error(400, "源SQL不能为空");
        }
        if (request.getTargetDb() == null || request.getTargetDb().trim().isEmpty()) {
            return Result.error(400, "目标数据库不能为空");
        }

        try {
            // 先进行基础转换
            String convertedSql = sqlConverter.convert(request.getSourceSql(), request.getTargetDb());

            // 再进行AI优化
            String optimizedSql = aiService.optimizeSql(convertedSql, request.getTargetDb());

            ConvertResponse response = new ConvertResponse();
            response.setConvertedSql(optimizedSql);
            response.setSourceSql(request.getSourceSql());
            response.setTargetDb(request.getTargetDb());
            response.setOptimized(true);
            return Result.success(response);
        } catch (Exception e) {
            log.error("AI优化SQL失败", e);
            return Result.error("AI优化SQL失败: " + e.getMessage());
        }
    }

    /**
     * AI优化SQL —— 流式（SSE）。
     *
     * <p>事件序列：{@code base}（基础转换结果，立刻可见）→ 若干 {@code delta} → {@code done}；
     * 出错则以 {@code error} 结束。客户端断开会让 emitter 抛异常，进而中断上游 AI 请求。
     */
    @PostMapping(value = "/optimize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter optimizeStream(@RequestBody ConvertRequest request) {
        // SSE 连接自身不设短超时：真正的"多久算断"由 AiService 的 readTimeout 判定，
        // 这里给一个足够宽的上限只是兜底，避免线程永久泄漏。
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onError(e -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));
        emitter.onCompletion(() -> clientGone.set(true));

        if (request.getSourceSql() == null || request.getSourceSql().trim().isEmpty()) {
            return failFast(emitter, "源SQL不能为空");
        }
        if (request.getTargetDb() == null || request.getTargetDb().trim().isEmpty()) {
            return failFast(emitter, "目标数据库不能为空");
        }

        AI_STREAM_POOL.execute(() -> {
            try {
                String convertedSql = sqlConverter.convert(request.getSourceSql(), request.getTargetDb());
                send(emitter, "base", Map.of("convertedSql", convertedSql));

                String optimized = aiService.optimizeSqlStreaming(convertedSql, request.getTargetDb(),
                        new AiService.StreamListener() {
                            @Override
                            public void onDelta(String delta) throws IOException {
                                abortIfClientGone();
                                send(emitter, "delta", Map.of("text", delta));
                            }

                            @Override
                            public void onThinking(int totalChars) throws IOException {
                                abortIfClientGone();
                                send(emitter, "thinking", Map.of("chars", totalChars));
                            }

                            private void abortIfClientGone() throws IOException {
                                if (clientGone.get()) {
                                    // 前端已经关掉/点了中断，没必要再让模型继续跑
                                    throw new IOException("客户端已断开");
                                }
                            }
                        });

                send(emitter, "done", Map.of("convertedSql", optimized));
                emitter.complete();
            } catch (Exception e) {
                if (clientGone.get()) {
                    log.debug("AI流式优化被客户端中断: {}", e.getMessage());
                    emitter.complete();
                    return;
                }
                log.error("AI流式优化SQL失败", e);
                Map<String, Object> payload = new HashMap<>();
                payload.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                try {
                    send(emitter, "error", payload);
                    emitter.complete();
                } catch (Exception sendFailed) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    private SseEmitter failFast(SseEmitter emitter, String message) {
        try {
            send(emitter, "error", Map.of("message", message));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Map<String, ?> data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    }

    /**
     * 获取支持的数据库列表
     */
    @GetMapping("/databases")
    public Result<?> getDatabases() {
        return Result.success(sqlConverter.getSupportedDatabases());
    }

    @Data
    public static class ConvertRequest {
        private String sourceSql;
        private String targetDb;
    }

    @Data
    public static class ConvertResponse {
        private String sourceSql;
        private String convertedSql;
        private String targetDb;
        private boolean optimized = false;
    }
}