package com.dbconverter;

import com.dbconverter.service.SqlConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlConverterTest {

    private SqlConverter sqlConverter;

    @BeforeEach
    void setUp() {
        sqlConverter = new SqlConverter();
        sqlConverter.init();
    }

    @Test
    @DisplayName("测试空SQL输入")
    void testEmptySql() {
        assertThrows(IllegalArgumentException.class, () -> {
            sqlConverter.convert("", "gaussdb");
        });
    }

    @Test
    @DisplayName("测试空目标数据库")
    void testEmptyTargetDb() {
        assertThrows(IllegalArgumentException.class, () -> {
            sqlConverter.convert("SELECT * FROM users", "");
        });
    }

    @Test
    @DisplayName("测试不支持的目标数据库")
    void testUnsupportedTargetDb() {
        assertThrows(IllegalArgumentException.class, () -> {
            sqlConverter.convert("SELECT * FROM users", "unknown_db");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gaussdb", "dameng", "kingbase", "oceanbase", "tidb", "gbase", "shentong"})
    @DisplayName("测试所有支持的数据库")
    void testAllSupportedDatabases(String targetDb) {
        String result = sqlConverter.convert("SELECT * FROM users", targetDb);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("测试高斯数据库 IFNULL 转换")
    void testGaussDbIfNullConversion() {
        String source = "SELECT IFNULL(name, 'default') FROM users";
        String result = sqlConverter.convert(source, "gaussdb");
        assertTrue(result.contains("COALESCE") || result.toLowerCase().contains("coalesce"));
    }

    @Test
    @DisplayName("测试达梦数据库 IFNULL 转换")
    void testDamengIfNullConversion() {
        String source = "SELECT IFNULL(name, 'default') FROM users";
        String result = sqlConverter.convert(source, "dameng");
        assertTrue(result.contains("NVL") || result.toUpperCase().contains("NVL"));
    }

    @Test
    @DisplayName("测试高斯数据库 NOW 转换")
    void testGaussDbNowConversion() {
        String source = "SELECT NOW() FROM dual";
        String result = sqlConverter.convert(source, "gaussdb");
        assertNotNull(result);
    }

    @Test
    @DisplayName("测试达梦数据库 NOW 转换")
    void testDamengNowConversion() {
        String source = "SELECT NOW() FROM dual";
        String result = sqlConverter.convert(source, "dameng");
        assertTrue(result.contains("SYSDATE") || result.toUpperCase().contains("SYSDATE"));
    }

    @Test
    @DisplayName("测试 TEXT 类型转换 - 高斯")
    void testGaussDbTypeConversion() {
        String source = "CREATE TABLE test (content TEXT)";
        String result = sqlConverter.convert(source, "gaussdb");
        assertTrue(result.contains("CLOB") || result.toUpperCase().contains("CLOB"));
    }

    @Test
    @DisplayName("测试 VARCHAR2 类型转换")
    void testVarchar2TypeConversion() {
        String source = "CREATE TABLE test (name VARCHAR2(100))";
        String result = sqlConverter.convert(source, "gaussdb");
        assertTrue(result.contains("VARCHAR") || result.toUpperCase().contains("VARCHAR"));
    }

    @Test
    @DisplayName("测试 TiDB 保持不变")
    void testTiDbCompatibility() {
        String source = "SELECT * FROM users LIMIT 10";
        String result = sqlConverter.convert(source, "tidb");
        assertTrue(result.contains("LIMIT") || result.toUpperCase().contains("LIMIT"));
    }

    @Test
    @DisplayName("测试 OceanBase 兼容性")
    void testOceanBaseCompatibility() {
        String source = "SELECT * FROM users LIMIT 10";
        String result = sqlConverter.convert(source, "oceanbase");
        assertNotNull(result);
    }

    @Test
    @DisplayName("测试获取支持的数据库列表")
    void testGetSupportedDatabases() {
        var databases = sqlConverter.getSupportedDatabases();
        assertNotNull(databases);
        assertTrue(databases.contains("gaussdb"));
        assertTrue(databases.contains("dameng"));
        assertTrue(databases.contains("kingbase"));
        assertTrue(databases.contains("oceanbase"));
        assertTrue(databases.contains("tidb"));
        assertEquals(7, databases.size());
    }

    @Test
    @DisplayName("测试复杂SQL转换")
    void testComplexSqlConversion() {
        String source = """
            SELECT u.id, IFNULL(u.name, 'unknown'), NOW() as created_at
            FROM users u
            WHERE u.status = 1
            ORDER BY u.created_at DESC
            LIMIT 10
            """;

        String result = sqlConverter.convert(source, "gaussdb");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("测试 INSERT 语句转换")
    void testInsertConversion() {
        String source = "INSERT INTO users (name, created_at) VALUES ('test', NOW())";
        String result = sqlConverter.convert(source, "dameng");
        assertNotNull(result);
    }

    @Test
    @DisplayName("测试 UPDATE 语句转换")
    void testUpdateConversion() {
        String source = "UPDATE users SET name = 'test', updated_at = NOW() WHERE id = 1";
        String result = sqlConverter.convert(source, "kingbase");
        assertNotNull(result);
    }

    @Test
    @DisplayName("测试 DELETE 语句转换")
    void testDeleteConversion() {
        String source = "DELETE FROM users WHERE created_at < NOW()";
        String result = sqlConverter.convert(source, "tidb");
        assertNotNull(result);
    }

    // ============================================================
    //  结尾分号保留
    //  JSqlParser 的 toString() 不输出分号，而自动处理会把结果原样替换回原文，
    //  分号一丢，.sql 脚本里相邻语句就会粘连成一条。
    // ============================================================

    @Test
    @DisplayName("原文带分号时，转换结果必须保留分号")
    void keepsTrailingSemicolon() {
        String source = "SELECT id, name FROM orders WHERE amount > 100 LIMIT 5;";
        String result = sqlConverter.convert(source, "gaussdb");
        assertTrue(result.stripTrailing().endsWith(";"),
                "原文以分号结尾，转换结果也必须以分号结尾，实际: " + result);
    }

    @Test
    @DisplayName("原文不带分号时，不能凭空多出分号")
    void doesNotAddSemicolon() {
        String source = "SELECT id, name FROM orders WHERE amount > 100 LIMIT 5";
        String result = sqlConverter.convert(source, "gaussdb");
        assertFalse(result.stripTrailing().endsWith(";"),
                "原文没有分号，转换结果不应多出分号，实际: " + result);
    }

    @Test
    @DisplayName("分号不会被重复追加（正则降级路径也保持幂等）")
    void doesNotDuplicateSemicolon() {
        String source = "ALTER TABLE users ADD COLUMN nickname VARCHAR(50);";
        String result = sqlConverter.convert(source, "dameng");
        assertFalse(result.stripTrailing().endsWith(";;"),
                "分号不应被重复追加，实际: " + result);
        assertTrue(result.stripTrailing().endsWith(";"));
    }

    @Test
    @DisplayName("无需转换的带分号语句，转换前后应完全一致（避免产生虚假改造项）")
    void noSpuriousChangeForAlreadyCompatibleSql() {
        // 这条 SQL 不含任何需要转换的方言语法，唯一的差异风险就是分号被吞掉。
        // 自动处理用 !source.trim().equals(target.trim()) 判断是否要生成改造项，
        // 分号一丢就会凭空多出一条"只把分号删掉"的改造项。
        String source = "SELECT id FROM users WHERE status = 1;";
        String result = sqlConverter.convert(source, "gaussdb");
        assertEquals(source.trim(), result.trim(),
                "兼容语法 + 带分号的 SQL 转换前后应一致，否则会产生虚假改造项");
    }

    @DisplayName("分号保留对所有目标数据库生效")
    @ParameterizedTest
    @ValueSource(strings = {"gaussdb", "dameng", "kingbase", "oceanbase", "tidb", "gbase", "shentong"})
    void keepsSemicolonForAllDialects(String targetDb) {
        String source = "SELECT id FROM users WHERE created > NOW() LIMIT 10;";
        String result = sqlConverter.convert(source, targetDb);
        assertTrue(result.stripTrailing().endsWith(";"),
                targetDb + " 转换结果丢失了结尾分号: " + result);
    }

    // ============================================================
    //  LIMIT → ROWNUM（达梦）：不能产生双 WHERE
    // ============================================================

    @Test
    @DisplayName("已有 WHERE 时，LIMIT→ROWNUM 必须用 AND 拼接")
    void limitToRownumUsesAndWhenWhereExists() {
        String result = sqlConverter.convert(
                "SELECT id FROM t WHERE status = 1 LIMIT 3", "dameng");
        assertFalse(countWhere(result) > 1,
                "同一层级出现两个 WHERE 是非法 SQL: " + result);
        assertTrue(result.toUpperCase().contains("AND ROWNUM <= 3"),
                "应改用 AND 拼接 ROWNUM 条件: " + result);
    }

    @Test
    @DisplayName("没有 WHERE 时，LIMIT→ROWNUM 仍然用 WHERE")
    void limitToRownumUsesWhereWhenNoWhere() {
        String result = sqlConverter.convert("SELECT id FROM t LIMIT 3", "dameng");
        assertTrue(result.toUpperCase().contains("WHERE ROWNUM <= 3"),
                "原句没有 WHERE，应该用 WHERE 引出 ROWNUM 条件: " + result);
    }

    @Test
    @DisplayName("WHERE 只出现在子查询里时，外层仍应用 WHERE")
    void limitToRownumIgnoresSubqueryWhere() {
        String result = sqlConverter.convert(
                "SELECT id FROM (SELECT id FROM t WHERE status = 1) x LIMIT 3", "dameng");
        assertTrue(result.toUpperCase().contains("WHERE ROWNUM <= 3"),
                "子查询里的 WHERE 不属于外层，外层应该用 WHERE: " + result);
    }

    @Test
    @DisplayName("引号内的 where 字面量不应被当成 WHERE 子句")
    void limitToRownumIgnoresQuotedWhere() {
        String result = sqlConverter.convert(
                "SELECT id FROM t LIMIT 3", "dameng");
        assertTrue(result.toUpperCase().contains("WHERE ROWNUM <= 3"), result);

        String quoted = sqlConverter.convert(
                "SELECT id FROM t HAVING name = 'where' LIMIT 3", "dameng");
        assertTrue(quoted.toUpperCase().contains("WHERE ROWNUM <= 3"),
                "'where' 是字符串字面量，不是 WHERE 子句: " + quoted);
    }

    /** 统计顶层 WHERE 关键字出现次数（简单计数，用于断言不出现双 WHERE） */
    private int countWhere(String sql) {
        int count = 0;
        String upper = sql.toUpperCase();
        int idx = upper.indexOf("WHERE");
        while (idx >= 0) {
            count++;
            idx = upper.indexOf("WHERE", idx + 5);
        }
        return count;
    }

    // ---------- NOW() 零参数函数的括号处理 ----------

    @ParameterizedTest
    @ValueSource(strings = {"gaussdb", "kingbase", "oceanbase", "tidb", "gbase", "shentong"})
    @DisplayName("NOW() 转 CURRENT_TIMESTAMP 时必须去掉括号")
    void noArgFunctionDropsParenthesis(String dialect) {
        String result = sqlConverter.convert("SELECT id FROM t WHERE a > NOW()", dialect);
        assertTrue(result.toUpperCase().contains("CURRENT_TIMESTAMP"),
                "应转换为 CURRENT_TIMESTAMP: " + result);
        assertFalse(result.toUpperCase().contains("CURRENT_TIMESTAMP()"),
                "CURRENT_TIMESTAMP 是不带括号的关键字，带括号是非法语法: " + result);
    }

    @Test
    @DisplayName("达梦 NOW() 转 SYSDATE 时必须去掉括号")
    void noArgFunctionDropsParenthesisForDameng() {
        String result = sqlConverter.convert("SELECT id FROM t WHERE a > NOW()", "dameng");
        assertTrue(result.toUpperCase().contains("SYSDATE"), result);
        assertFalse(result.toUpperCase().contains("SYSDATE()"),
                "Oracle 系的 SYSDATE 不接受括号: " + result);
    }

    @Test
    @DisplayName("NOW ( ) 中间有空格也要正确处理")
    void noArgFunctionHandlesWhitespace() {
        String result = sqlConverter.convert("SELECT id FROM t WHERE a > NOW( )", "gaussdb");
        assertFalse(result.toUpperCase().contains("CURRENT_TIMESTAMP()"), result);
        assertFalse(result.contains("( )"), "空括号应被一并消除: " + result);
    }

    @Test
    @DisplayName("带参数的函数替换不受影响，括号与参数需保留")
    void functionWithArgsKeepsParenthesis() {
        String result = sqlConverter.convert("SELECT IFNULL(name, 'x') FROM t", "gaussdb");
        assertTrue(result.contains("(name, 'x')") || result.contains("(name,'x')"),
                "参数应原样保留: " + result);
        assertFalse(result.toUpperCase().contains("IFNULL"),
                "函数名应已被替换: " + result);
    }

    // ---------- 字面量 / 注释保护 ----------

    @Test
    @DisplayName("字符串字面量里的类型名不得被替换（会静默改变业务语义）")
    void typeNameInsideStringLiteralIsPreserved() {
        String result = sqlConverter.convert("SELECT id FROM docs WHERE kind = 'text'", "gaussdb");
        assertTrue(result.contains("'text'"),
                "'text' 是数据值，替换成 'CLOB' 会让查询返回完全不同的行: " + result);
        assertFalse(result.contains("'CLOB'"), result);
    }

    @Test
    @DisplayName("字符串字面量里的函数名不得被替换")
    void functionNameInsideStringLiteralIsPreserved() {
        String result = sqlConverter.convert(
                "SELECT id FROM logs WHERE msg = 'call NOW() please'", "gaussdb");
        assertTrue(result.contains("'call NOW() please'"), result);
    }

    @Test
    @DisplayName("同一条 SQL 里：字面量保持不变，字面量外的转换照常生效")
    void literalPreservedWhileRealConversionStillApplies() {
        String result = sqlConverter.convert(
                "SELECT id FROM docs WHERE kind = 'text' AND created > NOW()", "gaussdb");
        assertTrue(result.contains("'text'"), "字面量应保持: " + result);
        assertTrue(result.toUpperCase().contains("CURRENT_TIMESTAMP"),
                "字面量外的 NOW() 仍应转换: " + result);
        assertFalse(result.toUpperCase().contains("CURRENT_TIMESTAMP()"), result);
    }

    @Test
    @DisplayName("真正的类型声明仍然要被转换（DDL 不能被保护逻辑误伤）")
    void realTypeDeclarationIsStillConverted() {
        String result = sqlConverter.convert(
                "CREATE TABLE t (id INT, body TEXT, created DATETIME)", "gaussdb");
        assertTrue(result.toUpperCase().contains("INTEGER"), result);
        assertTrue(result.toUpperCase().contains("CLOB"), result);
        assertTrue(result.toUpperCase().contains("TIMESTAMP"), result);
    }

    @Test
    @DisplayName("转义引号 '' 不应被误判为字面量结束")
    void escapedQuoteDoesNotTerminateLiteral() {
        String result = sqlConverter.convert(
                "SELECT id FROM t WHERE a = 'it''s text' AND created > NOW()", "gaussdb");
        assertTrue(result.contains("'it''s text'"),
                "整个字面量（含转义引号）都应保持原样: " + result);
        assertTrue(result.toUpperCase().contains("CURRENT_TIMESTAMP"),
                "字面量之后的转换仍应生效: " + result);
    }

    @Test
    @DisplayName("引号标识符内的类型名不得被替换")
    void quotedIdentifierIsPreserved() {
        String result = sqlConverter.convert("SELECT \"text\" FROM notes", "gaussdb");
        assertTrue(result.contains("\"text\""),
                "\"text\" 是列名，替换掉会导致列不存在: " + result);
    }

    @Test
    @DisplayName("字面量里的 LIMIT 不应触发语法转换")
    void limitInsideStringLiteralIsNotConverted() {
        String result = sqlConverter.convert(
                "SELECT id FROM t WHERE note = 'use LIMIT 5 here'", "gaussdb");
        assertTrue(result.contains("'use LIMIT 5 here'"), result);
        assertFalse(result.toUpperCase().contains("FETCH FIRST"), result);
    }

    @Test
    @DisplayName("字面量外的 LIMIT 仍然要转换")
    void limitOutsideLiteralIsStillConverted() {
        String result = sqlConverter.convert(
                "SELECT id FROM t WHERE note = 'use LIMIT 5 here' LIMIT 3", "gaussdb");
        assertTrue(result.contains("'use LIMIT 5 here'"), "字面量应保持: " + result);
        assertTrue(result.toUpperCase().contains("FETCH FIRST 3"),
                "真正的 LIMIT 3 应被转换: " + result);
    }

    @Test
    @DisplayName("正则降级路径同样受字面量保护，且带 NOW() 括号修复")
    void regexFallbackPathAlsoProtectedAndFixed() {
        // 故意写成 JSqlParser 无法解析的形式，强制走 convertByRegex
        String result = sqlConverter.convert(
                "MERGE WEIRD SYNTAX kind = 'text' AND created > NOW()", "gaussdb");
        assertTrue(result.contains("'text'"),
                "降级路径也不能改坏字面量: " + result);
        assertTrue(result.toUpperCase().contains("CURRENT_TIMESTAMP"), result);
        assertFalse(result.toUpperCase().contains("CURRENT_TIMESTAMP()"),
                "降级路径此前漏掉了 NOW() 括号修复: " + result);
    }

    /**
     * 与类型名撞名的**裸标识符**：TEXT / INT / DATE / DATETIME 既是类型名也是极常见的列名。
     * <p>基于引号/注释的词法掩码无法区分两者，所以改为白名单——只有落在
     * 「类型声明槽位」上的词才替换（CREATE TABLE 列定义、CAST(x AS t)、x::t、
     * ALTER TABLE 的 ADD/MODIFY/CHANGE/ALTER COLUMN），其余一律当标识符。
     */
    @Test
    @DisplayName("与类型名同名的裸列名不应被替换")
    void unquotedIdentifierCollidingWithTypeNameShouldBePreserved() {
        assertEquals("SELECT text FROM notes",
                sqlConverter.convert("SELECT text FROM notes", "gaussdb"));
        assertTrue(sqlConverter.convert("INSERT INTO t (int) VALUES (1)", "gaussdb")
                .contains("(int)"));
    }

    @Test
    @DisplayName("裸列名撞名：各种子句里的 text/int/date 都不得被改")
    void typeNamedColumnsSurviveEveryClause() {
        assertEquals("SELECT text, int, date FROM t ORDER BY text",
                sqlConverter.convert("SELECT text, int, date FROM t ORDER BY text", "gaussdb"));
        assertTrue(sqlConverter.convert("SELECT a FROM t WHERE text = 1", "gaussdb")
                .contains("text = 1"));
        assertTrue(sqlConverter.convert("UPDATE t SET text = 'x' WHERE id = 1", "gaussdb")
                .toUpperCase().contains("SET TEXT ="),
                "SET 后的列名不是类型声明");
        assertTrue(sqlConverter.convert("SELECT COUNT(datetime) FROM logs", "gaussdb")
                .contains("datetime"), "函数参数里的列名不是类型声明");
    }

    @Test
    @DisplayName("CREATE TABLE 里列名与类型撞名时，只改类型不改列名")
    void columnNamedLikeTypeIsNotConvertedButItsTypeIs() {
        String result = sqlConverter.convert(
                "CREATE TABLE notes (text TEXT, int INT)", "gaussdb");

        assertTrue(result.contains("text CLOB") || result.contains("text  CLOB"),
                "列名 text 必须保留，其类型 TEXT 应转成 CLOB: " + result);
        assertTrue(result.contains("int INTEGER"),
                "列名 int 必须保留，其类型 INT 应转成 INTEGER: " + result);
        assertFalse(result.contains("CLOB CLOB"), "列名被误改: " + result);
        assertFalse(result.contains("INTEGER INTEGER"), "列名被误改: " + result);
    }

    @Test
    @DisplayName("表级约束项不是列定义，其中的标识符不得被当成类型")
    void tableLevelConstraintsAreNotColumnDefinitions() {
        String result = sqlConverter.convert(
                "CREATE TABLE t (id INT, body TEXT, PRIMARY KEY (id), KEY text (body))",
                "gaussdb");

        assertTrue(result.toUpperCase().contains("PRIMARY KEY"), result);
        assertTrue(result.contains("KEY text (body)") || result.contains("KEY text(body)"),
                "索引名 text 不是类型: " + result);
        assertTrue(result.contains("INTEGER"), "真正的类型仍要转换: " + result);
        assertTrue(result.toUpperCase().contains("CLOB"), result);
    }

    @Test
    @DisplayName("CAST(x AS type) 里的类型要转换")
    void castTargetTypeIsConverted() {
        String result = sqlConverter.convert(
                "SELECT CAST(body AS TEXT) FROM notes", "gaussdb");
        assertTrue(result.toUpperCase().contains("AS CLOB"),
                "CAST 的目标类型应转换: " + result);
    }

    @Test
    @DisplayName("SELECT a AS text 是列别名，不是 CAST，不得被替换")
    void columnAliasNamedLikeTypeIsNotConverted() {
        String result = sqlConverter.convert("SELECT body AS text FROM notes", "gaussdb");
        assertTrue(result.contains("AS text"),
                "别名 text 被当成类型替换会导致结果列名错误: " + result);
        assertFalse(result.toUpperCase().contains("AS CLOB"), result);
    }

    @Test
    @DisplayName("PostgreSQL 的 x::type 里的类型要转换")
    void castOperatorTypeIsConverted() {
        String result = sqlConverter.convert("SELECT body::TEXT FROM notes", "gaussdb");
        assertTrue(result.toUpperCase().contains("::CLOB") || result.toUpperCase().contains(":: CLOB"),
                "::TEXT 应转换: " + result);
    }

    @Test
    @DisplayName("ALTER TABLE 的 ADD / MODIFY / ALTER COLUMN 里的类型要转换")
    void alterTableColumnTypesAreConverted() {
        assertTrue(sqlConverter.convert("ALTER TABLE t ADD COLUMN body TEXT", "gaussdb")
                        .toUpperCase().contains("CLOB"),
                "ADD COLUMN 的类型应转换");
        assertTrue(sqlConverter.convert("ALTER TABLE t MODIFY body TEXT", "gaussdb")
                        .toUpperCase().contains("CLOB"),
                "MODIFY 的类型应转换");
        assertTrue(sqlConverter.convert("ALTER TABLE t ALTER COLUMN body TYPE TEXT", "gaussdb")
                        .toUpperCase().contains("CLOB"),
                "ALTER COLUMN ... TYPE 的类型应转换");
        assertTrue(sqlConverter.convert("ALTER TABLE t CHANGE old_body body TEXT", "gaussdb")
                        .toUpperCase().contains("CLOB"),
                "CHANGE 要跳过两个列名再取类型");
    }

    @Test
    @DisplayName("ALTER TABLE ADD CONSTRAINT 不是列定义")
    void alterTableAddConstraintIsNotAColumnDefinition() {
        String result = sqlConverter.convert(
                "ALTER TABLE t ADD CONSTRAINT text FOREIGN KEY (a) REFERENCES u(b)", "gaussdb");
        assertTrue(result.contains("CONSTRAINT text"),
                "约束名 text 不是类型: " + result);
    }

    @Test
    @DisplayName("保形转换路径同样只在类型声明位置替换")
    void preservingTextPathAlsoScopesTypeConversion() {
        // MyBatis 片段：占位符、缩进、列名 text 都必须原样保留
        String raw = "\n  SELECT text FROM notes\n  WHERE id = #{id}\n  LIMIT 1\n";
        String result = sqlConverter.convertPreservingText(raw, "gaussdb");

        assertTrue(result.contains("SELECT text FROM notes"), "列名不得被改: " + result);
        assertTrue(result.contains("#{id}"), "占位符必须保留: " + result);
        assertTrue(result.toUpperCase().contains("FETCH FIRST 1 ROWS ONLY"),
                "该转的语法仍要转: " + result);
    }

    @Test
    @DisplayName("列注释不得导致类型被漏改（真实建表语句几乎每列都带注释）")
    void columnCommentsDoNotHideTheType() {
        String withLineComment = sqlConverter.convertPreservingText(
                "CREATE TABLE t (\n  -- 主键\n  id INT,\n  body TEXT\n)", "gaussdb");
        assertTrue(withLineComment.contains("id INTEGER"),
                "行注释后面的类型仍要转换: " + withLineComment);
        assertTrue(withLineComment.contains("-- 主键"), "注释原文必须保留: " + withLineComment);

        String withBlockComment = sqlConverter.convertPreservingText(
                "CREATE TABLE t (id /*pk*/ INT, body TEXT)", "gaussdb");
        assertTrue(withBlockComment.contains("INTEGER"),
                "列名与类型之间的块注释不应挡住类型: " + withBlockComment);
        assertTrue(withBlockComment.toUpperCase().contains("CLOB"), withBlockComment);
    }

    @Test
    @DisplayName("多语句 .sql 脚本里每条 DDL 的类型都要转换")
    void everyStatementInAScriptIsConverted() {
        String result = sqlConverter.convertPreservingText(
                "CREATE TABLE t (a INT, b TEXT);\nCREATE TABLE u (c DATETIME);", "gaussdb");

        assertTrue(result.contains("INTEGER"), result);
        assertTrue(result.toUpperCase().contains("CLOB"), result);
        assertTrue(result.toUpperCase().contains("TIMESTAMP"), result);
        assertTrue(result.contains(";\n"), "语句分隔与换行应原样保留: " + result);
    }

    // ---------- CONVERT()：参数顺序按方言相反，靠内容判别 ----------

    @Test
    @DisplayName("CONVERT(expr, TYPE)（MySQL 形式）的第二参数是类型")
    void convertMySqlFormConvertsSecondArgument() {
        assertTrue(sqlConverter.convertPreservingText(
                        "SELECT CONVERT(body, TEXT) FROM notes", "gaussdb")
                        .contains("CONVERT(body, CLOB)"),
                "MySQL 形式的类型在第二个参数");
        assertTrue(sqlConverter.convertPreservingText(
                        "SELECT CONVERT(a, DECIMAL(10,2)) FROM t", "gaussdb")
                        .contains("NUMERIC(10,2)"),
                "精度修饰要原样保留");
    }

    @Test
    @DisplayName("CONVERT(TYPE, expr)（SQL Server 形式）的第一参数是类型")
    void convertSqlServerFormConvertsFirstArgument() {
        assertTrue(sqlConverter.convertPreservingText(
                        "SELECT CONVERT(TEXT, body) FROM notes", "gaussdb")
                        .contains("CONVERT(CLOB, body)"),
                "SQL Server 形式的类型在第一个参数");
        assertTrue(sqlConverter.convertPreservingText(
                        "SELECT CONVERT(DATETIME, a, 120) FROM t", "gaussdb")
                        .contains("CONVERT(TIMESTAMP, a, 120)"),
                "第三个 style 参数不影响判别");
    }

    @Test
    @DisplayName("CONVERT 两侧都像类型名时无法判别，宁可漏改也不能改错")
    void convertAmbiguousArgumentsAreLeftAlone() {
        // text 可能是列名（MySQL 形式）也可能是类型（SQL Server 形式），CHAR 同理
        String result = sqlConverter.convertPreservingText(
                "SELECT CONVERT(text, CHAR) FROM notes", "gaussdb");
        assertEquals("SELECT CONVERT(text, CHAR) FROM notes", result,
                "歧义情况下一个都不该动: " + result);
    }

    @Test
    @DisplayName("CONVERT(expr USING charset) 转的是字符集，不含类型")
    void convertUsingCharsetIsNotATypeConversion() {
        String sql = "SELECT CONVERT(body USING utf8mb4) FROM notes";
        assertEquals(sql, sqlConverter.convertPreservingText(sql, "gaussdb"));
    }

    @Test
    @DisplayName("Oracle 的 CONVERT(str, charset, charset) 字符集是字面量，不受影响")
    void convertWithQuotedCharsetsIsLeftAlone() {
        String sql = "SELECT CONVERT('abc', 'WE8ISO8859P1', 'UTF8') FROM dual";
        assertEquals(sql, sqlConverter.convertPreservingText(sql, "gaussdb"));
    }

    // ---------- DECLARE：三种写法的区间边界不同 ----------

    @Test
    @DisplayName("T-SQL 的 DECLARE @a INT, @b TEXT 逗号分隔，止于分号")
    void tsqlDeclareListIsConverted() {
        String result = sqlConverter.convertPreservingText("DECLARE @a INT, @b TEXT;", "gaussdb");
        assertTrue(result.contains("@a INTEGER"), result);
        assertTrue(result.contains("@b CLOB"), result);
    }

    @Test
    @DisplayName("T-SQL 的 DECLARE 后续语句不得被当成声明（否则列名会被改）")
    void tsqlDeclareDoesNotLeakIntoFollowingStatements() {
        String result = sqlConverter.convertPreservingText(
                "DECLARE @a INT; SELECT text FROM t;", "gaussdb");
        assertTrue(result.contains("@a INTEGER"), "声明本身要转: " + result);
        assertTrue(result.contains("SELECT text FROM t"),
                "后续语句里的列名 text 不是类型声明: " + result);
    }

    @Test
    @DisplayName("PL/SQL 声明块用分号分隔、止于 BEGIN，每条都要转")
    void plsqlDeclareBlockConvertsEveryDeclaration() {
        String result = sqlConverter.convertPreservingText(
                "DECLARE\n  v_count INT;\n  v_body TEXT;\nBEGIN\n  SELECT text INTO v_body FROM notes;\nEND;",
                "gaussdb");

        assertTrue(result.contains("v_count INTEGER"), result);
        assertTrue(result.contains("v_body CLOB"), result);
        assertTrue(result.contains("SELECT text INTO v_body"),
                "BEGIN 之后是语句体，列名 text 不得被改: " + result);
    }

    @Test
    @DisplayName("PL/SQL 声明带默认值时类型仍要转")
    void plsqlDeclareWithDefaultValueIsConverted() {
        assertTrue(sqlConverter.convertPreservingText("DECLARE v INT := 0;", "gaussdb")
                .contains("v INTEGER := 0"));
    }

    @Test
    @DisplayName("游标声明后面跟的是完整查询，整段跳过")
    void cursorDeclarationIsSkippedEntirely() {
        String sql = "DECLARE c CURSOR FOR SELECT a, text FROM t;";
        assertEquals(sql, sqlConverter.convertPreservingText(sql, "gaussdb"),
                "游标查询里的列名 text 不得被当成类型");

        // 游标查询的选择项与"变量名 类型名"同形（label text 是隐式别名），
        // 只有整段跳过才能不误判，逐项解析一定会踩中
        String aliased = "DECLARE c CURSOR FOR SELECT id, label text FROM notes;";
        assertEquals(aliased, sqlConverter.convertPreservingText(aliased, "gaussdb"),
                "隐式别名 text 不是类型声明");
    }
}
