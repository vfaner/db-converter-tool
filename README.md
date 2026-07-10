# 数据库信创改造工具

[English](README_EN.md) | 中文

---

## 📖 项目简介

数据库信创改造工具是一个基于 Spring Boot 3 的 Web 应用，用于将标准 SQL 智能转换为国产信创数据库的兼容语法。支持手工逐条转换和批量自动扫描替换两种模式，并提供 AI 深度优化能力。

### ✨ 主要特性

- 🔄 **智能 SQL 转换**：基于 JSqlParser 解析 AST，进行函数替换、类型映射、语法改写
- 🤖 **AI 深度优化**：集成阿里云百炼（通义千问），对转换后的 SQL 进行深度优化
- 🖼️ **图片 OCR 识别**：上传包含 SQL 的图片，通过多模态视觉模型自动识别 SQL 语句
- 📂 **批量扫描**：自动扫描项目目录，精准识别需要改造的 SQL（支持 Java / MyBatis XML / SQL / 配置文件）
- 📁 **文件夹选择器**：可视化浏览和选择项目目录，无需手动输入路径
- 🚀 **一键改造**：自动生成备份并执行批量替换

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2, JSqlParser 4.9, OkHttp 4.12 |
| 前端 | Vue 3 CDN + Element Plus CDN（纯 HTML，无构建工具） |
| AI 接口 | 阿里云百炼 OpenAI 兼容接口（qwen-max + qwen-vl-max） |
| 构建 | Maven, JDK 17+ |

## 📋 系统要求

- JDK 17+
- Maven 3.6+
- 阿里云百炼 API Key（可选，用于 OCR 和 AI 优化功能）

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd db-converter-tool
```

### 2. 配置 API Key（可选）

编辑 `src/main/resources/application.yml`：

```yaml
anthropic:
  api:
    key: 你的百炼API_Key
```

或通过环境变量设置：

```bash
export ANTHROPIC_API_KEY=你的百炼API_Key
```

> API Key 在 [阿里云百炼控制台](https://bailian.console.aliyun.com) 获取。不配置 API Key 也可以使用基础的 SQL 转换和批量扫描功能。

### 3. 编译打包

```bash
mvn clean package
```

### 4. 运行应用

```bash
java -jar target/db-converter-tool-1.0.0.jar
```

或使用 Maven 直接运行：

```bash
mvn spring-boot:run
```

### 5. 访问应用

打开浏览器访问：http://localhost:8080

## 📖 使用指南

### 手工处理

1. 访问首页，默认进入「手工处理」页面
2. **上传图片**（需配置 API Key）：点击上传区域选择图片，系统自动通过 AI 识别 SQL
3. **输入 SQL**：在文本框中输入或粘贴 SQL 语句
4. **选择目标数据库**：从下拉框选择目标数据库
5. **AI 优化**（可选，需配置 API Key）：开启「启用 AI 深度优化」开关
6. **转换**：点击「转换」按钮，查看转换结果
7. **复制结果**：点击「复制」按钮复制转换后的 SQL

### 自动处理

1. 点击左侧菜单「自动处理」
2. **选择项目路径**：
   - 手动输入目录路径，或
   - 点击「📁 选择文件夹」按钮，在弹窗中浏览并选择目录
3. **选择目标数据库**：选择目标数据库
4. **扫描**：点击「开始扫描」按钮，等待扫描完成
5. **查看清单**：扫描完成后查看改造清单，确认每项改造内容
6. **执行改造**：点击「立即改造」按钮，确认后执行批量替换（自动创建 `.bak` 备份）

## 📊 支持的数据库

| 数据库 | 标识 | 说明 |
|--------|------|------|
| 高斯数据库 | `gaussdb` | 华为 GaussDB |
| 达梦数据库 | `dameng` | DM8 |
| 人大金仓 | `kingbase` | KingBase ES |
| OceanBase | `oceanbase` | 蚂蚁 OceanBase |
| TiDB | `tidb` | PingCAP TiDB |
| 南大通用 | `gbase` | GBase 8s |
| 神州通用 | `shentong` | ShenTong OSCAR |

## 📡 API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ocr` | POST | 上传图片识别 SQL（需 AI） |
| `/api/convert` | POST | 转换 SQL |
| `/api/optimize` | POST | AI 优化 SQL（需 AI） |
| `/api/databases` | GET | 获取支持的数据库列表 |
| `/api/scan` | POST | 启动目录扫描任务 |
| `/api/scan/progress` | GET | 查询扫描进度 |
| `/api/replace` | POST | 执行批量替换 |
| `/api/replace/custom` | POST | 使用自定义清单执行替换 |
| `/api/directories` | GET | 列出目录下的子文件夹（文件夹选择器用） |

## 🔍 SQL 扫描能力

自动处理模式下的扫描器支持从多种文件类型中精准提取 SQL：

| 文件类型 | 扫描策略 |
|---------|---------|
| `.java` | `@Query` 注解、Text Block、SQL 字符串字面量；自动排除日志/断言等非 SQL 上下文 |
| `.xml` | MyBatis Mapper 标签（`<select>` / `<insert>` / `<update>` / `<delete>`），处理动态标签（`<if>` / `<where>` / `<foreach>` 等） |
| `.sql` | 完整的 SQL 文件语句（以 `;` 结尾），自动过滤注释 |
| `.properties` / `.yml` | 配置项中包含 SQL 关键字的值 |

扫描器内置 7 层过滤器，有效排除 `FUNCTION()` 占位符、控制流片段、Java 代码片段等假阳性结果。

## 🔧 配置说明

### application.yml 配置项

```yaml
# 服务端口
server:
  port: 8080

# 文件上传限制
spring:
  servlet:
    multipart:
      max-file-size: 10MB

# AI 接口配置（阿里云百炼 OpenAI 兼容接口）
anthropic:
  api:
    key: ${ANTHROPIC_API_KEY:YOUR_BAILIAN_API_KEY}
    base-url: ${ANTHROPIC_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}
    model: ${ANTHROPIC_MODEL:qwen-max-latest}
    vision-model: ${ANTHROPIC_VISION_MODEL:qwen-vl-max-latest}

# 日志配置
logging:
  level:
    com.dbconverter: DEBUG
  file:
    name: logs/db-converter.log
```

## 📁 项目结构

```
db-converter-tool/
├── src/
│   ├── main/
│   │   ├── java/com/dbconverter/
│   │   │   ├── DbConverterApplication.java      # 启动类
│   │   │   ├── common/                          # 通用类（Result, ConversionItem, ScanTask）
│   │   │   ├── config/                          # 全局异常处理
│   │   │   ├── controller/
│   │   │   │   ├── ManualController.java        # 手工处理 API
│   │   │   │   └── AutoController.java          # 自动处理 + 目录浏览 API
│   │   │   └── service/
│   │   │       ├── AnthropicApiService.java     # 百炼 AI 服务
│   │   │       ├── SqlConverter.java            # SQL 方言转换引擎
│   │   │       ├── FileScannerService.java      # 文件扫描服务
│   │   │       └── FileReplacerService.java     # 文件替换服务
│   │   └── resources/
│   │       ├── application.yml                  # 应用配置
│   │       └── static/                          # 前端页面
│   │           ├── index.html                   # 入口（跳转手工处理）
│   │           ├── manual.html                  # 手工处理页面
│   │           └── auto.html                    # 自动处理页面
│   └── test/                                    # 测试代码
├── pom.xml
├── README.md
└── README_EN.md
```

## 🧪 运行测试

```bash
mvn test
```

## 📝 注意事项

1. **API Key 安全**：请勿将 API Key 提交到代码仓库，建议使用环境变量传入
2. **文件备份**：自动改造会创建 `.bak` 备份文件，请在改造前确认备份
3. **大项目扫描**：扫描大型项目可能需要一定时间，页面会实时显示进度
4. **SQL 解析**：部分复杂 SQL 可能无法被 JSqlParser 完全解析，会自动降级为正则处理
5. **目录过滤**：扫描时自动跳过 `target/`、`build/`、`.git/`、`node_modules/`、`.idea/` 等非业务目录

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

## ☕ 打赏支持

如果这个项目对你有帮助，欢迎请我喝杯咖啡 ☕

<p align="center">
  <img src="docs/ds.png" alt="打赏二维码" width="300">
</p>

## 📄 许可证

个人 / 非商业使用免费，商业使用需获得作者授权。详见 [LICENSE](LICENSE)。
