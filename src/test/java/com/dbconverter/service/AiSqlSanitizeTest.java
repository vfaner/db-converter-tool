package com.dbconverter.service;

import com.dbconverter.common.ConversionItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 输出安全校验测试。
 * 这些用例守的是同一条底线：FileReplacerService 会把 targetSql 原样写回源文件，
 * 所以任何可能破坏 .java / .xml 语法的模型输出都必须被拒绝（返回 null → 保留规则转换结果）。
 */
class AiSqlSanitizeTest {

    private final FileScannerService scanner = new FileScannerService(null, null);

    private ConversionItem javaItem(String ruleSql) {
        ConversionItem item = new ConversionItem();
        item.setFilePath("/proj/src/main/java/com/demo/UserDao.java");
        item.setTargetSql(ruleSql);
        return item;
    }

    private ConversionItem sqlFileItem(String ruleSql) {
        ConversionItem item = new ConversionItem();
        item.setFilePath("/proj/src/main/resources/schema.sql");
        item.setTargetSql(ruleSql);
        return item;
    }

    // ================= 代码围栏 =================

    @Test
    @DisplayName("剥离 ```sql 围栏")
    void stripsSqlFence() {
        assertEquals("SELECT id FROM users",
                AiService.stripCodeFence("```sql\nSELECT id FROM users\n```"));
    }

    @Test
    @DisplayName("剥离无语言标注的围栏")
    void stripsPlainFence() {
        assertEquals("SELECT id FROM users",
                AiService.stripCodeFence("```\nSELECT id FROM users\n```"));
    }

    @Test
    @DisplayName("没有围栏时原样返回（去空白）")
    void keepsUnfencedText() {
        assertEquals("SELECT id FROM users",
                AiService.stripCodeFence("  SELECT id FROM users  "));
    }

    @Test
    @DisplayName("围栏未闭合（被截断）也能剥离")
    void stripsUnclosedFence() {
        assertEquals("SELECT id FROM users",
                AiService.stripCodeFence("```sql\nSELECT id FROM users"));
    }

    @Test
    @DisplayName("围栏输出经过 sanitize 后可用，且不残留反引号")
    void fencedOutputIsAccepted() {
        String result = scanner.sanitizeAiSql(
                "```sql\nSELECT id, name FROM users WHERE status = 1\n```",
                javaItem("SELECT id, name FROM users WHERE state = 1"));
        assertNotNull(result);
        assertFalse(result.contains("`"), "反引号必须被清除，否则会写坏源文件");
    }

    // ================= 破坏源码的输出必须被拒绝 =================

    @Test
    @DisplayName("java 文件中：多行输出被压成单行")
    void collapsesMultilineForJava() {
        String result = scanner.sanitizeAiSql(
                "SELECT id, name\nFROM users\nWHERE status = 1",
                javaItem("SELECT id, name FROM users WHERE state = 1"));
        assertNotNull(result);
        assertFalse(result.contains("\n"), "Java 字符串字面量内不能出现换行");
        assertEquals("SELECT id, name FROM users WHERE status = 1", result);
    }

    @Test
    @DisplayName("java 文件中：含裸双引号的输出被拒绝")
    void rejectsDoubleQuoteForJava() {
        assertNull(scanner.sanitizeAiSql(
                "SELECT id FROM users WHERE name = \"admin\"",
                javaItem("SELECT id FROM users WHERE name = 'admin'")),
                "裸双引号会提前结束 Java 字符串字面量");
    }

    @Test
    @DisplayName("java 文件中：含反斜杠的输出被拒绝")
    void rejectsBackslashForJava() {
        assertNull(scanner.sanitizeAiSql(
                "SELECT id FROM users WHERE path = 'C:\\tmp'",
                javaItem("SELECT id FROM users WHERE path = 'tmp'")));
    }

    @Test
    @DisplayName("残留反引号的输出被拒绝")
    void rejectsLeftoverBacktick() {
        assertNull(scanner.sanitizeAiSql(
                "SELECT `id` FROM `users` WHERE id = 1",
                javaItem("SELECT id FROM users WHERE id = 1")));
    }

    @Test
    @DisplayName("夹带解释性文字的输出被拒绝")
    void rejectsExplanatoryProse() {
        String verbose = "这条 SQL 的问题在于使用了 MySQL 专有语法，下面给出适配 GaussDB 的写法，"
                + "同时建议为 status 字段建立索引以提升查询性能，另外需要注意分页语法的差异，"
                + "GaussDB 使用 LIMIT ... OFFSET 或者 FETCH FIRST 语法。"
                + "SELECT id FROM users WHERE status = 1";
        assertNull(scanner.sanitizeAiSql(verbose,
                javaItem("SELECT id FROM users WHERE status = 1")));
    }

    @Test
    @DisplayName("原文已有的中文字面量允许保留（不能误杀）")
    void keepsChineseLiteralPresentInSource() {
        String result = scanner.sanitizeAiSql(
                "SELECT id FROM users WHERE name = '张三' AND status = 1",
                javaItem("SELECT id FROM users WHERE name = '张三' AND state = 1"));
        assertNotNull(result, "原文本来就带中文字面量，不应被当成解释性文字拒绝");
        assertTrue(result.contains("张三"));
    }

    @Test
    @DisplayName("空输出 / null 被拒绝")
    void rejectsEmpty() {
        ConversionItem item = javaItem("SELECT id FROM users WHERE id = 1");
        assertNull(scanner.sanitizeAiSql(null, item));
        assertNull(scanner.sanitizeAiSql("   ", item));
        assertNull(scanner.sanitizeAiSql("```sql\n```", item));
    }

    @Test
    @DisplayName("不是 SQL 的输出被拒绝")
    void rejectsNonSql() {
        assertNull(scanner.sanitizeAiSql("抱歉，我无法处理这个请求。",
                javaItem("SELECT id FROM users WHERE id = 1")));
    }

    // ================= 分号必须与原文一致 =================

    @Test
    @DisplayName("原文有分号时补回分号")
    void restoresTrailingSemicolon() {
        String result = scanner.sanitizeAiSql(
                "SELECT id FROM users WHERE status = 1",
                sqlFileItem("SELECT id FROM users WHERE state = 1;"));
        assertNotNull(result);
        assertTrue(result.endsWith(";"), "原文以分号结尾，替换结果也必须以分号结尾");
    }

    @Test
    @DisplayName("原文无分号时去掉多出的分号")
    void stripsExtraSemicolon() {
        String result = scanner.sanitizeAiSql(
                "SELECT id FROM users WHERE status = 1;",
                javaItem("SELECT id FROM users WHERE state = 1"));
        assertNotNull(result);
        assertFalse(result.endsWith(";"), "原文没有分号，不能多出一个");
    }

    @Test
    @DisplayName("分号以 sourceSql（真正被替换的原文）为准，而非规则结果")
    void alignsSemicolonWithSourceSql() {
        ConversionItem item = new ConversionItem();
        item.setFilePath("/proj/src/main/resources/schema.sql");
        // 文件里的原文带分号，但规则转换结果把分号丢了
        item.setSourceSql("SELECT id FROM orders WHERE amount > 100 LIMIT 5;");
        item.setTargetSql("SELECT id FROM orders WHERE amount > 100 FETCH FIRST 5 ROWS ONLY");

        String result = scanner.sanitizeAiSql(
                "SELECT id FROM orders WHERE amount > 100 FETCH FIRST 5 ROWS ONLY", item);
        assertNotNull(result);
        assertTrue(result.endsWith(";"),
                "原文以分号结尾，替换结果必须保留分号，否则 .sql 脚本里的语句会粘连");
    }

    // ================= .sql 文件保留多行 =================

    @Test
    @DisplayName(".sql 文件允许保留多行格式")
    void keepsMultilineForSqlFile() {
        String result = scanner.sanitizeAiSql(
                "SELECT id, name\nFROM users\nWHERE status = 1;",
                sqlFileItem("SELECT id, name FROM users WHERE state = 1;"));
        assertNotNull(result);
        assertTrue(result.contains("\n"), ".sql 文件里多行是安全的，应当保留");
    }
}
