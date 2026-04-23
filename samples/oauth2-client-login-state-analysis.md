# OAuth2 客户端登录态与授权流程解析

基于 Spring Authorization Server 的 `demo-client` 与 `demo-authorizationserver` 示例项目，梳理 OAuth2 客户端登录态判断、会话管理、Token 过期等核心机制。

---

## 一、客户端如何判断是否有登录态

客户端**完全依赖 Spring Security 的 SecurityFilterChain** 来判断登录态，而非主动检查。

### 判断机制

```
用户请求 → SecurityFilterChain 拦截 → 检查 HttpSession 中是否存在已认证的 SecurityContext
  ├── 存在 → 已登录，直接放行
  └── 不存在 → 未登录，重定向到 OAuth2 授权流程
```

核心配置（`SecurityConfig.java`）：

```java
http
    .authorizeHttpRequests(authorize ->
        authorize
            .requestMatchers("/jwks", "/logged-out").permitAll()
            .anyRequest().authenticated()
    )
    .oauth2Login(oauth2Login ->
        oauth2Login.loginPage("/oauth2/authorization/messaging-client-oidc"))
    .oauth2Client(withDefaults())
    .logout(logout ->
        logout.logoutSuccessHandler(oidcLogoutSuccessHandler(...)));
```

- `anyRequest().authenticated()`：所有请求需认证
- `oauth2Login`：未认证时自动发起 OIDC 授权码流程
- 判断依据是 **HttpSession 中的 SecurityContext**，JSESSIONID 是 Session 的索引

---

## 二、JSESSIONID 与 id_token 的关系

**不是映射关系，是索引与内容的关系。**

### 完整流程

```
authorization_code
    │
    ▼
Token Endpoint 换取 tokens
    │
    ├── access_token
    ├── id_token ──→ 解析、验证 → 提取用户信息 → 构造 OidcUser 对象
    └── refresh_token
                              │
                              ▼
                    创建 OAuth2AuthenticationToken（包含 OidcUser + clientId）
                              │
                              ▼
                    存入 SecurityContext
                              │
                              ▼
                    SecurityContext 存入 HttpSession
                              │
                              ▼
                    Tomcat 为该 HttpSession 生成随机 JSESSIONID（与 id_token 无关）
```

### 类比

| 概念 | 类比 |
|------|------|
| HttpSession | 保险柜 |
| JSESSIONID | 保险柜的编号/钥匙 |
| OidcUser（来自 id_token） | 保险柜里存的贵重物品 |

JSESSIONID 是 Servlet 容器（Tomcat）生成的随机标识符，和 id_token 的内容没有任何关系。id_token 中的用户信息（sub、name、email 等）是作为 HttpSession 的**内容**被存储的。

### id_token 在客户端的生命周期

```
1. 授权服务器回调返回 id_token
2. Spring Security 解析 id_token → 提取用户信息 → 构造 OidcUser
3. OidcUser 存入 Session
4. id_token 本身不再被使用（过期也无所谓）
5. 后续每次请求从 Session 中取 OidcUser，不再看 id_token
```

**关键结论：id_token 过期 ≠ 客户端登录态丢失。客户端判断登录态完全不看 id_token 的过期时间，只看 Session。**

### 三个 Token 都存储在同一个 HttpSession 中

授权码流程完成后，客户端得到的 access_token、refresh_token、id_token **全部存储在同一个 HttpSession 中**：

```
HttpSession
├── SecurityContext
│   └── OAuth2AuthenticationToken
│       └── OidcUser（来自 id_token 的用户信息：sub, name, email 等）
│
└── OAuth2AuthorizedClient（由 HttpSessionOAuth2AuthorizedClientRepository 管理）
    ├── access_token    ← 访问资源服务器用
    ├── refresh_token   ← 刷新 access_token 用
    └── client_id       ← 关联的客户端标识
```

存储过程由 Spring Security 自动完成：

```
1. Token Endpoint 返回 { access_token, refresh_token, id_token }
2. Spring Security 自动解析 id_token → 构造 OidcUser → 存入 SecurityContext
3. Spring Security 自动构造 OAuth2AuthorizedClient（包含 access_token、refresh_token）
4. HttpSessionOAuth2AuthorizedClientRepository 将 OAuth2AuthorizedClient 存入 HttpSession
5. SecurityContext 也存入 HttpSession
6. Tomcat 为该 Session 生成 JSESSIONID → 通过 Set-Cookie 返回浏览器
```

**这意味着：JSESSIONID 一丢，三个 Token 全丢。** 因为它们都存在 HttpSession 里，而 JSESSIONID 是找到这个 Session 的唯一线索。

| Token | 存储位置 | 丢失后 |
|-------|---------|--------|
| id_token（→ OidcUser） | HttpSession → SecurityContext | Session 丢失则丢失，但可重新授权获取 |
| access_token | HttpSession → OAuth2AuthorizedClient | Session 丢失则丢失，重新授权获取新的 |
| refresh_token | HttpSession → OAuth2AuthorizedClient | Session 丢失则丢失，无法刷新，必须重新走授权码流程 |

**这就是为什么删除 JSESSIONID 后要重新走授权流程——不是某个 token 过期了，而是所有 token 都跟着 Session 一起丢了。**

---

## 三、删除 JSESSIONID 后的"无感重新登录"

### 现象

删除客户端的 JSESSIONID 后，再次访问 `/index`，浏览器被重定向到授权流程，但没有出现登录页面和 Consent 页面，直接回到了 index。

### 原因：两端的 Session 是独立的

```
浏览器 Cookie 存储（按域名隔离）：

127.0.0.1:8080  →  JSESSIONID = "client-session-id"    ← 删除了这个
localhost:9000  →  JSESSIONID = "server-session-id"    ← 这个还在！
```

Cookie 按域名隔离，浏览器访问 `localhost:9000` 时，自动携带 `localhost:9000` 域下的 cookie。删除客户端的 JSESSIONID 不影响授权服务器的 cookie。

### 完整请求流程

```
浏览器                     demo-client:8080              授权服务器:9000
  │                            │                            │
  │ 1. GET /index              │                            │
  │    (无8080的JSESSIONID)    │                            │
  │ ─────────────────────────→ │                            │
  │                            │ SecurityFilterChain: 未认证 │
  │ 2. 302 → /oauth2/authorization/messaging-client-oidc   │
  │ ←───────────────────────── │                            │
  │                            │                            │
  │ 3. GET /oauth2/authorization/messaging-client-oidc     │
  │ ─────────────────────────→ │                            │
  │                            │ 构建authorize URL, 302 →   │
  │ 4. 302 → localhost:9000/oauth2/authorize?...           │
  │ ←───────────────────────── │                            │
  │                            │                            │
  │ 5. GET /oauth2/authorize?...                            │
  │    ★ 自动携带9000的JSESSIONID ★                        │
  │ ─────────────────────────────────────────────────────→ │
  │                            │              检查Session:   │
  │                            │              已登录 ✓       │
  │                            │              已授权 ✓       │
  │ 6. 302 → callback?code=xxx │                            │
  │ ←───────────────────────────────────────────────────── │
  │                            │                            │
  │ 7. GET /login/oauth2/code/...?code=                    │
  │ ─────────────────────────→ │                            │
  │                            │ 用code换token, 建新Session  │
  │ 8. 302 → /index            │                            │
  │    Set-Cookie: JSESSIONID=新值                           │
  │ ←───────────────────────── │                            │
```

第 5 步是关键：浏览器自动携带授权服务器域下的 JSESSIONID，授权服务器据此识别用户已登录。

---

## 四、JSESSIONID 的自动携带机制

### 浏览器存 Cookie 的唯一规则：按域名

浏览器存 Cookie 只有一个规则：**一个域名下，同一时刻只有一份 Cookie。**

```
浏览器的 Cookie 存储：

localhost:9000  →  JSESSIONID=XYZ789     ← 就这一个，没有第二个
127.0.0.1:8080  →  JSESSIONID=ABC123     ← 就这一个，没有第二个
```

**不存在"用户A+客户端D的JSESSIONID"这种东西。** 浏览器根本不知道什么是"用户"、什么是"客户端"，它只知道域名。

### 自动携带规则

浏览器发请求时，**自动把该域名下的所有 Cookie 带上**：

```
请求发往 localhost:9000 → 自动带上 localhost:9000 域下的 JSESSIONID
请求发往 127.0.0.1:8080 → 自动带上 127.0.0.1:8080 域下的 JSESSIONID
```

这是浏览器的内置行为，不需要代码做任何事。

### 常见误区

| 误区 | 实际 |
|------|------|
| 每对"用户+客户端"有一个 JSESSIONID | **每个域名只有一个 JSESSIONID** |
| 浏览器需要知道"对应"哪个 JSESSIONID | **浏览器只看域名，自动带上该域名下的 cookie** |
| 有很多个 JSESSIONID 需要管理 | **一个域名就一个，后登录的覆盖先登录的** |

### 具体场景

**场景1：用户 A 在自己电脑上用浏览器**

```
1. 用户A访问客户端D → 重定向到授权服务器 → 登录 → Set-Cookie: JSESSIONID=AAA
   浏览器存储：localhost:9000 → JSESSIONID=AAA

2. 用户A再访问客户端E → 重定向到授权服务器
   浏览器自动带 Cookie: JSESSIONID=AAA → 授权服务器认出用户A → 已登录 ✓

3. 用户A再访问客户端F → 重定向到授权服务器
   浏览器自动带 Cookie: JSESSIONID=AAA → 授权服务器认出用户A → 已登录 ✓
```

三个客户端，用的是**同一个 JSESSIONID=AAA**，因为都是 `localhost:9000` 域下的。

**场景2：用户 A 登出后，用户 B 用同一台电脑**

```
1. 用户A登出 → 授权服务器销毁 Session → 客户端清空 Cookie

2. 用户B访问客户端D → 重定向到授权服务器 → 登录 → Set-Cookie: JSESSIONID=BBB
   浏览器存储：localhost:9000 → JSESSIONID=BBB（覆盖了旧的）

3. 用户B再访问客户端E → 浏览器带 JSESSIONID=BBB → 授权服务器认出用户B ✓
```

新登录会**覆盖**旧的 JSESSIONID，同一时刻只有一个。

**场景3：用户 A 和用户 B 在不同电脑上**

```
电脑1（用户A的浏览器）：
  localhost:9000 → JSESSIONID=AAA    → 代表用户A的Session

电脑2（用户B的浏览器）：
  localhost:9000 → JSESSIONID=BBB    → 代表用户B的Session
```

不同浏览器实例各自有各自的 Cookie 存储，互不影响。

### 完整流程图

```
用户A的浏览器（一台电脑）
┌──────────────────────────────────────────────────────┐
│  Cookie 存储：                                        │
│  localhost:9000  →  JSESSIONID=AAA                   │
│  127.0.0.1:8080  →  JSESSIONID=DDD                  │
└──────────────────────────────────────────────────────┘

  访问客户端D(8080)                                      访问客户端E(8081)
       │                                                      │
       ▼                                                      ▼
  带8080的Cookie: DDD                                   带8081的Cookie: EEE
       │                                                      │
       ▼                                                      ▼
  客户端D发现未登录                                        客户端E发现未登录
       │                                                      │
       ▼                                                      ▼
  302 → localhost:9000/oauth2/authorize?client_id=D      302 → localhost:9000/oauth2/authorize?client_id=E
       │                                                      │
       ▼                                                      ▼
  浏览器自动带: Cookie: JSESSIONID=AAA              浏览器自动带: Cookie: JSESSIONID=AAA
       │                                                      │
       ▼                                                      ▼
  授权服务器：AAA → 用户A，已登录 ✓                    授权服务器：AAA → 用户A，已登录 ✓
       │                                                      │
       ▼                                                      ▼
  client_id=D → 查consent                              client_id=E → 查consent
```

两边的 JSESSIONID=AAA 是**同一个**，因为是同一个浏览器访问同一个域名。

---

## 五、多客户端场景：授权服务器如何区分

### 问题

授权服务器只有一个，客户端有很多。既然授权服务器的 JSESSIONID 是共享的，怎么知道客户端 A 做了授权，客户端 B 什么都没做？

### 答案：JSESSIONID 管"你是谁"，client_id 管"谁在申请"

**找 Session 跟 client_id 没有任何关系，靠的是浏览器自动携带的 JSESSIONID cookie。**

浏览器重定向到授权服务器时：

```
GET /oauth2/authorize?client_id=A&scope=openid&redirect_uri=...&state=...

Cookie: JSESSIONID=XYZ789    ← 浏览器自动带上，跟 client_id 无关！
```

授权服务器的处理顺序：

```
第一步：看 JSESSIONID（浏览器自动携带的 cookie）
    │
    ├── 找到有效 Session → 用户已登录，不需要重新登录
    │
    └── 没找到 / Session过期 → 用户未登录，需要重新登录
          │
          ▼
      这时候才看 client_id → 登录成功后判断是否需要 Consent
```

JSESSIONID 是浏览器根据域名自动管理的，**跟从哪个客户端跳过来完全无关**。client_id 只管"授权给谁"，不管"谁在登录"。

授权请求 URL 中**始终携带 `client_id`**：

```
客户端A：GET /oauth2/authorize?client_id=client-A&scope=openid+profile&...
客户端B：GET /oauth2/authorize?client_id=client-B&scope=openid+profile&...
```

### Consent 表的联合主键

```sql
CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,   -- 客户端ID
    principal_name varchar(200) NOT NULL,          -- 用户ID
    authorities varchar(1000) NOT NULL,            -- 已授权的scope
    PRIMARY KEY (registered_client_id, principal_name)   -- 联合主键！
);
```

每条记录精确表示：**哪个用户对哪个客户端授权了哪些 scope**。

### 查询逻辑

```java
OAuth2AuthorizationConsent currentAuthorizationConsent =
    this.authorizationConsentService.findById(registeredClient.getId(), principal.getName());
```

### 授权服务器的完整判断逻辑

```
授权服务器收到 /oauth2/authorize 请求

第一步：JSESSIONID → 识别"你是谁"（用户身份）
  └─ 未登录 → 登录页
  └─ 已登录 → 继续

第二步：client_id → 识别"谁在申请"（客户端身份）
  └─ 查 consent 表 (registered_client_id + principal_name)
  └─ 有记录且scope够 → 跳过Consent，直接发code
  └─ 无记录或scope不够 → 弹出Consent页面
```

### 示例数据

| registered_client_id | principal_name | authorities |
|---|---|---|
| `client-A` | `user-张三` | `SCOPE_profile,SCOPE_email` |
| `client-B` | `user-张三` | *(空，从未授权)* |
| `client-A` | `user-李四` | `SCOPE_profile` |

张三通过客户端B登录 → 查不到 consent 记录 → 需要弹出 Consent 页面。
张三通过客户端A登录（第二次）→ 查到记录，scope 足够 → 跳过 Consent。

### 是否需要 Consent 的判断源码

```java
private static boolean isAuthorizationConsentRequired(
        OAuth2AuthorizationCodeRequestAuthenticationContext authenticationContext) {
    // 客户端未开启consent要求 → 不需要
    if (!authenticationContext.getRegisteredClient().getClientSettings().isRequireAuthorizationConsent()) {
        return false;
    }
    // scope只有openid → 不需要
    if (authenticationContext.getAuthorizationRequest().getScopes().contains(OidcScopes.OPENID)
            && authenticationContext.getAuthorizationRequest().getScopes().size() == 1) {
        return false;
    }
    // 之前已同意过所有请求的scope → 不需要
    if (authenticationContext.getAuthorizationConsent() != null && authenticationContext.getAuthorizationConsent()
        .getScopes()
        .containsAll(authenticationContext.getAuthorizationRequest().getScopes())) {
        return false;
    }
    return true;
}
```

---

## 六、各种过期时间的关系

### 默认值一览

| 过期项 | 默认值 | 存储位置 | 说明 |
|--------|--------|----------|------|
| authorization_code | 5 分钟 | 授权服务器数据库/内存 | 一次性使用，用完即失效 |
| access_token | 5 分钟 | JWT 自带 exp | 过期后无法访问资源 |
| id_token | 与 access_token 相同 | JWT 自带 exp | 过期后对客户端登录态无影响 |
| refresh_token | 60 分钟 | 授权服务器数据库/内存 | 用于刷新 access_token |
| 客户端 JSESSIONID | 30 分钟 | Tomcat 内存 | 无活动超时，可自定义 |
| 授权服务器 JSESSIONID | 30 分钟 | Tomcat 内存 | 无活动超时，可自定义 |
| consent 记录 | 永不过期 | 数据库 | 除非手动删除 |

### 过期时间完全解耦

这些过期时间之间**没有任何绑定关系**，各自独立：

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. authorization_code    默认 5 分钟                              │
│    一次性使用，用完即失效                                            │
├──────────────────────────────────────────────────────────────────┤
│ 2. id_token / access_token  默认 5 分钟                           │
│    JWT 自带 exp 声明，过期后无法使用                                 │
│    ★ 客户端不验证 id_token 的过期时间来判定登录态 ★                    │
├──────────────────────────────────────────────────────────────────┤
│ 3. refresh_token         默认 60 分钟                              │
│    用于刷新 access_token，获取新的 access_token                      │
├──────────────────────────────────────────────────────────────────┤
│ 4. 客户端 JSESSIONID     默认 30 分钟（Servlet 容器默认）            │
│    Tomcat 默认 session 超时 = 30 分钟无活动                          │
├──────────────────────────────────────────────────────────────────┤
│ 5. 授权服务器 JSESSIONID  默认 30 分钟                              │
│    同上                                                            │
├──────────────────────────────────────────────────────────────────┤
│ 6. consent 记录          永不过期（除非手动删库）                     │
│    持久化在数据库中，没有 TTL                                        │
└──────────────────────────────────────────────────────────────────┘
```

### JSESSIONID 超时时间可自定义

在 `application.yml` 中配置：

```yaml
server:
  servlet:
    session:
      timeout: 60m   # Session 超时设为 1 小时
```

这个配置和任何 token 的有效期都没有绑定关系，完全可以独立设置。

### 已登录 vs 已授权的存储差异

| 判断 | 查询方式 | 存储位置 | 持久性 |
|------|---------|---------|--------|
| 已登录 | JSESSIONID → HttpSession → SecurityContext | 内存（Tomcat Session） | 临时，超时即丢 |
| 已授权 | consent 表 (registered_client_id + principal_name) | 数据库 | 持久化，除非手动删除 |

---

## 七、何时需要重新登录、重新授权

"已登录"和"已授权"是两个独立状态，排列组合后有四种场景：

### 两个独立状态

| 状态 | 由什么决定 | 存储在哪 | 丢失条件 |
|------|-----------|---------|---------|
| 已登录 | 授权服务器的 Session | 内存 | Session 超时 / 浏览器关闭 / 手动登出 |
| 已授权 | consent 记录 | 数据库 | 数据库记录被删除 / 请求了新的 scope |

### 场景1：无需重新登录，无需重新授权（无感刷新）

```
条件：客户端Session过期，但授权服务器Session还在，consent记录也在

流程：客户端无Session → 发起授权 → 授权服务器识别已登录 → 查到consent → 直接发code → 回到index
用户感知：无感
```

发生时机：客户端 Session 短于授权服务器 Session，且在授权服务器 Session 有效期内客户端 Session 过期了。

### 场景2：需要重新登录，无需重新授权

```
条件：授权服务器Session也过期了，但consent记录还在

流程：客户端无Session → 发起授权 → 授权服务器Session过期 → 要求输入账号密码
      → 登录成功 → 查到consent → 跳过Consent → 发code → 回到index
用户感知：需要输入账号密码，但不需要点授权同意
```

发生时机：授权服务器 Session 超时（默认30分钟无活动），但数据库中的 consent 记录没有被删除。

### 场景3：无需重新登录，需要重新授权

```
条件：授权服务器Session还在，但consent记录没了（或请求了新的scope）

流程：客户端无Session → 发起授权 → 授权服务器识别已登录 → 查不到consent（或scope不够）
      → 弹出Consent页面 → 用户同意 → 发code → 回到index
用户感知：不需要输入密码，但需要点授权同意
```

发生时机：数据库中 consent 记录被手动删除了，或客户端请求了新的 scope（比如之前只授权了 `openid`，现在还请求 `message.write`）。

### 场景4：需要重新登录，需要重新授权

```
条件：授权服务器Session过期，consent记录也没了

流程：客户端无Session → 发起授权 → 授权服务器Session过期 → 要求输入账号密码
      → 登录成功 → 查不到consent → 弹出Consent页面 → 用户同意 → 发code → 回到index
用户感知：需要输入账号密码，还需要点授权同意
```

发生时机：长时间未使用（两端 Session 都过期），且 consent 记录被删除（如运维清理、用户撤销授权）。

### 判断流程图

```
客户端Session过期，需要重新走授权流程
    │
    ▼
授权服务器Session还在吗？
    │
    ├── 否 ──→ 需要重新登录（输入账号密码）
    │              │
    │              ▼
    │         consent记录存在且scope足够吗？
    │              │
    │              ├── 是 ──→ 无需重新授权（跳过Consent）      ← 场景2
    │              └── 否 ──→ 需要重新授权（弹出Consent）      ← 场景4
    │
    └── 是 ──→ 无需重新登录（跳过登录页）
                   │
                   ▼
              consent记录存在且scope足够吗？
                   │
                   ├── 是 ──→ 无需重新授权（跳过Consent）      ← 场景1（无感）
                   └── 否 ──→ 需要重新授权（弹出Consent）      ← 场景3
```

### 特殊情况：客户端 Session 没过期

如果客户端 Session 还在，**根本不会触发授权流程**，无论授权服务器的状态如何。用户一直可以正常访问，直到客户端 Session 过期。

这就是为什么会出现这种情况：授权服务器早就重启了（Session 全丢了），但用户在客户端上毫无感知——因为客户端的 Session 还在，根本不需要去找授权服务器。

### 超时配置组合示例

**两边 Session 都是 1 小时：**

```
0:00  用户首次登录 → 两边都建立 Session
0:30  用户在客户端操作 → 客户端 Session 刷新（还有1小时）
      授权服务器 Session 没有被访问 → 还剩30分钟
0:50  客户端 Session 失效（假设用户没操作）
      授权服务器 Session 已在 1:00 失效

1:10  用户再次访问客户端：
      ├─ 客户端无 Session → 发起授权流程
      ├─ 授权服务器也无 Session → 要求重新登录！
      └─ 用户需要输入账号密码 ← 不是"无感"的
```

**客户端 1 小时，授权服务器 30 分钟：**

```
0:35  客户端 Session 还在 → 直接放行 ✓
      但此时如果用户手动删了客户端 JSESSIONID 再访问：
      ├─ 客户端无 Session → 发起授权流程
      ├─ 授权服务器 Session 已过期 → 要求重新登录！
      └─ 需要输入账号密码
```

**正常的连续使用：**

```
用户持续在客户端操作：
├─ 客户端 Session 不断刷新，一直有效
├─ 授权服务器 Session 无人访问，到时间就过期
└─ 但只要客户端 Session 在，就不会去找授权服务器
    → 登录态持续有效，直到客户端 Session 超时
```

---

## 八、真正"登出"需要清除两端会话

项目中配置了 `OidcClientInitiatedLogoutSuccessHandler`：

```java
private LogoutSuccessHandler oidcLogoutSuccessHandler(
        ClientRegistrationRepository clientRegistrationRepository) {
    OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler =
            new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
    oidcLogoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/logged-out");
    return oidcLogoutSuccessHandler;
}
```

通过页面上的 Logout 按钮（POST `/logout`），会同时：

1. **清除客户端 Session**
2. **重定向到授权服务器的 RP-Initiated Logout 端点**，清除授权服务器 Session
3. 最终重定向回 `/logged-out` 页面

这样再访问 `/index` 就会看到完整的登录页面了。

---

## 九、Session 超时时间建议配置

### 关键原则：授权服务器 Session ≥ 客户端 Session

```
如果 客户端Session > 授权服务器Session：
  客户端Session过期时 → 找授权服务器 → 授权服务器Session也过期 → 用户必须重新输入密码
  用户体验：❌ 明明刚还在用，突然要重新登录

如果 客户端Session ≤ 授权服务器Session：
  客户端Session过期时 → 找授权服务器 → 授权服务器Session还在 → 无感刷新
  用户体验：✅ 无感知
```

**建议：授权服务器 Session 要比客户端 Session 长，留出"无感续期"的窗口。**

### 三种典型配置方案

**方案1：高安全场景（金融、支付）**

| | 客户端 | 授权服务器 |
|---|---|---|
| Session 超时 | 15~30 分钟 | 30~60 分钟 |
| 特点 | 短超时，减少被盗用风险 | 比客户端长，允许无感续期 |

```
用户15分钟不操作 → 客户端Session过期
  → 找授权服务器 → Session还在(还有15~45分钟) → 无感刷新 ✓
用户超过30~60分钟不操作 → 两边都过期 → 必须重新输入密码
```

**方案2：常规场景（大多数 Web 应用）**

| | 客户端 | 授权服务器 |
|---|---|---|
| Session 超时 | 30~60 分钟 | 2~4 小时 |
| 特点 | 平衡安全与体验 | 较长窗口，减少重新登录频率 |

```
用户30~60分钟不操作 → 客户端Session过期
  → 找授权服务器 → Session还在 → 无感刷新 ✓
用户2~4小时不操作 → 授权服务器Session过期 → 需要重新输入密码
  → 但consent还在 → 不需要再点授权同意
```

**方案3：高便利场景（社交、内容平台）**

| | 客户端 | 授权服务器 |
|---|---|---|
| Session 超时 | 2~8 小时 | 1~7 天 |
| 特点 | 长超时，用户几乎无感 | 很少需要重新登录 |

```
用户2~8小时不操作 → 客户端Session过期
  → 找授权服务器 → Session还在 → 无感刷新 ✓
用户1~7天不操作 → 授权服务器Session过期 → 才需要重新输入密码
```

### 配置方式

```yaml
# 客户端 application.yml
server:
  servlet:
    session:
      timeout: 60m    # 根据方案选择

# 授权服务器 application.yml
server:
  servlet:
    session:
      timeout: 240m   # 比客户端长
```

### 多客户端的统一体验

如果多个客户端共用一个授权服务器，授权服务器的 Session 超时决定了**所有客户端的"无感续期窗口"**：

```
授权服务器 Session = 4小时

客户端A Session=30min → 最长每30分钟可能无感续期一次
客户端B Session=2h    → 最长每2小时可能无感续期一次
客户端C Session=8h    → 比授权服务器长！4小时后可能要重新输入密码

所以：所有客户端的 Session 都应该 ≤ 授权服务器 Session
```

---

## 十、Refresh Token 刷新时的影响

### 刷新流程概述

当客户端用 access_token 访问资源服务器发现过期时，客户端后端会自动用 refresh_token 向授权服务器的 Token Endpoint 发起刷新请求：

```
客户端后端(WebClient) ──→ 授权服务器 POST /oauth2/token
                              grant_type=refresh_token
                              refresh_token=xxx
                         ──→ 返回新 access_token + 新 id_token + 可能新 refresh_token
```

### 刷新时 id_token 会重新生成吗？

**会。** 根据授权服务器源码 `OAuth2RefreshTokenAuthenticationProvider`：

```java
// ----- ID token -----
OidcIdToken idToken;
if (authorizedScopes.contains(OidcScopes.OPENID)) {
    tokenContext = tokenContextBuilder
            .tokenType(ID_TOKEN_TOKEN_TYPE)
            .build();
    OAuth2Token generatedIdToken = this.tokenGenerator.generate(tokenContext);
    ...
    idToken = new OidcIdToken(generatedIdToken.getTokenValue(), ...);
    authorizationBuilder.token(idToken, ...);
}
```

如果 scope 包含 `openid`，刷新时会**同时生成新的 id_token** 并返回。但这不影响客户端的登录态——因为客户端用的是 Session 中的 OidcUser，不是 id_token 本身。

### 刷新时 Session 超时时间会变吗？

**客户端 Session：不变。** refresh_token 是客户端后端直接向授权服务器发请求，**不经过浏览器**，不触碰客户端的 HttpSession，不会重置超时倒计时。

**授权服务器 Session：不变。** refresh_token 请求走的是 Token Endpoint（`/oauth2/token`），这个端点不会刷新授权服务器的 HttpSession 超时时间。

```
refresh token 刷新流程：

客户端后端(WebClient) ──→ 授权服务器 /oauth2/token ──→ 返回新token

这个流程中：
├─ 浏览器不参与 → 客户端 HttpSession 不受影响
├─ /oauth2/token 是无状态端点 → 授权服务器 HttpSession 不受影响
└─ Session 超时倒计时继续走，不会重置
```

### 完整变化对比

| | access_token 过期后刷新时 | 原因 |
|---|---|---|
| id_token | **会重新生成** | 授权服务器刷新时重新颁发（scope 含 openid 时） |
| access_token | **会重新生成** | 这就是刷新的目的 |
| refresh_token | **可能重新生成** | 取决于 `TokenSettings.isReuseRefreshTokens()` 配置 |
| 客户端 Session 超时 | **不变** | 刷新请求不经过浏览器，不触碰 HttpSession |
| 授权服务器 Session 超时 | **不变** | Token Endpoint 不刷新 Session |
| 客户端 Session 内容 | **可能变** | 新的 access_token/refresh_token 会更新到 Session 中的 OAuth2AuthorizedClient |

### 完整时间线示例

```
0:00  用户登录，建立两端的 Session（都设1小时超时）
      拿到 access_token(5min) + refresh_token(60min) + id_token(5min)

0:05  access_token 过期，客户端后端自动用 refresh_token 刷新
      → 拿到新 access_token + 新 id_token + 可能新 refresh_token
      → 客户端 Session 超时倒计时：还剩 55 分钟（不变）
      → 授权服务器 Session 超时倒计时：还剩 55 分钟（不变）

0:10  access_token 又过期，再次刷新
      → 客户端 Session 剩 50 分钟
      → 授权服务器 Session 剩 50 分钟

0:55  用户一直在操作客户端（每次操作刷新客户端 Session）
      → 客户端 Session 剩 60 分钟（被重置了）
      → 授权服务器 Session 已过期！（从0:00起1小时无人访问）

1:05  access_token 又过期，客户端后端用 refresh_token 刷新
      → 仍然可以刷新 ✓（refresh_token 还没过期）
      → 拿到新 token
      → 但授权服务器 Session 已经过期了
      → 注意：这没问题！refresh_token 刷新走的是 Token Endpoint，不需要 Session

1:00  refresh_token 过期（60min）
      → 无法刷新了 → 客户端需要重新走授权码流程
      → 找授权服务器 → Session 过期 → 要求重新登录
```

### 关键理解

**refresh_token 是独立于 Session 的。** 它存在授权服务器的数据库中，有自己的过期时间（默认60分钟）。刷新 token 不需要 Session 参与，所以不会影响任何一端的 Session 超时。

**只有浏览器和服务器之间的交互（页面跳转）才会刷新 Session 超时**，后端到后端的 token 刷新不会。

---

## 十一、核心结论

1. **客户端的登录态 = Session（JSESSIONID）**，和 id_token 的有效期完全解耦
2. **id_token 只在授权回调时用一次**，之后就是 Session 的天下
3. **客户端 JSESSIONID 和授权服务器 JSESSIONID 是独立的**，按域名隔离
4. **已登录查 Session（内存），已授权查数据库**，两者存储和生命周期完全不同
5. **多客户端不会乱套**，因为 consent 表的联合主键 `(registered_client_id, principal_name)` 精确区分了每对"用户-客户端"的授权关系
6. **各过期时间完全独立配置**，互不绑定，按业务需求自行设定
7. **JSESSIONID 是浏览器按域名自动管理的**，一个域名同一时刻只有一个，跟用户和客户端无关；浏览器发请求时自动带上该域名下的 cookie，不需要代码干预
8. **client_id 只管"授权给谁"**，不参与判断"谁在登录"；判断登录靠 JSESSIONID，判断授权靠 client_id + 用户名查 consent 表
9. **授权服务器 Session 应 ≥ 客户端 Session**，确保客户端 Session 过期时能无感续期
10. **refresh_token 刷新时两端的 Session 超时都不会变**，因为刷新是后端到后端的请求，不经过浏览器，不触碰 HttpSession
