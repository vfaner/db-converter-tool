package com.dbconverter.service;

import com.dbconverter.common.ConversionItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖三类曾经导致"静默改坏 / 丢备份"的缺陷：
 * 备份被二次运行覆盖、匹配失败被当成成功、替换处数统计不准。
 */
class FileReplacerServiceTest {

    private final FileReplacerService replacer = new FileReplacerService(null);

    @TempDir
    Path tempDir;

    private Path writeFile(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private ConversionItem item(Path file, String source, String target) {
        return new ConversionItem(file.toString(), source, target, "测试转换", 0);
    }

    private String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ---------- 备份保护 ----------

    @Test
    @DisplayName("第二次改造不得覆盖 .bak，原始版本必须保留")
    void backupIsNotOverwrittenOnSecondRun() throws IOException {
        Path file = writeFile("Dao.java", "sql = \"SELECT a FROM t WHERE x > NOW()\";");
        Path backup = tempDir.resolve("Dao.java.bak");

        // 第一次：NOW() -> CURRENT_TIMESTAMP
        replacer.replaceItems(List.of(item(file, "NOW()", "CURRENT_TIMESTAMP")));
        assertTrue(read(backup).contains("NOW()"), "首次备份应是原始内容");
        assertTrue(read(file).contains("CURRENT_TIMESTAMP"));

        // 第二次：在已改造的文件上再做一次替换
        replacer.replaceItems(List.of(item(file, "SELECT a", "SELECT a, b")));
        assertTrue(read(file).contains("SELECT a, b"), "第二次替换应生效");
        assertTrue(read(backup).contains("NOW()"),
                "备份必须仍是最初的原始版本，否则回滚能力丢失: " + read(backup));
        assertFalse(read(backup).contains("SELECT a, b"),
                "备份不应包含第二次改造的内容");
    }

    @Test
    @DisplayName("一处都没命中时不应创建备份、不应改动文件")
    void noBackupWhenNothingMatched() throws IOException {
        Path file = writeFile("Mapper.xml", "<select>SELECT id FROM t WHERE id = #{id}</select>");
        String before = read(file);

        // 模拟扫描端归一化后的 sourceSql（#{id} 已变成 ?），原文里并不存在
        FileReplacerService.ReplaceResult result = replacer.replaceItems(
                List.of(item(file, "SELECT id FROM t WHERE id = ?", "SELECT id FROM t WHERE id = ? FETCH FIRST 1 ROWS ONLY")));

        assertFalse(Files.exists(tempDir.resolve("Mapper.xml.bak")),
                "没有任何改动就不该留下备份文件");
        assertEquals(before, read(file), "文件内容不应变化");
        assertEquals(0, result.getTotalReplacements());
    }

    // ---------- 匹配失败必须上报 ----------

    @Test
    @DisplayName("匹配失败的条目要计入 unmatched 并产生告警，不能算成功文件")
    void unmatchedItemsAreReportedNotSilentlySucceeded() throws IOException {
        Path file = writeFile("Mapper.xml", "<select>SELECT id FROM t WHERE id = #{id}</select>");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(
                List.of(item(file, "SELECT id FROM t WHERE id = ?", "SELECT id FROM t")));

        assertEquals(1, result.getUnmatchedItems(), "未命中条目应被计数");
        assertEquals(0, result.getSuccessFiles(), "没改动过的文件不算成功");
        assertEquals(1, result.getSkippedFiles(), "应计入跳过文件");
        assertTrue(result.hasWarnings(), "必须产生告警而非静默通过");
        assertFalse(result.hasErrors(), "这是告警不是异常");
        assertTrue(result.getWarnings().get(0).contains("Mapper.xml"),
                "告警应指明文件: " + result.getWarnings().get(0));
    }

    @Test
    @DisplayName("部分命中的文件：改动生效，同时仍上报未命中项")
    void partiallyMatchedFileReportsBothOutcomes() throws IOException {
        Path file = writeFile("Dao.java",
                "a = \"SELECT x FROM t\";\nb = \"SELECT y FROM t\\n WHERE z = 1\";");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                item(file, "SELECT x FROM t", "SELECT x FROM t2"),
                // 扫描端把 \n 转义清洗成了真实空格，原文里是反斜杠+n，匹配不上
                item(file, "SELECT y FROM t WHERE z = 1", "SELECT y FROM t2 WHERE z = 1")));

        assertEquals(1, result.getSuccessFiles(), "有实际改动，算成功文件");
        assertEquals(1, result.getUnmatchedItems(), "另一条未命中要上报");
        assertTrue(result.hasWarnings());
        assertTrue(read(file).contains("SELECT x FROM t2"), "命中的那条应已替换");
        assertTrue(read(file).contains("SELECT y FROM t\\n WHERE z = 1"),
                "未命中的那条应保持原样");
    }

    @Test
    @DisplayName("source 与 target 相同的条目不算未命中")
    void identicalSourceAndTargetIsNotUnmatched() throws IOException {
        Path file = writeFile("q.sql", "SELECT 1;");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(
                List.of(item(file, "SELECT 1;", "SELECT 1;")));

        assertEquals(0, result.getUnmatchedItems(), "本身无需改动，不是失败");
        assertFalse(result.hasWarnings());
        assertFalse(Files.exists(tempDir.resolve("q.sql.bak")));
    }

    // ---------- 计数准确性 ----------

    @Test
    @DisplayName("同一条目命中多处时，替换处数按实际出现次数统计")
    void countsEveryOccurrenceNotJustOne() throws IOException {
        Path file = writeFile("multi.sql", "SELECT NOW();\nSELECT NOW();\nSELECT NOW();");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(
                List.of(item(file, "NOW()", "CURRENT_TIMESTAMP")));

        assertEquals(3, result.getTotalReplacements(),
                "String.replace 是全局替换，计数必须反映真实改动处数");
        assertFalse(read(file).contains("NOW()"), "三处都应被替换");
    }

    @Test
    @DisplayName("文件不存在时计入 errors")
    void missingFileIsReportedAsError() {
        ConversionItem missing = new ConversionItem(
                tempDir.resolve("nope.sql").toString(), "a", "b", "测试转换", 0);

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(missing));

        assertTrue(result.hasErrors());
        assertEquals(0, result.getSuccessFiles());
    }

    // ---------- 按偏移替换 ----------

    private ConversionItem itemAt(Path file, String source, String target, int start, int end) {
        ConversionItem it = item(file, source, target);
        it.setStartOffset(start);
        it.setEndOffset(end);
        return it;
    }

    @Test
    @DisplayName("按偏移替换：XML 里的 #{} 占位符与缩进原样保留")
    void offsetReplaceKeepsPlaceholdersAndFormatting() throws IOException {
        String xml = "<select id=\"q\">\n"
                + "    SELECT id FROM t\n"
                + "    WHERE id = #{id}\n"
                + "    LIMIT 1\n"
                + "</select>";
        Path file = writeFile("Mapper.xml", xml);

        int start = xml.indexOf('\n') + 1;
        int end = xml.indexOf("</select>");
        String raw = xml.substring(start, end);

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, raw, raw.replace("LIMIT 1", "FETCH FIRST 1 ROWS ONLY"), start, end)));

        assertEquals(1, result.getTotalReplacements());
        assertEquals(0, result.getUnmatchedItems());
        String after = read(file);
        assertTrue(after.contains("WHERE id = #{id}"), "占位符必须保留: " + after);
        assertTrue(after.contains("    FETCH FIRST 1 ROWS ONLY\n"), "缩进必须保留: " + after);
        assertTrue(after.startsWith("<select id=\"q\">\n"), "标签必须完整");
        assertTrue(after.endsWith("</select>"), "标签必须完整");
    }

    @Test
    @DisplayName("同一文件多个区间：必须倒序拼接，后面的下标不能被前面的改动带偏")
    void multipleOffsetsInOneFileAllLandCorrectly() throws IOException {
        // 两处完全相同的 SQL，只能靠偏移区分；正序替换会让第二处下标错位
        String java = "String a = \"SELECT x LIMIT 1\";\nString b = \"SELECT x LIMIT 1\";";
        Path file = writeFile("D.java", java);

        int first = java.indexOf("SELECT x LIMIT 1");
        int second = java.indexOf("SELECT x LIMIT 1", first + 1);
        String src = "SELECT x LIMIT 1";
        String tgt = "SELECT x FETCH FIRST 1 ROWS ONLY";

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, src, tgt, first, first + src.length()),
                itemAt(file, src, tgt, second, second + src.length())));

        assertEquals(2, result.getTotalReplacements(), "两处都应替换");
        assertEquals(0, result.getUnmatchedItems());
        assertEquals("String a = \"" + tgt + "\";\nString b = \"" + tgt + "\";", read(file));
    }

    @Test
    @DisplayName("扫描后文件被改动导致偏移失效：拒绝写入并上报，不得创建备份")
    void staleOffsetsAreRefusedNotBlindlySpliced() throws IOException {
        Path file = writeFile("A.xml", "<!-- 扫描后新增的这一行让所有偏移前移 -->\n<t>SELECT x LIMIT 1</t>");
        String before = read(file);

        // 偏移按插入前的内容计算，现在指向错误的位置
        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, "SELECT x LIMIT 1", "SELECT x FETCH FIRST 1 ROWS ONLY", 3, 19)));

        assertEquals(1, result.getUnmatchedItems(), "区间内容不符应上报为未命中");
        assertEquals(0, result.getTotalReplacements());
        assertEquals(before, read(file), "文件不得被改动");
        assertFalse(Files.exists(tempDir.resolve("A.xml.bak")), "没有改动就不该留备份");
        assertTrue(result.hasWarnings());
    }

    @Test
    @DisplayName("区间越界（文件已被截短）时拒绝替换，不抛异常")
    void offsetBeyondFileLengthIsRefused() throws IOException {
        Path file = writeFile("B.sql", "SELECT 1;");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, "SELECT x LIMIT 1", "SELECT x", 100, 200)));

        assertEquals(1, result.getUnmatchedItems());
        assertEquals("SELECT 1;", read(file));
        assertFalse(result.hasErrors(), "越界应作为未命中处理，不是异常");
    }

    @Test
    @DisplayName("替换文本比原文多出双引号时拒绝写入，避免提前结束 Java 字面量")
    void replacementAddingDoubleQuoteIsRefused() throws IOException {
        String java = "String a = \"SELECT x\";";
        Path file = writeFile("C.java", java);
        int start = java.indexOf("SELECT x");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, "SELECT x", "SELECT \"x\"", start, start + 8)));

        assertEquals(1, result.getUnmatchedItems(), "结构不安全应上报");
        assertEquals(java, read(file), "源码不得被弄坏");
    }

    @Test
    @DisplayName("AI 压行使换行减少是安全的，应允许写入")
    void replacementWithFewerNewlinesIsAllowed() throws IOException {
        String java = "String a = \"\"\"\n    SELECT x\n    FROM t\n    \"\"\";";
        Path file = writeFile("E.java", java);
        int start = java.indexOf("\n    SELECT");
        int end = java.lastIndexOf("\"\"\";");
        String raw = java.substring(start, end);

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, raw, "SELECT x FROM t", start, end)));

        assertEquals(1, result.getTotalReplacements(), "减少换行不影响结构，应放行");
        assertTrue(read(file).contains("SELECT x FROM t"));
    }

    @Test
    @DisplayName("无偏移信息的条目（手工提交清单）仍走逐字匹配，且覆盖全部出现处")
    void itemsWithoutOffsetsStillFallBackToTextMatching() throws IOException {
        Path file = writeFile("multi.sql", "SELECT NOW();\nSELECT NOW();");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(
                List.of(item(file, "NOW()", "CURRENT_TIMESTAMP")));

        assertEquals(2, result.getTotalReplacements());
        assertFalse(read(file).contains("NOW()"));
    }

    @Test
    @DisplayName("带偏移与不带偏移的条目混在同一文件时都能生效")
    void offsetAndTextItemsCoexistInOneFile() throws IOException {
        String sql = "SELECT a FROM t LIMIT 1;\nSELECT NOW();";
        Path file = writeFile("mix.sql", sql);
        int start = sql.indexOf("LIMIT 1");

        FileReplacerService.ReplaceResult result = replacer.replaceItems(List.of(
                itemAt(file, "LIMIT 1", "FETCH FIRST 1 ROWS ONLY", start, start + 7),
                item(file, "NOW()", "CURRENT_TIMESTAMP")));

        assertEquals(0, result.getUnmatchedItems());
        assertEquals("SELECT a FROM t FETCH FIRST 1 ROWS ONLY;\nSELECT CURRENT_TIMESTAMP;",
                read(file));
    }
}
