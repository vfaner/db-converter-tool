package com.dbconverter.common;

import lombok.Data;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class ScanTask {
    private String taskId;
    private String path;
    private String targetDb;
    private int progress;
    private String status; // "running", "completed", "failed"
    private List<ConversionItem> items;
    private long startTime;
    private long endTime;

    public ScanTask(String taskId, String path, String targetDb) {
        this.taskId = taskId;
        this.path = path;
        this.targetDb = targetDb;
        this.progress = 0;
        this.status = "running";
        this.items = new CopyOnWriteArrayList<>();
        this.startTime = System.currentTimeMillis();
    }

    public void addProgress(int increment) {
        this.progress = Math.min(100, this.progress + increment);
    }

    public void addItem(ConversionItem item) {
        this.items.add(item);
    }

    public void complete() {
        this.status = "completed";
        this.progress = 100;
        this.endTime = System.currentTimeMillis();
    }

    public void fail() {
        this.status = "failed";
        this.endTime = System.currentTimeMillis();
    }
}
