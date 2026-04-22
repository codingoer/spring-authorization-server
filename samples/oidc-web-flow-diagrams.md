# OIDC 授权登录流程图解

场景：用户在小红书点击"迪士尼登录"，完成授权后访问迪士尼 API 拿到头像。

---

## 一、完整 OIDC 授权码流程（时序图）

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant Client as 📕 小红书<br/>（客户端）
    participant AS as 🏰 迪士尼授权服务器<br/>（Authorization Server）
    participant RS as 🎭 迪士尼API<br/>（Resource Server）

    Note over User,RS: 第一阶段：发起授权请求

    User->>Client: 1. 点击"使用迪士尼账号登录"
    Client->>Client: 2. 检查 Session：未登录
    Client-->>User: 3. 302 重定向到迪士尼授权服务器
    Note right of Client: Location: https://disney.com/oauth2/authorize<br/>?client_id=xiaohongshu<br/>&scope=openid+profile<br/>&redirect_uri=https://xhs.com/callback<br/>&state=abc123<br/>&response_type=code

    Note over User,RS: 第二阶段：用户登录 & 授权

    User->>AS: 4. 浏览器携带请求访问 /oauth2/authorize
    AS->>AS: 5. 检查 Session：未登录
    AS-->>User: 6. 展示迪士尼登录页面

    User->>AS: 7. 输入迪士尼账号密码
    AS->>AS: 8. 验证身份，创建 Session<br/>Set-Cookie: JSESSIONID=DDD
    AS-->>User: 9. 展示授权同意页面<br/>"小红书请求访问你的头像和昵称"

    User->>AS: 10. 点击"同意"
    AS->>AS: 11. 保存 consent 记录到数据库<br/>(xiaohongshu, user-001, profile)
    AS-->>User: 12. 302 重定向回小红书
    Note right of AS: Location: https://xhs.com/callback<br/>?code=SplxlOBeZQQYbYS6WxSbIA<br/>&state=abc123

    Note over User,RS: 第三阶段：用授权码换取令牌

    User->>Client: 13. 浏览器携带 code 回调 /callback
    Client->>Client: 14. 验证 state=abc123 ✓
    Client->>+AS: 15. POST /oauth2/token<br/>grant_type=authorization_code<br/>code=SplxlOBeZQQYbYS6WxSbIA<br/>client_id=xiaohongshu<br/>client_secret=xxx<br/>redirect_uri=https://xhs.com/callback

    AS->>AS: 16. 验证 code，生成令牌

    AS-->>-Client: 17. 返回令牌
    Note left of AS: {<br/>  "access_token": "at-xxx",<br/>  "token_type": "Bearer",<br/>  "expires_in": 300,<br/>  "refresh_token": "rt-xxx",<br/>  "id_token": "eyJhbG..."<br/>}

    Client->>Client: 18. 解析 id_token，提取用户信息<br/>构造 OidcUser，存入 Session<br/>Set-Cookie: JSESSIONID=XXX

    Client-->>User: 19. 302 重定向到首页

    Note over User,RS: 第四阶段：访问资源服务器获取头像

    User->>Client: 20. 访问个人主页
    Client->>+RS: 21. GET /api/userinfo<br/>Authorization: Bearer at-xxx
    RS->>RS: 22. 验证 access_token
    RS-->>-Client: 23. 返回用户信息<br/>{"name": "Mickey", "avatar": "https://..."}

    Client-->>User: 24. 展示迪士尼头像 🐭
```

---

## 二、登录态判断流程（流程图）

```mermaid
flowchart TD
    A["👤 用户访问小红书"] --> B{"📕 小红书 Session<br/>中是否有 SecurityContext?"}
    
    B -->|有| C["✅ 已登录，直接展示页面"]
    B -->|没有| D["302 → /oauth2/authorization/disney"]
    
    D --> E["📕 小红书构建授权 URL<br/>302 → 迪士尼 /oauth2/authorize"]
    E --> F{"🏰 迪士尼 Session<br/>是否有效?"}
    
    F -->|有效| G["跳过登录页"]
    F -->|无效| H["展示迪士尼登录页<br/>用户输入账号密码"]
    H --> I["登录成功，建立迪士尼 Session"]
    I --> G
    
    G --> J{"consent 表中是否有<br/>(xiaohongshu, user-001) 记录?"}
    
    J -->|有且 scope 足够| K["跳过授权同意页"]
    J -->|没有或 scope 不够| L["展示授权同意页<br/>用户点击同意"]
    L --> M["保存 consent 到数据库"]
    M --> K
    
    K --> N["颁发 authorization_code<br/>302 → 小红书 /callback?code=xxx"]
    N --> O["小红书用 code 换 token<br/>解析 id_token → 存入 Session"]
    O --> C

    style C fill:#4CAF50,color:#fff
    style H fill:#FF9800,color:#fff
    style L fill:#FF9800,color:#fff
```

---

## 三、Cookie / Session 分布图

```mermaid
flowchart LR
    subgraph Browser["🌐 浏览器 Cookie 存储（按域名隔离）"]
        direction TB
        C1["xhs.com<br/>JSESSIONID=XXX<br/>（小红书 Session）"]
        C2["disney.com<br/>JSESSIONID=DDD<br/>（迪士尼 Session）"]
    end

    subgraph XHS["📕 小红书服务器 (xhs.com)"]
        direction TB
        S1["HttpSession XXX<br/>├─ SecurityContext<br/>│  └─ OidcUser (来自 id_token)<br/>│     ├─ sub: user-001<br/>│     └─ name: Mickey<br/>└─ OAuth2AuthorizedClient<br/>   ├─ access_token: at-xxx<br/>   └─ refresh_token: rt-xxx"]
    end

    subgraph Disney["🏰 迪士尼授权服务器 (disney.com)"]
        direction TB
        S2["HttpSession DDD<br/>└─ SecurityContext<br/>   └─ UsernamePasswordAuthenticationToken<br/>      └─ user-001"]
        DB[("数据库<br/>oauth2_authorization_consent<br/>┌──────────────┬──────────┬──────────────┐<br/>│ client_id    │ user     │ scopes       │<br/>├──────────────┼──────────┼──────────────┤<br/>│ xiaohongshu  │ user-001 │ SCOPE_profile│<br/>└──────────────┴──────────┴──────────────┘")]
    end

    C1 -.->|索引| S1
    C2 -.->|索引| S2
```

---

## 四、Token 刷新流程

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant Client as 📕 小红书<br/>（客户端后端）
    participant AS as 🏰 迪士尼授权服务器
    participant RS as 🎭 迪士尼API

    Note over User,RS: access_token 过期后的刷新流程

    User->>Client: 1. 刷新个人主页
    Client->>+RS: 2. GET /api/userinfo<br/>Authorization: Bearer at-xxx（已过期）
    RS-->>-Client: 3. 401 Unauthorized<br/>token 过期

    Client->>Client: 4. 检测到 access_token 过期<br/>发现还有有效的 refresh_token

    Client->>+AS: 5. POST /oauth2/token<br/>grant_type=refresh_token<br/>refresh_token=rt-xxx<br/>client_id=xiaohongshu<br/>client_secret=xxx

    Note right of AS: ⚠️ 此请求是后端到后端<br/>浏览器不参与<br/>❌ 不刷新任何 Session

    AS->>AS: 6. 验证 refresh_token<br/>生成新 access_token<br/>生成新 id_token（scope 含 openid 时）<br/>可能生成新 refresh_token

    AS-->>-Client: 7. 返回新令牌<br/>{access_token, id_token, refresh_token}

    Client->>Client: 8. 更新 Session 中的 OAuth2AuthorizedClient<br/>⚠️ Session 超时倒计时不变

    Client->>+RS: 9. GET /api/userinfo<br/>Authorization: Bearer at-new
    RS-->>-Client: 10. 200 OK 返回用户信息

    Client-->>User: 11. 展示页面（用户无感知）
```

---

## 五、refresh_token 也过期后的完整流程

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant Client as 📕 小红书
    participant AS as 🏰 迪士尼授权服务器
    participant RS as 🎭 迪士尼API

    Note over User,RS: refresh_token 过期 → 必须重新走授权码流程

    User->>Client: 1. 刷新个人主页
    Client->>RS: 2. 用 access_token 请求 → 过期
    Client->>AS: 3. 用 refresh_token 刷新 → 也过期！
    AS-->>Client: 4. 400 invalid_grant（refresh_token 无效）

    Client->>Client: 5. 清除本地 OAuth2AuthorizedClient

    Note over User,RS: 重新走授权码流程

    Client-->>User: 6. 302 → /oauth2/authorization/disney
    User->>AS: 7. 浏览器访问 /oauth2/authorize<br/>携带 disney.com 域的 JSESSIONID

    alt 迪士尼 Session 还在
        AS-->>User: 跳过登录页
    else 迪士尼 Session 也过期
        AS-->>User: 展示登录页 → 用户重新输入密码
    end

    Note over AS: consent 记录还在数据库 → 跳过同意页

    AS-->>User: 302 → /callback?code=new-code
    User->>Client: 回调
    Client->>AS: 用新 code 换新 token
    AS-->>Client: 新 access_token + 新 refresh_token + 新 id_token
    Client-->>User: 展示页面
```

---

## 六、登出流程

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant Client as 📕 小红书
    participant AS as 🏰 迪士尼授权服务器

    User->>Client: 1. 点击"退出登录"
    Client->>Client: 2. 清除小红书 Session<br/>（删除 xhs.com 的 JSESSIONID）

    Client-->>User: 3. 302 → 迪士尼 /oauth2/logout<br/>id_token_hint=eyJhbG...<br/>post_logout_redirect_uri=https://xhs.com/logged-out

    User->>AS: 4. 浏览器访问 /oauth2/logout<br/>携带 disney.com 的 JSESSIONID
    AS->>AS: 5. 清除迪士尼 Session<br/>（删除 disney.com 的 JSESSIONID）

    AS-->>User: 6. 302 → https://xhs.com/logged-out

    User->>Client: 7. 访问登出成功页面

    Note over User,AS: 两端 Session 都已清除<br/>再次访问需要重新登录
```

---

## 七、核心交互总结

```mermaid
flowchart TB
    subgraph 交互类型与Session影响
        A["浏览器 ↔ 服务器<br/>（页面跳转/重定向）"] --> A1["✅ 会刷新 Session 超时"]
        B["后端 ↔ 后端<br/>（Token Endpoint / API 调用）"] --> B1["❌ 不刷新 Session 超时"]
    end

    subgraph 各端Session独立
        C["📕 小红书 Session<br/>xhs.com 域<br/>JSESSIONID=XXX"] 
        D["🏰 迪士尼 Session<br/>disney.com 域<br/>JSESSIONID=DDD"]
    end

    subgraph 登录态vs授权态
        E["已登录 = Session 存在<br/>（内存，临时）"]
        F["已授权 = consent 记录存在<br/>（数据库，持久）"]
    end

    subgraph Token生命周期
        G["access_token: 5min<br/>访问资源用"]
        H["id_token: 5min<br/>只用于提取用户信息<br/>过期不影响登录态"]
        I["refresh_token: 60min<br/>刷新 access_token 用<br/>独立于 Session"]
    end
```
