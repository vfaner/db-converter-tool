package com.dbconverter.controller;

import com.dbconverter.common.ConversionItem;
import com.dbconverter.common.Result;
import com.dbconverter.common.ScanTask;
import com.dbconverter.service.FileReplacerService;
import com.dbconverter.service.FileScannerService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class AutoController {

    private final FileScannerService scannerService;
    private final FileReplacerService replacerService;

    @Autowired
    public AutoController(FileScannerService scannerService, FileReplacerService replacerService) {
        this.scannerService = scannerService;
        this.replacerService = replacerService;
    }

    /**
     * 启动扫描任务
     */
    @PostMapping("/scan")
    public Result<ScanResponse> scan(@RequestBody ScanRequest request) {
        if (request.getPath() == null || request.getPath().trim().isEmpty()) {
            return Result.error(400, "项目目录不能为空");
        }
        if (request.getTargetDb() == null || request.getTargetDb().trim().isEmpty()) {
            return Result.error(400, "目标数据库不能为空");
        }

        try {
            String taskId = scannerService.startScan(
                    request.getPath(), request.getTargetDb(), request.isEnableAi());
            ScanResponse response = new ScanResponse();
            response.setTaskId(taskId);
            response.setMessage(request.isEnableAi()
                    ? "扫描任务已启动（含 AI 优化）" : "扫描任务已启动");
            return Result.success(response);
        } catch (Exception e) {
            log.error("启动扫描任务失败", e);
            return Result.error("启动扫描任务失败: " + e.getMessage());
        }
    }

    /**
     * 查询扫描进度
     */
    @GetMapping("/scan/progress")
    public Result<ProgressResponse> getProgress(@RequestParam("taskId") String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            return Result.error(400, "任务ID不能为空");
        }

        ScanTask task = scannerService.getScanTask(taskId);
        if (task == null) {
            return Result.error(404, "任务不存在: " + taskId);
        }

        ProgressResponse response = new ProgressResponse();
        response.setTaskId(taskId);
        response.setProgress(task.getProgress());
        response.setStatus(task.getStatus());
        response.setItems(task.getItems());
        response.setPath(task.getPath());
        response.setTargetDb(task.getTargetDb());
        response.setEnableAi(task.isEnableAi());
        response.setPhase(task.getPhase());
        response.setAiTotal(task.getAiTotal());
        response.setAiDone(task.getAiDone());
        response.setAiApplied(task.getAiApplied());
        response.setAiFailed(task.getAiFailed());
        response.setAiSkipped(task.getAiSkipped());
        response.setAiMessage(task.getAiMessage());

        return Result.success(response);
    }

    /**
     * 执行替换
     */
    @PostMapping("/replace")
    public Result<ReplaceResponse> replace(@RequestBody ReplaceRequest request) {
        if (request.getTaskId() == null || request.getTaskId().trim().isEmpty()) {
            return Result.error(400, "任务ID不能为空");
        }

        try {
            FileReplacerService.ReplaceResult result = replacerService.replaceByTaskId(request.getTaskId());
            return Result.success(toReplaceResponse(result));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("执行替换失败", e);
            return Result.error("执行替换失败: " + e.getMessage());
        }
    }

    /**
     * 使用自定义清单执行替换
     */
    @PostMapping("/replace/custom")
    public Result<ReplaceResponse> replaceCustom(@RequestBody List<ConversionItem> items) {
        if (items == null || items.isEmpty()) {
            return Result.error(400, "替换清单不能为空");
        }

        try {
            FileReplacerService.ReplaceResult result = replacerService.replaceItems(items);
            return Result.success(toReplaceResponse(result));
        } catch (Exception e) {
            log.error("执行替换失败", e);
            return Result.error("执行替换失败: " + e.getMessage());
        }
    }

    private ReplaceResponse toReplaceResponse(FileReplacerService.ReplaceResult result) {
        ReplaceResponse response = new ReplaceResponse();
        response.setTotalFiles(result.getTotalFiles());
        response.setSuccessFiles(result.getSuccessFiles());
        response.setSkippedFiles(result.getSkippedFiles());
        response.setTotalReplacements(result.getTotalReplacements());
        response.setUnmatchedItems(result.getUnmatchedItems());
        response.setErrors(result.getErrors());
        response.setWarnings(result.getWarnings());
        response.setHasErrors(result.hasErrors());
        response.setHasWarnings(result.hasWarnings());
        return response;
    }

    /**
     * 列出指定路径下的子目录（用于前端文件夹选择器）
     * path 为空时返回用户主目录或根目录
     */
    @GetMapping("/directories")
    public Result<DirectoryListResponse> listDirectories(
            @RequestParam(value = "path", required = false) String path) {

        File dir;
        if (path == null || path.trim().isEmpty()) {
            // 默认：用户主目录
            dir = new File(System.getProperty("user.home"));
        } else {
            dir = new File(path.trim());
        }

        if (!dir.exists()) {
            return Result.error(404, "路径不存在: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            return Result.error(400, "不是目录: " + dir.getAbsolutePath());
        }

        DirectoryListResponse response = new DirectoryListResponse();
        response.setCurrentPath(dir.getAbsolutePath());
        response.setParentPath(dir.getParent());

        File[] subDirs = dir.listFiles(File::isDirectory);
        List<DirectoryItem> items = new ArrayList<>();

        if (subDirs != null) {
            Arrays.sort(subDirs, Comparator.comparing(f -> f.getName().toLowerCase()));
            for (File subDir : subDirs) {
                // 跳过隐藏目录
                if (subDir.getName().startsWith(".")) continue;
                DirectoryItem item = new DirectoryItem();
                item.setName(subDir.getName());
                item.setPath(subDir.getAbsolutePath());
                items.add(item);
            }
        }

        response.setDirectories(items);
        return Result.success(response);
    }

    @Data
    public static class DirectoryItem {
        private String name;
        private String path;
    }

    @Data
    public static class DirectoryListResponse {
        private String currentPath;
        private String parentPath;
        private List<DirectoryItem> directories;
    }

    @Data
    public static class ScanRequest {
        private String path;
        private String targetDb;
        /** 是否在规则转换之后再用 AI 优化一轮 */
        private boolean enableAi;
    }

    @Data
    public static class ScanResponse {
        private String taskId;
        private String message;
    }

    @Data
    public static class ProgressResponse {
        private String taskId;
        private int progress;
        private String status;
        private List<ConversionItem> items;
        private String path;
        private String targetDb;
        // AI 优化阶段信息
        private boolean enableAi;
        private String phase;
        private int aiTotal;
        private int aiDone;
        private int aiApplied;
        private int aiFailed;
        private int aiSkipped;
        private String aiMessage;
    }

    @Data
    public static class ReplaceRequest {
        private String taskId;
    }

    @Data
    public static class ReplaceResponse {
        private int totalFiles;
        private int successFiles;
        /** 一处都没命中、因而未做任何改动的文件数 */
        private int skippedFiles;
        private int totalReplacements;
        /** 未能在原文中定位的条目数（改造实际未生效） */
        private int unmatchedItems;
        private List<String> errors;
        /** 未命中条目的明细提示 */
        private List<String> warnings;
        private boolean hasErrors;
        private boolean hasWarnings;
    }
}
