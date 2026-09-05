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

## 🖼️ 效果展示

### 手工处理

<p align="center">
  <img src="docs/db_xc_sd.png" alt="手工处理页面" width="800">
</p>

### 自动处理

<p align="center">
  <img src="docs/db_xc_zd.png" alt="自动处理页面" width="800">
</p>

### AI 配置管理

<p align="center">
  <img src="docs/db_xc_ai.png" alt="AI 配置列表页面" width="800">
</p>

### 新增 AI 配置

<p align="center">
  <img src="docs/db_xc_ai_add.png" alt="新增 AI 配置弹窗" width="800">
</p>

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | Spring Boot 3.5, JSqlParser 4.9, OkHttp 4.12 |
| 前端 | Vue 3 + Element Plus（资源全部本地化，无构建工具、无外网依赖） |
| AI 接口 | 支持 OpenAI 兼容协议 / Anthropic 原生协议，厂商与模型可在网页端自由配置 |
| 构建 | Maven, JDK 17+ |

## 📋 系统要求

**构建机器**

- JDK 17+
- Maven 3.6+

**运行机器**

- JRE 17+（打包产物为自包含 jar，无需 Maven 与联网，详见 [离线 / 内网部署](#-离线--内网部署)）
- 任意 AI 模型服务（可选，用于 OCR 和 AI 优化功能）：支持云端厂商或内网私有化部署

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd db-converter-tool
```

### 2. 配置 AI（可选，全部在网页端完成）

**无需修改任何配置文件**。启动服务后打开浏览器，进入左侧菜单 **「AI配置」** 即可可视化维护：

- **多厂商**：可添加任意数量的厂商配置，一键切换当前启用项
- **协议选择**：`OpenAI 兼容` 或 `Anthropic 原生`
- **完全自定义**：API 地址、文本模型、视觉模型、API Key、最大 Token、温度、超时均可自定义
- **快速模板**：内置阿里云百炼 / DeepSeek / 智谱 / Kimi / Claude / Ollama / vLLM 等模板，填充后可任意修改
- **连接测试**：保存前即可点击「测试连接」验证地址、Key 与模型是否可用

> 配置持久化在 `config/ai-config.json`（与 jar 分离，重新部署不丢失，文件权限自动收紧为 600）。
> 不配置 AI 也可以正常使用基础的 SQL 转换和批量扫描功能。

#### 内网 / 离线环境

前端资源（Vue、Element Plus、axios）已全部本地化，不请求任何外网 CDN。
AI 侧只需把 API 地址指向内网服务即可，例如：

| 场景 | 协议 | API 地址 | 说明 |
|------|------|----------|------|
| Ollama | OpenAI 兼容 | `http://192.168.1.10:11434/v1` | Key 可填任意占位值 |
| vLLM | OpenAI 兼容 | `http://192.168.1.10:8000/v1` | Key 可填任意占位值 |
| one-api / new-api 网关 | OpenAI 兼容 | `http://192.168.1.10:3000` | 使用网关下发的 Key |
| 内网 Claude 网关 | Anthropic 原生 | `http://192.168.1.10:8080` | 走 `/v1/messages` 接口 |

API 地址支持填写根地址或含 `/v1` 的地址，系统会自动补全接口路径，并在页面上实时预览最终请求地址。

### 3. 编译打包

```bash
mvn clean package
```

### 4. 运行应用

```bash
java -jar target/db-converter-tool-1.0.1.jar
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
4. **（可选）启用 AI 自动优化**：打开「启用 AI 自动优化」开关，
   在规则转换的基础上再让 AI 针对目标数据库优化一轮（需先在「AI 配置」中配置可用模型）
5. **扫描**：点击「开始扫描」按钮，等待扫描完成
6. **查看清单**：扫描完成后查看改造清单，确认每项改造内容。
   「来源」列会标出该条是 `规则` 转换还是 `🤖 AI` 优化；
   AI 优化的条目在悬浮预览里可同时看到规则转换结果与 AI 优化结果的对比
7. **执行改造**：点击「立即改造」按钮，确认后执行批量替换（自动创建 `.bak` 备份）

#### 自动处理中的 AI 优化

自动处理的 AI 优化与手工处理共用「AI 配置」中当前启用的模型，行为如下：

| 行为 | 说明 |
|------|------|
| 触发时机 | 规则转换完成后，对已识别出的改造项再跑一轮 AI |
| 条目上限 | 默认最多 200 条（`app.ai.auto-optimize.max-items`），超出部分仅用规则转换结果 |
| 并发 | 默认 4 线程（`app.ai.auto-optimize.concurrency`） |
| 进度 | 扫描占 0–60%，AI 优化占 60–100% |
| AI 不可用 | 自动跳过 AI 阶段，任务正常完成，页面提示"已跳过 AI 优化" |
| 单条失败 | 保留该条的规则转换结果，不影响整体任务 |

> ⚠️ **AI 优化结果会被写回源文件，请务必在「立即改造」前逐条核对目标 SQL。**
>
> 为避免 AI 输出破坏源码，AI 结果在写回前会经过安全校验，
> 以下情况一律**拒绝并保留规则转换结果**：
> - 输出带 Markdown 代码围栏（``` ```sql ... ``` ```）或残留反引号
> - 在 `.java`/`.xml`/`.properties`/`.yml` 中出现裸双引号或反斜杠（会破坏字符串字面量）
> - 夹带原文中不存在的中文等解释性文字
> - 输出长度相比规则结果暴增
> - 输出不再是一条合法 SQL
>
> 此外，非 `.sql` 文件中的多行输出会被压成单行，结尾分号会与被替换的原文保持一致。
>
> 需要强调的是：安全校验保证的是**语法安全**（不破坏文件），
> **无法保证语义正确**——模型完全可能返回一条语法合法但业务语义有偏差的 SQL，
> 因此人工核对不可省略。改造前的 `.bak` 备份是最后一道防线。

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
| 中兴 GoldenDB | `golden` | GoldenDB，MySQL 兼容 |
| MySQL | `mysql` | **反方向**：把国产库 / Oracle 写法回迁成 MySQL |

`mysql` 是唯一一个反方向的目标库，用于国产库回迁或者两套库并行维护：`NVL`→`IFNULL`、`SYSDATE`→`NOW()`、`TO_CHAR`→`DATE_FORMAT`（格式串一并翻回 `%Y-%m-%d`）、`VARCHAR2`→`VARCHAR`、`CLOB`→`LONGTEXT`、`NUMBER`→`DECIMAL`、`WHERE ROWNUM <= n`→`LIMIT n`、`FETCH FIRST n ROWS ONLY`→`LIMIT n`、`IDENTITY(1,1)`→`AUTO_INCREMENT`。源本来就是 MySQL 时输出与输入完全一致，不会被改坏。

其中 `WHERE ROWNUM <= n` 会连同 `WHERE` 一起换成 `LIMIT n`；但 `AND ROWNUM <= n`（同层已有其他条件）**故意不动** —— `LIMIT` 必须挪到句尾才合法，就地替换会产出 `status = 1 AND LIMIT 10` 这种半对半错的结果，这种情况留给人工判断。

## 📡 API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ocr` | POST | 上传图片识别 SQL（需 AI） |
| `/api/convert` | POST | 转换 SQL |
| `/api/optimize` | POST | AI 优化 SQL（需 AI） |
| `/api/databases` | GET | 获取支持的数据库列表 |
| `/api/scan` | POST | 启动目录扫描任务（`enableAi` 可选，开启 AI 优化） |
| `/api/scan/progress` | GET | 查询扫描进度（含 AI 阶段进度与统计） |
| `/api/replace` | POST | 执行批量替换（返回 `successFiles`/`skippedFiles`/`unmatchedItems`/`warnings`） |
| `/api/replace/custom` | POST | 使用自定义清单执行替换（返回字段同上） |
| `/api/directories` | GET | 列出目录下的子文件夹（文件夹选择器用） |
| `/api/ai-config` | GET | 查询全部 AI 配置（Key 脱敏） |
| `/api/ai-config` | POST | 新增 AI 配置 |
| `/api/ai-config/{id}` | PUT | 更新 AI 配置（Key 留空表示不修改） |
| `/api/ai-config/{id}` | DELETE | 删除 AI 配置 |
| `/api/ai-config/{id}/activate` | POST | 启用指定 AI 配置 |
| `/api/ai-config/status` | GET | 查询当前 AI 可用状态 |
| `/api/ai-config/test` | POST | 测试 AI 配置连通性 |

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
# 服务端口与监听地址
server:
  port: 8080
  # 本工具可浏览目录并就地改写文件，且无鉴权，默认只监听本机回环地址。
  # 确需从其他机器访问时才覆盖为 0.0.0.0，并自行做好网络隔离。
  address: ${SERVER_ADDRESS:127.0.0.1}

# 文件上传限制
spring:
  servlet:
    multipart:
      max-file-size: 10MB

# AI 配置持久化文件（AI 厂商/模型/Key 均在网页「AI配置」菜单维护）
app:
  ai:
    config-file: ${AI_CONFIG_FILE:config/ai-config.json}

# 仅用于「首次启动」时生成一条默认 AI 配置，之后修改这里不影响已保存的配置
anthropic:
  api:
    key: ${ANTHROPIC_API_KEY:}
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

## 🔒 离线 / 内网部署

`mvn package` 产出的已经是**自包含的可执行 jar**（约 27MB），44 个依赖、内嵌 Tomcat、以及 Vue / Element-Plus / axios 等前端资源全部打在包内，页面无任何外网 CDN 引用。目标机器上**只需要一个 JRE 17+，不需要 Maven、不需要 `~/.m2`、不需要联网**。

### 部署步骤

在**有外网的机器**上构建，只把 jar 带进内网：

```bash
# 外网机器
mvn clean package -DskipTests
# 产物：target/db-converter-tool-1.0.1.jar —— 只需拷贝这一个文件

# 内网机器
mkdir -p /opt/db-converter && cd /opt/db-converter
# 放入 jar 后直接启动
java -jar db-converter-tool-1.0.1.jar
```

首次启动会在**当前工作目录**下自动生成两个目录，所以请先 `cd` 到你希望存放数据的位置再启动：

```
/opt/db-converter/
├── db-converter-tool-1.0.1.jar
├── config/ai-config.json          # AI 配置持久化，升级换 jar 不会丢
└── logs/db-converter.log          # 日志
```

> `ai-config.json` 内含 API Key，创建时权限为 `600`（仅属主可读写），迁移或备份时请注意保持。

如需固定路径而不依赖启动目录：

```bash
java -jar db-converter-tool-1.0.1.jar \
     --app.ai.config-file=/etc/db-converter/ai-config.json \
     --logging.file.name=/var/log/db-converter/db-converter.log
```

### 监听地址：内网多机访问需要显式放开

出于安全考虑（本工具可浏览任意目录并就地改写文件，且**无任何鉴权**），默认只监听回环地址 `127.0.0.1`，即只能从本机访问。

若需让内网其他同事访问，必须显式覆盖：

```bash
SERVER_ADDRESS=0.0.0.0 java -jar db-converter-tool-1.0.1.jar
# 或
java -jar db-converter-tool-1.0.1.jar --server.address=0.0.0.0
```

> ⚠️ **放开前请务必评估风险**：`GET /api/directories?path=/` 可遍历服务器上任意目录，`/api/replace/*` 可就地改写文件。放开监听等同于把该机器的文件系统读写能力开放给同网段所有人。建议仅在可信网段内放开，或叠加反向代理鉴权 / 防火墙白名单。

### 离线环境下的功能边界

| 功能 | 离线可用 | 说明 |
|------|---------|------|
| SQL 手工转换 | ✅ | 纯本地规则引擎，无需网络 |
| 批量目录扫描 | ✅ | 纯本地文件遍历 |
| 文件就地替换 + 备份 | ✅ | 纯本地文件操作 |
| 网页界面 / 静态资源 | ✅ | 前端资源已全部内置 |
| 图片 OCR 识别（`/api/ocr`） | ⚠️ | **需要可达的大模型服务** |
| AI 深度优化（`/api/optimize`） | ⚠️ | **需要可达的大模型服务** |

后两项需要能访问 OpenAI 兼容或 Anthropic 原生接口。内网若有私有化部署的模型服务（vLLM / Ollama / One-API / 各厂商信创一体机等），进入网页 **「AI配置」** 菜单，把 API 地址改为内网地址即可，例如：

```
协议：OpenAI 兼容
API 地址：http://192.168.1.10:8000/v1
模型：<你的内网模型名>
```

内网若**没有**任何可用模型服务，SQL 转换与批量替换等核心能力不受影响，仅 OCR 与 AI 优化两项功能不可用，请提前告知使用方。

### 备选：在内网机器上构建

若因流程要求必须在内网执行 `mvn package`，需先在外网预热本地仓库并整体搬迁：

```bash
# 外网机器：把包括父 POM 在内的依赖全部拉到本地仓库
mvn dependency:go-offline -DincludeParent=true
mvn clean package -DskipTests          # 确保插件也已下载
tar czf m2-repo.tar.gz -C ~ .m2/repository

# 内网机器：解压到 ~/.m2/repository 后离线构建
mvn -o clean package -DskipTests
```

> 直接搬 jar 比搬仓库可靠得多（Maven 插件的解析很容易漏包），除非有强制要求，优先用前者。

## 📁 项目结构

```
db-converter-tool/
├── src/
│   ├── main/
│   │   ├── java/com/dbconverter/
│   │   │   ├── DbConverterApplication.java      # 启动类
│   │   │   ├── common/                          # 通用类（Result, ConversionItem, ScanTask, AiConfig）
│   │   │   ├── config/                          # 全局异常处理
│   │   │   ├── controller/
│   │   │   │   ├── ManualController.java        # 手工处理 API
│   │   │   │   ├── AutoController.java          # 自动处理 + 目录浏览 API
│   │   │   │   └── AiConfigController.java      # AI 配置管理 API
│   │   │   └── service/
│   │   │       ├── AiService.java               # AI 服务（OpenAI / Anthropic 双协议）
│   │   │       ├── AiConfigService.java         # AI 配置持久化服务
│   │   │       ├── SqlConverter.java            # SQL 方言转换引擎
│   │   │       ├── FileScannerService.java      # 文件扫描服务
│   │   │       └── FileReplacerService.java     # 文件替换服务
│   │   └── resources/
│   │       ├── application.yml                  # 应用配置
│   │       └── static/                          # 前端页面（资源全部本地化）
│   │           ├── index.html                   # 入口（跳转手工处理）
│   │           ├── manual.html                  # 手工处理页面
│   │           ├── auto.html                    # 自动处理页面
│   │           ├── ai-config.html               # AI 配置页面
│   │           ├── support.html                 # 支持与更新页面
│   │           └── libs/                        # Vue / Element Plus / axios 本地资源
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
6. **AI 优化需人工复核**：AI 结果的安全校验只保证语法安全（不破坏文件），
   无法保证语义正确，改造前请逐条核对

## 🐛 已修复的转换缺陷

以下问题会直接产出错误的 SQL 并写回源文件，已修复并补充了回归测试：

| 问题 | 影响 | 修复 |
|------|------|------|
| 转换丢失语句结尾分号 | JSqlParser 的 `toString()` 不输出 `;`，`.sql` 脚本中相邻语句替换后会**粘连成一条**；同时会为"仅丢分号"的兼容语句产生**虚假改造项** | 转换结果按原文补回 `;`（幂等，原文无分号则不加） |
| 达梦 `LIMIT n` → `WHERE ROWNUM <= n` 产生双 WHERE | 原句已有 WHERE 时得到 `... WHERE status = 1 WHERE ROWNUM <= 3`，是**非法 SQL** | 同层已有 WHERE 时改用 `AND`；子查询/引号内的 `where` 不误判 |
| `NOW()` → `CURRENT_TIMESTAMP()` / `SYSDATE()` 多余括号 | 函数替换只换函数名保留括号，但这两个是**不带括号的关键字**；7 个方言中 5 个（PG 系 + 达梦）产出**非法语法**，而 `NOW()` 是最常用的函数之一 | 目标为无括号关键字时，零参数调用形式 `NOW( )` 连括号一起消除；带参数的函数替换不受影响 |
| `.bak` 备份被二次运行覆盖 | 备份在替换前**无条件** `REPLACE_EXISTING`。对同一目录重跑改造时，`.bak` 会被覆盖成"已改造"的内容，**原始版本永久丢失、无法回滚**；即使本次一处都没改也照样覆盖 | 仅在 `.bak` 不存在时创建；且改为"确有改动才写盘 + 才建备份" |
| 匹配失败被当成改造成功 | 扫描端对 SQL 做过归一化（XML 去标签、`#{}`→`?`，Java 反转义、多行压平），归一化后的文本在原文里不逐字存在，`contains` 失败 → 不改文件、不报错、`successFiles` 照样 +1，界面显示"改造完成" | 未命中条目计入 `unmatchedItems` 并生成告警明细；只有真正改动过的文件才计入 `successFiles`，未改动的计入 `skippedFiles`；前端弹窗显式列出未生效项与原因 |
| 替换处数统计不准 | `String.replace` 是**全局**替换，但每个条目无论命中几处都只 `+1`，上报的改动数低估真实范围 | 按实际非重叠出现次数统计 |
| 字符串字面量被当成代码替换 | `WHERE kind = 'text'` 被改成 `WHERE kind = 'CLOB'`，**静默改变业务语义**（查询返回的行完全不同），比语法错误危险得多；字面量里的 `NOW()` 同样会被改 | 替换前先做词法扫描，标记出字符串字面量、引号标识符（`"x"` / `` `x` ``）与注释的位置，落在其中的匹配一律跳过 |
| 降级路径漏掉修复 | `convertByRegex`（JSqlParser 解析失败时的兜底）抄了一份独立的函数/类型替换实现，导致 `NOW()` 括号等修复只生效于 AST 路径 | 降级路径改为复用同一批替换方法，消除重复实现 |
| XML / 复杂 Java 字符串**扫得出来改不进去** | 扫描端归一化后的文本在原文里不逐字存在，MyBatis XML（含 `#{}` 或动态标签）与 Java 多行字符串 / Text Block **全部无法改造**——几乎覆盖所有真实项目 | 改用「保形转换 + 偏移量倒序拼接」，详见下方专节 |
| 改造清单行号恒为 0 | 前端无法根据行号定位到源码位置 | 按片段偏移量计算真实行号 |
| 与类型名撞名的**裸标识符**被误替换 | `TEXT`/`INT`/`DATE` 既是类型名也是常见列名，`SELECT text FROM notes` 被改成 `SELECT CLOB FROM notes`、`CREATE TABLE notes (text TEXT)` 被改成 `(CLOB CLOB)`——**静默引用不存在的列**，运行时才炸 | 类型替换从"到处替换、只避开字面量"改为**只在类型声明槽位替换**，详见下方专节 |

字面量保护的覆盖范围：

```sql
-- ✅ 字面量内容保持原样，字面量外的转换照常生效
WHERE kind = 'text' AND created > NOW()
WHERE kind = 'text' AND created > CURRENT_TIMESTAMP

-- ✅ 引号标识符、注释、转义引号（''）均受保护
SELECT "text", `text` FROM t WHERE a = 'it''s text'   -- 全部保持不变
```

### ✅ 已修复：与类型名撞名的**裸标识符**

`TEXT` / `INT` / `DATE` / `DATETIME` / `DOUBLE` 既是类型名，也是极常见的列名。词法掩码只能识别引号和注释，无法判断一个裸词是列名还是类型声明，因此**列名会被当成类型替换掉**——这是静默的语义破坏，改完的 SQL 引用了不存在的列，运行时才炸：

```sql
SELECT text FROM notes            →  SELECT CLOB FROM notes             -- ❌ 列不存在
INSERT INTO t (int) VALUES (1)    →  INSERT INTO t (INTEGER) VALUES (1) -- ❌ 列不存在
CREATE TABLE notes (text TEXT)    →  CREATE TABLE notes (CLOB CLOB)     -- ❌ 列名也被改了
SELECT body AS text FROM notes    →  SELECT body AS CLOB FROM notes     -- ❌ 结果列名错误
```

**修复**：把类型替换从"到处都替换，只避开字面量"（黑名单）改成**只在类型声明槽位替换**（白名单）。已覆盖的槽位：

| 槽位 | 示例 |
|------|------|
| `CREATE TABLE` 列定义的第二个词 | `CREATE TABLE t (body TEXT)` |
| `CAST(expr AS type)` | `SELECT CAST(body AS TEXT)` |
| PostgreSQL / 高斯的 `expr::type` | `SELECT body::TEXT` |
| `ALTER TABLE` 的 `ADD` / `MODIFY` / `CHANGE` / `ALTER COLUMN` | `ALTER TABLE t ALTER COLUMN n SET DATA TYPE TEXT` |
| `CONVERT()` 的类型参数（顺序按方言判别，见下） | `CONVERT(body, TEXT)` / `CONVERT(TEXT, body)` |
| `DECLARE` 声明的变量类型（T-SQL 与 PL/SQL 两种写法） | `DECLARE @a INT, @b TEXT;` / `DECLARE\n v_body TEXT;\nBEGIN` |

配套细节：表级约束项（`PRIMARY KEY (...)`、`KEY text (...)`、`CONSTRAINT text CHECK (...)`）不是列定义，整项跳过；`CREATE TABLE x AS SELECT ...` 的括号里是查询而非列定义，也跳过；列名与类型之间的**注释会一并跳过**（真实建表语句几乎每列都带 `-- 注释`，只跳空白会导致类型被漏改）。

**`CONVERT()` 的参数顺序靠内容判别**：MySQL 是 `CONVERT(expr, TYPE)`，SQL Server 是 `CONVERT(TYPE, expr)`，顺序正好相反；而本工具只收到目标库、**从不知道源库方言**，所以不能按方言配置去猜。改为看哪一侧「像类型名」（对照一份内置类型词表，允许 `DECIMAL(10,2)` 这样的精度修饰）：只有一侧像 → 那一侧就是类型；**两侧都像 → 一个都不动**（`CONVERT(text, CHAR)` 既可读作 MySQL 形式也可读作 SQL Server 形式，无解）。`CONVERT(expr USING charset)` 不含类型，整段跳过；Oracle 的 `CONVERT(str, 'charset', 'charset')` 里字符集是字符串字面量，本身就在掩码内。

**`DECLARE` 的区间边界按写法不同**：T-SQL（`DECLARE @a INT, @b TEXT;`）以逗号分隔、止于分号；PL/SQL 声明块以分号分隔、**止于 `BEGIN`**（`BEGIN` 之后是语句体，里面的 `SELECT text` 是列名不是声明）。游标声明（`DECLARE c CURSOR FOR SELECT ...`）后面跟的是完整查询，**整段跳过**——查询的选择项与「变量名 类型名」同形（`SELECT id, label text` 里 `text` 是隐式别名），逐项解析必然误判。

**方向性权衡**：白名单的失效模式是**漏改**（少迁移一处，人工可发现），而不是**改坏**（静默产出错误 SQL）。这是有意选择的方向。目前已知的剩余漏改点：`CONVERT()` 两侧都像类型名的歧义情形（上文），以及 `DECLARE @t TABLE (id INT, body TEXT)` 里的**内层列定义**（识别到第二个词是 `TABLE` 而非类型名后整项跳过，括号内不再深入）。这两类需人工复核。

> **为什么不用 JSqlParser AST**（README 早前版本曾把 AST 称为"正解"，实测证明行不通）：
> 1. JSqlParser 4.9 的 `ColumnDefinition` / `ColDataType` **没有实现 `ASTNodeAccess`**，`Token` 也只有行列号、没有绝对字符偏移——无法把类型节点映射回原文位置，而"按偏移写回原文"是保形改造的前提。
> 2. 含 `#{}` / 动态标签的 MyBatis 片段**根本无法被解析**，而这恰好是保形改造的主场景。
>
> 所以只能走词法扫描。好处是它对两条转换路径（AST 路径与保形路径）一视同仁，也对解析不了的片段同样有效。

### ✅ 已修复：XML 与复杂 Java 字符串改造不生效

**根因**是扫描端与替换端的文本"表示层"不一致：扫描端把 SQL 归一化（XML 去标签、`#{}`→`?`、Java 反转义、多行压平）之后才交给转换器，而替换端拿归一化后的文本回原文里做 `contains` 匹配——原文里根本不存在这段文本。因此下列写法（几乎覆盖所有真实项目）**扫得出来但改不进去**：

- MyBatis XML：只要含 `#{}` / `${}` 参数或动态标签
- Java：多行字符串（含 `\n` 转义）、字符串拼接、Text Block

**修复分两步，缺一不可：**

1. **保形转换** —— 新增 `SqlConverter.convertPreservingText()`，只做局部 token 替换，不经过 JSqlParser 的 `toString()` 重排。原本的 `convertStatement` 除了 `toString()` 之外并未真正用到 AST，所以跳过它**不损失任何转换能力**，却能完整保留换行、缩进、`#{}` 占位符和 Java 转义。
2. **偏移量替换** —— 扫描时记录每个片段在原文中的区间 `[startOffset, endOffset)`（由 `Matcher.start(n)/end(n)` 得到；注释改为用等长空格抹除以保持下标对齐），替换时**按 `startOffset` 倒序**逐个拼接，不再做文本匹配。倒序是必需的，否则前一处改动会让后面所有下标错位。

配套的三道安全闸：

| 闸门 | 作用 |
|------|------|
| 写入前校验 `content.substring(start, end)` 仍与 `sourceSql` 逐字一致 | 扫描后文件被改过 / 清单被手工编辑过时，下标已失效——此时拒绝写入并上报为未命中，**绝不盲目按下标覆盖** |
| 替换文本的换行数、双引号数不得**多于**原文 | 防止提前结束 Java 单行字面量或 XML 属性；少于原文（AI 压行）是安全的，放行 |
| 含 `#{}` / `${}` / XML 标签的片段不送 AI 优化 | AI 只保证输出"一条合法 SQL"，不保证保留占位符和动态标签；按偏移写回是原样覆盖，占位符一旦丢失就是源文件损坏 |

无偏移信息的条目（如手工提交给 `/api/replace/custom` 的清单）自动退回原来的逐字匹配路径，行为不变。

改造后的效果（缩进、`#{id}`、字面量、结尾 `";` 全部保持原样，diff 只落在真正需要改的 token 上）：

```diff
  <update id="touch">
-     UPDATE users SET updated_at = NOW() WHERE id = #{id}
+     UPDATE users SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}
  </update>

- "SELECT id, name FROM users WHERE created > NOW() AND kind = 'CLOB' LIMIT 10";
+ "SELECT id, name FROM users WHERE created > CURRENT_TIMESTAMP AND kind = 'CLOB' FETCH FIRST 10 ROWS ONLY";
```

> 顺带修掉的两处：改造清单的**行号**此前恒为 0（前端无法定位），现在是真实行号；Java 的三个提取正则会重复命中同一段文本，按偏移替换要求区间互不重叠，因此新增了重叠区间去重。

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

## ☕ 打赏支持

如果这个项目对你有帮助，欢迎请我喝杯咖啡 ☕

<p align="center">
  <img src="docs/ds.png" alt="打赏二维码" width="600">
</p>

## 📄 许可证

个人 / 非商业使用免费，商业使用需获得作者授权。详见 [LICENSE](LICENSE)。
