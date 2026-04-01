# Spring Authorization Server 项目结构总览

> 目标：帮助你在第一次阅读源码时，快速建立“这个仓库由哪些部分组成、每部分负责什么”的整体认知。  
> 范围：分析仓库根目录与一级目录（忽略 `archive` 目录内容）。

## 1) 工程整体定位

这是一个 **Gradle 多模块工程**，核心是 `oauth2-authorization-server` 模块（即授权服务器库本体），其余目录主要分为：

- 构建与发布基础设施（`buildSrc`、`dependencies`、`gradle`、`scripts`、`.github`）
- 文档体系（`docs`）
- 示例应用体系（`samples`）
- 质量与规范配置（`etc`、`git/hooks`、`.editorconfig` 等）

`settings.gradle` 会自动扫描并包含仓库中的 `*.gradle` / `*.gradle.kts`（排除 `build`、`buildSrc` 等），所以像 `samples/*/*.gradle` 这种“非默认命名的构建文件”也会被当作独立子项目纳入构建。

---

## 2) 根目录下各目录用途（忽略 archive）

### `.github`

- GitHub 平台配置目录。
- `workflows/`：CI/CD 工作流（构建、测试、发布、文档部署、依赖更新等）。
- `ISSUE_TEMPLATE/`：Issue 模板（bug、feature request）。
- `dependabot.yml`：自动依赖升级策略（Gradle/GitHub Actions/NPM）。
- `dco.yml`：DCO 相关配置。

### `.git`

- Git 元数据目录（版本历史、分支、对象等）。  
- 不属于业务源码，但对版本管理必需。

### `.gradle`

- 本地 Gradle 缓存与构建状态目录（例如 wrapper 解压、任务状态、缓存）。
- 由本地构建产生，不是项目业务源码。

### `.idea`

- IntelliJ IDEA/Cursor 的工程配置目录（模块、编译器、工作区状态等）。
- 与代码逻辑无关，偏本地 IDE 配置。

### `buildSrc`

- **自定义 Gradle 构建逻辑模块**（本工程构建插件与约定集中地）。
- 包含 `io.spring.convention.`* 插件和一批 Spring 风格构建插件（checkstyle/nohttp/publish/docs 等）。
- 作用是统一所有子模块的构建规范，减少重复配置。

### `dependencies`

- 依赖版本平台（BOM）模块。
- `spring-authorization-server-dependencies.gradle` 使用 `java-platform`，统一约束三方依赖版本。
- 核心模块和示例可通过 platform 引入一致版本。
- 它不是业务代码，而是“版本总闸门”：集中定义依赖版本，避免每个模块重复写版本号。
- 在本仓库中，核心模块通过 `management platform(project(":spring-authorization-server-dependencies"))` 使用这组版本约束。
- 这样做的直接收益是：升级依赖时改动集中、跨模块版本更一致、冲突更少。


### `docs`

- 参考文档工程（Antora + Asciidoc）。
- 包含文档内容、导航、示例片段、构建脚本，以及文档站点发布配置。
- `spring-authorization-server-docs.gradle` 定义文档构建任务（如生成 API 文档附件、打包 docs zip）。
- `docs/src/main/java` 里的 Java 代码是**文档示例源码**，供页面通过 `include::...` 直接引用，避免“文档代码片段和真实代码不一致”。
- `docs/src/test/java` 里的测试用于**验证文档示例可运行**（如授权码流程、PKCE、JPA/Redis、多租户等），属于“活文档”保障机制。
- `docs/src/main/resources` 与 `docs/src/main/java/**/*.yml` 提供示例配置文件，同样会被文档引用。

### `etc`

- 代码质量与仓库卫生配置。
- `checkstyle/`：Checkstyle 规则、抑制项、许可证头模板。
- `nohttp/`：nohttp 扫描规则，防止不安全或不合规链接。

### `git`

- 仓库自带的 Git hooks 脚本目录（如前向合并辅助脚本）。
- 用于维护分支流程和自动化约束。

### `gradle`

- Gradle 版本目录与 wrapper。
- `libs.versions.toml`：集中管理依赖/插件版本别名。
- `wrapper/`：`gradle-wrapper.jar` 和 `gradle-wrapper.properties`。

### `oauth2-authorization-server`

- **核心库模块**（Spring Authorization Server 实现主体）。
- `src/main`：授权服务器核心实现代码与资源。
- `src/test`：核心行为与协议兼容性测试。
- `spring-security-oauth2-authorization-server.gradle`：该库模块的依赖与打包配置。

### `samples`

- 示例应用集合，用于演示不同场景下的集成方式。
- 关键子模块：
  - `default-authorizationserver`：最小可运行授权服务器示例。
  - `demo-authorizationserver`：功能更完整的演示授权服务器。
  - `demo-client`：演示 OAuth2/OIDC 客户端应用。
  - `messages-resource`：受保护资源服务示例。
  - `users-resource`：用户资源服务示例（资源端 + OAuth2 支持）。
  - `backend-for-spa-client`：BFF 后端（给 SPA 使用，管理 token，不暴露给前端）。
  - `spa-client`：前端 SPA（与 BFF 配合）。
  - `x509-certificate-generator`：证书生成辅助工具示例。
- `samples/README.adoc` 给出了各示例用途和启动方式。

### `scripts`

- 构建/发布辅助脚本目录。
- `release/`：发布流程辅助脚本与发布说明片段配置。
- `update-dependencies.sh`：依赖更新辅助脚本。

---

## 3) 根目录下所有文件说明

> 这里指“仓库根目录直接可见文件”（不展开子目录）。

- `.editorconfig`  
统一编辑器格式规则（换行、缩进、行宽等）。
- `.gitignore`  
Git 忽略规则（构建产物、IDE 文件、日志等）。
- `CONTRIBUTING.adoc`  
贡献指南（PR 流程、DCO、提交规范、安全漏洞报告方式等）。
- `LICENSE.txt`  
开源许可证（Apache License 2.0）。
- `README.adoc`  
项目介绍、特性入口、文档链接、构建方式、支持渠道。
- `build.gradle`  
根项目构建配置：应用根级约定插件、发布节奏、Develocity scan 等。
- `gradle.properties`  
全局 Gradle 属性：项目版本、JVM 参数、并行与缓存开关等。
- `gradlew`  
Unix/macOS Gradle Wrapper 启动脚本。
- `gradlew.bat`  
Windows Gradle Wrapper 启动脚本。
- `settings.gradle`  
多模块装配入口：仓库命名、仓库源配置、动态扫描并 `include` 子项目。

---

## 4) 快速阅读建议（按顺序）

如果你想尽快“读懂项目”，建议按这个顺序：

1. `README.adoc`：先建立项目目标和边界。
2. `settings.gradle` + 根 `build.gradle`：理解模块是怎么被装配进来的。
3. `oauth2-authorization-server/`：进入核心库代码。
4. `samples/README.adoc` + 对应 sample：通过运行示例理解实际使用姿势。
5. `docs/modules/ROOT/pages/`：补全协议与配置模型的文档认知。
6. `buildSrc/`：最后看构建约定，理解团队工程化规则。

---

## 5) 你当前这份仓库的状态提醒

- 你已经执行过 `gradle build`，因此 `.gradle/`、`buildSrc/build/` 等目录里会出现本地构建产物。
- 这些是正常的本地状态，不影响你理解源码主结构。

