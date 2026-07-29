# 授权认证服务整体成本评估

## 背景

公司计划基于 Spring Security 和 Spring Authorization Server 搭建统一授权认证服务，并结合 API Market、TYK 网关和业务资源服务完成 OAuth2/OIDC 授权流程、scope 申请审批、网关校验和资源服务本地权限控制。

当前公司已有两套自研 token 服务：

| 现有服务 | 当前用途 | 当前 token 形态 | 与新授权服务的关系 |
|----------|----------|----------------|--------------------|
| `ctoken-service` | 公司 App / 官网等 C 端用户登录态 | opaque token | 先改造为 ctoken JWT，后续再由新授权服务替换 |
| `btoken-service` | 公司内部服务与服务之间调用 | opaque token | 后续迁移为 SAS `client_credentials + scope` |

本文件是不分阶段的整体成本评估，用于看完整落地范围的总成本。分阶段推进版本见 [`authorization-cost-phased-estimate.md`](./authorization-cost-phased-estimate.md)。

## 目标范围

完整落地范围包括：

- 第三方 Web / App / 小程序使用 `authorization_code + PKCE`。
- 第三方服务端系统使用 `client_credentials`。
- 支持 Refresh Token、Logout、Token Revocation。
- 授权服务器接入现有用户中心/CUR 做用户认证。
- API Market 支持 client 注册、scope 申请、审批、同步。
- TYK 网关第一步支持 SAS JWT 基础验签兼容模式，后续支持 scope 校验和 API Market 映射缓存。
- 资源服务支持本地 scope / data permission 校验。
- 内部服务间调用从 `btoken-service` 迁移到 SAS `client_credentials + scope`。
- `ctoken-service` 先支持 JWT 化，公司 App / 官网继续走现有登录入口但获得 ctoken JWT。
- 公司 App / 官网从 `ctoken-service` 迁移到 SAS `authorization_code + PKCE`。
- 旧 opaque token 不做 token exchange，不迁移；用户或调用方按节奏重新登录/重新接入获取新 token。
- 合规审计：登录、授权、token、scope 审批、管理员操作、访问拒绝等日志。

## 估算前提

- 不要求第一天替换 `ctoken-service` 和 `btoken-service`，但完整成本中包含最终替换。
- `ctoken-service` 不直接改造成 Spring Authorization Server，但完整成本中包含一个过渡性的 JWT 化阶段。
- `btoken-service` 不直接改造成 Spring Authorization Server。
- 现有用户中心/CUR 可提供稳定的密码、验证码、微信授权码等认证校验能力。
- API Market、TYK、资源服务需要做对应改造。
- 资源服务基准按 3 个业务服务、约 30 个 API 估算。
- 原生 App / 小程序侧主要评估授权服务接入、SDK/cookie/token 迁移，不包含业务功能重写。
- 暂不包含生产高可用、异地灾备、大规模压测专项、复杂风控系统。

## 总体估算

完整落地大约需要：

```text
500 - 855 人日
```

按角色粗拆：

| 角色 | 人日 |
|------|------:|
| 后端 / 授权服务 / 网关 / 资源服务 | 253 - 423 |
| 前端 / API Market / Consent 页 / App&官网接入 | 96 - 170 |
| 测试 / 联调 / 安全验证 | 128 - 226 |
| 文档 / 接入支持 | 23 - 36 |

如果配置团队为 3-4 后端、1-2 前端、2 测试，完整推进建议按 30 - 48 周准备。实际排期取决于阶段重叠程度、资源服务数量、App/官网发布节奏和 API Market/TYK 现有能力。

## 能力域拆分

| 能力域 | 主要内容 | 后端 | 前端 | 测试 | 文档/接入支持 | 小计 |
|--------|----------|------:|------:|------:|------:|------:|
| 1. 第三方授权认证 | SAS 基础、`authorization_code + PKCE`、OIDC、第三方 `client_credentials`、refresh/logout/revocation、用户中心认证适配、TYK JWT 基础验签兼容 | 69-117 | 12-22 | 26-45 | 5-8 | 112-192 |
| 2. Scope 与开放 API 校验 | scope 模型、Consent 增强、API Market scope 申请审批、同步 SAS、TYK scope 映射校验、资源服务 scope/data permission | 60-93 | 25-40 | 24-43 | 5-8 | 114-184 |
| 3. 内部服务间调用迁移 | `btoken-service` 盘点、内部 client 管理、内部服务 `client_credentials` 接入、btoken 并行与下线 | 45-77 | 10-18 | 20-37 | 4-6 | 79-138 |
| 4. ctoken-service JWT 化 | `ctoken-service` 签发 JWT、claim 对齐、JWK/JWKS、多 issuer 校验、旧 opaque token 并行兼容、SDK/cookie 兼容 | 52-86 | 10-20 | 27-48 | 4-6 | 93-160 |
| 5. 公司 App / 官网迁移 | App/官网接入授权服务器、SDK/cookie 从 ctoken JWT 切到 SAS JWT、C 端访问链路切换、ctoken 下线 | 27-50 | 39-70 | 31-53 | 5-8 | 102-181 |

> 这五个能力域对应完整建设范围。它们可以分阶段推进，但本文件按整体成本理解。

## 关键调整点

| 调整点 | 说明 |
|--------|------|
| 第三方服务端 `client_credentials` 应纳入第三方接入范围 | 第三方不一定都有用户参与，服务端系统需要应用身份 token |
| TYK JWT 基础验签应纳入第三方接入范围 | 第三方拿到 SAS JWT 后调用我方资源服务器，流量会先经过 TYK |
| 内部服务间调用属于 `btoken-service` 迁移 | 不是第四、五阶段，也不是 C 端登录态迁移 |
| C 端登录态拆成两步迁移 | 第一步 `ctoken-service` JWT 化，第二步 App/官网正式接入授权服务器 |
| 公司 App / 官网迁移只处理 C 端用户入口 | 主要替换 `ctoken-service` 登录态，不处理内部服务间调用 |
| 旧 token 不做 exchange | ctoken 用户重新登录，btoken 调用方按服务节奏切换到 `/oauth2/token` |
| scope 是开放 API 安全边界的核心 | 第三方能拿 token 后，必须尽快补齐 scope 与资源服务校验 |

## 现有 ctoken-service 处理方式

现有 `ctoken-service` 是 C 端用户登录态服务，当前使用 opaque token。为了降低公司 App / 官网直接接入 Spring Authorization Server 的风险，建议先增加一个过渡阶段：`ctoken-service` 继续承接现有登录入口，但登录成功后签发 ctoken JWT。

这个阶段不是 OAuth2/OIDC 标准化，也不是把 `ctoken-service` 改造成 SAS。它的目标是先让资源服务、TYK、SDK、cookie 和灰度机制适配 JWT token 形态，后续再把 token 颁发方从 `ctoken-service` 切到 Spring Authorization Server。

推荐处理方式：

| 项 | 处理方式 |
|----|----------|
| 当前 App / 官网登录 | 短期继续使用 ctoken |
| 新授权服务上线初期 | 先服务第三方，不冲击现有 C 端登录 |
| ctoken JWT 化阶段 | 现有登录接口不变，重新登录后获得 ctoken JWT |
| ctoken JWT claim | 尽量对齐 SAS JWT，但 issuer 必须区分 |
| 资源服务 / TYK | 支持 ctoken JWT、SAS JWT、旧 opaque token 并行兼容 |
| 旧 opaque token | 不迁移，用户重新登录获取 ctoken JWT |
| 后续 App / 官网迁移 | 通过 `authorization_code + PKCE` 接入 SAS，重新登录获取 SAS JWT |
| ctoken 下线 | 灰度迁移、保留回滚窗口、最终下线 |

## 现有 btoken-service 处理方式

现有 `btoken-service` 是内部服务间调用的 opaque token 服务，模型接近自定义版 `client_credentials`。

推荐迁移关系：

| 当前 btoken-service | 新授权服务 |
|---------------------|------------|
| `clientId` | OAuth2 `client_id` |
| `clientSecret` | OAuth2 client secret 或更强客户端认证 |
| opaque access token | JWT access token |
| `accessTokenExpiration` | SAS `TokenSettings.accessTokenTimeToLive` |
| `clientType=INTERNAL/VENDOR` | client 分类、审批策略、scope 命名空间 |
| `piQueryPrivilege` | 明确的业务 scope |
| validate/authenticate | JWT + scope + data permission 校验 |

## 关键增量规则

| 增量项 | 额外人日 |
|--------|----------:|
| 每新增 1 个资源服务接入 scope/data permission | 4 - 8 后端 + 2 - 4 测试 |
| 每新增 1 个资源服务做新旧 token 并行兼容 | 2 - 4 后端 + 1 - 2 测试 |
| 每新增 10 个 API scope 映射 | 2 - 4 后端/API Market 配置 + 2 - 3 测试 |
| 每新增 10 个内部 btoken client 迁移 | 2 - 5 后端 + 2 - 4 测试 |
| 每新增 1 类 ctoken 客户端形态做 JWT 灰度，如 App、官网、小程序 | 3 - 6 后端/前端 + 2 - 4 测试 |
| 每新增 1 类高敏感业务权限，如支付、核销 | 4 - 8 后端 + 2 - 4 前端 + 3 - 5 测试 |
| 如果用户中心/CUR 接口不稳定 | 额外 10 - 20 后端 |
| 如果 TYK 现有 JWT/scope 能力不足，需要插件开发 | 额外 10 - 20 后端 |

## 主要风险点

| 风险点 | 影响 | 建议 |
|--------|------|------|
| 第三方 client 管理边界不清 | client 注册、redirect URI、grant type、scope 审批反复调整 | 先冻结 client 元模型 |
| scope 粒度频繁变化 | API Market、TYK、资源服务都会受影响 | 先选 2-3 个业务域试点 |
| TYK 集成方式不确定 | 网关 scope 校验和缓存刷新成本可能上升 | 提前验证 TYK JWT 和自定义校验能力 |
| TYK 兼容模式边界不清 | 新 SAS JWT、旧 ctoken、旧 btoken 并行时路由和错误码容易混乱 | 先做 JWT 基础验签兼容，scope 强校验后续打开 |
| ctoken JWT 撤销语义变弱 | JWT 本地验签后不能天然实时撤销 | 保留 jti/session/token version/黑名单或短 access token + refresh token |
| ctoken JWT 与 SAS JWT claim 不一致 | App/官网后续切 SAS 时资源服务仍需重复适配 | 第四阶段按 SAS claim 规范对齐，issuer 明确区分 |
| 资源服务数据权限不统一 | 本地 data permission 成本放大 | 抽公共 starter / filter / annotation |
| btoken 调用方分散 | 内部服务迁移和回归测试成本增加 | 先盘点调用方，分批切换 |
| App/官网迁移影响用户体验 | 登录、刷新、登出、重新登录体验风险高 | 最后迁移，灰度发布，保留 ctoken JWT 回滚窗口 |
| 合规要求后置 | 审计、授权撤销、日志留存后补成本高 | 第一阶段即定义审计事件模型 |

## 一句话结论

完整建设授权服务并覆盖第三方接入、TYK JWT 基础兼容、scope 校验、内部 `btoken-service` 替换、`ctoken-service` JWT 化、公司 App/官网接入授权服务器，整体建议按 500 - 855 人日准备预算。实际推进不建议一次性铺开，应优先参考五阶段版本：先第三方授权认证和 TYK JWT 基础验签，再 scope，再内部服务间调用，然后把 ctoken 登录态 JWT 化，最后迁移公司 App 与官网到授权服务器。
