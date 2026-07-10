# Database Migration Tool

English | [中文](README.md)

---

## 📖 Introduction

Database Migration Tool is a Spring Boot 3 web application that converts standard SQL into compatible syntax for domestic Chinese databases. It supports both manual single-statement conversion and batch auto-scanning with replacement, along with AI-powered deep optimization.

### ✨ Features

- 🔄 **Intelligent SQL Conversion**: AST-based parsing via JSqlParser — function replacement, type mapping, syntax rewriting
- 🤖 **AI Deep Optimization**: Integrated with Alibaba Cloud Bailian (Qwen) for deep SQL optimization
- 🖼️ **Image OCR Recognition**: Upload images containing SQL, automatically recognized via multimodal vision model
- 📂 **Batch Scanning**: Scan project directories to precisely identify SQL that needs migration (Java / MyBatis XML / SQL / config files)
- 📁 **Folder Picker**: Visual directory browser — no need to type paths manually
- 🚀 **One-click Migration**: Auto-generate backups and execute batch replacements

## 🛠️ Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Spring Boot 3.2, JSqlParser 4.9, OkHttp 4.12 |
| Frontend | Vue 3 CDN + Element Plus CDN (pure HTML, no build tools) |
| AI API | Alibaba Cloud Bailian OpenAI-compatible API (qwen-max + qwen-vl-max) |
| Build | Maven, JDK 17+ |

## 📋 Requirements

- JDK 17+
- Maven 3.6+
- Alibaba Cloud Bailian API Key (optional, for OCR and AI optimization)

## 🚀 Quick Start

### 1. Clone the project

```bash
git clone <repository-url>
cd db-converter-tool
```

### 2. Configure API Key (optional)

Edit `src/main/resources/application.yml`:

```yaml
anthropic:
  api:
    key: YOUR_BAILIAN_API_KEY
```

Or set via environment variable:

```bash
export ANTHROPIC_API_KEY=your-bailian-api-key
```

> Get your API Key from the [Alibaba Cloud Bailian Console](https://bailian.console.aliyun.com). Basic SQL conversion and batch scanning work without an API Key.

### 3. Build

```bash
mvn clean package
```

### 4. Run

```bash
java -jar target/db-converter-tool-1.0.0.jar
```

Or run directly with Maven:

```bash
mvn spring-boot:run
```

### 5. Access

Open your browser: http://localhost:8080

## 📖 Usage Guide

### Manual Processing

1. Visit the homepage — defaults to the "Manual Processing" page
2. **Upload Image** (requires API Key): Click the upload area to select an image; the AI will recognize the SQL
3. **Input SQL**: Type or paste SQL statements in the text box
4. **Select Target Database**: Choose from the dropdown
5. **AI Optimization** (optional, requires API Key): Toggle the "AI Deep Optimization" switch
6. **Convert**: Click "Convert" to see the result
7. **Copy**: Click "Copy" to copy the converted SQL

### Automatic Processing

1. Click "Automatic Processing" in the left menu
2. **Select Project Path**:
   - Type the directory path manually, or
   - Click the "📁 Select Folder" button to browse and pick a directory
3. **Select Target Database**: Choose from the dropdown
4. **Scan**: Click "Start Scan" and wait for completion
5. **Review**: Check the migration checklist after scanning
6. **Execute**: Click "Start Migration" to confirm and execute batch replacements (`.bak` backups are created automatically)

## 📊 Supported Databases

| Database | Identifier | Description |
|----------|------------|-------------|
| GaussDB | `gaussdb` | Huawei GaussDB |
| DM | `dameng` | DM8 |
| KingBase | `kingbase` | KingBase ES |
| OceanBase | `oceanbase` | Ant Group OceanBase |
| TiDB | `tidb` | PingCAP TiDB |
| GBase | `gbase` | GBase 8s |
| ShenTong | `shentong` | ShenTong OSCAR |

## 📡 API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/ocr` | POST | Upload image for SQL recognition (requires AI) |
| `/api/convert` | POST | Convert SQL |
| `/api/optimize` | POST | AI-optimize SQL (requires AI) |
| `/api/databases` | GET | Get supported database list |
| `/api/scan` | POST | Start directory scan task |
| `/api/scan/progress` | GET | Query scan progress |
| `/api/replace` | POST | Execute batch replacement |
| `/api/replace/custom` | POST | Execute replacement with custom checklist |
| `/api/directories` | GET | List subdirectories (for folder picker) |

## 🔍 SQL Scanning Capabilities

The scanner in automatic mode extracts SQL from multiple file types with high precision:

| File Type | Scanning Strategy |
|-----------|-------------------|
| `.java` | `@Query` annotations, Text Blocks, SQL string literals; auto-excludes logging/assertion contexts |
| `.xml` | MyBatis Mapper tags (`<select>` / `<insert>` / `<update>` / `<delete>`), handles dynamic tags (`<if>` / `<where>` / `<foreach>`, etc.) |
| `.sql` | Complete statements (terminated by `;`), auto-filters comments |
| `.properties` / `.yml` | Config values containing SQL keywords |

The scanner includes a 7-layer filter to eliminate false positives such as `FUNCTION()` placeholders, control-flow fragments, and Java code snippets.

## 🔧 Configuration

### application.yml

```yaml
# Server port
server:
  port: 8080

# File upload limits
spring:
  servlet:
    multipart:
      max-file-size: 10MB

# AI API config (Alibaba Cloud Bailian OpenAI-compatible)
anthropic:
  api:
    key: ${ANTHROPIC_API_KEY:YOUR_BAILIAN_API_KEY}
    base-url: ${ANTHROPIC_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}
    model: ${ANTHROPIC_MODEL:qwen-max-latest}
    vision-model: ${ANTHROPIC_VISION_MODEL:qwen-vl-max-latest}

# Logging
logging:
  level:
    com.dbconverter: DEBUG
  file:
    name: logs/db-converter.log
```

## 📁 Project Structure

```
db-converter-tool/
├── src/
│   ├── main/
│   │   ├── java/com/dbconverter/
│   │   │   ├── DbConverterApplication.java      # Entry point
│   │   │   ├── common/                          # Common classes (Result, ConversionItem, ScanTask)
│   │   │   ├── config/                          # Global exception handler
│   │   │   ├── controller/
│   │   │   │   ├── ManualController.java        # Manual processing API
│   │   │   │   └── AutoController.java          # Auto processing + directory browsing API
│   │   │   └── service/
│   │   │       ├── AnthropicApiService.java     # Bailian AI service
│   │   │       ├── SqlConverter.java            # SQL dialect conversion engine
│   │   │       ├── FileScannerService.java      # File scanning service
│   │   │       └── FileReplacerService.java     # File replacement service
│   │   └── resources/
│   │       ├── application.yml                  # App configuration
│   │       └── static/                          # Frontend pages
│   │           ├── index.html                   # Entry (redirects to manual)
│   │           ├── manual.html                  # Manual processing page
│   │           └── auto.html                    # Auto processing page
│   └── test/                                    # Test code
├── pom.xml
├── README.md
└── README_EN.md
```

## 🧪 Running Tests

```bash
mvn test
```

## 📝 Notes

1. **API Key Security**: Do not commit API Keys to the repository; use environment variables instead
2. **File Backups**: Automatic migration creates `.bak` backup files; verify backups before proceeding
3. **Large Projects**: Scanning large projects may take time; progress is displayed in real-time
4. **SQL Parsing**: Complex SQL that JSqlParser cannot fully parse will fall back to regex processing
5. **Directory Filtering**: Automatically skips `target/`, `build/`, `.git/`, `node_modules/`, `.idea/` and other non-business directories

## 🤝 Contributing

Issues and Pull Requests are welcome!

## ☕ Support

If this project has been helpful to you, feel free to buy me a coffee ☕

<p align="center">
  <img src="docs/ds.png" alt="Donation QR Code" width="300">
</p>

## 📄 License

Free for personal / non-commercial use. Commercial use requires prior permission from the author. See [LICENSE](LICENSE) for details.
