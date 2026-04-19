# Spring Authorization Server 文档体系学习笔记

这份笔记总结了当前项目里“文档相关工程”的核心知识点，重点回答：

1. 文档格式  
2. 如何编译成 HTML（`api` 目录和 `site` 目录）  
3. `modules/ROOT/examples` 的作用、软连接（symlink）是什么、为什么这样设计  
4. `docs/src/test` 的作用（验证文档示例真的可用）  
5. 为什么 `samples/README.adoc` 不会自动进文档站，它的作用是什么  

---

## 1) 文档格式：`.adoc`（AsciiDoc）

- 项目文档主要使用 `.adoc`（AsciiDoc）格式。
- 文档页面主体在：`docs/modules/ROOT/pages`
- 常见写法：
  - `=`, `==`, `===`：标题层级
  - `include::...[]`：把外部文件内容插入页面
  - `xref:...`：页面内/跨页面链接
  - `[source,java]` + `----`：代码块
- 这个项目通过 Antora + Asciidoctor 构建文档站。

---

## 2) 编译成 HTML 的命令与产物目录

### 2.1 查看 docs 模块可用任务

```bash
./gradlew :spring-authorization-server-docs:tasks --all
```

### 2.2 生成 API 文档（Javadoc）

```bash
./gradlew :spring-authorization-server-docs:docs
```

输出目录（API）：

- `docs/build/api`

可直接打开：

```bash
open docs/build/api/index.html
```

### 2.3 生成整站（Antora Site）

```bash
./gradlew :spring-authorization-server-docs:antora
```

输出目录（Site）：

- `docs/build/site`

当前版本页面通常在：

- `docs/build/site/1.5-SNAPSHOT/index.html`

可直接打开：

```bash
open docs/build/site/1.5-SNAPSHOT/index.html
```

> 说明：首次执行 `antora` 可能因网络导致 UI bundle 下载超时（`antora-playbook.yml` 里配置了 GitHub UI bundle URL），重试或使用代理后通常可恢复。

---

## 3) `modules/ROOT/examples` 的作用与软连接设计

### 3.1 关键结论

- `docs/modules/ROOT/examples/docs-src` 是一个 symlink，真实指向 `docs/src`
- `docs/modules/ROOT/examples/samples` 是一个 symlink，真实指向 `samples`

也就是说，这不是两份代码，而是同一份内容的两个访问路径。

### 3.2 为什么这样设计

`docs/antora.yml` 中定义了文档属性：

- `examples-dir: example$docs-src`
- `samples-dir: example$samples`

文档页通过下面这种方式引入代码：

- `include::{examples-dir}/main/java/...[]`
- `include::{samples-dir}/demo-authorizationserver/...[]`

设计收益：

- **单一事实来源**：文档代码片段来自真实源码，不需要手抄/粘贴。
- **降低漂移风险**：减少“文档代码块和源码不一致”。
- **路径解耦**：文档只依赖 `{examples-dir}` / `{samples-dir}`，底层目录调整更容易。
- **支持多版本站点构建**：Antora 对这类 “example$...” 资源定位友好。

### 3.3 symlink（软连接）是什么

- 软连接可以理解成“文件系统快捷方式”。
- 访问软连接路径时，系统会跳到它指向的真实路径。
- 改任一侧，实际上是改同一个真实文件。

---

## 4) `docs/src/test` 的作用：验证文档示例真的可用

`docs/src/test` 通常不会被文档页面 `include::` 引用，它的核心职责是质量保障：

- 验证 `docs/src/main` 中示例代码在当前版本仍然能编译并运行。
- 覆盖核心流程（如授权码流程、PKCE、多租户、Redis/JPA 示例等）。
- 当框架升级或内部实现变化时，及时暴露“文档示例失效”问题。

这类测试本质上是“文档示例回归测试”，避免文档变成“看起来对、运行报错”。

可单独运行：

```bash
./gradlew :spring-authorization-server-docs:test
```

---

## 5) `samples/README.adoc` 不会自动进文档站，它的作用

`samples/README.adoc` 主要是 **samples 目录的运行手册**，而不是 Antora 文档站页面。

原因：

- Antora 的内容源配置是 `start_path: docs`，只会把 `docs` 下符合模块结构的页面构建进站点。
- `samples/README.adoc` 位于 `samples` 目录，不在站点页面源路径内。

它的实际价值：

- 指导如何本地启动样例工程（给维护者、贡献者、学习者）。
- 作为仓库内样例入口说明（各 sample 的目标和运行方式）。
- 可被正式文档通过链接或代码片段 include 的方式间接引用。

### 5.1 可以单独转换成 HTML 吗？

可以。虽然它不进入 Antora 站点，但你可以独立把它转换为 HTML。

```bash
npx --yes @asciidoctor/cli -D samples/build/html samples/README.adoc
```

输出文件：

- `samples/build/html/README.html`

打开方式（macOS）：

```bash
open samples/build/html/README.html
```

说明：

- 当前环境如果没有全局 `asciidoctor` 命令，使用 `npx @asciidoctor/cli` 即可。

---

## 补充：常见误区

- 误区 1：`examples/docs-src` 和 `docs/src` 是两份文件  
  - 实际：前者是 symlink，指向后者。
- 误区 2：用了 `include` 就不会有任何不一致  
  - 实际：代码块一致性大幅提升，但“说明文字”仍可能和代码语义漂移，仍需评审与测试。
- 误区 3：`asciidoctor` 一定会生成完整站点  
  - 实际：完整站点通常由 `antora` 任务生成，`docs/build/site` 才是最终站点目录。

---

## 一页速查（命令）

```bash
# 查看 docs 模块任务
./gradlew :spring-authorization-server-docs:tasks --all

# 生成 API 文档（Javadoc）
./gradlew :spring-authorization-server-docs:docs
open docs/build/api/index.html

# 生成完整文档站
./gradlew :spring-authorization-server-docs:antora
open docs/build/site/1.5-SNAPSHOT/index.html

# 运行 docs 示例测试
./gradlew :spring-authorization-server-docs:test
```
