# Samples Overview

本文件是 `samples` 目录的快速导航，说明每个 demo 的用途、端口和配对关系。

## 每个 Demo 的作用与端口

| 模块 | 主要作用 | 本服务端口 | 配置中依赖的其他端口 |
|---|---|---:|---|
| `default-authorizationserver` | 最小可用的授权服务器样例（快速入门） | `9000` | 回调示例指向 `8080`（客户端） |
| `demo-authorizationserver` | 功能最全的授权服务器样例（OIDC、Device Code、Token Exchange、mTLS、社交登录等） | `9443`(HTTPS) + `9000`(HTTP) | 无固定本地依赖端口（社交登录为外部 IdP） |
| `demo-client` | OAuth2 客户端样例，演示多种授权类型与调用资源服务 | `8080` | `9000`(issuer), `9443`(mTLS token), `8443`(messages), `8091`(users) |
| `messages-resource` | 资源服务器样例（`/messages`），配合 scope 与 mTLS 场景 | `8443`(HTTPS) + `8090`(HTTP) | `9000`(JWK Set URI) |
| `users-resource` | 用户资源服务，演示 token exchange（delegation / impersonation） | `8091` | `9000`(issuer), `8090`(messages) |
| `backend-for-spa-client` | BFF 后端，前端通过会话访问，后端持有并转发 token | `8080` | `9000`(issuer + `/userinfo`), `8090`(`/messages`), `4200`(SPA base-uri) |
| `spa-client` | Angular SPA 前端，配合 BFF 模式使用 | `4200` (`ng serve`) | `8080`(BFF) |
| `x509-certificate-generator` | 证书与 keystore 生成工具（支持 mTLS 示例） | 无 Web 端口 | 无 |

## Demo 工程补充说明

### `default-authorizationserver`

- 这是“最小配置”授权服务器，重点是跑通基础 OAuth2/OIDC 流程。
- 配置里的回调地址指向 `8080`（如 `/login/oauth2/code/...`、`/authorized`），`8080` 对应的是 OAuth2 客户端应用（通常是 `demo-client`）。
- 因此它更适合作为“授权服务器单体入门”，如果要完整体验登录回调链路，建议搭配一个客户端服务一起运行。

### `demo-authorizationserver`

- 这是“功能展示型”授权服务器，包含 OIDC、Device Code、Token Exchange、mTLS、社交登录等扩展能力。
- 它会同时监听两个端口：
  - `9443`：主端口（HTTPS）
  - `9000`：额外 HTTP 端口（通过 `TomcatServerConfig` 添加的 connector）
- `9443` 的 HTTPS 来自 SSL bundle 配置，而不是传统 `server.ssl.key-store` 写法。关键配置在 `application.yml`：
  - `server.port: 9443`
  - `server.ssl.bundle: demo-authorizationserver`
  - `spring.ssl.bundle.jks.demo-authorizationserver` 下定义了 `keystore.p12`、alias、password 等信息
- `9000` 的作用是给本地 demo 中常规 `issuer-uri`（例如 `http://localhost:9000`）和普通联调场景使用；mTLS 等需要 TLS 的 token 请求走 `https://localhost:9443/oauth2/token`。
- 关于浏览器请求 `/.well-known/appspecific/com.chrome.devtools.json?continue` 的说明：
  - 这是 Chrome/DevTools 的探测请求，不属于 OAuth 业务接口。
  - 若该路径返回 404，通常不影响 OAuth 流程，但在某些本地调试场景下可能干扰回跳体验。
  - 当前 sample 已做兼容处理：
    - 在 `DefaultSecurityConfig` 放行 `/.well-known/appspecific/**`
    - 增加静态文件 `static/.well-known/appspecific/com.chrome.devtools.json`（返回 `{}`）
  - 若修改后未生效，请重启 `demo-authorizationserver`。

启动成功后可以看到
```text
2026-04-19T18:12:44.957+08:00  INFO 39733 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on ports 9443 (https), 9000 (http) with context path '/'
```

### `messages-resource`

- 资源服务器同样采用“双端口”模式：
  - `8443`：主端口（HTTPS）
  - `8090`：额外 HTTP 端口（通过 `TomcatServerConfig` 添加）
- 其设计思路与 `demo-authorizationserver` 类似，都是“主 HTTPS + 额外 HTTP”。
- 区别在于使用侧重点：
  - `8443` 主要用于安全访问路径（配置了 `server.ssl.client-auth: need`，要求客户端证书）
  - `8090` 主要用于本地联调和普通调用路径（例如 `backend-for-spa-client` 与 `users-resource` 默认都调用 `8090`）

启动成功后可以看到
```text
2026-04-19T18:15:22.370+08:00  INFO 42233 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on ports 8443 (https), 8090 (http) with context path ''
```

### `users-resource`（与 `messages-resource` 对比）

- 两者都属于资源服务，但职责不同：
  - `messages-resource`：最终下游资源服务，直接校验 token 并返回 `/messages`
  - `users-resource`：中间层资源服务，接收上游请求后通过 Token Exchange 获取/转换 token，再调用 `messages-resource`
- 如果你只想验证“demo-client 带 token 调资源服务”，只启动 `messages-resource` 就可以。
- 如果你要验证“多跳微服务中的 token 传递与转换”（delegation / impersonation），需要启动 `users-resource`。
- `Token Exchange` 不是 demo 私有协议，使用的是 OAuth2 标准扩展（RFC 8693），对应授权类型值：
  - `urn:ietf:params:oauth:grant-type:token-exchange`

### `spa-client`

- `spa-client` 是 Angular 前端，配合 `backend-for-spa-client` 实现 BFF（Backend For Frontend）模式。
- 启动方式：
  - 进入目录：`samples/spa-client`
  - 首次启动先安装依赖：`npm install`
  - 启动开发服务器：`ng serve`
  - 访问地址：`http://127.0.0.1:4200`
- 运行前建议先启动：
  - `demo-authorizationserver`
  - `messages-resource`
  - `backend-for-spa-client`
- 如果本机没有 Angular CLI，可先安装：
  - `npm install -g @angular/cli`

## 配对启动建议

### 1) Demo 组合（推荐先学）

- `demo-authorizationserver`
- `demo-client`
- `messages-resource`
- （可选）`users-resource`（需要看 token exchange 时再加）

访问：`http://127.0.0.1:8080`

### 2) SPA/BFF 组合

- `demo-authorizationserver`
- `messages-resource`
- `backend-for-spa-client`
- `spa-client`（`ng serve`）

访问：`http://127.0.0.1:4200`

## 端口冲突与注意事项

- `demo-client` 和 `backend-for-spa-client` 都使用 `8080`，通常不要同时启动。
- `demo-authorizationserver` 除 `9443` 外还会额外监听 `9000`（见 `TomcatServerConfig` 的附加 connector）。
- `messages-resource` 除 `8443` 外还会额外监听 `8090`（同样由附加 connector 提供）。
- mTLS 相关 token 请求走 `https://localhost:9443/oauth2/token`。
- 推荐启动顺序：先启动 `demo-authorizationserver`，再启动 `demo-client`。若先启动 `demo-client`，由于依赖 `issuer-uri`（`http://localhost:9000`）不可用，启动阶段可能报错。

## 常用启动命令

```bash
# Demo authorization server
./gradlew -b samples/demo-authorizationserver/samples-demo-authorizationserver.gradle bootRun

# Demo client
./gradlew -b samples/demo-client/samples-demo-client.gradle bootRun

# Messages resource
./gradlew -b samples/messages-resource/samples-messages-resource.gradle bootRun

# Users resource (optional)
./gradlew -b samples/users-resource/samples-users-resource.gradle bootRun

# Backend for SPA
./gradlew -b samples/backend-for-spa-client/samples-backend-for-spa-client.gradle bootRun

# SPA frontend (in samples/spa-client)
npm install
ng serve
```
