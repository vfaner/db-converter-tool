package com.dbconverter.controller;

import com.dbconverter.common.Result;
import com.dbconverter.service.AiService;
import com.dbconverter.service.SqlConverter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api")
public class ManualController {

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