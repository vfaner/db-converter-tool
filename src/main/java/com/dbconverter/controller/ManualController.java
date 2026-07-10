package com.dbconverter.controller;

import com.dbconverter.common.Result;
import com.dbconverter.service.AnthropicApiService;
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
    private final AnthropicApiService anthropicApiService;

    @Autowired
    public ManualController(SqlConverter sqlConverter, AnthropicApiService anthropicApiService) {
        this.sqlConverter = sqlConverter;
        this.anthropicApiService = anthropicApiService;
    }

    /**
     * OCR识别图片中的SQL
     */
    @PostMapping("/ocr")
    public Result<String> ocr(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "上传文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.error(400, "只支持图片文件（.png/.jpg/.jpeg/.bmp）");
        }

        try {
            byte[] imageData = file.getBytes();
            String mimeType = contentType;
            String sql = anthropicApiService.recognizeImage(imageData, mimeType);
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
            String optimizedSql = anthropicApiService.optimizeSql(convertedSql, request.getTargetDb());

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
