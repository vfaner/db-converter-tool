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
        if (sourceSql == null || sourceSql.trim().isEmpty()) {
            throw new IllegalArgumentException("源SQL不能为空");
        }
        if (targetDb == null || targetDb.trim().isEmpty()) {
            throw new IllegalArgumentException("目标数据库不能为空");
        }

        String normalizedTarget = targetDb.toLowerCase().trim();
        DialectConfig config = dialectConfigs.get(normalizedTarget);
        if (config == null) {
            throw new IllegalArgumentException("不支持的目标数据库: " + targetDb);
        }

        try {
            // 尝试使用 JSqlParser 解析
            Statement statement = CCJSqlParserUtil.parse(sourceSql);
            return convertStatement(statement, config);
        } catch (JSQLParserException e) {
            log.warn("JSqlParser 解析失败，使用正则降级: {}", e.getMessage());
            return convertByRegex(sourceSql, config);
        }
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
                Matcher matcher = pattern.matcher(result);
                while (matcher.find()) {
                    String args = matcher.group(1);
                    String[] argParts = args.split(",", -1);
                    String replacement = targetFunc;
                    for (int i = 0; i < argParts.length; i++) {
                        replacement = replacement.replace("{" + i + "}", argParts[i].trim());
                    }
                    result = result.substring(0, matcher.start()) + replacement + result.substring(matcher.end());
                    matcher = pattern.matcher(result);
                }
            } else {
                // 简单函数替换
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(",
                    Pattern.CASE_INSENSITIVE
                );
                result = pattern.matcher(result).replaceAll(targetFunc + "(");
            }
        }

        return result;
    }

    private String convertTypes(String sql, DialectConfig config) {
        String result = sql;

        for (Map.Entry<String, String> entry : config.types.entrySet()) {
            String sourceType = entry.getKey().toUpperCase();
            String targetType = entry.getValue();

            // 匹配类型定义（考虑大小写）
            Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(sourceType) + "\\b",
                Pattern.CASE_INSENSITIVE
            );
            result = pattern.matcher(result).replaceAll(targetType);
        }

        return result;
    }

    private String convertSyntax(String sql, DialectConfig config) {
        String result = sql;

        // 处理 LIMIT 子句
        String limitSyntax = config.syntax.get("limit");
        if (limitSyntax != null) {
            // 匹配 LIMIT n 或 LIMIT n OFFSET m
            Pattern limitPattern = Pattern.compile(
                "\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?",
                Pattern.CASE_INSENSITIVE
            );
            Matcher limitMatcher = limitPattern.matcher(result);
            if (limitMatcher.find()) {
                String n = limitMatcher.group(1);
                String replacement = limitSyntax.replace("{n}", n);
                result = limitMatcher.replaceFirst(replacement);
            }
        }

        // 处理 AUTO_INCREMENT
        String autoIncSyntax = config.syntax.get("auto_increment");
        if (autoIncSyntax != null && !autoIncSyntax.isEmpty()) {
            Pattern autoIncPattern = Pattern.compile(
                "\\bAUTO_INCREMENT\\b",
                Pattern.CASE_INSENSITIVE
            );
            result = autoIncPattern.matcher(result).replaceAll(autoIncSyntax);
        }

        return result;
    }

    private String convertByRegex(String sql, DialectConfig config) {
        String result = sql;

        // 函数替换
        for (Map.Entry<String, String> entry : config.functions.entrySet()) {
            String sourceFunc = entry.getKey().toUpperCase();
            String targetFunc = entry.getValue();

            if (targetFunc.contains("{0}")) {
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(([^)]+)\\)",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher matcher = pattern.matcher(result);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String args = matcher.group(1);
                    String[] argParts = args.split(",", -1);
                    String replacement = targetFunc;
                    for (int i = 0; i < argParts.length; i++) {
                        replacement = replacement.replace("{" + i + "}", argParts[i].trim());
                    }
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(sb);
                result = sb.toString();
            } else {
                Pattern pattern = Pattern.compile(
                    "\\b" + Pattern.quote(sourceFunc) + "\\s*\\(",
                    Pattern.CASE_INSENSITIVE
                );
                result = pattern.matcher(result).replaceAll(targetFunc + "(");
            }
        }

        // 类型替换
        for (Map.Entry<String, String> entry : config.types.entrySet()) {
            String sourceType = entry.getKey().toUpperCase();
            String targetType = entry.getValue();

            Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(sourceType) + "\\b",
                Pattern.CASE_INSENSITIVE
            );
            result = pattern.matcher(result).replaceAll(targetType);
        }

        // 语法替换
        String limitSyntax = config.syntax.get("limit");
        if (limitSyntax != null) {
            Pattern limitPattern = Pattern.compile(
                "\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?",
                Pattern.CASE_INSENSITIVE
            );
            Matcher limitMatcher = limitPattern.matcher(result);
            if (limitMatcher.find()) {
                String n = limitMatcher.group(1);
                String replacement = limitSyntax.replace("{n}", n);
                result = limitMatcher.replaceFirst(replacement);
            }
        }

        return result;
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
    }
}
