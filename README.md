# 个人智能投顾系统

基于 Spring Boot 的个人资产管理与投资辅助分析项目。系统将持仓、交易、基金定投、投资目标、价格预警和市场信息放在同一账户下，并使用大语言模型生成围绕用户账户的分析报告。报告仅供学习和参考，不构成投资建议。

## 主要功能

| 领域 | 当前实现 |
| --- | --- |
| 账户与画像 | 注册、登录、Session 会话、风险画像及风险评分 |
| 资产与交易 | 股票、基金、现金持仓；买入、卖出、交易流水及加权成本计算 |
| 投资计划 | 基金定投计划、自动及手动执行、执行记录、投资目标进度 |
| 风险与市场 | 行情关注、价格预警、财经新闻、市场情绪和规则式组合配置建议 |
| AI 分析 | 组合、单个标的、新闻、关键词及投资目标分析；分析历史查看与复用 |

组合分析会由 `AiPromptService` 整理画像、持仓、预警、新闻、市场情绪、目标和近期分析记录。`AIService` 并行请求 DeepSeek、Qwen、Kimi 生成候选建议，再将候选匿名交给 Gemini 评分。系统按八个维度的**得分之和**选择结果；候选不足或评审失败时有降级处理。其他分析场景主要调用 DeepSeek。模型返回的文字不代替原始账户数据或人工判断。

## 技术与数据来源

- Java 17、Spring Boot 4.0.5、Spring MVC、Thymeleaf、Spring Data JPA、MySQL、Redis。
- OkHttp、Jsoup、BCrypt、Spring Mail；定时任务负责行情、新闻、预警、净值快照和定投扫描。
- 当前代码中的股票行情使用东方财富接口；基金估值及净值使用天天基金/东方财富相关接口；新闻抓取使用新浪财经接口。第三方接口的可用性和数据时效可能变化。
- 主界面由 Thymeleaf 渲染。另有本地 `finance-frontend` Vue/Vite 工程，仅实现画像和组合建议页面，**不属于本 GitHub 仓库的完整前端替代品**。

## 本地运行

1. 安装 JDK 17、MySQL 和 Redis，并确保 Redis 在 `localhost:6379` 可访问。
2. 创建数据库：

   ```sql
   CREATE DATABASE finance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```

3. 配置环境变量。至少设置数据库密码；要使用相应 AI 服务时，设置对应密钥。PowerShell 当前终端示例：

   ```powershell
   $env:DB_USERNAME = 'root'
   $env:DB_PASSWORD = '你的数据库密码'
   $env:DEEPSEEK_API_KEY = '你的密钥'
   $env:QWEN_API_KEY = '你的密钥'
   $env:KIMI_API_KEY = '你的密钥'
   $env:GEMINI_API_KEY = '你的密钥'
   ```

   数据库地址可用 `DB_URL` 覆盖，端口可用 `SERVER_PORT` 覆盖。邮件提醒另需 `MAIL_USERNAME`、`MAIL_PASSWORD`（SMTP 授权码）。完整默认配置见 `src/main/resources/application.properties`。不要把实际密钥提交到仓库。

4. 在仓库根目录启动：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   如 Maven Wrapper 无法下载所需文件，可使用已安装的 Maven：`mvn spring-boot:run`。

5. 打开 `http://localhost:8080/register` 注册账户。首次使用按页面提示填写风险画像。

默认使用 JPA 的 `ddl-auto=update` 创建/更新表。`src/main/resources/sql/create_tables.sql` 只包含部分早期表结构，**不能作为完整数据库初始化脚本**。

## 项目结构

```text
src/main/java/org/example/finance/
  controller/   页面与业务入口
  api/          画像、组合建议 REST 接口
  service/      业务计算、外部数据获取、AI 编排
  repository/   JPA 数据访问
  model/        持久化实体
  config/       MVC、HTTP 与跨域配置
src/main/resources/
  templates/    Thymeleaf 页面
  application.properties
  sql/         早期 SQL 示例（非完整建表脚本）
src/test/       当前仅有 Spring 上下文加载测试
docs/HANDOFF.md  开发交接说明
```

## 已知限制

- 外部行情、净值、新闻和模型接口可能限流或返回缺失数据；AI 调用也可能产生费用。
- 市场情绪使用 Redis 历史数据；缺少 Redis 或外部行情时，相关功能需要单独排查。
- 当前测试覆盖有限，尚未形成完整的自动化业务测试与 AI 质量回归测试。
- 多模型评审只用于组合投资建议；分项评分由评审模型生成，并非独立的客观投资绩效验证。

接手开发时可查看 [交接文档](docs/HANDOFF.md)。

## 许可

当前仓库尚未提供 `LICENSE` 文件。公开仓库不等于已授予开源使用许可；如需允许他人复用、修改或分发，请先选择并添加合适的许可证。
