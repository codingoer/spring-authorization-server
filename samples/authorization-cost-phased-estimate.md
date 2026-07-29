# 授权服务五阶段成本评估

## 背景与评估口径

当前公司已经有自己的登录态和 `ctoken-service`，公司 App 与官网可以完成用户登录，现阶段不需要马上替换现有登录体系。

同时，公司内部服务与服务之间调用当前使用 `btoken-service`。`btoken-service` 是自研 opaque token 服务，使用 `clientId + clientSecret` 换取服务端 token，模型上接近自定义版 `client_credentials`，但不是标准 OAuth2。

为了降低公司 App / 官网直接切换到 Spring Authorization Server 的风险，中间增加一个 `ctoken-service` JWT 化阶段。这个阶段仍然沿用现有登录入口和接口，只把 C 端登录态从 opaque token 改为 JWT。后续公司 App / 官网再切到授权服务器的 `authorization_code + PKCE`。

新授权服务的建设可以分五个阶段推进：

1. 第三方接入，完成标准授权认证流程。
2. scope 阶段，第三方调用我方资源服务器时校验 scope。
3. 我方服务与服务之间调用也接入授权服务器和 scope。
4. `ctoken-service` JWT 化，公司 App / 官网仍走现有登录接口，但登录 token 从 opaque 改为 JWT。
5. 我方 App 与官网接入授权服务器，逐步替换 `ctoken-service` 登录态。

本评估按人日拆分，并按后端、前端、测试给出阶段汇总。

## 总览

| 阶段 | 阶段目标 | 后端 | 前端 | 测试 | 文档/接入支持 | 小计 |
|------|----------|------:|------:|------:|------:|------:|
| 第一阶段 | 支持第三方完成 OAuth2/OIDC 授权认证流程，并让 TYK 兼容 SAS JWT 基础验签 | 69-117 | 12-22 | 26-45 | 5-8 | 112-192 |
| 第二阶段 | 引入 scope，第三方调用资源服务器时做 scope 校验 | 60-93 | 25-40 | 24-43 | 5-8 | 114-184 |
| 第三阶段 | 我方服务间调用从 btoken 迁移到授权服务器 + scope | 45-77 | 10-18 | 20-37 | 4-6 | 79-138 |
| 第四阶段 | `ctoken-service` JWT 化，公司登录态先从 opaque token 过渡到 JWT | 52-86 | 10-20 | 27-48 | 4-6 | 93-160 |
| 第五阶段 | 我方 App 与官网接入授权服务器，逐步替换 ctoken | 27-50 | 39-70 | 31-53 | 5-8 | 102-181 |
| **合计** | 五阶段完整落地 | **253-423** | **96-170** | **128-226** | **23-36** | **500-855** |

> 说明：阶段估算存在重叠和复用空间，最终总量不一定机械相加。若阶段之间连续推进、人员稳定、公共组件复用充分，整体可向低位靠近；如果每阶段间隔较久、资源服务数量多、前端 SDK 改造复杂，则会靠近高位。

## 当前口径下需要特别调整的点

| 调整点 | 原因 | 对阶段的影响 |
|--------|------|--------------|
| 第三方服务端系统的 `client_credentials` 前移到第一阶段 | 第三方不一定都有用户参与，服务端系统对接需要应用身份 token | 第一阶段增加 `client_credentials` 基础能力 |
| TYK JWT 基础验签前移到第一阶段 | 第三方拿到 SAS JWT 后调用我方资源服务器，流量会先经过 TYK | 第一阶段做 JWT 验签兼容模式；第二阶段再做 scope 映射校验 |
| 第三阶段不再从零建设 `client_credentials` | 第一阶段已经支持第三方服务端系统，第三阶段主要是复用并扩展到内部服务 | 第三阶段重点变成 `btoken-service` 迁移 |
| 第二阶段 scope 仍聚焦第三方开放 API | 第三方拿到 token 后调用资源服务器，必须先完成 scope 与资源校验 | 第二阶段不要求内部服务全部接入 |
| 增加 `ctoken-service` JWT 化阶段 | 公司 App / 官网当前已有稳定登录态，直接切 SAS 影响面大 | 第四阶段先让现有登录态 JWT 化，资源侧先适配 JWT |
| 第五阶段只迁移我方 App / 官网到授权服务器 | 服务间调用属于第三阶段，`ctoken-service` JWT 化属于第四阶段 | 第五阶段聚焦 C 端 OAuth2/OIDC 登录入口切换 |

## 第一阶段：第三方授权认证流程

### 阶段目标

第一阶段先支持第三方应用接入，完成标准 OAuth2/OIDC 授权认证流程。

这一阶段重点不是替换公司 App / 官网现有登录态，而是让第三方能通过标准协议完成两类接入：

```text
用户参与的第三方应用：
第三方应用 -> 授权服务器 /oauth2/authorize
用户登录/确认
授权服务器返回 authorization code
第三方后端用 code 换 JWT token
第三方获得用户身份 token

无用户参与的第三方服务端系统：
第三方服务端系统 -> 授权服务器 /oauth2/token
grant_type=client_credentials
授权服务器返回应用身份 JWT token
```

### 范围

- 搭建 Spring Authorization Server 基础能力。
- 支持 `authorization_code + PKCE`。
- 支持第三方服务端系统使用 `client_credentials` 获取应用身份 token。
- 支持 Web 后端应用、App、小程序等第三方客户端形态。
- 支持第三方 client 注册，至少包括 `client_id`、`redirect_uri`、grant type、PKCE 要求。
- 支持基础 OIDC：`openid`、`id_token`、基础 `userinfo`。
- 支持 `refresh_token`。
- 支持 logout / token revocation 的基础能力。
- 授权服务器接入现有用户中心/CUR 做密码、验证码、微信授权码等用户认证。
- TYK 网关支持新 SAS JWT 基础验签，并保留现有 ctoken/btoken 兼容通道。
- 可选择识别现有 ctoken 登录态，用于提升用户体验，但不强制在第一阶段替换 ctoken。
- 提供第三方接入样例和文档。

### 不包含

- 不做完整业务 scope 体系。
- 不做 API Market scope 申请审批。
- 不要求 TYK 按 API Market 做 scope 拦截。
- 不要求资源服务器按 scope 拦截业务 API。
- 不替换公司 App / 官网现有 ctoken 登录态。
- 不做旧 opaque token 到新 JWT token 的迁移或 exchange。

### 工作拆分

| 工作项 | 说明 | 后端 | 前端 | 测试 |
|--------|------|------:|------:|------:|
| 授权服务器基础搭建 | SAS 引入、JDBC 持久化、JWK、issuer、token 配置 | 15-22 | 0 | 5-8 |
| OAuth2/OIDC 主流程 | `/oauth2/authorize`、`/oauth2/token`、authorization code、PKCE、OIDC id_token | 15-25 | 0 | 6-10 |
| 第三方服务端 client_credentials | 第三方服务端系统无用户场景，使用 `grant_type=client_credentials` 获取应用身份 JWT | 6-10 | 0 | 2-4 |
| 用户认证适配 | 接现有用户中心/CUR，适配密码、验证码、微信授权码等认证方式 | 10-16 | 4-8 | 4-7 |
| 基础登录/授权页面 | 登录页、基础授权确认页、错误页 | 4-8 | 8-14 | 3-5 |
| 第三方 client 管理 | 初版 client 注册、redirect URI、client secret、PKCE 策略，可先弱管理后台或配置化 | 6-10 | 0-4 | 2-4 |
| TYK JWT 基础兼容 | TYK 支持 SAS JWT issuer/JWKS/audience 基础校验，同时保留旧 ctoken/btoken 通道 | 8-14 | 0 | 4-6 |
| refresh/logout/revocation | refresh token、登出、token 撤销基础流程 | 5-8 | 0-2 | 3-5 |
| 接入样例与文档 | Web 后端、App/小程序 PKCE 接入说明，错误码说明 | 0-4 | 0 | 2-4 |

### 阶段汇总

| 角色 | 人日 |
|------|------:|
| 后端 | 69 - 117 |
| 前端 | 12 - 22 |
| 测试 | 26 - 45 |
| 文档/接入支持 | 5 - 8 |
| **小计** | **112 - 192** |

### 阶段产出

- 第三方可以基于标准 OAuth2/OIDC 接入。
- 第三方可以通过 `authorization_code + PKCE` 获取 JWT token。
- 第三方服务端系统可以通过 `client_credentials` 获取应用身份 JWT token。
- TYK 可以识别并基础校验 SAS JWT，同时不影响现有 ctoken/btoken 流量。
- 用户认证仍复用现有用户中心能力。
- 公司 App / 官网继续使用当前 ctoken 登录态，不受影响。

## 第二阶段：scope 与资源服务器校验

### 阶段目标

第三方已经可以完成授权认证后，第二阶段引入 scope，确保第三方调用我方资源服务器时按授权范围访问。

这一阶段重点是：

```text
第三方 token 中携带 scope
TYK 网关校验 JWT 和 scope
资源服务器做本地 scope/data permission 校验
API Market 管理 API 与 scope 的映射
```

### 范围

- 设计并落地用户 scope 与业务 scope。
- 建立 scope 定义、client scope 申请审批、API 与 scope 映射。
- API Market 审批通过后同步到 Spring Authorization Server 的 RegisteredClient。
- Consent 页支持按 scope 展示和授权确认。
- TYK 网关接入 API Market 映射，校验 JWT、scope、路由。
- 资源服务器引入本地 scope / data permission 校验。
- 第三方 API 调用支持 `insufficient_scope` 错误返回。
- 现有公司 App / 官网仍可继续使用 ctoken 调用原有接口；第三方新接口走 SAS JWT。

### 不包含

- 不要求所有内部服务间调用都接入授权服务器。
- 不要求公司 App / 官网迁移到 SAS。
- 不要求一次性覆盖全部业务域。

### 工作拆分

| 工作项 | 说明 | 后端 | 前端 | 测试 |
|--------|------|------:|------:|------:|
| scope 模型设计 | 用户 scope、业务 scope、read/write、静默/非静默、授权记录模型 | 8-12 | 0 | 2-3 |
| API Market scope 管理 | scope 定义、API-scope 映射、client scope 申请、审批流 | 20-30 | 18-28 | 8-12 |
| SAS scope 同步 | 审批通过后同步 RegisteredClient.scopes，撤回/禁用同步 | 8-14 | 0-2 | 4-6 |
| Consent 页增强 | scope 分组、三状态勾选、已授权 scope 展示、增量授权 | 8-12 | 7-10 | 4-7 |
| TYK scope 校验 | 在第一阶段 JWT 基础验签之上，增加 scope 提取、API Market 映射缓存、缓存刷新、错误码 | 10-18 | 0 | 5-8 |
| 资源服务校验 | 公共鉴权组件、本地 data permission、用户数据边界校验 | 15-25 | 0 | 8-15 |
| 审计与日志 | scope 申请、审批、授权、访问拒绝、资源访问审计 | 6-10 | 0-2 | 3-5 |

### 阶段汇总

| 角色 | 人日 |
|------|------:|
| 后端 | 60 - 93 |
| 前端 | 25 - 40 |
| 测试 | 24 - 43 |
| 文档/接入支持 | 5 - 8 |
| **小计** | **114 - 184** |

### 阶段产出

- 第三方 token 中带业务 scope。
- TYK 网关可以按 API Market 配置校验 scope。
- 资源服务器可以做本地 scope/data permission 校验。
- 第三方 scope 不足时返回标准 `insufficient_scope`。
- ctoken 与 SAS JWT 在资源侧开始并行存在，但面向不同接入方和迁移阶段。

## 第三阶段：我方服务间调用接入授权服务器

### 阶段目标

第三阶段把我方内部服务与服务之间的调用也纳入授权服务器和 scope 体系。

当前这部分能力由 `btoken-service` 承担。它通过自定义接口使用 `clientId + clientSecret` 生成 opaque access token，并提供 validate/authenticate/revoke 能力。未来应逐步迁移为 Spring Authorization Server 的标准 `client_credentials`。

这一阶段重点是：

```text
内部服务注册为 OAuth2 client
服务间调用通过 client_credentials 获取 token
token 中携带应用身份 scope
网关和资源服务校验应用身份 scope
```

### 现有 btoken-service 对应关系

| 当前 btoken-service | 未来授权服务器 |
|---------------------|----------------|
| `clientId` | OAuth2 `client_id` |
| `clientSecret` | OAuth2 client secret 或更强客户端认证方式 |
| opaque `accessToken` | JWT access token |
| `accessTokenExpiration` | SAS `TokenSettings.accessTokenTimeToLive` |
| `clientType=INTERNAL/VENDOR` | client 分类、审批策略、scope 命名空间 |
| `piQueryPrivilege` | 明确的业务 scope，例如内部资料查询类 scope |
| `/api/v1/int/token/validate` | 资源服务本地 JWT 校验，或网关校验 |
| `/api/v1/int/token/authenticate` | JWT + scope + data permission 校验 |
| `/api/v1/int/token/revoke` | OAuth2 token revocation 或 client 禁用/密钥轮换 |

### 范围

- 支持内部系统 client 注册和密钥管理。
- 支持 `client_credentials`。
- 建立服务间调用 scope，例如 `partner.order.read`、`partner.verification.write`，也可以按公司内部命名规范调整为 `internal.*`。
- API Market 支持内部服务 scope 申请、审批、授权。
- TYK 和资源服务区分用户委托 token 与应用身份 token。
- 资源服务基于 `client_id`、系统身份、租户、合同或内部授权边界做 data permission。
- 改造首批内部服务间调用链路。
- `btoken-service` 与 SAS `client_credentials` 并行一段时间，内部服务逐个迁移。

### 不包含

- 不迁移公司 App / 官网用户登录。
- 不要求所有内部服务一次性改完。
- 不改变第二阶段已经支持的第三方授权流程。
- 不做旧 btoken 到新 JWT 的 token exchange；内部调用方按服务节奏切换到 `/oauth2/token`。

### 工作拆分

| 工作项 | 说明 | 后端 | 前端 | 测试 |
|--------|------|------:|------:|------:|
| btoken 现状梳理 | 梳理现有 btoken client、调用方、validate/authenticate 使用点、过期时间、权限位 | 4-6 | 0 | 2-3 |
| 内部 client_credentials 扩展 | 复用第一阶段 `client_credentials` 基础能力，扩展内部服务 client、claim、策略 | 3-6 | 0 | 2-3 |
| 内部服务 client 管理 | 内部服务注册、密钥管理、scope 分配、禁用/轮换 | 8-12 | 6-10 | 3-5 |
| 应用身份 scope 模型 | APP/服务身份 scope、USER/APP 轨道隔离、grant 与 scope 组合限制 | 8-12 | 0 | 3-5 |
| 网关与资源服务适配 | 区分用户 token 与应用 token，校验 client_id、scope、路由类型 | 12-20 | 0 | 6-10 |
| 内部调用方改造 | 首批服务接入获取 token、缓存 token、刷新/失败重试 | 6-12 | 0 | 3-6 |
| 审计与运维 | 服务账号调用日志、密钥轮换记录、异常调用追踪 | 3-5 | 4-8 | 1-3 |
| btoken 并行与下线 | 新旧 token 并行策略、灰度开关、回滚方案、最终下线计划 | 1-4 | 0 | 2-5 |

### 阶段汇总

| 角色 | 人日 |
|------|------:|
| 后端 | 45 - 77 |
| 前端 | 10 - 18 |
| 测试 | 20 - 37 |
| 文档/接入支持 | 4 - 6 |
| **小计** | **79 - 138** |

### 阶段产出

- 内部服务间调用可以通过 `client_credentials` 获取 JWT token。
- 内部服务 API 可以按应用身份 scope 校验。
- 用户委托 token 与应用身份 token 语义隔离。
- 内部服务逐步从 `btoken-service` 的自定义 opaque token 迁移到统一授权服务器。
- `btoken-service` 进入并行兼容、逐步下线阶段。

## 第四阶段：ctoken-service JWT 化

### 阶段目标

第四阶段先不改变公司 App / 官网的登录入口，不要求它们立即接入 Spring Authorization Server。

这一阶段的目标是把现有 `ctoken-service` 的 C 端登录态从 opaque token 改为 JWT，让资源服务、TYK、SDK、cookie 和灰度机制先适配 JWT token 形态。

这一阶段重点是：

```text
公司 App / 官网继续调用现有 ctoken-service 登录接口
ctoken-service 登录成功后签发 ctoken JWT
资源服务 / TYK 支持 ctoken JWT 与 SAS JWT 多 issuer 校验
旧 opaque token 与新 ctoken JWT 并行一段时间
用户重新登录后获得 ctoken JWT
```

### 与第五阶段的关系

第四阶段不是 OAuth2/OIDC 改造，也不是把 `ctoken-service` 改造成 Spring Authorization Server。

它的作用是先完成 C 端登录态 JWT 化，降低后续第五阶段公司 App / 官网切到授权服务器的风险：

| 项 | 第四阶段 | 第五阶段 |
|----|----------|----------|
| 登录入口 | 继续使用 `ctoken-service` 现有登录接口 | 切到授权服务器 `/oauth2/authorize` |
| token 颁发方 | `ctoken-service` | Spring Authorization Server |
| token 形态 | ctoken JWT | SAS JWT |
| 协议形态 | 现有自研登录协议 | OAuth2/OIDC `authorization_code + PKCE` |
| 资源侧改造重点 | 适配 JWT、本地验签、多 issuer | 切换 issuer、audience、claim 来源 |

### 范围

- `ctoken-service` 支持 JWT access token 签发。
- 设计 ctoken JWT 的 `issuer`、`audience`、`sub`、`jti`、`exp`、用户身份、租户、客户端类型等 claim。
- ctoken JWT claim 尽量与 SAS JWT 对齐，但必须使用不同 issuer。
- `ctoken-service` 提供 JWK/JWKS 或内部公钥分发机制。
- 保留现有登录、刷新、登出、撤销接口的调用方式，减少 App / 官网前端改造。
- 支持旧 opaque token 与新 ctoken JWT 并行校验一段时间。
- 资源服务和 TYK 支持 `ctoken-service` issuer 与 SAS issuer 并行识别。
- 通过 `jti`、session、token version、黑名单或短 access token + refresh token 处理撤销和踢登诉求。
- SDK / cookie 名尽量保持兼容，用户重新登录后获得 ctoken JWT。
- 建立灰度、回滚、监控和下线旧 opaque token 的策略。

### 不包含

- 不把 `ctoken-service` 改造成 OAuth2 授权服务器。
- 不支持 OAuth2 `password grant`。
- 不要求 App / 官网在本阶段走 `authorization_code + PKCE`。
- 不做旧 opaque token 到 ctoken JWT 的 token exchange。
- 不迁移内部服务间调用，`btoken-service` 已在第三阶段处理。

### 工作拆分

| 工作项 | 说明 | 后端 | 前端 | 测试 |
|--------|------|------:|------:|------:|
| ctoken 现状与 claim 对齐 | 梳理现有登录、刷新、validate、revoke、cookie、SDK 依赖，设计与 SAS 尽量一致的 claim | 4-6 | 0 | 2-3 |
| JWT 签发基础能力 | ctoken JWT 签名、JWK/JWKS、公私钥管理、issuer/audience、token TTL、kid | 10-16 | 0 | 4-7 |
| 登录/刷新/撤销兼容改造 | 现有 obtain/refresh/revoke/logout 接口兼容 JWT，保留 session/jti/token version 等撤销能力 | 10-16 | 0-2 | 5-8 |
| 资源服务与 TYK 多 issuer 校验 | 支持 ctoken JWT、SAS JWT、旧 opaque token 并行识别，逐步减少远程 validate 调用 | 12-20 | 0 | 6-10 |
| SDK / Cookie 兼容 | 保持或兼容现有 cookie 名、SDK token 读取、过期刷新、重新登录策略 | 6-10 | 6-10 | 4-8 |
| 灰度、回滚与监控 | 按客户端、版本、渠道灰度 JWT，失败回滚 opaque，补充登录成功率和鉴权失败监控 | 6-10 | 4-8 | 3-6 |
| 安全审计与密钥轮换 | 密钥轮换、token 撤销审计、异常 token 拦截、旧 token 下线记录 | 4-8 | 0 | 3-6 |

### 阶段汇总

| 角色 | 人日 |
|------|------:|
| 后端 | 52 - 86 |
| 前端 | 10 - 20 |
| 测试 | 27 - 48 |
| 文档/接入支持 | 4 - 6 |
| **小计** | **93 - 160** |

### 阶段产出

- 公司 App / 官网仍使用现有登录入口，但重新登录后获取 ctoken JWT。
- ctoken JWT 与 SAS JWT 在资源侧并行校验，issuer 明确区分。
- 旧 opaque token 只作为灰度兼容保留，不再作为目标形态。
- 资源服务逐步从远程 validate 过渡到本地 JWT 验签。
- 后续公司 App / 官网切到 SAS 时，主要切换登录入口和 token 颁发方，资源侧改造成本降低。

## 第五阶段：我方 App 与官网接入授权服务器

### 阶段目标

第五阶段才迁移公司自己的 App 和官网，把第四阶段的 ctoken JWT 登录态逐步替换为标准 OAuth2/OIDC 登录态。

这一阶段重点是：

```text
公司 App / 官网注册为 OAuth2 client
App、小程序走 authorization_code + PKCE
官网 Web 走 authorization_code + PKCE + client authentication
前端 SDK 和 cookie 机制从 ctoken JWT 切到 SAS JWT
ctoken-service 逐步下线
```

### 范围

- 公司 App 接入 `authorization_code + PKCE`。
- 公司官网 Web 接入 `authorization_code + PKCE`，后端客户端使用 client authentication。
- 统一登录页、登录态、logout、refresh 策略。
- 改造现有前端 SDK、cookie 名、token 存储和刷新逻辑，将 token 颁发方从 `ctoken-service` 切到授权服务器。
- 我方 App / 官网发起的 C 端用户访问链路，从 ctoken JWT 鉴权逐步切换到 SAS JWT 鉴权。
- ctoken JWT 不迁移为 SAS JWT，用户重新登录后获得 SAS token。
- 设计灰度、回滚、ctoken 下线策略。

### 不包含

- 不做 ctoken JWT 到 SAS JWT 的 token exchange。
- 不要求所有历史 token 立即失效。
- 不重写全部业务前端，只改认证和 token 接入相关部分。

### 工作拆分

| 工作项 | 说明 | 后端 | 前端 | 测试 |
|--------|------|------:|------:|------:|
| 我方 App OAuth 接入 | App 注册、PKCE、回调 URI、refresh/revoke/logout 接入 | 8-14 | 14-23 | 7-11 |
| 官网 Web OAuth 接入 | Web 后端 OAuth client、Session、callback、logout、BFF/后端持 token | 7-12 | 9-15 | 5-9 |
| SDK / Cookie 切换 | 从 ctoken JWT 切到 SAS JWT，兼容 cookie 名、SDK token 读取、刷新和错误处理 | 4-8 | 8-14 | 5-8 |
| C 端访问链路切换 | App/官网用户请求从 ctoken JWT 逐步切到 SAS JWT，保留 issuer 级灰度和回滚 | 3-6 | 0-2 | 4-7 |
| ctoken 退出策略 | ctoken JWT 与 SAS JWT 并行窗口、重新登录策略、回滚、监控指标 | 3-6 | 4-8 | 5-8 |
| 用户体验与异常页 | 登录过期、重新登录、授权失败、登出成功页 | 2-4 | 4-8 | 2-5 |
| 全链路回归 | App、官网、第三方、资源 API、新旧 token 灰度场景 | 0 | 0 | 3-5 |

### 阶段汇总

| 角色 | 人日 |
|------|------:|
| 后端 | 27 - 50 |
| 前端 | 39 - 70 |
| 测试 | 31 - 53 |
| 文档/接入支持 | 5 - 8 |
| **小计** | **102 - 181** |

### 阶段产出

- 我方 App 和官网开始使用统一授权服务器。
- 现有 ctoken JWT 登录态逐步退出。
- 用户重新登录后获取新 SAS token。
- ctoken-service 可以针对 C 端用户登录态进入只读、兼容、最终下线阶段。
  服务与服务之间的 `btoken-service` 替换不属于第五阶段，已放在第三阶段。

## 分阶段推进建议

### 推荐优先级

| 优先级 | 阶段 | 原因 |
|--------|------|------|
| P0 | 第一阶段 | 先满足第三方接入的核心诉求，且不影响现有 App/官网 |
| P1 | 第二阶段 | 没有 scope 就无法安全开放资源 API，必须紧跟第一阶段 |
| P2 | 第三阶段 | 内部服务治理收益高，但可在第三方开放后推进 |
| P3 | 第四阶段 | 先让现有 C 端登录态 JWT 化，降低后续直接切 SAS 的风险 |
| P4 | 第五阶段 | 影响我方 App/官网用户面最大，适合最后灰度迁移 |

### 推荐排期

如果团队配置为 3-4 后端、1-2 前端、2 测试：

| 阶段 | 建议自然周期 |
|------|--------------|
| 第一阶段 | 7 - 11 周 |
| 第二阶段 | 7 - 11 周 |
| 第三阶段 | 7 - 10 周 |
| 第四阶段 | 6 - 10 周 |
| 第五阶段 | 7 - 12 周 |

阶段之间可以部分并行。例如第二阶段的 API Market scope 建模可以在第一阶段后半启动，第三阶段的内部 `btoken-service` 调用方盘点可以在第二阶段资源服务校验组件稳定后开始。第四阶段的 ctoken JWT claim 设计应尽量复用第一阶段 SAS JWT 的 claim 规范，第五阶段公司 App / 官网接入授权服务器时再切换登录入口和 token 颁发方。

## 关键风险

| 风险 | 影响阶段 | 影响 | 建议 |
|------|----------|------|------|
| 用户中心/CUR 接口不稳定 | 第一、五阶段 | 登录认证流程受阻 | 先定义授权服务器认证适配接口，支持 mock 联调 |
| 第三方 client 管理边界不清 | 第一、二阶段 | client 注册、审批、scope 同步反复调整 | 先冻结 client 元模型 |
| TYK 兼容模式边界不清 | 第一、二阶段 | 新 SAS JWT、旧 ctoken、旧 btoken 并行时路由和错误码容易混乱 | 第一阶段只做 JWT 基础验签，第二阶段再启用 scope 强校验 |
| scope 粒度频繁变化 | 第二、三阶段 | API Market、TYK、资源服务都受影响 | 先选 2-3 个业务域试点 |
| TYK 插件/策略能力不满足 | 第二、三阶段 | 网关 scope 校验成本上升 | 提前验证 TYK JWT 和自定义校验能力 |
| ctoken JWT 撤销语义变弱 | 第四阶段 | JWT 本地验签后不能天然实时撤销 | 保留 jti/session/token version/黑名单或短 access token + refresh token |
| ctoken JWT 与 SAS JWT claim 不一致 | 第四、五阶段 | 第五阶段切 SAS 时资源服务仍要重复适配 | 第四阶段按 SAS claim 规范对齐，issuer 明确区分 |
| 多 issuer 兼容边界不清 | 第四、五阶段 | ctoken JWT、SAS JWT、旧 opaque token 并行时容易误判 | 明确 issuer、audience、kid、token type 和灰度策略 |
| 资源服务数据权限不统一 | 第二、三、四、五阶段 | 本地 data permission 成本放大 | 抽公共 starter / filter / annotation |
| btoken 调用方分散 | 第三阶段 | 内部服务改造和回归测试成本增加 | 先盘点调用方，按服务分批切换 |
| 我方 App/官网迁移影响用户体验 | 第五阶段 | 登录、续期、登出体验风险高 | 最后迁移，灰度发布，ctoken JWT 保留回滚窗口 |

## 一句话结论

建议把授权服务建设拆成五阶段推进：第一阶段先服务第三方授权认证，支持第三方服务端系统的 `client_credentials`，并让 TYK 具备 SAS JWT 基础验签兼容模式，约 112 - 192 人日；第二阶段补齐 scope 和资源服务器校验，约 114 - 184 人日；第三阶段将我方服务间调用从 `btoken-service` 迁移到标准 `client_credentials + scope`，约 79 - 138 人日；第四阶段先把 `ctoken-service` 从 opaque token 改为 JWT，约 93 - 160 人日；第五阶段再让我方 App 与官网接入授权服务器，约 102 - 181 人日。这样可以先满足第三方开放诉求，同时避免一开始就冲击现有 ctoken 登录体系，并把 C 端登录态迁移拆成 JWT 化和 OAuth2/OIDC 化两步推进。
