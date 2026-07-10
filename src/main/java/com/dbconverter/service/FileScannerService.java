package com.dbconverter.service;

import com.dbconverter.common.ConversionItem;
import com.dbconverter.common.ScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class FileScannerService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".xml", ".properties", ".yml", ".yaml", ".sql"
    );

    // ========================
    // .sql 文件：匹配完整 SQL 语句（必须以 ; 结尾）
    // ========================
    private static final Pattern SQL_FILE_PATTERN = Pattern.compile(
            "(?i)\\b(SELECT|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE)\\b"
            + "[^;]{5,2000};",
            Pattern.DOTALL
    );

    // ========================
    // .java 文件：匹配字符串字面量（双引号 / Text Block）
    // ========================
    // 双引号字符串（支持转义 \"）
    private static final Pattern JAVA_STRING_PATTERN = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\""
    );
    // Java Text Block（"""..."""）
    private static final Pattern JAVA_TEXT_BLOCK_PATTERN = Pattern.compile(
            "\"\"\"(.*?)\"\"\"",
            Pattern.DOTALL
    );
    // @Query 注解
    private static final Pattern JAVA_QUERY_ANNOTATION_PATTERN = Pattern.compile(
            "@Query\\s*\\(\\s*(?:value\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL
    );

    // ========================
    // .xml 文件：MyBatis mapper 标签
    // ========================
    private static final Pattern MYBATIS_TAG_PATTERN = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ========================
    // .properties / .yml 文件
    // ========================
    private static final Pattern CONFIG_SQL_PATTERN = Pattern.compile(
            "(?i)(?:sql|query|statement|select|insert|update|delete)\\s*[:=]\\s*[\"']?(.+?)(?:[\"']?\\s*$|[\"']?\\s*\\n)",
            Pattern.MULTILINE
    );

    // ========================
    // SQL 有效性校验：判断一个字符串是否"像"真正的 SQL
    // ========================
    private static final Pattern HAS_SELECT_FROM = Pattern.compile(
            "(?i)\\bSELECT\\b.+\\bFROM\\b", Pattern.DOTALL);
    private static final Pattern HAS_INSERT_INTO = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\b", Pattern.DOTALL);
    private static final Pattern HAS_UPDATE_SET = Pattern.compile(
            "(?i)\\bUPDATE\\b.+\\bSET\\b", Pattern.DOTALL);
    private static final Pattern HAS_DELETE_FROM = Pattern.compile(
            "(?i)\\bDELETE\\s+FROM\\b", Pattern.DOTALL);
    private static final Pattern HAS_CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\b", Pattern.DOTALL);
    private static final Pattern HAS_ALTER_TABLE = Pattern.compile(
            "(?i)\\bALTER\\s+TABLE\\b", Pattern.DOTALL);

    // 假阳性过滤：包含这些内容说明不是真正的 SQL
    private static final Pattern FALSE_POSITIVE_INDICATORS = Pattern.compile(
            "(?i)\\bFUNCTION\\s*\\(\\s*\\)"           // FUNCTION() 占位符
            + "|\\bXXX+\\b"                             // XXX 占位符
            + "|\\bTODO\\b"                             // TODO 标记
            + "|\\bFIXME\\b"                            // FIXME 标记
            + "|\\$\\{[^}]+\\}"                         // ${variable} 模板变量（过多时）
            + "|logger\\."                              // 日志调用
            + "|log\\."                                 // 日志调用
            + "|System\\.out"                           // 打印语句
            + "|\\bthrow\\s+new\\b"                     // 异常抛出
            + "|\\bassertEquals\\b"                     // 测试断言
            + "|\\bassertThat\\b"                       // 测试断言
    );

    // 需要跳过的 Java 上下文（字符串前面的代码）
    private static final Set<String> SKIP_CONTEXT_KEYWORDS = Set.of(
            "logger.", "log.", "System.out", "System.err",
            "throw new", "assertEquals", "assertThat", "assertTrue",
            "assertFalse", "fail(", "MessageFormat", "String.format"
    );

    private final SqlConverter sqlConverter;
    private final Map<String, ScanTask> scanTasks = new ConcurrentHashMap<>();

    @Autowired
    public FileScannerService(SqlConverter sqlConverter) {
        this.sqlConverter = sqlConverter;
    }

    public String startScan(String path, String targetDb) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        ScanTask task = new ScanTask(taskId, path, targetDb);
        scanTasks.put(taskId, task);
        scanAsync(task);
        return taskId;
    }

    @Async
    public void scanAsync(ScanTask task) {
        try {
            Path rootPath = Paths.get(task.getPath());
            if (!Files.exists(rootPath)) {
                throw new IllegalArgumentException("目录不存在: " + task.getPath());
            }

            List<Path> files = new ArrayList<>();
            Files.walkFileTree(rootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 20,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString().toLowerCase();
                            // 跳过常见的非业务目录
                            String absPath = file.toString();
                            if (absPath.contains("/target/") || absPath.contains("/build/")
                                    || absPath.contains("/.git/") || absPath.contains("/node_modules/")
                                    || absPath.contains("/.idea/") || absPath.contains("/.mvn/")) {
                                return FileVisitResult.CONTINUE;
                            }
                            for (String ext : SUPPORTED_EXTENSIONS) {
                                if (name.endsWith(ext)) {
                                    files.add(file);
                                    break;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String dirName = dir.getFileName().toString();
                            if (dirName.startsWith(".") || dirName.equals("target")
                                    || dirName.equals("build") || dirName.equals("node_modules")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });

            int totalFiles = files.size();
            int processedFiles = 0;

            for (Path file : files) {
                try {
                    scanFile(file, task);
                } catch (Exception e) {
                    log.warn("扫描文件失败: {} - {}", file, e.getMessage());
                }
                processedFiles++;
                task.setProgress((processedFiles * 100) / totalFiles);
            }

            task.complete();
        } catch (Exception e) {
            log.error("扫描任务失败", e);
            task.fail();
        }
    }

    private void scanFile(Path file, ScanTask task) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String fileName = file.getFileName().toString().toLowerCase();

        List<String> sqls = new ArrayList<>();

        if (fileName.endsWith(".java")) {
            sqls.addAll(extractSqlFromJava(content));
        } else if (fileName.endsWith(".xml")) {
            sqls.addAll(extractSqlFromXml(content));
        } else if (fileName.endsWith(".properties") || fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            sqls.addAll(extractSqlFromConfig(content));
        } else if (fileName.endsWith(".sql")) {
            sqls.addAll(extractSqlFromSqlFile(content));
        }

        // 去重
        Set<String> seen = new HashSet<>();
        for (String sql : sqls) {
            String normalized = sql.trim().replaceAll("\\s+", " ");
            if (seen.contains(normalized)) continue;
            seen.add(normalized);

            try {
                String convertedSql = sqlConverter.convert(sql, task.getTargetDb());
                if (!sql.trim().equals(convertedSql.trim())) {
                    ConversionItem item = new ConversionItem(
                            file.toString(),
                            sql,
                            convertedSql,
                            determineConversionType(sql, convertedSql),
                            0
                    );
                    task.addItem(item);
                }
            } catch (Exception e) {
                log.debug("转换SQL失败（已跳过）: {} - {}", sql.substring(0, Math.min(60, sql.length())), e.getMessage());
            }
        }
    }

    // ============================================================
    //  Java 文件 SQL 提取
    // ============================================================
    private List<String> extractSqlFromJava(String content) {
        List<String> sqls = new ArrayList<>();

        // 1. @Query 注解中的 SQL（优先级最高、最可靠）
        Matcher queryMatcher = JAVA_QUERY_ANNOTATION_PATTERN.matcher(content);
        while (queryMatcher.find()) {
            String sql = cleanJavaString(queryMatcher.group(1));
            addIfValidSql(sql, sqls);
        }

        // 2. Text Block（"""..."""）
        Matcher textBlockMatcher = JAVA_TEXT_BLOCK_PATTERN.matcher(content);
        while (textBlockMatcher.find()) {
            String sql = textBlockMatcher.group(1).trim();
            // Text Block 中可能有 Java 字符串拼接的 + 号，简单清理
            sql = sql.replace("\"", "").replace("+", " ").replaceAll("\\s+", " ").trim();
            addIfValidSql(sql, sqls);
        }

        // 3. 普通双引号字符串
        Matcher stringMatcher = JAVA_STRING_PATTERN.matcher(content);
        while (stringMatcher.find()) {
            int start = stringMatcher.start();
            // 检查字符串前面的上下文，排除日志/断言等
            String contextBefore = content.substring(Math.max(0, start - 100), start);
            if (isSkipContext(contextBefore)) continue;

            String raw = stringMatcher.group(1);
            String sql = cleanJavaString(raw);
            addIfValidSql(sql, sqls);
        }

        return sqls;
    }

    /**
     * 检查字符串前面的上下文是否表明这不是 SQL
     */
    private boolean isSkipContext(String contextBefore) {
        for (String keyword : SKIP_CONTEXT_KEYWORDS) {
            if (contextBefore.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 清理 Java 字符串字面量中的转义和拼接
     */
    private String cleanJavaString(String raw) {
        if (raw == null) return "";
        // 还原常见转义
        String s = raw.replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\\\", "\\");
        // 去掉字符串拼接的 "+ 和 +"
        s = s.replaceAll("\"\\s*\\+\\s*\"", " ");
        // 合并空白
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    // ============================================================
    //  XML 文件 SQL 提取（MyBatis mapper）
    // ============================================================
    private List<String> extractSqlFromXml(String content) {
        List<String> sqls = new ArrayList<>();

        Matcher matcher = MYBATIS_TAG_PATTERN.matcher(content);
        while (matcher.find()) {
            String rawSql = matcher.group(2).trim();
            if (rawSql.length() < 10) continue;

            // 移除 CDATA 包裹
            rawSql = rawSql.replaceAll("<!\\[CDATA\\[", "").replaceAll("]]>", "");

            // 移除 MyBatis 动态标签，但保留里面的内容
            rawSql = rawSql.replaceAll("<if\\b[^>]*>", " ")
                    .replaceAll("</if>", " ")
                    .replaceAll("<where\\b[^>]*>", " WHERE ")
                    .replaceAll("</where>", " ")
                    .replaceAll("<set\\b[^>]*>", " SET ")
                    .replaceAll("</set>", " ")
                    .replaceAll("<choose\\b[^>]*>", " ")
                    .replaceAll("</choose>", " ")
                    .replaceAll("<when\\b[^>]*>", " ")
                    .replaceAll("</when>", " ")
                    .replaceAll("<otherwise\\b[^>]*>", " ")
                    .replaceAll("</otherwise>", " ")
                    .replaceAll("<foreach\\b[^>]*>", " ")
                    .replaceAll("</foreach>", " ")
                    .replaceAll("<trim\\b[^>]*>", " ")
                    .replaceAll("</trim>", " ")
                    .replaceAll("<include\\b[^>]*/?>", " ");

            // 移除其他 XML 标签
            rawSql = rawSql.replaceAll("<[^>]+>", " ");

            // MyBatis 参数 #{xxx} 和 ${xxx} 替换为占位符 ?
            rawSql = rawSql.replaceAll("#\\{[^}]*}", "?");
            rawSql = rawSql.replaceAll("\\$\\{[^}]*}", "?");

            // 合并空白
            rawSql = rawSql.replaceAll("\\s+", " ").trim();

            addIfValidSql(rawSql, sqls);
        }

        return sqls;
    }

    // ============================================================
    //  配置文件 SQL 提取
    // ============================================================
    private List<String> extractSqlFromConfig(String content) {
        List<String> sqls = new ArrayList<>();
        Matcher matcher = CONFIG_SQL_PATTERN.matcher(content);
        while (matcher.find()) {
            String sql = matcher.group(1).trim();
            // 去掉尾部引号和分号
            sql = sql.replaceAll("[\"']\\s*$", "").trim();
            addIfValidSql(sql, sqls);
        }
        return sqls;
    }

    // ============================================================
    //  .sql 文件 SQL 提取
    // ============================================================
    private List<String> extractSqlFromSqlFile(String content) {
        List<String> sqls = new ArrayList<>();

        // 先去掉 SQL 注释
        String cleaned = content
                .replaceAll("--[^\n]*", "")           // 单行注释
                .replaceAll("/\\*[\\s\\S]*?\\*/", ""); // 块注释

        Matcher matcher = SQL_FILE_PATTERN.matcher(cleaned);
        while (matcher.find()) {
            String sql = matcher.group().trim();
            addIfValidSql(sql, sqls);
        }
        return sqls;
    }

    // ============================================================
    //  SQL 有效性校验（核心过滤器）
    // ============================================================

    /**
     * 判断一个字符串是否是有效的、值得转换的 SQL 语句。
     * 只有通过校验的 SQL 才会被加入结果列表。
     */
    private void addIfValidSql(String sql, List<String> result) {
        if (sql == null) return;
        sql = sql.trim();

        // 1. 长度过滤
        if (sql.length() < 15) return;

        // 2. 假阳性过滤
        if (FALSE_POSITIVE_INDICATORS.matcher(sql).find()) return;

        // 3. 必须包含 SQL 关键字
        String upper = sql.toUpperCase();
        boolean hasKeyword = upper.contains("SELECT") || upper.contains("INSERT")
                || upper.contains("UPDATE") || upper.contains("DELETE")
                || upper.contains("CREATE TABLE") || upper.contains("ALTER TABLE")
                || upper.contains("DROP TABLE");
        if (!hasKeyword) return;

        // 4. 结构校验：SELECT 必须有 FROM，INSERT 必须有 INTO，UPDATE 必须有 SET，DELETE 必须有 FROM
        boolean validStructure = false;
        if (upper.contains("SELECT")) {
            // SELECT 语句：必须有 FROM（子查询除外，简单检查）
            // 特例：SELECT 1, SELECT COUNT(*) 等可以没有 FROM
            if (HAS_SELECT_FROM.matcher(sql).find()
                    || upper.matches(".*SELECT\\s+\\d+.*")
                    || upper.matches(".*SELECT\\s+\\w+\\s*\\(.*")) {
                validStructure = true;
            }
        }
        if (upper.contains("INSERT")) {
            if (HAS_INSERT_INTO.matcher(sql).find()) validStructure = true;
        }
        if (upper.contains("UPDATE") && !upper.contains("INSERT")) {
            if (HAS_UPDATE_SET.matcher(sql).find()) validStructure = true;
        }
        if (upper.startsWith("DELETE") || upper.contains("DELETE FROM")) {
            if (HAS_DELETE_FROM.matcher(sql).find()) validStructure = true;
        }
        if (HAS_CREATE_TABLE.matcher(sql).find()) validStructure = true;
        if (HAS_ALTER_TABLE.matcher(sql).find()) validStructure = true;

        if (!validStructure) return;

        // 5. 过滤纯控制流片段（IF/WHILE/BEGIN/END 开头、没有独立 DML 的）
        String trimmed = sql.trim();
        if (trimmed.toUpperCase().startsWith("IF") && !trimmed.toUpperCase().startsWith("IF EXISTS")) return;
        if (trimmed.toUpperCase().startsWith("WHILE")) return;
        if (trimmed.toUpperCase().startsWith("BEGIN")) return;
        if (trimmed.toUpperCase().startsWith("END")) return;

        // 6. 过滤含有过多 Java/代码痕迹的字符串
        long parenCount = sql.chars().filter(c -> c == '(').count();
        long closeParenCount = sql.chars().filter(c -> c == ')').count();
        if (Math.abs(parenCount - closeParenCount) > 2) return; // 括号严重不匹配

        // 7. 过滤含有明显 Java 代码片段的
        if (sql.contains("public ") || sql.contains("private ") || sql.contains("void ")
                || sql.contains("return ") || sql.contains("new ") || sql.contains(".get(")
                || sql.contains(".set(") || sql.contains("this.")) return;

        result.add(sql);
    }

    // ============================================================
    //  转换类型判定
    // ============================================================
    private String determineConversionType(String source, String target) {
        List<String> types = new ArrayList<>();
        String srcUpper = source.toUpperCase();
        String tgtUpper = target.toUpperCase();

        if (srcUpper.contains("LIMIT") && tgtUpper.contains("FETCH FIRST")) {
            types.add("语法转换(LIMIT→FETCH)");
        }
        if (srcUpper.contains("LIMIT") && tgtUpper.contains("ROWNUM")) {
            types.add("语法转换(LIMIT→ROWNUM)");
        }
        if (containsFunction(source, "IFNULL") && !containsFunction(target, "IFNULL")) {
            types.add("函数转换(IFNULL)");
        }
        if (containsFunction(source, "NOW") && !containsFunction(target, "NOW")) {
            types.add("函数转换(NOW)");
        }
        if (containsFunction(source, "DATE_FORMAT") && !containsFunction(target, "DATE_FORMAT")) {
            types.add("函数转换(DATE_FORMAT)");
        }
        if (containsFunction(source, "CONCAT") && !containsFunction(target, "CONCAT")) {
            types.add("函数转换(CONCAT)");
        }
        if (containsFunction(source, "SUBSTRING") && !containsFunction(target, "SUBSTRING")) {
            types.add("函数转换(SUBSTRING)");
        }
        if (types.isEmpty()) {
            types.add("SQL转换");
        }
        return String.join(", ", types);
    }

    private boolean containsFunction(String sql, String funcName) {
        return sql.toUpperCase().contains(funcName.toUpperCase() + "(");
    }

    public ScanTask getScanTask(String taskId) {
        return scanTasks.get(taskId);
    }

    public Map<String, ScanTask> getAllTasks() {
        return scanTasks;
    }
}
