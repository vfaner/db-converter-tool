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
}
