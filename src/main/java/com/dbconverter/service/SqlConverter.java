package com.dbconverter.service;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.alter.Alter;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
public class SqlConverter {

    private final Map<String, DialectConfig> dialectConfigs = new HashMap<>();

    @PostConstruct
    public void init() {
        // 初始化方言配置
        dialectConfigs.put("gaussdb", createGaussDbConfig());
        dialectConfigs.put("dameng", createDamengConfig());
        dialectConfigs.put("kingbase", createKingbaseConfig());
        dialectConfigs.put("oceanbase", createOceanBaseConfig());
        dialectConfigs.put("tidb", createTiDbConfig());
        dialectConfigs.put("gbase", createGBaseConfig());
        dialectConfigs.put("shentong", createShenTongConfig());
    }

    public String convert(String sourceSql, String targetDb) {
        DialectConfig config = requireConfig(sourceSql, targetDb);

        String converted;
        try {
            // 尝试使用 JSqlParser 解析
            Statement statement = CCJSqlParserUtil.parse(sourceSql);
            converted = convertStatement(statement, config);
        } catch (JSQLParserException e) {
            log.warn("JSqlParser 解析失败，使用正则降级: {}", e.getMessage());
            converted = convertByRegex(sourceSql, config);
        }

        return preserveStatementTerminator(sourceSql, converted);
    }

    /**
     * 保形转换：只做局部的函数/类型/语法替换，**不经过 JSqlParser 重排**。
     * <p>
     * 自动改造要把结果原样写回源文件，因此必须保住原文的一切细节——换行、缩进、
     * MyBatis 的 {@code #{id}}、XML 标签、Java 字符串里的 {@code \n} 转义。
     * 走 {@link #convert} 的话 {@code statement.toString()} 会把这些全部抹平，
     * 写回去等于毁掉 mapper 和 Java 源码。
     * <p>
     * 注意 {@code convertStatement} 本身也只是"toString 之后套同一批正则"，
     * AST 并未参与实际改写，所以这里跳过解析不会损失任何转换能力。
     */
    public String convertPreservingText(String rawSql, String targetDb) {
        DialectConfig config = requireConfig(rawSql, targetDb);

        String result = rawSql;
        result = convertFunctions(result, config);
        result = convertTypes(result, config);
        result = convertSyntax(result, config);
        return result;
    }

    private DialectConfig requireConfig(String sourceSql, String targetDb) {
        if (sourceSql == null || sourceSql.trim().isEmpty()) {
            throw new IllegalArgumentException("源SQL不能为空");
        }
        if (targetDb == null || targetDb.trim().isEmpty()) {
            throw new IllegalArgumentException("目标数据库不能为空");
        }

        DialectConfig config = dialectConfigs.get(targetDb.toLowerCase().trim());
        if (config == null) {
            throw new IllegalArgumentException("不支持的目标数据库: " + targetDb);
        }
        return config;
    }

    /**
     * 还原语句结尾的分号。
     * <p>
     * JSqlParser 的 {@code Statement.toString()} 不会输出结尾分号，
     * 而自动处理是把转换结果原样替换回原文的——原文的 {@code ;} 一旦丢失，
     * .sql 脚本里相邻的两条语句就会粘连成一条。
     * <p>
     * 正则降级路径本身会保留分号，所以这里只在"原文有、结果没有"时补，保证幂等。
     */
    private String preserveStatementTerminator(String sourceSql, String converted) {
        if (converted == null) return null;
        if (!sourceSql.trim().endsWith(";")) return converted;

        String trimmedEnd = converted.stripTrailing();
        if (trimmedEnd.endsWith(";")) return converted;
        return trimmedEnd + ";";
    }

    private String convertStatement(Statement statement, DialectConfig config) {
        String sql = statement.toString();

        // 函数替换
        sql = convertFunctions(sql, config);

        // 类型替换
        sql = convertTypes(sql, config);

        // 语法替换（如 LIMIT）
        sql = convertSyntax(sql, config);

        return sql;
    }

    private String convertFunctions(String sql, DialectConfig config) {
        String result = sql;

        for (Map.Entry<String, String> entry : config.functions.entrySet()) {
            String sourceFunc = entry.getKey().toUpperCase();
            String targetFunc = entry.getValue();

            // 处理带参数的函数
            if (targetFunc.contains("{0}")) {
                // 带参数的函数，如 DATE_FORMAT -> TO_CHAR({0}, {1})
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(([^)]+)\\)",
                    Pattern.CASE_INSENSITIVE
                );
                result = replaceOutsideLiterals(result, pattern, m -> {
                    String[] argParts = m.group(1).split(",", -1);
                    String replacement = targetFunc;
                    for (int i = 0; i < argParts.length; i++) {
                        replacement = replacement.replace("{" + i + "}", argParts[i].trim());
                    }
                    return replacement;
                });
            } else {
                // 零参数调用（如 NOW()）：目标若是不带括号的关键字，必须把括号一起消掉。
                // 否则会产出 CURRENT_TIMESTAMP() / SYSDATE() —— 这在 PG 系和 Oracle 系都是非法语法。
                if (!targetFunc.contains("(")) {
                    Pattern noArgPattern = Pattern.compile(
                        "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(\\s*\\)",
                        Pattern.CASE_INSENSITIVE
                    );
                    result = replaceOutsideLiterals(result, noArgPattern, targetFunc);
                }

                // 带参数调用：只换函数名，保留括号和参数
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(",
                    Pattern.CASE_INSENSITIVE
                );
                result = replaceOutsideLiterals(result, pattern, targetFunc + "(");
            }
        }

        return result;
    }

    private String convertTypes(String sql, DialectConfig config) {
        if (config.types == null || config.types.isEmpty()) {
            return sql;
        }

        boolean[] masked = maskLiterals(sql);
        boolean[] typeSlot = collectTypeSlots(sql, masked);

        Matcher matcher = config.typePattern().matcher(sql);
        StringBuilder out = new StringBuilder(sql.length());
        int last = 0;
        boolean changed = false;

        while (matcher.find()) {
            if (masked[matcher.start()]) continue;      // 字面量/注释/占位符内
            if (!typeSlot[matcher.start()]) continue;   // 不在类型声明位置 => 这是标识符
            String target = config.types.get(matcher.group().toUpperCase(Locale.ROOT));
            if (target == null) continue;

            out.append(sql, last, matcher.start()).append(target);
            last = matcher.end();
            changed = true;
        }

        if (!changed) return sql;
        out.append(sql, last, sql.length());
        return out.toString();
    }

    // ==================== 类型声明位置识别 ====================
    //
    // TEXT / INT / DATE / DATETIME / DOUBLE 既是类型名，也是极常见的列名。
    // 只靠"是不是在字面量里"无法区分，于是 SELECT text FROM notes 会被改成
    // SELECT CLOB FROM notes —— 列不存在，查询直接失败。
    //
    // 因此改为白名单：只有落在**类型声明槽位**上的词才替换，其余一律视为标识符。
    // 覆盖 CREATE TABLE 列定义、CAST(x AS t)、PostgreSQL 的 x::t、ALTER TABLE 的
    // ADD / MODIFY / CHANGE / ALTER COLUMN 四种子句。
    //
    // 这里用词法扫描而非 JSqlParser AST，原因有两个（都已实测确认）：
    //   1. JSqlParser 4.9 的 ColumnDefinition / ColDataType 没有实现 ASTNodeAccess，
    //      Token 也只有行列号、没有绝对字符偏移，无法把类型节点映射回原文位置——
    //      而"按偏移写回原文"是保形改造的前提。
    //   2. 含 #{} / 动态标签的 MyBatis 片段根本无法被解析，恰好是保形改造的主场景。

    /** 标记出每个字符是否属于"类型声明槽位"。 */
    private boolean[] collectTypeSlots(String sql, boolean[] masked) {
        boolean[] slot = new boolean[sql.length()];
        markCreateTableTypeSlots(sql, masked, slot);
        markCastTypeSlots(sql, masked, slot);
        markCastOperatorTypeSlots(sql, masked, slot);
        markAlterTableTypeSlots(sql, masked, slot);
        markConvertTypeSlots(sql, masked, slot);
        markDeclareTypeSlots(sql, masked, slot);
        return slot;
    }

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "\\bCREATE\\s+(?:(?:GLOBAL|LOCAL|TEMP|TEMPORARY|UNLOGGED|EXTERNAL)\\s+)*TABLE\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE_PATTERN =
            Pattern.compile("\\bALTER\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_CLAUSE_PATTERN = Pattern.compile(
            "\\b(ADD|MODIFY|CHANGE|ALTER)\\s+(?:COLUMN\\s+)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern CAST_PATTERN =
            Pattern.compile("\\b(?:CAST|TRY_CAST|SAFE_CAST)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 表级约束：这些开头的项不是列定义，第二个词也就不是类型。 */
    private static final Set<String> TABLE_LEVEL_CONSTRAINTS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN",
            "CHECK", "FULLTEXT", "SPATIAL", "EXCLUDE", "PERIOD");

    private void markCreateTableTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int open = indexOfUnmasked(sql, '(', matcher.end(), sql.length(), masked);
            if (open < 0) continue;
            // CREATE TABLE x AS SELECT ...：括号里是查询而不是列定义
            if (containsKeyword(sql, matcher.end(), open, "AS", masked)) continue;

            int close = matchingParen(sql, open, masked);
            if (close < 0) continue;
            markColumnDefinitionTypes(sql, open + 1, close, masked, slot);
        }
    }

    /** 按顶层逗号切开列定义列表，逐项标记类型槽位。 */
    private void markColumnDefinitionTypes(String sql, int start, int end,
                                           boolean[] masked, boolean[] slot) {
        for (int[] item : splitTopLevel(sql, start, end, masked, ',')) {
            markColumnType(sql, item[0], item[1], masked, slot);
        }
    }

    /** 单个列定义形如 {@code name TYPE(n) [约束...]}：跳过列名，标记第二个词。 */
    private void markColumnType(String sql, int start, int end, boolean[] masked, boolean[] slot) {
        int nameStart = skipWhitespace(sql, start, end);
        int nameEnd = tokenEnd(sql, nameStart, end);
        if (nameEnd <= nameStart) return;

        String firstWord = sql.substring(nameStart, nameEnd).toUpperCase(Locale.ROOT);
        if (TABLE_LEVEL_CONSTRAINTS.contains(firstWord)) return;

        markToken(sql, nameEnd, end, masked, slot);
    }

    private void markCastTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = CAST_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int open = matcher.end() - 1;
            int close = matchingParen(sql, open, masked);
            if (close < 0) continue;

            // 只认 CAST 括号内顶层的 AS；SELECT a AS text 那种别名里的 AS 不会走到这里
            int afterAs = lastTopLevelKeywordEnd(sql, open + 1, close, "AS", masked);
            if (afterAs < 0) continue;
            markToken(sql, afterAs, close, masked, slot);
        }
    }

    /** PostgreSQL / 高斯的 {@code expr::type}。 */
    private void markCastOperatorTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        for (int i = 0; i + 1 < sql.length(); i++) {
            if (masked[i]) continue;
            if (sql.charAt(i) != ':' || sql.charAt(i + 1) != ':') continue;
            markToken(sql, i + 2, sql.length(), masked, slot);
            i++;
        }
    }

    private void markAlterTableTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher outer = ALTER_TABLE_PATTERN.matcher(sql);
        while (outer.find()) {
            if (masked[outer.start()]) continue;

            int stmtEnd = indexOfUnmasked(sql, ';', outer.end(), sql.length(), masked);
            if (stmtEnd < 0) stmtEnd = sql.length();

            Matcher clause = ALTER_CLAUSE_PATTERN.matcher(sql);
            clause.region(outer.end(), stmtEnd);
            while (clause.find()) {
                if (masked[clause.start()]) continue;
                markAlterClauseType(sql, clause, stmtEnd, masked, slot);
            }
        }
    }

    private void markAlterClauseType(String sql, Matcher clause, int stmtEnd,
                                     boolean[] masked, boolean[] slot) {
        int cursor = clause.end();

        // ADD CONSTRAINT / ADD PRIMARY KEY (...) 之类不是列定义
        int firstStart = skipWhitespace(sql, cursor, stmtEnd);
        int firstEnd = tokenEnd(sql, firstStart, stmtEnd);
        if (firstEnd <= firstStart) return;
        if (TABLE_LEVEL_CONSTRAINTS.contains(
                sql.substring(firstStart, firstEnd).toUpperCase(Locale.ROOT))) {
            return;
        }

        // CHANGE 是 old_name new_name TYPE，要跳两个标识符；其余跳一个
        int names = "CHANGE".equalsIgnoreCase(clause.group(1)) ? 2 : 1;
        cursor = firstEnd;
        for (int k = 1; k < names && cursor < stmtEnd; k++) {
            cursor = tokenEnd(sql, skipWhitespace(sql, cursor, stmtEnd), stmtEnd);
        }

        // PostgreSQL: ALTER COLUMN c TYPE bigint / ALTER COLUMN c SET DATA TYPE bigint
        cursor = skipKeywords(sql, cursor, stmtEnd, "SET", "DATA", "TYPE");

        markToken(sql, cursor, stmtEnd, masked, slot);
    }

    private static final Pattern CONVERT_PATTERN =
            Pattern.compile("\\bCONVERT\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * 用于**判别**（而非替换）的类型名词表：比方言映射表宽得多，
     * 涵盖 CHAR / SIGNED / UNSIGNED 等不参与转换但确实是类型的词。
     * <p>判别越宽越安全：只要两侧都可能是类型名就放弃，避免把列名当类型改掉。
     */
    private static final Set<String> KNOWN_TYPE_WORDS = Set.of(
            "CHAR", "NCHAR", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHARACTER",
            "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB",
            "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY", "RAW", "BYTEA",
            "INT", "INT2", "INT4", "INT8", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT", "BIGINT",
            "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "REAL", "DOUBLE", "PRECISION",
            "BIT", "BOOLEAN", "BOOL", "SIGNED", "UNSIGNED",
            "DATE", "TIME", "DATETIME", "DATETIME2", "TIMESTAMP", "YEAR", "INTERVAL",
            "JSON", "JSONB", "UUID", "XML", "MONEY", "SERIAL", "BIGSERIAL");

    /**
     * {@code CONVERT()} 的参数顺序在不同数据库里是**相反**的：
     * <pre>
     *   MySQL       CONVERT(expr, TYPE)
     *   SQL Server  CONVERT(TYPE, expr [, style])
     * </pre>
     * 本工具只接收目标库、不接收源库，无法靠方言判断，因此改为看内容：
     * 哪一侧像类型名而另一侧不像，就认那一侧是类型；两侧都像则放弃——
     * 宁可漏改，也不能把 {@code CONVERT(text, CHAR)} 里的列名 text 改成 CLOB。
     * <p>MySQL 的 {@code CONVERT(expr USING utf8mb4)} 转的是字符集，不含类型，直接跳过。
     */
    private void markConvertTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = CONVERT_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int open = matcher.end() - 1;
            int close = matchingParen(sql, open, masked);
            if (close < 0) continue;
            if (findKeyword(sql, open + 1, close, "USING", masked, true, true) >= 0) continue;

            List<int[]> args = splitTopLevel(sql, open + 1, close, masked, ',');
            if (args.size() < 2) continue;

            int[] first = args.get(0);
            int[] second = args.get(1);
            boolean firstIsType = isTypeLikeArgument(sql, first, masked);
            boolean secondIsType = isTypeLikeArgument(sql, second, masked);

            if (firstIsType && !secondIsType) {
                markToken(sql, first[0], first[1], masked, slot);      // SQL Server 形式
            } else if (secondIsType && !firstIsType) {
                markToken(sql, second[0], second[1], masked, slot);    // MySQL 形式
            }
            // 两侧都像类型名 => 无法判断，一个都不动
        }
    }

    /** 该参数是否形如一个纯类型：单个类型名，后面最多跟一组长度/精度括号。 */
    private boolean isTypeLikeArgument(String sql, int[] span, boolean[] masked) {
        int start = skipWhitespace(sql, span[0], span[1]);
        int end = tokenEnd(sql, start, span[1]);
        if (end <= start || masked[start]) return false;
        if (!KNOWN_TYPE_WORDS.contains(sql.substring(start, end).toUpperCase(Locale.ROOT))) {
            return false;
        }

        int rest = skipWhitespace(sql, end, span[1]);
        if (rest >= span[1]) return true;                 // CHAR
        if (sql.charAt(rest) != '(') return false;        // 还有别的东西，不是纯类型
        int close = matchingParen(sql, rest, masked);
        return close >= 0 && skipWhitespace(sql, close + 1, span[1]) >= span[1];   // DECIMAL(10,2)
    }

    private static final Pattern DECLARE_PATTERN =
            Pattern.compile("\\bDECLARE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 存储过程里的变量声明。三种写法的**区间边界不同**，必须先判形再切分：
     * <pre>
     *   T-SQL     DECLARE @a INT, @b TEXT;          -- 逗号分隔，止于分号
     *   PL/SQL    DECLARE a INT; b TEXT; BEGIN ...  -- 分号分隔，止于 BEGIN
     *   单条       DECLARE v INT;                    -- 止于分号
     * </pre>
     * 判形依据：T-SQL 变量以 {@code @} 开头。若按 PL/SQL 那样一律切到 BEGIN，
     * T-SQL 的 {@code DECLARE @a INT; SELECT text FROM t} 会把 {@code text FROM t}
     * 当成一条声明，把列名 text 当作类型改掉。
     */
    private void markDeclareTypeSlots(String sql, boolean[] masked, boolean[] slot) {
        Matcher matcher = DECLARE_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (masked[matcher.start()]) continue;

            int bodyStart = matcher.end();
            int semicolon = indexOfUnmasked(sql, ';', bodyStart, sql.length(), masked);
            int firstTokenStart = skipWhitespace(sql, bodyStart, sql.length());
            boolean tsqlStyle = firstTokenStart < sql.length() && sql.charAt(firstTokenStart) == '@';

            int end;
            char[] separators;
            if (tsqlStyle) {
                end = (semicolon < 0) ? sql.length() : semicolon;
                separators = new char[]{','};
            } else {
                int begin = findKeyword(sql, bodyStart, sql.length(), "BEGIN", masked, true, true);
                if (begin >= 0) {
                    end = begin;                              // PL/SQL 声明块
                    separators = new char[]{';', ','};
                } else {
                    end = (semicolon < 0) ? sql.length() : semicolon;
                    separators = new char[]{','};
                }
            }

            // 游标声明后面跟的是完整查询，不是类型声明，整段跳过
            if (findKeyword(sql, bodyStart, end, "CURSOR", masked, true, true) >= 0) continue;

            for (int[] item : splitTopLevel(sql, bodyStart, end, masked, separators)) {
                markDeclaredVariableType(sql, item[0], item[1], masked, slot);
            }
        }
    }

    /** 单条声明形如 {@code name TYPE [:= 默认值]}：跳过变量名，标记第二个词。 */
    private void markDeclaredVariableType(String sql, int start, int end,
                                          boolean[] masked, boolean[] slot) {
        int nameStart = skipWhitespace(sql, start, end);
        int nameEnd = tokenEnd(sql, nameStart, end);
        if (nameEnd <= nameStart) return;
        markToken(sql, nameEnd, end, masked, slot);
    }

    // ---------- 词法游标工具 ----------

    /** 按顶层分隔符切分区间，返回每段的 {@code [start, end)}。括号内与掩码内的分隔符不算。 */
    private List<int[]> splitTopLevel(String sql, int start, int end, boolean[] masked, char... separators) {
        List<int[]> parts = new ArrayList<>();
        int partStart = start;
        int depth = 0;
        for (int i = start; i < end; i++) {
            if (masked[i]) continue;
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && isSeparator(c, separators)) {
                parts.add(new int[]{partStart, i});
                partStart = i + 1;
            }
        }
        parts.add(new int[]{partStart, end});
        return parts;
    }

    private boolean isSeparator(char c, char[] separators) {
        for (char s : separators) {
            if (c == s) return true;
        }
        return false;
    }

    /** 从 {@code from} 起跳过空白后标记一个完整 token 为类型槽位。 */
    private void markToken(String sql, int from, int end, boolean[] masked, boolean[] slot) {
        int start = skipWhitespace(sql, from, end);
        int stop = tokenEnd(sql, start, end);
        if (stop <= start) return;
        if (masked[start]) return;   // 引号标识符不是类型
        for (int i = start; i < stop; i++) {
            slot[i] = true;
        }
    }

    /**
     * 跳过空白**和注释**。
     * <p>注释必须一起跳过：真实项目的建表语句几乎每列都带 {@code -- 注释}，
     * 若只跳空白，{@code -- 主键\n  id INT} 里的 INT 就会被漏掉（漏改不会改坏文件，但会漏迁移）。
     */
    private int skipWhitespace(String sql, int i, int end) {
        while (i < end) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < end && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                i = (newline < 0 || newline >= end) ? end : newline + 1;
            } else if (c == '/' && i + 1 < end && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = (close < 0 || close + 2 > end) ? end : close + 2;
            } else {
                break;
            }
        }
        return i;
    }

    /** 消费一个标识符 / 关键字 token；遇到引号标识符则整段消费。 */
    private int tokenEnd(String sql, int i, int end) {
        if (i >= end) return i;
        if (isQuote(sql.charAt(i))) {
            return Math.min(findQuoteEnd(sql, i), end);
        }
        int j = i;
        while (j < end && isIdentifierChar(sql.charAt(j))) j++;
        return j;
    }

    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@';
    }

    /** 依次跳过给定的可选关键字（出现即跳过，不出现就原地返回）。 */
    private int skipKeywords(String sql, int i, int end, String... keywords) {
        for (String keyword : keywords) {
            int start = skipWhitespace(sql, i, end);
            int stop = tokenEnd(sql, start, end);
            if (stop > start && sql.substring(start, stop).equalsIgnoreCase(keyword)) {
                i = stop;
            }
        }
        return i;
    }

    private int indexOfUnmasked(String sql, char target, int from, int end, boolean[] masked) {
        for (int i = from; i < end; i++) {
            if (!masked[i] && sql.charAt(i) == target) return i;
        }
        return -1;
    }

    /** 找到与 {@code open} 处左括号配对的右括号下标；找不到返回 -1。 */
    private int matchingParen(String sql, int open, boolean[] masked) {
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            if (masked[i]) continue;
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private boolean containsKeyword(String sql, int from, int end, String keyword, boolean[] masked) {
        return findKeyword(sql, from, end, keyword, masked, false, true) >= 0;
    }

    /** 返回区间内**最后一个**顶层 keyword 的结束位置（供 CAST 的 AS 使用）。 */
    private int lastTopLevelKeywordEnd(String sql, int from, int end, String keyword, boolean[] masked) {
        int start = findKeyword(sql, from, end, keyword, masked, true, false);
        return start < 0 ? -1 : start + keyword.length();
    }

    /**
     * 查找关键字 token 的**起始**下标；找不到返回 -1。
     *
     * @param topLevelOnly 只认括号深度 0 处的匹配
     * @param wantFirst    true 取第一个匹配，false 取最后一个
     */
    private int findKeyword(String sql, int from, int end, String keyword,
                            boolean[] masked, boolean topLevelOnly, boolean wantFirst) {
        int found = -1;
        int depth = 0;
        int i = from;
        while (i < end) {
            if (masked[i]) { i++; continue; }
            char c = sql.charAt(i);
            if (c == '(') { depth++; i++; continue; }
            if (c == ')') { depth--; i++; continue; }
            if (!isIdentifierChar(c)) { i++; continue; }

            int stop = tokenEnd(sql, i, end);
            if ((!topLevelOnly || depth == 0)
                    && sql.substring(i, stop).equalsIgnoreCase(keyword)) {
                if (wantFirst) return i;
                found = i;
            }
            i = Math.max(stop, i + 1);
        }
        return found;
    }

    // ==================== 字面量保护 ====================
    //
    // 类型/函数替换都是对整条 SQL 做正则替换。如果不区分字符串字面量，
    // WHERE kind = 'text' 会被改成 WHERE kind = 'CLOB'，
    // 这不是语法错误而是**静默改变业务语义**，比报错危险得多。
    // 引号标识符（"text"、`text`）和注释同理，都不该被改动。

    /**
     * 只替换落在字面量/注释之外的匹配。
     * <p>掩码基于传入的 {@code sql} 一次算好，替换结果写入新串，因此下标始终有效。
     */
    private String replaceOutsideLiterals(String sql, Pattern pattern, String replacement) {
        return replaceOutsideLiterals(sql, pattern, m -> replacement);
    }

    /**
     * 只替换落在字面量/注释之外的匹配，替换文本由 {@code replacer} 依据匹配结果生成。
     * <p>单趟扫描：不会像"每次替换后从头重新匹配"那样退化成 O(n²)，
     * 也不会在替换结果又能命中同一模式时死循环。
     */
    private String replaceOutsideLiterals(String sql, Pattern pattern,
                                          java.util.function.Function<Matcher, String> replacer) {
        boolean[] masked = maskLiterals(sql);
        Matcher matcher = pattern.matcher(sql);

        StringBuilder out = new StringBuilder(sql.length());
        int last = 0;
        boolean changed = false;

        while (matcher.find()) {
            if (masked[matcher.start()]) continue;   // 命中在字面量/注释内，跳过
            out.append(sql, last, matcher.start()).append(replacer.apply(matcher));
            last = matcher.end();
            changed = true;
        }

        if (!changed) return sql;
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * 标记出不应参与替换的字符位置：字符串字面量、引号标识符（{@code "x"} / {@code `x`}）、
     * 行注释（{@code --}）、块注释（{@code /*}），以及自动改造场景下的
     * MyBatis 占位符（{@code #{x}} / {@code ${x}}）与 XML 标签。
     * <p>后两类是为"保形转换"服务的：转换结果要原样写回源文件，
     * {@code #{text}} 若被改成 {@code #{CLOB}}、{@code <if test="text != null">} 若被改动，
     * mapper 就废了。
     */
    private boolean[] maskLiterals(String sql) {
        boolean[] masked = new boolean[sql.length()];
        int n = sql.length();
        int i = 0;

        while (i < n) {
            char c = sql.charAt(i);
            int end;

            if (c == '\\' && i + 1 < n && !isQuote(sql.charAt(i + 1))) {
                // Java 原文里的 \n \t \\ 等转义对：整体跳过，避免 n/t 之类被误当关键字起点
                i += 2;
                continue;
            } else if (c == '\\' && i + 1 < n) {
                // \" 或 \'：反斜杠只是 Java 层转义，后面那个引号仍是 SQL 的引号
                end = findQuoteEnd(sql, i + 1);
            } else if (isQuote(c)) {
                end = findQuoteEnd(sql, i);
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                end = sql.indexOf('\n', i);
                if (end < 0) end = n;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                end = (close < 0) ? n : close + 2;
            } else {
                i++;
                continue;
            }

            for (int j = i; j < end; j++) {
                masked[j] = true;
            }
            i = end;
        }

        maskAll(MYBATIS_PLACEHOLDER_PATTERN, sql, masked);
        maskAll(XML_TAG_PATTERN, sql, masked);

        return masked;
    }

    private boolean isQuote(char c) {
        return c == '\'' || c == '"' || c == '`';
    }

    /** MyBatis 占位符。普通 SQL 里不会出现这种写法，因此可以无条件保护。 */
    private static final Pattern MYBATIS_PLACEHOLDER_PATTERN = Pattern.compile(
            "[#$]\\{[^}]*}");

    /**
     * XML 标签。刻意写得保守：要求 {@code <} 后紧跟字母或 {@code /}，
     * 这样 {@code a < 5}、{@code a <> b}、{@code a <= 5} 都不会被误判成标签。
     */
    private static final Pattern XML_TAG_PATTERN = Pattern.compile(
            "</?[A-Za-z][\\w:.-]*(?:\\s[^<>]*)?/?>");

    private void maskAll(Pattern pattern, String sql, boolean[] masked) {
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            for (int j = m.start(); j < m.end(); j++) {
                masked[j] = true;
            }
        }
    }

    /**
     * 返回引号片段的结束下标（不含）。重复引号（{@code ''}、{@code ""}）是转义形式，不算闭合；
     * 片段内的 {@code \x} 转义对整体跳过，因此 {@code 'it\'s'} 不会被提前判定结束。
     * 引号未闭合时把剩余内容整体视为字面量——宁可漏改，也不能改坏。
     */
    private int findQuoteEnd(String sql, int start) {
        char quote = sql.charAt(start);
        int n = sql.length();
        int i = start + 1;

        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;     // 'it\'s' 这类转义引号
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;     // '' 表示一个引号字符本身
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private String convertSyntax(String sql, DialectConfig config) {
        String result = sql;

        // 处理 LIMIT 子句
        result = applyLimitSyntax(result, config.syntax.get("limit"));

        // 处理 AUTO_INCREMENT
        String autoIncSyntax = config.syntax.get("auto_increment");
        if (autoIncSyntax != null && !autoIncSyntax.isEmpty()) {
            Pattern autoIncPattern = Pattern.compile(
                "\\bAUTO_INCREMENT\\b",
                Pattern.CASE_INSENSITIVE
            );
            result = replaceOutsideLiterals(result, autoIncPattern, autoIncSyntax);
        }

        return result;
    }

    /**
     * JSqlParser 解析失败时的降级路径。
     * <p>函数/类型/LIMIT 的替换规则与 AST 路径完全一致，直接复用同一批方法——
     * 早先这里抄了一份独立实现，导致修复只落在一条路径上（例如 NOW() 的括号问题）。
     */
    private String convertByRegex(String sql, DialectConfig config) {
        String result = sql;
        result = convertFunctions(result, config);
        result = convertTypes(result, config);
        result = applyLimitSyntax(result, config.syntax.get("limit"));
        return result;
    }

    /** 匹配 LIMIT n 或 LIMIT n OFFSET m */
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 把 LIMIT n 替换成目标方言的写法。
     * <p>
     * 达梦用的是 {@code WHERE ROWNUM <= n}：如果原句在同一层级上已经有 WHERE 子句，
     * 直接拼上去会得到 {@code ... WHERE status = 1 WHERE ROWNUM <= 3} 这种双 WHERE 的非法 SQL，
     * 此时必须改用 {@code AND}。
     */
    private String applyLimitSyntax(String sql, String limitSyntax) {
        if (limitSyntax == null) return sql;

        // 只认字面量/注释之外的第一个 LIMIT
        boolean[] masked = maskLiterals(sql);
        Matcher limitMatcher = LIMIT_PATTERN.matcher(sql);
        boolean found = false;
        while (limitMatcher.find()) {
            if (!masked[limitMatcher.start()]) {
                found = true;
                break;
            }
        }
        if (!found) return sql;

        String replacement = limitSyntax.replace("{n}", limitMatcher.group(1));

        // 目标写法本身以 WHERE 开头，且原句同层已有 WHERE ⇒ 换成 AND
        if (startsWithWhere(replacement)
                && hasTopLevelWhere(sql.substring(0, limitMatcher.start()))) {
            replacement = "AND" + replacement.substring("WHERE".length());
        }

        return sql.substring(0, limitMatcher.start())
                + replacement
                + sql.substring(limitMatcher.end());
    }

    private boolean startsWithWhere(String s) {
        return s.length() >= 5 && s.regionMatches(true, 0, "WHERE", 0, 5);
    }

    /**
     * 判断 SQL 片段里是否存在「顶层」WHERE 子句。
     * 括号内（子查询、函数参数）的 WHERE 不算——那种情况下拼 WHERE 才是对的。
     */
    private boolean hasTopLevelWhere(String prefix) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') inSingleQuote = false;
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
                continue;
            }
            if (c == '\'') { inSingleQuote = true; continue; }
            if (c == '"') { inDoubleQuote = true; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { if (depth > 0) depth--; continue; }

            if (depth == 0 && (c == 'W' || c == 'w')
                    && prefix.regionMatches(true, i, "WHERE", 0, 5)
                    && isWordBoundary(prefix, i - 1)
                    && isWordBoundary(prefix, i + 5)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWordBoundary(String s, int index) {
        if (index < 0 || index >= s.length()) return true;
        char c = s.charAt(index);
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    private DialectConfig createGaussDbConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "COALESCE"),
            Map.entry("DATE_FORMAT", "TO_CHAR"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "CLOB"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INTEGER"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "NUMERIC"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "SMALLINT"),
            Map.entry("MEDIUMINT", "INTEGER"),
            Map.entry("LONGTEXT", "CLOB"),
            Map.entry("MEDIUMTEXT", "CLOB"),
            Map.entry("DOUBLE", "DOUBLE PRECISION"),
            Map.entry("FLOAT", "REAL")
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY",
            "auto_increment", "",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createDamengConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "SYSDATE"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "NVL"),
            Map.entry("DATE_FORMAT", "TO_CHAR"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "CLOB"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "INT"),
            Map.entry("LONGTEXT", "CLOB"),
            Map.entry("MEDIUMTEXT", "CLOB"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "WHERE ROWNUM <= {n}",
            "auto_increment", "IDENTITY(1,1)",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createKingbaseConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "COALESCE"),
            Map.entry("DATE_FORMAT", "TO_CHAR"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "TEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INTEGER"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "NUMERIC"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "SMALLINT"),
            Map.entry("MEDIUMINT", "INTEGER"),
            Map.entry("LONGTEXT", "TEXT"),
            Map.entry("MEDIUMTEXT", "TEXT"),
            Map.entry("DOUBLE", "DOUBLE PRECISION"),
            Map.entry("FLOAT", "REAL")
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY",
            "auto_increment", "",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createOceanBaseConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "NVL"),
            Map.entry("DATE_FORMAT", "DATE_FORMAT"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "LONGTEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "MEDIUMINT"),
            Map.entry("LONGTEXT", "LONGTEXT"),
            Map.entry("MEDIUMTEXT", "MEDIUMTEXT"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "LIMIT {n}",
            "auto_increment", "AUTO_INCREMENT",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createTiDbConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.ofEntries(
            Map.entry("NOW", "CURRENT_TIMESTAMP"),
            Map.entry("CONCAT", "CONCAT"),
            Map.entry("IFNULL", "IFNULL"),
            Map.entry("DATE_FORMAT", "DATE_FORMAT"),
            Map.entry("SUBSTRING", "SUBSTR"),
            Map.entry("LENGTH", "LENGTH"),
            Map.entry("TRIM", "TRIM"),
            Map.entry("UPPER", "UPPER"),
            Map.entry("LOWER", "LOWER"),
            Map.entry("REPLACE", "REPLACE"),
            Map.entry("ROUND", "ROUND"),
            Map.entry("CEIL", "CEIL"),
            Map.entry("FLOOR", "FLOOR")
        );
        config.types = Map.ofEntries(
            Map.entry("TEXT", "TEXT"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("DATETIME", "DATETIME"),
            Map.entry("DATE", "DATE"),
            Map.entry("TINYINT", "TINYINT"),
            Map.entry("MEDIUMINT", "MEDIUMINT"),
            Map.entry("LONGTEXT", "LONGTEXT"),
            Map.entry("MEDIUMTEXT", "MEDIUMTEXT"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("FLOAT", "FLOAT")
        );
        config.syntax = Map.of(
            "limit", "LIMIT {n}",
            "auto_increment", "AUTO_INCREMENT",
            "comment", "-- {comment}"
        );
        return config;
    }

    private DialectConfig createGBaseConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.of(
            "NOW", "CURRENT_TIMESTAMP",
            "CONCAT", "CONCAT",
            "IFNULL", "NVL"
        );
        config.types = Map.of(
            "TEXT", "CLOB",
            "VARCHAR2", "VARCHAR",
            "INT", "INTEGER",
            "BIGINT", "BIGINT",
            "DATETIME", "TIMESTAMP"
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY"
        );
        return config;
    }

    private DialectConfig createShenTongConfig() {
        DialectConfig config = new DialectConfig();
        config.functions = Map.of(
            "NOW", "CURRENT_TIMESTAMP",
            "CONCAT", "CONCAT",
            "IFNULL", "COALESCE"
        );
        config.types = Map.of(
            "TEXT", "CLOB",
            "VARCHAR2", "VARCHAR",
            "INT", "INTEGER",
            "BIGINT", "BIGINT",
            "DATETIME", "TIMESTAMP"
        );
        config.syntax = Map.of(
            "limit", "FETCH FIRST {n} ROWS ONLY"
        );
        return config;
    }

    public Set<String> getSupportedDatabases() {
        return dialectConfigs.keySet();
    }

    @lombok.Data
    static class DialectConfig {
        Map<String, String> functions = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> syntax = new HashMap<>();

        /** 所有源类型名合成的单个正则，惰性构建后复用（原来每种类型各编译一次并各扫一趟）。 */
        private volatile Pattern typePattern;

        Pattern typePattern() {
            Pattern cached = typePattern;
            if (cached != null) return cached;
            synchronized (this) {
                if (typePattern == null) {
                    // 长的排前面：保证 LONGTEXT 不会先被 TEXT 部分命中
                    List<String> names = new ArrayList<>(types.keySet());
                    names.sort(Comparator.comparingInt(String::length).reversed());
                    StringJoiner joiner = new StringJoiner("|", "\\b(?:", ")\\b");
                    for (String name : names) {
                        joiner.add(Pattern.quote(name));
                    }
                    typePattern = Pattern.compile(joiner.toString(), Pattern.CASE_INSENSITIVE);
                }
                return typePattern;
            }
        }
    }
}
