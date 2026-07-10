package com.dbconverter.service;

import com.dbconverter.common.ConversionItem;
import com.dbconverter.common.ScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileReplacerService {

    private final FileScannerService scannerService;

    @Autowired
    public FileReplacerService(FileScannerService scannerService) {
        this.scannerService = scannerService;
    }

    public ReplaceResult replaceByTaskId(String taskId) {
        ScanTask task = scannerService.getScanTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        if (!"completed".equals(task.getStatus())) {
            throw new IllegalStateException("任务尚未完成，当前状态: " + task.getStatus());
        }

        return replaceItems(task.getItems());
    }

    public ReplaceResult replaceItems(List<ConversionItem> items) {
        // 按文件分组
        Map<String, List<ConversionItem>> groupedByFile = items.stream()
                .collect(Collectors.groupingBy(ConversionItem::getFilePath));

        int totalFiles = 0;
        int successFiles = 0;
        int totalReplacements = 0;
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, List<ConversionItem>> entry : groupedByFile.entrySet()) {
            String filePath = entry.getKey();
            List<ConversionItem> fileItems = entry.getValue();
            totalFiles++;

            try {
                int replacements = replaceInFile(filePath, fileItems);
                totalReplacements += replacements;
                successFiles++;
            } catch (Exception e) {
                log.error("替换文件失败: {} - {}", filePath, e.getMessage());
                errors.add(filePath + ": " + e.getMessage());
            }
        }

        ReplaceResult result = new ReplaceResult();
        result.setTotalFiles(totalFiles);
        result.setSuccessFiles(successFiles);
        result.setTotalReplacements(totalReplacements);
        result.setErrors(errors);

        return result;
    }

    private int replaceInFile(String filePath, List<ConversionItem> items) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + filePath);
        }

        // 创建备份
        Path backupPath = Paths.get(filePath + ".bak");
        Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("已创建备份: {}", backupPath);

        // 读取文件内容
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String originalContent = content;

        // 执行替换
        int replacements = 0;
        for (ConversionItem item : items) {
            String sourceSql = item.getSourceSql();
            String targetSql = item.getTargetSql();

            if (sourceSql != null && targetSql != null && !sourceSql.equals(targetSql)) {
                // 尝试多种匹配方式
                if (content.contains(sourceSql)) {
                    content = content.replace(sourceSql, targetSql);
                    replacements++;
                } else {
                    // 尝试忽略大小写
                    String lowerContent = content.toLowerCase();
                    String lowerSource = sourceSql.toLowerCase();
                    int index = lowerContent.indexOf(lowerSource);
                    if (index >= 0) {
                        content = content.substring(0, index) + targetSql +
                                content.substring(index + sourceSql.length());
                        replacements++;
                    }
                }
            }
        }

        // 写回文件
        if (replacements > 0) {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info("文件已更新: {}，替换了 {} 处", filePath, replacements);
        }

        return replacements;
    }

    public static class ReplaceResult {
        private int totalFiles;
        private int successFiles;
        private int totalReplacements;
        private List<String> errors;

        public int getTotalFiles() {
            return totalFiles;
        }

        public void setTotalFiles(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        public int getSuccessFiles() {
            return successFiles;
        }

        public void setSuccessFiles(int successFiles) {
            this.successFiles = successFiles;
        }

        public int getTotalReplacements() {
            return totalReplacements;
        }

        public void setTotalReplacements(int totalReplacements) {
            this.totalReplacements = totalReplacements;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void setErrors(List<String> errors) {
            this.errors = errors;
        }

        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }
}
