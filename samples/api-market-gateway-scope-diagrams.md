# API Market + 网关 Scope 校验架构图解

场景：API Market 平台统一管理 API 与 scope 的映射关系，网关集中验证 scope，资源服务器零改动。

---

## 一、整体架构

```mermaid
flowchart TB
    subgraph Client["📕 客户端"]
        C1["持有 access_token<br/>scope: message.read"]
    end

    subgraph GW["🚪 网关（Gateway）"]
        GW1["1. 验证 JWT 签名/有效期"]
        GW2["2. 提取 token 中的 scope"]
        GW3["3. 查 API Market：该 API 需要什么 scope"]
        GW4["4. 比对 scope"]
        GW5["5. 放行 / 拦截"]
        GW1 --> GW2 --> GW3 --> GW4 --> GW5
    end

    subgraph API_Market["📊 API Market 平台"]
        AM1[("api_definition 表<br/>api_path + scope 映射")]
        AM2["管理后台<br/>维护 API 与 scope 的关系"]
        AM2 --> AM1
    end

    subgraph RS["🎭 资源服务器（多个）"]
        RS1["message-service<br/>只验证 JWT 签名<br/>不验证 scope"]
        RS2["user-service<br/>只验证 JWT 签名<br/>不验证 scope"]
    end

    subgraph AS["🏰 授权服务器"]
        AS1["颁发 access_token<br/>token 中携带 scope"]
    end

    Client -->|"请求 + Bearer token"| GW
    GW -->|"查 API→scope 映射"| API_Market
    GW -->|"scope 满足 → 转发"| RS1
    GW -->|"scope 满足 → 转发"| RS2
    AS -->|"颁发 token"| Client

    style GW fill:#FF9800,color:#fff
    style API_Market fill:#2196F3,color:#fff
    style RS1 fill:#4CAF50,color:#fff
    style RS2 fill:#4CAF50,color:#fff
```

---

## 二、完整请求时序图

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant Client as 📕 客户端
    participant AS as 🏰 授权服务器
    participant GW as 🚪 网关
    participant API_Market as 📊 API Market
    participant RS as 🎭 资源服务器

    Note over User,RS: 第一阶段：获取 access_token

    User->>Client: 1. 访问应用
    Client->>+AS: 2. 授权码流程（省略细节）
    AS-->>-Client: 3. 返回 access_token<br/>scope: message.read
    Client-->>User: 4. 展示页面

    Note over User,RS: 第二阶段：通过网关访问资源服务器

    User->>Client: 5. 请求消息列表
    Client->>+GW: 6. GET /messages<br/>Authorization: Bearer at-xxx

    Note right of GW: ★ 网关核心职责 ★

    GW->>GW: 7. 验证 JWT 签名 ✓<br/>验证 JWT 有效期 ✓
    GW->>GW: 8. 提取 scope: ["message.read"]
    GW->>+API_Market: 9. 查询：/messages GET → 需要 scope？
    API_Market-->>-GW: 10. 需要 "message.read"
    GW->>GW: 11. 比对：<br/>token scope ["message.read"]<br/>⊇ 所需 ["message.read"] ✓
    GW->>+RS: 12. 转发请求<br/>Authorization: Bearer at-xxx
    RS->>RS: 13. 验证 JWT 签名 ✓<br/>（不验证 scope）
    RS-->>-GW: 14. 200 OK 返回消息列表
    GW-->>-Client: 15. 200 OK
    Client-->>User: 16. 展示消息列表

    Note over User,RS: 第三阶段：scope 不满足时的拦截

    User->>Client: 17. 请求发送消息
    Client->>+GW: 18. POST /messages/send<br/>Authorization: Bearer at-xxx

    GW->>GW: 19. 验证 JWT 签名 ✓
    GW->>GW: 20. 提取 scope: ["message.read"]
    GW->>+API_Market: 21. 查询：/messages/send POST → 需要 scope？
    API_Market-->>-GW: 22. 需要 "message.write"
    GW->>GW: 23. 比对：<br/>token scope ["message.read"]<br/>⊉ 所需 ["message.write"] ✗
    GW-->>-Client: 24. 403 Forbidden<br/>WWW-Authenticate: Bearer error="insufficient_scope"<br/>scope="message.write"
    Client-->>User: 25. 提示权限不足
```

---

## 三、网关 Scope 校验流程图

```mermaid
flowchart TD
    A["收到请求<br/>GET /messages"] --> B{"Authorization 头<br/>是否存在 Bearer token？"}
    
    B -->|没有| C["401 Unauthorized"]
    B -->|有| D["解析 JWT<br/>验证签名 + 有效期"]
    
    D -->|签名无效/过期| C
    D -->|验证通过| E["从 JWT 提取 scope<br/>如: message.read"]
    
    E --> F["根据请求路径 + 方法<br/>查 API Market"]
    
    F --> G{"API Market 中<br/>是否有该 API 的 scope 要求？"}
    
    G -->|没有记录| H["无需 scope 校验<br/>直接放行"]
    G -->|有记录| I{"token 的 scope<br/>⊇ API 所需 scope？"}
    
    I -->|满足| J["✅ 放行<br/>转发到资源服务器"]
    I -->|不满足| K["❌ 403 Forbidden<br/>WWW-Authenticate: insufficient_scope<br/>scope=所需scope列表"]
    
    H --> J
    
    style C fill:#F44336,color:#fff
    style J fill:#4CAF50,color:#fff
    style K fill:#FF9800,color:#fff
```

---

## 四、API Market 数据模型

```mermaid
erDiagram
    SCOPE_DEFINITION ||--o{ API_DEFINITION : "被引用"
    SCOPE_DEFINITION ||--o{ CLIENT_SCOPE : "分配给客户端"

    SCOPE_DEFINITION {
        bigint id PK
        varchar scope_name "message.read"
        varchar description "读取消息"
    }

    API_DEFINITION {
        bigint id PK
        varchar api_name "消息列表"
        varchar api_path "/messages/**"
        varchar http_method "GET"
        varchar service_id "message-service"
        bigint scope_id FK "→ scope_definition.id"
    }

    CLIENT_SCOPE {
        bigint id PK
        varchar client_id "messaging-client"
        bigint scope_id FK "→ scope_definition.id"
    }
```

### 示例数据

**scope_definition**

| id | scope_name | description |
|----|-----------|-------------|
| 1 | message.read | 读取消息 |
| 2 | message.write | 发送/删除消息 |
| 3 | user.read | 读取用户信息 |

**api_definition**

| id | api_name | api_path | http_method | service_id | scope_id |
|----|----------|----------|-------------|------------|----------|
| 1 | 消息列表 | /messages/** | GET | message-service | 1 |
| 2 | 发送消息 | /messages/send | POST | message-service | 2 |
| 3 | 删除消息 | /messages/*/delete | POST | message-service | 2 |
| 4 | 用户信息 | /user/** | GET | user-service | 3 |

**client_scope**（客户端申请 client_id 时分配的 scope）

| id | client_id | scope_id |
|----|-----------|----------|
| 1 | messaging-client | 1 |
| 2 | messaging-client | 2 |
| 3 | user-client | 3 |

---

## 五、各组件职责对比

```mermaid
flowchart LR
    subgraph AS_职责["🏰 授权服务器"]
        direction TB
        A1["颁发 token<br/>token 中写入 scope"]
        A2["验证 client_id 是否有权<br/>请求该 scope"]
        A3["不关心资源服务器<br/>需要什么 scope"]
    end

    subgraph GW_职责["🚪 网关"]
        direction TB
        G1["验证 JWT 签名/有效期"]
        G2["★ 核心职责 ★<br/>比对 token scope vs API 所需 scope"]
        G3["缓存 API→scope 映射<br/>定期从 API Market 同步"]
    end

    subgraph RS_职责["🎭 资源服务器"]
        direction TB
        R1["只验证 JWT 签名"]
        R2["不验证 scope<br/>（网关已验证）"]
        R3["专注业务逻辑"]
    end

    subgraph AM_职责["📊 API Market"]
        direction TB
        M1["维护 API 列表"]
        M2["维护 API→scope 映射"]
        M3["变更时通知网关刷新"]
    end
```

| 组件 | 验证 JWT 签名 | 验证 scope | 维护 scope 规则 | 说明 |
|------|:---:|:---:|:---:|------|
| 授权服务器 | ✅ 颁发时 | ❌ | ❌ | 只管"给谁发什么 scope"，不管"谁需要什么 scope" |
| 网关 | ✅ | ✅ **核心** | ❌ | 执行者：比对 token scope 和 API 所需 scope |
| 资源服务器 | ✅ | ❌ | ❌ | 只验签名，scope 校验已由网关完成 |
| API Market | ❌ | ❌ | ✅ **核心** | 数据源：定义"哪个 API 需要什么 scope" |

---

## 六、Cookie / Session / Token 分布

```mermaid
flowchart TB
    subgraph Browser["🌐 浏览器 Cookie 存储"]
        direction LR
        BC1["client.com<br/>JSESSIONID=XXX"]
        BC2["auth.com<br/>JSESSIONID=AAA"]
    end

    subgraph Client_Server["📕 客户端"]
        direction TB
        CS1["HttpSession XXX<br/>├─ SecurityContext<br/>│  └─ OidcUser (sub: user-001)<br/>└─ OAuth2AuthorizedClient<br/>   ├─ access_token (scope: message.read)<br/>   └─ refresh_token"]
    end

    subgraph Auth_Server["🏰 授权服务器"]
        direction TB
        AS1["HttpSession AAA<br/>└─ SecurityContext<br/>   └─ UsernamePasswordAuthenticationToken"]
        AS2[("数据库<br/>oauth2_authorization_consent<br/>┌──────────────┬──────────┐<br/>│ client_id    │ user     │<br/>├──────────────┼──────────┤<br/>│ msg-client   │ user-001 │<br/>└──────────────┴──────────┘")]
    end

    subgraph Gateway_Server["🚪 网关"]
        direction TB
        GS1["本地缓存<br/>API→scope 映射表"]
        GS2["ScopeAuthorizationFilter<br/>提取 token scope → 比对"]
    end

    subgraph API_Market_DB["📊 API Market 数据库"]
        direction TB
        AM1["api_definition<br/>api_path → scope_name"]
        AM2["scope_definition<br/>scope 元数据"]
    end

    BC1 -.->|索引| CS1
    BC2 -.->|索引| AS1
    GS1 -.->|同步| AM1
```

---

## 七、网关缓存刷新机制

```mermaid
sequenceDiagram
    participant Admin as 👨‍💼 运维人员
    participant API_Market as 📊 API Market
    participant MQ as 📨 消息队列
    participant GW as 🚪 网关
    participant RS as 🎭 资源服务器

    Note over Admin,RS: 场景：新增 API 或修改 scope 要求

    Admin->>+API_Market: 1. 新增 API：/messages/export<br/>所需 scope: message.export
    API_Market->>API_Market: 2. 写入 api_definition 表
    API_Market->>+MQ: 3. 发布事件<br/>topic: api.scope.changed<br/>body: {action: "ADD", api_path: "/messages/export"}
    MQ-->>-API_Market: 4. 确认

    MQ-->>+GW: 5. 推送事件
    GW->>+API_Market: 6. 拉取最新 API→scope 映射
    API_Market-->>-GW: 7. 返回全量映射
    GW->>GW: 8. 刷新本地缓存

    Note over GW: 后续请求立即生效<br/>无需改资源服务器代码

    Admin->>RS: 9. 部署 /messages/export 接口<br/>（只需验证 JWT 签名）
```

---

## 八、客户端 Scope 不足时的处理流程

```mermaid
flowchart TD
    A["客户端请求资源"] --> B["网关返回 403<br/>insufficient_scope<br/>scope=message.write"]
    
    B --> C{"客户端是否支持<br/>动态增量授权？"}
    
    C -->|不支持| D["提示用户权限不足<br/>联系管理员"]
    
    C -->|支持| E["重新发起授权请求<br/>追加缺失的 scope"]
    
    E --> F["302 → 授权服务器<br/>/oauth2/authorize<br/>scope=message.read+message.write"]
    
    F --> G{"授权服务器<br/>Session 还在吗？"}
    
    G -->|在| H["跳过登录页"]
    G -->|不在| I["展示登录页<br/>用户输入密码"]
    I --> H
    
    H --> J{"consent 表中有<br/>(client_id, user, message.write) 吗？"}
    
    J -->|有| K["跳过 Consent 页"]
    J -->|没有| L["展示 Consent 页<br/>用户同意新增 scope"]
    L --> K
    
    K --> M["颁发新 authorization_code"]
    M --> N["客户端用 code 换新 token<br/>scope: message.read+message.write"]
    N --> O["✅ 用新 token 重新请求<br/>网关 scope 校验通过"]
    
    style B fill:#FF9800,color:#fff
    style D fill:#F44336,color:#fff
    style O fill:#4CAF50,color:#fff
```

---

## 九、与传统方案对比

```mermaid
flowchart TB
    subgraph 传统方案["❌ 传统：scope 校验散落在资源服务器"]
        direction TB
        T1["客户端"] -->|"token"| T2["网关<br/>只验签名"]
        T2 --> T3["message-service<br/>hasAuthority SCOPE_message.read"]
        T2 --> T4["user-service<br/>hasAuthority SCOPE_user.read"]
        T2 --> T5["order-service<br/>hasAuthority SCOPE_order.read"]
        
        T3 -.->|"每个资源服务器<br/>都要写 scope 规则"| T6["❌ 新增 API 要改代码<br/>❌ 新增 scope 要改代码<br/>❌ 漏改 = 安全漏洞"]
    end

    subgraph 新方案["✅ 新方案：scope 校验集中在网关"]
        direction TB
        N1["客户端"] -->|"token"| N2["网关<br/>验签名 + 验 scope"]
        N2 --> N3["message-service<br/>只验签名"]
        N2 --> N4["user-service<br/>只验签名"]
        N2 --> N5["order-service<br/>只验签名"]
        
        N2 -.->|"查 API Market"| N6["✅ 新增 API 只需配置<br/>✅ 新增 scope 只需配置<br/>✅ 资源服务器零改动"]
    end
```

| 对比项 | 传统方案 | 新方案 |
|--------|---------|--------|
| scope 校验位置 | 每个资源服务器 | **网关集中** |
| 新增 API | 改资源服务器代码 | **API Market 配置** |
| 新增 scope | 改资源服务器代码 | **API Market 配置** |
| 资源服务器模板 | 各不相同 | **统一：只验签名** |
| 漏改风险 | 高（漏改 = 漏洞） | **无（网关统一入口）** |
| scope 规则可见性 | 分散在各服务 | **API Market 一目了然** |

---

## 十、核心结论

1. **网关是 scope 校验的唯一执行者**，资源服务器只验证 JWT 签名，不验证 scope
2. **API Market 是 scope 规则的唯一数据源**，定义"哪个 API 需要什么 scope"
3. **客户端申请 client_id 时分配 scope**，授权服务器只按分配的 scope 颁发 token
4. **授权服务器不管 scope 校验**，只管"给谁发什么 scope"，不管"谁需要什么 scope"
5. **网关通过缓存 + 消息总线感知变更**，API Market 修改后网关自动刷新，无需重启
6. **资源服务器代码完全统一**：`anyRequest().authenticated()` + `jwt(Customizer.withDefaults())`，零 scope 逻辑
7. **scope 不足时客户端可动态增量授权**，重新发起授权请求追加缺失的 scope

---

## 十一、API Market Scope 设计方案（To C 平台）

> 基于对微信、支付宝、京东、抖音、快手、百度、微博、小红书共 8 个平台的 scope 调研结论，结合 **To C 平台有用户信息** 的特性，以及 API Market 网关集中校验架构，制定以下 scope 设计方案。

### 11.1 设计决策：融合微信/支付宝的静默授权 + 京东的业务域读写分离

本平台是 **To C** 平台，核心特征：
- 有 C 端用户，需要第三方应用代表用户操作（authorization_code 流程）
- 需要静默识别用户身份（如自动登录），不需要弹窗确认
- 同时也有服务间调用场景（client_credentials 流程）

| 候选模式 | 代表平台 | 与本平台的匹配度 | 结论 |
|----------|----------|----------------|------|
| scope 区分静默/非静默 | 支付宝、微信 | ✅ **极高**。To C 场景核心需求：静默获取 openid 识别用户，非静默获取用户信息 | **核心采用** |
| `业务域.操作类型` 读写分离 | 京东 | ✅ 高。业务 API 需要精细权限控制，命名可预测 | **主体采用** |
| 三种权限勾选状态 | 抖音 | ⚠️ 部分。Consent 页可提供"必须/可选"勾选 | 参考采用 |
| 基础/高级分层 | 微博 | ⚠️ 部分。静默授权天然就是"基础层" | 参考采用 |
| 极简 3 scope | 百度 | ❌ 低。多业务域，3 个 scope 远不够 | 不采用 |
| 粗粒度 | 快手 | ❌ 低。`user_info` 一个 scope 含全部信息，太粗 | 不采用 |

**最终决策**：

```
核心架构 = 微信/支付宝的「静默/非静默」模式（用户身份层）
         + 京东的「业务域.操作类型」模式（业务 API 层）
         + 抖音的「必须/可选」勾选状态（Consent 页体验）
```

```mermaid
flowchart LR
    subgraph 身份层["👤 用户身份 Scope<br/>借鉴微信/支付宝"]
        S1["openid（静默）"]
        S2["profile（非静默）"]
    end

    subgraph 业务层["🏢 业务 API Scope<br/>借鉴京东"]
        B1["message.read / message.write"]
        B2["order.read / order.write"]
        B3["user.read / user.write"]
    end

    subgraph 体验层["🎯 Consent 体验<br/>借鉴抖音"]
        E1["必须授权（不可取消）"]
        E2["推荐授权（默认勾选）"]
        E3["可选授权（默认不选）"]
    end

    身份层 --> 业务层 --> 体验层
```

### 11.2 Scope 命名规范

#### 命名格式

**用户身份 Scope**（借鉴微信 `snsapi_` 前缀、支付宝 `auth_` 前缀）：

```
<身份域>
```

无点分、无操作后缀。身份 scope 是特殊的存在，不遵循业务域规则。

**业务 API Scope**（借鉴京东 `业务域.操作类型`）：

```
<业务域>.<操作类型>
```

- **业务域**：小写英文，对应一组相关 API 资源
- **操作类型**：`read`（读取）或 `write`（写入/创建/删除）
- **分隔符**：点号 `.`

#### 命名规则

| 规则 | 说明 | 示例 |
|------|------|------|
| 身份 scope 是特殊命名 | 不遵循业务域规则，单独定义 | `openid`、`profile` |
| 业务域对应微服务 | 一个微服务对应一个业务域 | `message-service` → `message.*` |
| 读写严格分离 | 读取用 `.read`，写入/创建/删除用 `.write` | `order.read` / `order.write` |
| 只读资源无 write | 如果业务域只有查询接口，则只有 `.read` | `category.read` |
| 禁止超细粒度 | 不拆到字段级别 | ✅ `user.read` ❌ `user.email.read` |
| 禁止超粗粒度 | 不使用 `all`、`*` 等通配 scope | ❌ `api.all` ❌ `*` |

#### 命名对比

```
❌ 不好的命名                         ✅ 好的命名（本方案）
─────────────────────────────         ─────────────────────────────
snsapi_base            (微信风格)      openid
auth_user              (支付宝风格)     profile
user_info              (太粗)          profile + user.read
message                (无操作)        message.read / message.write
msg_r                  (缩写不明)      message.read
user.email.read        (太细)          user.read
api.all                (太危险)        按业务域逐个分配
```

### 11.3 Scope 完整清单

#### 11.3.1 用户身份 Scope（authorization_code 专用）

| Scope | 说明 | 是否静默 | 映射 API | 对标平台 |
|-------|------|----------|----------|----------|
| `openid` | 获取用户唯一标识（sub/openid），不弹窗 | ✅ 静默 | `GET /userinfo`（仅返回 sub） | 微信 `snsapi_base` / 支付宝 `auth_base` |
| `profile` | 获取用户基本信息（昵称、头像等），弹窗确认 | ❌ 非静默 | `GET /userinfo`（返回 sub + 昵称 + 头像） | 微信 `snsapi_userinfo` / 支付宝 `auth_user` |
| `phone` | 获取用户手机号，弹窗确认 | ❌ 非静默 | `GET /user/phone` | 微信小程序 `scope.phone` |
| `email` | 获取用户邮箱，弹窗确认 | ❌ 非静默 | `GET /user/email` | 百度 `email` |

**静默授权规则**（借鉴微信/支付宝）：

```mermaid
flowchart TD
    A["第三方应用发起授权<br/>/oauth2/authorize"] --> B{"请求的 scope 包含<br/>非静默 scope 吗？"}

    B -->|"只有 openid"| C["🟢 静默授权<br/>不弹 Consent 页<br/>直接返回 code"]
    B -->|"包含 profile/phone/email<br/>或业务 scope"| D["🟡 非静默授权<br/>弹出 Consent 页<br/>用户确认后返回 code"]

    C --> E["token scope: openid"]
    D --> F["token scope: openid profile message.read ..."]

    style C fill:#4CAF50,color:#fff
    style D fill:#FF9800,color:#fff
```

| 场景 | 请求 scope | 是否弹窗 | token 中获得 |
|------|-----------|----------|-------------|
| 静默识别用户 | `openid` | ❌ 不弹窗 | 仅 sub |
| 获取用户信息 | `openid profile` | ✅ 弹窗 | sub + 昵称 + 头像 |
| 获取手机号 | `openid phone` | ✅ 弹窗 | sub + 手机号 |
| 完整授权 | `openid profile phone message.read` | ✅ 弹窗 | 全部信息 + 业务权限 |

> **关键规则**：`openid` 是静默 scope，请求中**只有** `openid` 时不弹 Consent 页，直接返回授权码。只要包含任何非静默 scope，就必须弹 Consent 页。`openid` 在所有 authorization_code 流程中**自动包含**，无需显式请求。

#### 11.3.2 业务 API Scope（两种授权模式通用）

| 业务域 | Scope | 说明 | 映射 API 示例 | 典型使用者 |
|--------|-------|------|---------------|-----------|
| message | `message.read` | 读取消息列表、消息详情 | `GET /messages/**` | 消息聚合工具 |
| message | `message.write` | 发送消息、删除消息 | `POST /messages/send` | 自动回复机器人 |
| user | `user.read` | 读取用户详细资料 | `GET /user/**` | 用户画像分析 |
| user | `user.write` | 修改用户资料 | `PUT /user/**`, `PATCH /user/**` | 资料编辑工具 |
| order | `order.read` | 查询订单 | `GET /orders/**` | 订单追踪 |
| order | `order.write` | 创建/取消/修改订单 | `POST /orders`, `PUT /orders/**` | 代下单工具 |
| product | `product.read` | 查询商品 | `GET /products/**` | 比价工具 |
| product | `product.write` | 创建/修改/删除商品 | `POST /products`, `PUT /products/**` | 商品管理 |
| payment | `payment.read` | 查询支付记录 | `GET /payments/**` | 财务对账 |
| payment | `payment.write` | 发起支付、退款 | `POST /payments/charge` | 支付网关 |
| category | `category.read` | 查询分类（只读） | `GET /categories/**` | 分类浏览 |
| analytics | `analytics.read` | 查询分析数据（只读） | `GET /analytics/**` | 数据看板 |
| notification | `notification.read` | 读取通知 | `GET /notifications/**` | 通知聚合 |
| notification | `notification.write` | 发送通知、标记已读 | `POST /notifications/**` | 消息推送 |
| file | `file.read` | 下载文件 | `GET /files/**` | 文件预览 |
| file | `file.write` | 上传/删除文件 | `POST /files` | 云存储 |

### 11.4 Scope 分层模型（四层）

```mermaid
flowchart TB
    subgraph L0["⚪ 第零层：隐式 Scope（无需申请）"]
        L0A["openid — 静默获取，authorization_code 流程自动包含"]
    end

    subgraph L1["🟢 第一层：用户身份 Scope（申请即得）"]
        L1A["profile — 用户基本信息"]
        L1B["phone — 用户手机号"]
        L1C["email — 用户邮箱"]
    end

    subgraph L2["🟡 第二层：业务读取 Scope（申请+快速审批）"]
        L2A["message.read"]
        L2B["order.read"]
        L2C["user.read"]
        L2D["product.read"]
        L2E["..."]
    end

    subgraph L3["🔴 第三层：业务写入 Scope（申请+人工审核）"]
        L3A["message.write"]
        L3B["order.write"]
        L3C["payment.write"]
        L3D["user.write"]
        L3E["..."]
    end

    L0 --> L1
    L1 --> L2
    L2 --> L3

    style L0 fill:#9E9E9E,color:#fff
    style L1 fill:#4CAF50,color:#fff
    style L2 fill:#FF9800,color:#fff
    style L3 fill:#F44336,color:#fff
```

| 层级 | 审批要求 | 是否静默 | 典型场景 |
|------|----------|----------|----------|
| ⚪ 隐式 Scope | 无需申请，authorization_code 自动包含 | ✅ 静默 | 识别用户身份、自动登录 |
| 🟢 身份 Scope | 提交申请，自动审批 | ❌ 非静默 | 获取昵称头像、手机号 |
| 🟡 读取 Scope | 提交申请，自动或快速审批 | ❌ 非静默 | 数据查询、报表查看 |
| 🔴 写入 Scope | 提交申请，必须人工审核 | ❌ 非静默 | 数据修改、资金操作 |

### 11.5 两种授权模式下的 Scope 使用

```mermaid
flowchart TB
    subgraph AC["authorization_code 模式（C 端用户）"]
        direction TB
        AC1["👤 用户在第三方应用中点击授权"]
        AC2["跳转授权服务器<br/>判断是否需要 Consent 页"]
        AC3a["🟢 只有 openid → 静默跳过"]
        AC3b["🟡 有其他 scope → 弹 Consent 页"]
        AC4["用户确认后颁发 token<br/>token 中包含用户授权的 scope"]
        AC1 --> AC2
        AC2 --> AC3a
        AC2 --> AC3b
        AC3a --> AC4
        AC3b --> AC4
    end

    subgraph CC["client_credentials 模式（服务器间）"]
        direction TB
        CC1["🏢 服务器直接请求 token"]
        CC2["授权服务器验证 client_id<br/>检查该客户端被分配的 scope"]
        CC3["颁发 token<br/>scope = 管理员分配的业务 scope"]
        CC1 --> CC2 --> CC3
    end
```

| 维度 | authorization_code | client_credentials |
|------|-------------------|-------------------|
| **适用场景** | 第三方应用代表用户操作 | 服务器间 API 调用 |
| **可用的身份 scope** | ✅ `openid`、`profile`、`phone`、`email` | ❌ 不适用（无用户） |
| **可用的业务 scope** | ✅ 用户 consent 确认的业务 scope | ✅ 管理员分配的业务 scope |
| **是否弹 Consent 页** | 取决于 scope 是否包含非静默 scope | 不需要 |
| **token 中的 sub** | 用户 ID | 客户端 ID |

### 11.6 Consent 页设计（借鉴抖音三状态）

当授权请求包含非静默 scope 时，弹出 Consent 页。参考抖音的三种权限勾选状态：

```mermaid
flowchart TD
    A["Consent 页"] --> B["身份信息"]
    B --> B1["☑️ openid（必选，不可取消）"]
    B --> B2["☑️ profile（默认勾选，可取消）"]
    B --> B3["☐ phone（默认不选，需勾选）"]

    A --> C["业务权限"]
    C --> C1["☑️ message.read（默认勾选，可取消）"]
    C --> C2["☐ message.write（默认不选，需勾选）"]
```

| 勾选状态 | 适用 scope | 用户体验 | 说明 |
|----------|-----------|----------|------|
| **必选，不可取消** | `openid` | 灰色勾选，不可操作 | 身份识别是最基础需求，必须授权 |
| **默认勾选，可取消** | `profile`、`*.read` | 勾选状态，可取消 | 重要但非必须，推荐授权 |
| **默认不选，需勾选** | `phone`、`email`、`*.write` | 未勾选状态，需主动勾选 | 敏感信息或写操作，需明确同意 |

### 11.7 Scope 与 API 的映射规则

#### 映射表设计（增强版）

```mermaid
erDiagram
    SCOPE_DEFINITION ||--o{ API_SCOPE : "被引用"
    SCOPE_DEFINITION ||--o{ CLIENT_SCOPE : "分配给客户端"

    SCOPE_DEFINITION {
        bigint id PK
        varchar scope_name "profile"
        varchar description "用户基本信息"
        varchar scope_category "IDENTITY / BUSINESS"
        varchar scope_level "IMPLICIT / IDENTITY / READ / WRITE"
        varchar business_domain "user"
        varchar operation_type "read"
        boolean is_silent "false"
        varchar consent_default "REQUIRED / CHECKED / UNCHECKED"
    }

    API_DEFINITION ||--o{ API_SCOPE : "需要"
    API_DEFINITION {
        bigint id PK
        varchar api_name "用户信息"
        varchar api_path "/user/**"
        varchar http_method "GET"
        varchar service_id "user-service"
    }

    API_SCOPE {
        bigint id PK
        bigint api_id FK
        bigint scope_id FK
        varchar require_type "ANY / ALL"
    }

    CLIENT_SCOPE {
        bigint id PK
        varchar client_id "messaging-client"
        bigint scope_id FK
        varchar grant_type "client_credentials / authorization_code / both"
        varchar approval_status "AUTO / PENDING / APPROVED / REJECTED"
    }
```

**关键改进**：

1. **`scope_definition` 增加 `scope_category`**：区分 `IDENTITY`（身份 scope）和 `BUSINESS`（业务 scope），两者规则完全不同
2. **`scope_definition` 增加 `is_silent`**：标记是否为静默 scope，授权服务器据此决定是否跳过 Consent 页
3. **`scope_definition` 增加 `consent_default`**：控制 Consent 页的默认勾选状态（借鉴抖音三状态）
4. **`client_scope` 增加 `approval_status`**：区分自动审批和人工审核
5. **`client_scope` 增加 `grant_type`**：`client_credentials` 模式下不能分配身份 scope

#### 映射示例

**scope_definition**

| id | scope_name | description | scope_category | scope_level | business_domain | operation_type | is_silent | consent_default |
|----|-----------|-------------|---------------|-------------|-----------------|---------------|-----------|----------------|
| 0 | openid | 用户唯一标识 | IDENTITY | IMPLICIT | - | - | true | REQUIRED |
| 1 | profile | 用户基本信息 | IDENTITY | IDENTITY | - | - | false | CHECKED |
| 2 | phone | 用户手机号 | IDENTITY | IDENTITY | - | - | false | UNCHECKED |
| 3 | email | 用户邮箱 | IDENTITY | IDENTITY | - | - | false | UNCHECKED |
| 4 | message.read | 读取消息 | BUSINESS | READ | message | read | false | CHECKED |
| 5 | message.write | 发送/删除消息 | BUSINESS | WRITE | message | write | false | UNCHECKED |
| 6 | user.read | 读取用户详情 | BUSINESS | READ | user | read | false | CHECKED |
| 7 | user.write | 修改用户信息 | BUSINESS | WRITE | user | write | false | UNCHECKED |
| 8 | order.read | 查询订单 | BUSINESS | READ | order | read | false | CHECKED |
| 9 | order.write | 创建/取消订单 | BUSINESS | WRITE | order | write | false | UNCHECKED |

**api_definition**

| id | api_name | api_path | http_method | service_id |
|----|----------|----------|-------------|------------|
| 1 | 用户身份 | /userinfo | GET | user-service |
| 2 | 用户手机号 | /user/phone | GET | user-service |
| 3 | 用户邮箱 | /user/email | GET | user-service |
| 4 | 消息列表 | /messages/** | GET | message-service |
| 5 | 发送消息 | /messages/send | POST | message-service |
| 6 | 删除消息 | /messages/*/delete | POST | message-service |

**api_scope**

| id | api_id | scope_id | require_type | 说明 |
|----|--------|----------|-------------|------|
| 1 | 1 | 0 | ALL | /userinfo 至少需要 openid |
| 2 | 1 | 1 | ANY | 有 profile 则返回更多信息 |
| 3 | 2 | 2 | ALL | 手机号必须 phone scope |
| 4 | 3 | 3 | ALL | 邮箱必须 email scope |
| 5 | 4 | 4 | ALL | |
| 6 | 5 | 5 | ALL | |
| 7 | 6 | 5 | ALL | |

### 11.8 Scope 校验规则

#### 网关校验逻辑（伪代码）

```java
// 网关 ScopeAuthorizationFilter 核心逻辑
public boolean checkScope(HttpServletRequest request, Jwt jwt) {
    // 1. 提取请求信息
    String path = request.getRequestURI();      // /messages/send
    String method = request.getMethod();         // POST

    // 2. 查 API Market：该 API 需要什么 scope
    List<ScopeRequirement> requirements = apiMarketService.getRequiredScopes(path, method);
    // → [{scope: "message.write", requireType: "ALL"}]

    // 3. 如果没有 scope 要求，直接放行
    if (requirements.isEmpty()) {
        return true;
    }

    // 4. 提取 token 中的 scope
    Set<String> tokenScopes = jwt.getClaimAsStringList("scope");
    // → ["openid", "profile", "message.read"]

    // 5. 按组校验
    // ALL 组：token 必须包含全部
    // ANY 组：token 包含任一即可
    return matchScopes(tokenScopes, requirements);
}
```

#### 授权服务器静默判断逻辑（伪代码）

```java
// 授权服务器判断是否需要弹出 Consent 页
public boolean requiresConsent(Set<String> requestedScopes) {
    // openid 是隐式 scope，自动包含
    // 只有 openid → 静默，不需要 Consent
    Set<String> nonImplicitScopes = requestedScopes.stream()
        .filter(s -> !s.equals("openid"))
        .collect(Collectors.toSet());

    return !nonImplicitScopes.isEmpty();
}
```

#### 校验规则明细

| 规则 | 说明 | 示例 |
|------|------|------|
| **静默判断** | 只有 `openid` 时不弹 Consent，直接返回 code | scope=`openid` → 静默 |
| **非静默判断** | 包含任何非 `openid` 的 scope 时弹 Consent | scope=`openid profile` → 弹窗 |
| **openid 自动包含** | authorization_code 流程中 `openid` 始终在 token 中 | 即使不显式请求也会包含 |
| **read 不包含 write** | `message.read` 只能访问 GET 接口 | `message.read` 不能 POST /messages/send |
| **write 不包含 read** | `message.write` 只能访问写接口 | `message.write` 不能 GET /messages |
| **身份 scope 仅限 authorization_code** | client_credentials 模式不能申请 `profile`、`phone`、`email` | 服务器间调用无用户概念 |
| **openid 无业务权限** | `openid` 只能访问 /userinfo（仅 sub） | `openid` 不能访问 /messages |
| **未配置 scope 的 API 直接放行** | 如果 API Market 没有该 API 的 scope 配置 | 公开接口无需 scope |

### 11.9 完整授权流程时序图（含静默/非静默）

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant App as 📱 第三方应用
    participant AS as 🏰 授权服务器
    participant GW as 🚪 网关
    participant AM as 📊 API Market
    participant RS as 🎭 资源服务器

    Note over User,RS: 场景一：静默授权（只有 openid）

    User->>App: 1. 访问应用
    App->>+AS: 2. /oauth2/authorize<br/>scope=openid
    AS->>AS: 3. 检测：只有 openid<br/>→ 静默授权，不弹 Consent
    AS-->>-App: 4. 302 redirect_uri?code=xxx
    App->>+AS: 5. /oauth2/token code=xxx
    AS-->>-App: 6. access_token<br/>scope: openid

    Note over User,RS: 场景二：非静默授权（包含 profile + 业务 scope）

    User->>App: 7. 点击"获取我的消息"
    App->>+AS: 8. /oauth2/authorize<br/>scope=openid profile message.read
    AS->>AS: 9. 检测：包含非静默 scope<br/>→ 弹出 Consent 页
    AS-->>User: 10. 展示 Consent 页<br/>☑️ openid（必选）<br/>☑️ profile（默认勾选）<br/>☑️ message.read（默认勾选）
    User->>AS: 11. 确认授权
    AS-->>-App: 12. 302 redirect_uri?code=yyy
    App->>+AS: 13. /oauth2/token code=yyy
    AS-->>-App: 14. access_token<br/>scope: openid profile message.read

    Note over User,RS: 场景三：用 token 访问业务 API

    App->>+GW: 15. GET /messages<br/>Authorization: Bearer at-yyy
    GW->>GW: 16. 验证 JWT 签名 ✓
    GW->>GW: 17. 提取 scope: openid profile message.read
    GW->>+AM: 18. 查询：GET /messages → 需要 message.read
    AM-->>-GW: 19. 需要 message.read
    GW->>GW: 20. 比对：✅ message.read ∈ token scope
    GW->>+RS: 21. 转发请求
    RS-->>-GW: 22. 200 OK 返回消息列表
    GW-->>-App: 23. 200 OK

    Note over User,RS: 场景四：scope 不足

    App->>+GW: 24. POST /messages/send<br/>Authorization: Bearer at-yyy
    GW->>+AM: 25. 查询：POST /messages/send → 需要 message.write
    AM-->>-GW: 26. 需要 message.write
    GW->>GW: 27. 比对：❌ message.write ∉ token scope
    GW-->>-App: 28. 403 insufficient_scope<br/>scope=message.write
    App->>App: 29. 重新发起授权<br/>scope=openid profile message.read message.write
```

### 11.10 客户端 Scope 申请与审批流程

```mermaid
sequenceDiagram
    actor Dev as 👨‍💻 开发者
    participant Portal as 📊 API Market 门户
    participant Admin as 👨‍💼 管理员
    participant AS as 🏰 授权服务器
    participant DB as 🗄️ 数据库

    Note over Dev,DB: 第一阶段：注册应用

    Dev->>+Portal: 1. 注册客户端应用<br/>client_name: "消息助手"<br/>grant_types: authorization_code, client_credentials
    Portal->>-DB: 2. 创建 client_id + client_secret<br/>自动分配 scope: [openid]<br/>grant_type: authorization_code
    Portal-->>Dev: 3. 返回 client_id: "msg-helper"<br/>scope: [openid]

    Note over Dev,DB: 第二阶段：申请身份 scope

    Dev->>+Portal: 4. 申请 scope: profile, phone
    Portal->>Portal: 5. 判断：IDENTITY 层 → 自动审批
    Portal->>DB: 6. 写入 profile, phone → client_scope<br/>grant_type: authorization_code
    Portal-->>-Dev: 7. 即时生效

    Note over Dev,DB: 第三阶段：申请业务 scope

    Dev->>+Portal: 8. 申请 scope: message.read, message.write
    Portal->>Portal: 9. 判断层级<br/>message.read → READ 层 → 自动审批<br/>message.write → WRITE 层 → 需人工审批

    Portal->>DB: 10. 写入 message.read → client_scope（APPROVED）<br/>grant_type: both
    Portal->>+Admin: 11. 通知：message.write 需审批
    Admin->>-Portal: 12. 审批通过
    Portal->>DB: 13. 写入 message.write → client_scope（APPROVED）<br/>grant_type: both
    Portal-->>Dev: 14. 通知：scope 已生效

    Note over Dev,DB: 第四阶段：用户授权（authorization_code）

    Dev->>+AS: 15. 用户点击授权<br/>/oauth2/authorize<br/>scope=openid profile message.read
    AS->>AS: 16. 检查 client_scope<br/>msg-helper 有权请求这些 scope
    AS-->>-Dev: 17. 非静默 → 弹 Consent → 颁发 code
```

### 11.11 Scope 扩展性设计

#### 新增业务域

```
新增业务域只需 3 步：

1. API Market 管理后台新增 scope_definition：
   INSERT INTO scope_definition (scope_name, description, scope_category, scope_level, business_domain, operation_type, is_silent, consent_default)
   VALUES ('inventory.read', '读取库存', 'BUSINESS', 'READ', 'inventory', 'read', false, 'CHECKED'),
          ('inventory.write', '管理库存', 'BUSINESS', 'WRITE', 'inventory', 'write', false, 'UNCHECKED');

2. 为新 API 配置 scope 映射：
   INSERT INTO api_scope (api_id, scope_id) VALUES (新API_id, inventory.read_id);

3. 网关自动感知变更（通过消息总线刷新缓存）
   → 资源服务器零改动
```

#### Scope 版本化

```
当 API 发生不兼容变更时，支持 scope 版本化：

v1 阶段：order.read, order.write
v2 阶段：order.read, order.write, order.v2.read, order.v2.write

- 旧客户端继续使用 v1 scope
- 新客户端申请 v2 scope
- 渐进式迁移，无强制切换
```

### 11.12 安全最佳实践

| 实践 | 说明 |
|------|------|
| **最小权限原则** | 客户端默认只有 `openid`，按需申请身份和业务 scope |
| **静默 scope 仅限 openid** | 只有 `openid` 可以静默授权，其他所有 scope 必须用户确认 |
| **write scope 需人工审核** | 所有 `.write` scope 必须人工审核，防止误操作 |
| **身份 scope 仅限 authorization_code** | `profile`、`phone`、`email` 不能分配给 client_credentials 模式 |
| **敏感信息默认不选** | `phone`、`email` 在 Consent 页默认不勾选，用户需主动勾选 |
| **定期审计** | 管理后台展示"scope 使用统计"，识别异常调用 |
| **scope 不可超出** | token 中的 scope 只能是 client_scope 的子集 |
| **拒绝通配 scope** | 不允许 `*`、`all` 等通配 scope |
| **敏感操作二次确认** | `payment.write` 等高危 scope 在 Consent 页强制展示 |

### 11.13 与各平台方案的对比总结

| 设计维度 | 本方案 | 微信 | 支付宝 | 京东 | 抖音 |
|----------|--------|------|--------|------|------|
| 静默授权 | ✅ `openid` | ✅ `snsapi_base` | ✅ `auth_base` | ❌ | ❌ |
| 非静默用户信息 | `profile` | `snsapi_userinfo` | `auth_user` | ❌ | `user_info` |
| 命名格式 | 身份: 单词<br/>业务: `域.操作` | `前缀_功能` | `前缀_功能` | `域.操作` | 混合 |
| 读写分离 | ✅ 业务 scope | ❌ | ❌ | ✅ | ❌ |
| 分层审批 | ✅ 四层 | ❌ | ❌ | ❌ | ✅ 三状态 |
| Consent 勾选状态 | ✅ 三状态 | ❌ | ❌ | ❌ | ✅ |
| scope 与 API 映射 | ✅ 数据库配置 | N/A | N/A | 硬编码 | 能力管理 |

**一句话总结**：To C 平台 scope 设计 = 微信/支付宝的「`openid` 静默 / `profile` 非静默」用户身份层 + 京东的「`业务域.read`/`业务域.write`」业务 API 层 + 抖音的「必选/默认勾选/默认不选」Consent 体验，四层审批（隐式→身份→读取→写入），身份 scope 仅限 authorization_code，业务 scope 两种模式通用。
