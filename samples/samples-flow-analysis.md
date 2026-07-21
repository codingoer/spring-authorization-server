# Samples - 授权请求分析

## 流程拆解

### 1. 访问客户端首页 `/`

- `GET http://127.0.0.1:8080/`
- 返回 `302`
- 跳转到 `http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc`

`demo-client` 要求大多数请求必须认证，而 `oauth2Login().loginPage("/oauth2/authorization/messaging-client-oidc")` 把登录入口设成了发起 OIDC 授权请求的地址。

也就是说，访问首页本身就会触发“去授权服务器登录”。

#### 1.1 如何重定向

http://127.0.0.1:8080/ → /oauth2/authorization/messaging-client-oidc

```java
.authorizeHttpRequests(authorize ->
    authorize
        .requestMatchers("/jwks", "/logged-out").permitAll()
        .anyRequest().authenticated()
)
.oauth2Login(oauth2Login ->
    oauth2Login.loginPage("/oauth2/authorization/messaging-client-oidc"))
```

- 用户未认证 → Spring Security 需要触发登录
- 因为配置了 oauth2Login 且显式指定了 `loginPage("/oauth2/authorization/messaging-client-oidc")`

### 2. 客户端发起 OIDC 授权请求

浏览器继续访问：

- `GET http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc`

随后被重定向到授权服务器：

- `GET http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&state=rU_s1EmFN7-kPD3K4pd-L5ASOh5rI_wpRBaqVgcy7Gs%3D&redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc&nonce=1goUj9w8Zbh3hgOyd7soHEldIaU1HUWSMl7Oj-7AoSc`

URLDecode：

`http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid profile&state=rU_s1EmFN7-kPD3K4pd-L5ASOh5rI_wpRBaqVgcy7Gs=&redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc&nonce=1goUj9w8Zbh3hgOyd7soHEldIaU1HUWSMl7Oj-7AoSc`

可以看到这些关键参数：

- `response_type=code`
- `client_id=messaging-client`
- `scope=openid profile`
- `redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc`
- `state=rU_s1EmFN7-kPD3K4pd-L5ASOh5rI_wpRBaqVgcy7Gs`
- `nonce=1goUj9w8Zbh3hgOyd7soHEldIaU1HUWSMl7Oj-7AoSc`

其中：

- `state` 用于防止 CSRF，并关联本次授权请求
- `nonce` 是 OIDC 登录常见参数，用于后续 ID Token 校验

#### 2.1 客户端侧源码链路：`/oauth2/authorization/messaging-client-oidc` 是怎么变成 `/oauth2/authorize?...` 的

这里最容易误解的一点是：

- `http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc`

不是 sample 里自己写的业务 Controller，而是 Spring Security OAuth2 Client 的内置入口。

#### 第一步：为什么会先跳到 `/oauth2/authorization/messaging-client-oidc`

`demo-client` 的安全配置中写了：

- `oauth2Login().loginPage("/oauth2/authorization/messaging-client-oidc")`

这表示：

- 当用户访问受保护资源但尚未登录时
- Spring Security 不显示默认登录页
- 而是直接把“登录入口”指向 `/oauth2/authorization/messaging-client-oidc`

所以访问首页 `/` 时，才会先被重定向到这个地址。

#### 第二步：谁处理了 `/oauth2/authorization/messaging-client-oidc`

处理它的不是业务代码，而是 Spring Security OAuth2 Client 内部组件：

- `OAuth2AuthorizationRequestRedirectFilter`
- `DefaultOAuth2AuthorizationRequestResolver`

可以把它理解为下面这条链路：

1. 浏览器请求 `/oauth2/authorization/messaging-client-oidc`
2. `OAuth2AuthorizationRequestRedirectFilter` 识别出这是默认模式：
   - `/oauth2/authorization/{registrationId}`
3. 从路径中提取：
   - `registrationId = messaging-client-oidc`
4. `DefaultOAuth2AuthorizationRequestResolver` 去 `ClientRegistrationRepository` 中查找这个 registration
5. 根据配置拼出完整授权请求
6. 返回 `302`，跳转到授权服务器的 `/oauth2/authorize?...`

#### 第三步：`application.yml` 的配置分别起什么作用

`application.yml` 中这段 registration：

- `client-id: messaging-client`
- `authorization-grant-type: authorization_code`
- `redirect-uri: http://127.0.0.1:8080/login/oauth2/code/{registrationId}`
- `scope: openid, profile`

会决定最终授权请求里的关键参数：

- `response_type=code`
- `client_id=messaging-client`
- `scope=openid profile`
- `redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc`

而 provider 里的：

- `issuer-uri: http://localhost:9000`

作用不是直接把 `authorization-uri` 写死，而是告诉 Spring：

- 这个客户端信任的授权服务器发行者是谁
- 由框架基于 issuer 去解析 provider metadata
- 最终得到授权端点，例如：
  - `http://localhost:9000/oauth2/authorize`

因此，第 2 步里的目标地址并不是 sample 手工拼出来的，而是：

- `SecurityConfig` 指定登录入口
- `application.yml` 提供客户端注册信息
- Spring Security 内部过滤器根据这些配置动态生成授权请求

#### 第四步：`state` 和 `nonce` 是谁加上的

这两个参数也不是 sample 手工拼出来的，而是框架在构造授权请求时自动补上的：

- `state`
  - 用于把本次授权请求与后续回调响应关联起来，并防止 CSRF
- `nonce`
  - 因为这里是 OIDC 登录，请求里包含 `openid`
  - 后续校验 `id_token` 时会使用

#### 一句话总结

第 2 步到第 3 步的本质是：

- `demo-client` 并没有自己写代码去拼 `/oauth2/authorize?...`
- 而是 Spring Security 从 `application.yml` 读取 `messaging-client-oidc` 的配置
- 再由 OAuth2 Client 内置过滤器自动生成授权请求并返回 `302`

### 3. 授权服务器发现用户未登录，跳转到 `/login`

HAR 中：

- `GET http://localhost:9000/oauth2/authorize?...`
- 返回 `302`
- `Location: http://localhost:9000/login`

对应的是授权服务器的安全配置：

- `AuthorizationServerConfig` 中，授权端点链路使用 `LoginUrlAuthenticationEntryPoint("/login")`
- `DefaultSecurityConfig` 中，`formLogin().loginPage("/login")`

含义是：

- `/oauth2/authorize` 先要求资源拥有者登录
- 如果当前会话还没有用户身份，就先去 `/login`

### 3.0 跳 `/login` 之前，其实已经做了一轮授权请求校验

这里很容易误解成：

- 只要访问 `/oauth2/authorize`
- 授权服务器就会立刻 `302 -> /login`

但从 Spring Authorization Server 源码来看，真实顺序不是“先登录、后校验参数”，而是：

1. 先解析并校验授权请求参数
2. 再判断当前资源拥有者是否已登录
3. 只有在“请求本身合法，但用户还没登录”时，才跳 `/login`

也就是说：

- **会先校验 `client_id`**
- 而且不只是 `client_id`
- 还会校验一整组授权请求参数

#### 第一层：请求参数解析阶段的基础校验

对应源码：

- `OAuth2AuthorizationCodeRequestAuthenticationConverter`

在这一层，授权服务器会先检查参数本身是否存在、是否重复、格式是否符合预期。包括：

- `response_type`
  - 必须存在
  - 必须是 `code`
- `client_id`
  - 必须存在
  - 只能出现一次
- `redirect_uri`
  - 如果提供，只能出现一次
- `scope`
  - 如果提供，只能出现一次
- `state`
  - 如果提供，只能出现一次
- `code_challenge`
  - 如果提供，不能为空且只能出现一次
- `code_challenge_method`
  - 如果提供，不能为空且只能出现一次
- `prompt`
  - 如果请求包含 `openid`，则会进一步检查它的出现形式

所以如果连最基础的：

- `client_id` 缺失
- `response_type` 不是 `code`
- 参数重复传了多次

这种情况都不会进入“跳 `/login`”的分支，而是直接按错误请求处理。

#### 第二层：授权请求语义校验

对应源码：

- `OAuth2AuthorizationCodeRequestAuthenticationProvider`
- `OAuth2AuthorizationCodeRequestAuthenticationValidator`

在这一层，授权服务器会继续做更严格的校验。包括：

- 根据 `client_id` 查找 `RegisteredClient`
  - 如果查不到，说明 `client_id` 非法
- 校验该客户端是否允许 `authorization_code`
- 校验 `redirect_uri`
  - 是否是合法 URI
  - 是否与注册值匹配
  - 对 OIDC 来说，如果缺失 `redirect_uri`，也会视为错误
- 校验 `scope`
  - 请求的 scope 是否都在客户端允许的 scope 范围内
- 校验 PKCE
  - 对要求 PKCE 的客户端，`code_challenge` 必须满足要求
- 校验 OIDC 的 `prompt`
  - 例如 `prompt=none` 不能和某些其他值冲突

#### 然后才会进入“是否已登录”的判断

只有当上述校验都通过之后，源码才会进入：

- `principal` 是否已经认证

这一步如果发现当前用户还没登录，才会返回“未认证的授权请求结果”，随后由 Spring Security 后续链路触发：

- `302 -> /login`

所以第 3 步更准确的理解应该是：

- **不是“收到 `/oauth2/authorize` 就跳登录页”**
- 而是“收到一个合法的授权请求后，如果资源拥有者尚未登录，才跳登录页”

### 3.1 这里其实是一个三分支判断，不是固定一定跳登录页

授权服务器收到：

- `GET http://localhost:9000/oauth2/authorize?...`

之后，并不是固定执行“跳到 `/login`”。

结合 Spring Authorization Server 源码，真实逻辑其实是三选一：

1. 用户未登录
   - 跳到 `/login`
2. 用户已登录，但需要 consent
   - 跳到 `/oauth2/consent`
3. 用户已登录，且不需要 consent
   - 直接签发 authorization code，并回调客户端

#### 未登录时，为什么会跳 `/login`

在 `OAuth2AuthorizationCodeRequestAuthenticationProvider` 中，会先判断当前 principal 是否已经认证：

- 如果没有认证，则不会继续生成 code
- 而是返回一个“未认证”的授权请求结果

随后 `OAuth2AuthorizationEndpointFilter` 发现这次认证结果仍然是未认证状态，就把请求继续交回 Spring Security 的后续链路；而授权服务器的 `SecurityFilterChain` 又配置了：

- `anyRequest().authenticated()`
- 未认证的 HTML 请求使用 `LoginUrlAuthenticationEntryPoint("/login")`

因此最终行为就是：

- `/oauth2/authorize` -> `302 /login`

#### 已登录时，为什么可以跳过 `/login`

如果浏览器已经带着授权服务器 `localhost:9000` 的有效会话 cookie，再次访问 `/oauth2/authorize` 时：

- 当前 principal 已经是 authenticated
- `OAuth2AuthorizationCodeRequestAuthenticationProvider` 就不会再走“未登录”分支
- 而是继续判断是否需要 consent

接下来又会分成两种情况：

- 如果该用户对当前 client 的当前 scopes 还没有授权完成
  - 跳 `/oauth2/consent`
- 如果这些 scopes 已经授权过
  - 直接生成 authorization code
  - 然后 `302` 回客户端 `redirect_uri`

#### 三分支判断图

```mermaid
flowchart TD
    A["Browser -> GET /oauth2/authorize?..."] --> B{"授权服务器当前用户是否已登录?"}
    B -- 否 --> C["302 -> /login"]
    B -- 是 --> D{"当前 client + scopes 是否需要 consent?"}
    D -- 是 --> E["302 -> /oauth2/consent"]
    D -- 否 --> F["生成 authorization code"]
    F --> G["302 -> redirect_uri?code=...&state=..."]
```

这个分支关系解释了为什么：

- 有时会看到 `/login`
- 有时不会再出现 `/login`
- 但即使已经登录，仍然可能因为 scope 变化而出现 `/oauth2/consent`

## 4. 展示授权服务器登录页

HAR 中：

- `GET http://localhost:9000/login`
- 返回 `200`

页面来源：

- `samples/demo-authorizationserver/src/main/java/sample/web/LoginController.java`
- `samples/demo-authorizationserver/src/main/resources/templates/login.html`

这个页面是授权服务器自己的登录页，不是 `demo-client` 的页面。

## 5. 提交用户名密码

HAR 中最关键的表单提交是：

- `POST http://localhost:9000/login`

表单内容：

```text
_csrf=...&username=user1&password=password
```

这里说明两件事：

1. 这次输入的用户名密码是提交给授权服务器本地登录表单的
2. 这不是 OAuth2 的 password grant，而是一次普通的 Spring Security 表单认证

其中：

- `username=user1`
- `password=password`
- `_csrf=...` 是 Spring Security CSRF 防护参数

## 5.5 为什么登录成功后会回到原始 `/oauth2/authorize` 请求

这里的跳转不是 sample 自己写 Controller 手工完成的，而是 Spring Security 默认的“保存原始请求并在登录成功后回跳”的机制。

可以把这段流程理解成：

1. 浏览器最初访问授权服务器：
   - `GET /oauth2/authorize?...`
2. 当前用户未登录，授权服务器不能继续处理授权请求
3. Spring Security 先把这个原始请求保存到 session
4. 再通过登录入口：
   - `302 /login`
5. 用户提交：
   - `POST /login`
6. 登录成功后，Spring Security 从 session 中取回之前保存的原始请求
7. 再次 `302` 回：
   - `/oauth2/authorize?...`

这套机制的关键不是业务代码，而是 Spring Security 默认组件协作完成的：

- `ExceptionTranslationFilter`
  - 负责在需要认证但当前未认证时启动认证流程
- `RequestCache`
  - 负责缓存原始请求
- `LoginUrlAuthenticationEntryPoint`
  - 负责把未认证用户重定向到 `/login`
- `SavedRequestAwareAuthenticationSuccessHandler`
  - 负责登录成功后把用户带回保存下来的原始请求

### sample 中能直接看到的相关配置

授权服务器要求授权端点必须先认证，且未认证时跳登录页：

- `AuthorizationServerConfig`
  - `anyRequest().authenticated()`
  - `defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), ...)`

而本地用户名密码登录来自：

- `DefaultSecurityConfig`
  - `formLogin(formLogin -> formLogin.loginPage("/login"))`

这里有个很重要的点：

- `formLogin()` 没有自定义 success handler
- 因此会使用 Spring Security 默认的登录成功处理逻辑
- 这个默认逻辑正是基于 Saved Request 的回跳机制

所以：

- `POST /login` 成功后为什么会回到 `/oauth2/authorize?...`
- 不是 sample 手写了一个 `redirect`
- 而是 Spring Security 自动恢复了先前保存的请求

## 6. 登录成功后回到原始授权请求

HAR 中：

- `POST /login` 返回 `302`
- 跳回 `http://localhost:9000/oauth2/authorize?...&continue`

这里的 `continue` 不是 OAuth 标准参数，而是 Spring Security 在“恢复之前保存的原始请求”时带上的继续访问标记。可以把它理解成：

- 当前这次跳转不是一条全新的业务请求
- 而是在用户登录成功后，继续执行登录前尚未完成的 `/oauth2/authorize` 请求

也就是说，`continue` 对应的是 Spring Security 的请求恢复语义，而不是 OAuth2 / OIDC 协议本身的参数。

本质上它表示：

- 用户已经登录成功
- 现在继续处理之前那个尚未完成的 `/oauth2/authorize` 请求

## 7. 授权服务器再次处理 `/oauth2/authorize`

再次访问：

- `GET http://localhost:9000/oauth2/authorize?...&continue`

此时用户已经登录，因此授权服务器会继续走授权码流程的核心处理逻辑。

核心代码位于：

- `oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/OAuth2AuthorizationEndpointFilter.java`
- `oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationCodeRequestAuthenticationProvider.java`

这一阶段会做的事包括：

- 根据 `client_id` 找到已注册客户端
- 校验 `redirect_uri`
- 校验 `scope`
- 校验 OIDC 请求参数
- 判断当前用户是否已经登录
- 判断是否需要 consent
- 生成 authorization code
- 重定向回客户端回调地址

HAR 中本次请求的结果是：

- 返回 `302`
- `Location: http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc?code=...&state=...`

这说明授权服务器已经签发了授权码。

### 7.1 第七步也是一个分支点：是否展示 consent

这里的请求：

- `GET http://localhost:9000/oauth2/authorize?...&continue`

是在用户刚刚完成表单登录之后，由 Spring Security 恢复出来的原始授权请求。

因此在这个时点上，通常“用户是否已登录”已经成立，接下来最关键的判断就是：

- 是否需要展示 consent 页面

从 Spring Authorization Server 源码来看，这一步仍然由：

- `OAuth2AuthorizationEndpointFilter`
- `OAuth2AuthorizationCodeRequestAuthenticationProvider`

共同处理。

它的结果可以概括成两个主要分支：

1. 已登录，但需要 consent
   - 返回 `302 -> /oauth2/consent?...`
2. 已登录，且不需要 consent
   - 直接生成 authorization code
   - 返回 `302 -> redirect_uri?code=...&state=...`

#### 为什么这里会分成这两条路

在 `OAuth2AuthorizationCodeRequestAuthenticationProvider` 中，授权服务器会先加载当前用户已有的 consent 记录，然后调用内部判断逻辑决定本次是否还需要授权确认。

默认规则大致是：

- 如果客户端没有要求 consent
  - 不展示 consent
- 如果本次请求只包含 `openid`
  - 不展示 consent
- 如果当前用户对本次请求的 scopes 已经全部授权过
  - 不展示 consent
- 其他情况
  - 展示 consent

因此第七步不是固定一定“直接回调客户端”，而是要先经过一次授权判断。

#### 第七步双分支判断图

```mermaid
flowchart TD
    A["GET /oauth2/authorize?...&continue"] --> B{"当前用户已完成登录"}
    B --> C{"是否需要 consent?"}
    C -- 是 --> D["302 -> /oauth2/consent?..."]
    C -- 否 --> E["生成 authorization code"]
    E --> F["302 -> redirect_uri?code=...&state=..."]
```

这也解释了为什么：

- `username-login.har` 中，第七步后直接回调了客户端
- `login-consent.har` 中，第七步后先跳到了 `/oauth2/consent`

两者的区别不在于“是不是同一个授权端点”，而在于：

- 当前用户对这组 scope 是否已经具备可复用的授权记录

## 8. consent 分支：第二个 HAR 已经验证

`demo-authorizationserver` 中注册客户端时开启了：

- `requireAuthorizationConsent(true)`

因此首次授权时，授权服务器可能会要求用户先确认授权范围，再签发 code。

单看 `username-login.har` 时，没有出现：

- `GET /oauth2/consent`
- `POST /oauth2/authorize` 提交 consent

这意味着在那次抓包中，该用户对这组 scope 大概率已经存在授权记录，因此不再展示 consent 页面。

源码里 `OAuth2AuthorizationCodeRequestAuthenticationProvider` 会在以下情况跳过 consent：

- 客户端未要求 consent
- 或者当前请求的 scope 已经全部被该用户批准过
- 或者仅请求 `openid` 且没有其他 scope

而本次请求的 scope 是 `openid profile`，没有出现 consent，更可能是：

- `profile` 之前已经批准过

现在 `login-consent.har` 已经把“首次授权”的真实链路补全了，实际发生的是：

1. `POST /login` 成功后，重新进入 `/oauth2/authorize?...&continue`
2. 授权服务器没有直接签发 code，而是返回：
   - `302 /oauth2/consent?scope=openid%20profile&client_id=messaging-client&state=<consent-state>`
3. 浏览器访问：
   - `GET /oauth2/consent?...`
4. 用户在 consent 页面提交表单：
   - `POST /oauth2/authorize`
   - 表单体中包含：
     - `_csrf=...`
     - `client_id=messaging-client`
     - `state=<consent-state>`
     - `scope=profile`
5. 授权服务器处理 consent 后，才返回：
   - `302 http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc?code=...&state=<original-state>`

也就是说，两个 HAR 合起来刚好证明了两条分支：

- **已授权过**：登录后直接签发 code
- **首次授权**：登录后先进入 consent，再签发 code

### 为什么 consent 页面里只提交了 `scope=profile`

`login-consent.har` 里，请求 `/oauth2/consent` 时 URL 上带的是：

- `scope=openid profile`

但用户提交 consent 时，表单只带：

- `scope=profile`

这是源码的预期行为，不是抓包异常：

- `AuthorizationConsentController` 会把 `openid` 从需要展示/勾选的 scope 中排除
- `OAuth2AuthorizationConsentAuthenticationProvider` 在用户批准了其他 scope 时，会自动把 `openid` 加回已授权 scope

换句话说：

- `openid` 不需要用户在 consent 页面手动勾选
- 真正展示给用户确认的是 `profile`

### 为什么 consent 页面上的 `state` 和最终回调里的 `state` 不一样

`login-consent.har` 还揭示了一个很容易忽略的细节：

- 初始 `/oauth2/authorize` 请求里的 `state` 是客户端生成的原始状态值
- 跳到 `/oauth2/consent` 时，授权服务器会额外生成一个 **consent state**
- 用户提交 consent 时，提交的是这个 **consent state**
- 最终重定向回客户端时，返回的又是最初授权请求中的 **original state**

这是因为 Spring Authorization Server 在“等待用户确认 consent”的中间态，会单独保存一笔 in-flight authorization，并用新的 state 来关联这次 consent 提交；而最终回调给客户端时，仍然要恢复原始授权请求里的 state，保证客户端能正确校验本次授权响应。

## 9. 客户端接收回调 `/login/oauth2/code/...`

HAR 中：

- `GET http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc?code=...&state=...`
- 返回 `302`
- 跳到 `http://127.0.0.1:8080/?continue`

然后又有：

- `GET http://127.0.0.1:8080/?continue`
- 返回 `302`
- 跳到 `http://127.0.0.1:8080/index`

最后：

- `GET http://127.0.0.1:8080/index`
- 返回 `200`

这里说明 `demo-client` 已经完成了登录后的本地会话建立，并把浏览器导回最开始访问的页面。

其中 `/ -> /index` 来自：

- `samples/demo-client/src/main/java/sample/web/DefaultController.java`

### 9.1 为什么回调后还会 `302 -> /?continue`

这里很容易误以为客户端在收到：

- `GET /login/oauth2/code/messaging-client-oidc?code=...&state=...`

之后，应该直接返回页面。

但实际上，这一步是 Spring Security OAuth2 Client 在客户端内部处理“登录成功回调”的专用入口，它的职责不是直接渲染页面，而是先在服务端完成这几件事：

1. 校验回调里的 `state`
2. 使用 `authorization code` 向授权服务器换取 token
3. 建立客户端本地登录会话

这些工作完成之后，客户端会把浏览器重新带回“最初用户真正想访问的页面”。

#### 为什么会回到 `/`

因为这次整条链路的起点本来就是：

- 浏览器先访问 `http://127.0.0.1:8080/`

但当时客户端尚未登录，于是 Spring Security 先把这个原始请求保存下来，再把用户送去 OIDC 登录。

所以当 `/login/oauth2/code/...` 处理成功后，客户端就会恢复这个先前保存的请求，也就是：

- `/`

因此你才会在 HAR 中看到：

- `/login/oauth2/code/...`
- `302 -> /?continue`

#### 这里的 `continue` 是什么

这个 `continue` 不是 OAuth2 / OIDC 协议参数，而是 Spring Security 在恢复先前保存请求时使用的继续访问标记。

可以把它理解成：

- 这不是用户主动发起的一次全新首页请求
- 而是“登录成功后，继续完成之前被认证流程中断的 `/` 请求”

因此，它表达的是客户端侧的请求恢复语义，而不是授权服务器侧的授权参数。

#### 为什么 `/` 后面又会跳到 `/index`

这是 `demo-client` 自己的控制器逻辑决定的：

- `GET /` 会重定向到 `GET /index`

所以完整链路是：

1. 客户端收到 `/login/oauth2/code/...`
2. 客户端服务端内部完成 `code -> token`
3. 建立本地会话
4. `302 -> /?continue`
5. `GET /?continue`
6. `302 -> /index`
7. `GET /index`
8. 返回最终页面

#### 一句话总结

这个 `302 -> /?continue` 说明的不是“授权流程还没结束”，而是：

- 客户端已经成功处理了授权回调
- 现在正在通过 Spring Security 的 Saved Request 恢复机制，把用户送回最初想访问的页面

## 10. HAR 为什么看不到 `/oauth2/token`

这是理解这次抓包最容易混淆的地方。

浏览器 HAR 中只记录了浏览器直接发起的 HTTP 请求，而授权码换 token 的动作通常发生在客户端服务端内部。

也就是说，这一步实际上发生了，但不是浏览器调用的：

1. 浏览器访问客户端回调地址 `/login/oauth2/code/messaging-client-oidc`
2. `demo-client` 的 Spring Security 在服务端校验 `state`
3. `demo-client` 使用 `client_id/client_secret` 向授权服务器 token endpoint 发起后端请求
4. 获取 `access_token`、`id_token`、可能还有 `refresh_token`
5. 建立客户端本地登录会话
6. 再把浏览器重定向回首页

因此：

- **HAR 里看不到 `/oauth2/token`**
- **不代表没有 token 交换**
- **只是说明 token 交换发生在服务端，不在浏览器里**

### 10.1 客户端收到 `code` 之后，内部到底做了什么

客户端收到：

- `GET /login/oauth2/code/messaging-client-oidc?code=...&state=...`

之后，Spring Security OAuth2 Client 并不会立刻把页面返回给浏览器，而是先在服务端内部完成一整段 OIDC 回调处理。

可以把这段内部流程理解成：

1. 从当前回调请求中读取：
   - `code`
   - `state`
2. 从 session 中取回最初发起登录时保存的 `OAuth2AuthorizationRequest`
3. 校验回调里的 `state` 是否与最初请求中保存的 `state` 一致
4. 根据 `registrationId=messaging-client-oidc` 找到对应的 `ClientRegistration`
5. 向授权服务器发起：
   - `POST /oauth2/token`
   - `grant_type=authorization_code`
   - `code=...`
   - `redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc`
6. 客户端收到 token 响应
7. 由于这是 OIDC 登录，请求中包含 `openid`，因此客户端会继续处理 `id_token`
8. 校验 `id_token`
   - 校验签名
   - 校验 `iss`
   - 校验 `aud`
   - 校验 `exp` / `iat`
   - 校验 `nonce`
9. 构造当前登录用户（`OidcUser`）和登录态
10. 保存 OAuth2 客户端信息与 token
11. 再把浏览器重定向回最初访问的页面

### 10.2 token 响应里通常会有哪些值

对这条 `messaging-client-oidc` 登录链路来说，客户端服务端在 `/oauth2/token` 响应里通常会拿到：

- `access_token`
- `id_token`
- `refresh_token`

其中三者职责不同：

- `access_token`
  - 给客户端后续调用资源服务时使用
- `id_token`
  - 给客户端确认“当前登录用户是谁，以及这次 OIDC 登录是否合法”
- `refresh_token`
  - 用于 access token 过期后续期

对这个 sample 来说，`id_token` 的核心作用不在于后续访问资源，而在于：

- 完成 OIDC 登录身份校验
- 构造 `OidcUser`
- 建立客户端本地登录会话

### 10.3 `nonce` 在这里的具体作用

第 2 步里客户端生成授权请求时，会自动附带一个：

- `nonce`

这个 `nonce` 不是给授权服务器做普通参数透传用的，而是专门服务于 OIDC 的安全校验链路。

它的作用是：

1. 客户端发起认证请求时，先生成一个随机 `nonce`
2. 授权服务器在后续签发 `id_token` 时，把这个 `nonce` 放进 `id_token` 的 claims 中
3. 客户端收到 `id_token` 后，再校验：
   - `id_token` 里的 `nonce`
   - 是否和最初请求中自己生成并保存的 `nonce` 一致

这样做的意义是：

- 确保这份 `id_token` 真的是针对当前这次登录请求签发的
- 防止登录响应被重放或串用

因此可以把：

- `state`
  - 理解成 OAuth2 授权响应关联与 CSRF 防护
- `nonce`
  - 理解成 OIDC `id_token` 关联与防重放校验

### 10.4 这一步处理成功后，token 被保存到了哪里

这一步成功后，客户端不会把 token 明文交给浏览器，而是把结果保存在服务端登录态相关结构中。

可以把结果分成两类理解：

- 用户身份信息
  - 进入当前登录用户对象，例如 `OidcUser`
  - 并进入服务端 `SecurityContext`
- OAuth2 token 信息
  - 进入 `OAuth2AuthorizedClient`
  - 由 `OAuth2AuthorizedClientRepository` / 会话相关机制管理

所以浏览器后续主要只持有：

- `JSESSIONID`

而不是直接持有：

- `access_token`
- `id_token`
- `refresh_token`

## 源码映射

## `demo-client`

### 发起登录入口

- 文件：`samples/demo-client/src/main/java/sample/config/SecurityConfig.java`
- 作用：未认证访问业务页面时，跳到 `/oauth2/authorization/messaging-client-oidc`

### OIDC 客户端注册

- 文件：`samples/demo-client/src/main/resources/application.yml`
- 作用：定义 `messaging-client-oidc` 的 `client-id`、`scope`、`redirect-uri`、`issuer-uri`

### 登录后页面跳转

- 文件：`samples/demo-client/src/main/java/sample/web/DefaultController.java`
- 作用：把 `/` 重定向到 `/index`

## `demo-authorizationserver`

### 授权端点安全链

- 文件：`samples/demo-authorizationserver/src/main/java/sample/config/AuthorizationServerConfig.java`
- 作用：
  - 注册授权服务器端点
  - 为 `/oauth2/authorize` 配置登录入口 `/login`
  - 注册客户端 `messaging-client`

### 本地用户名密码登录

- 文件：`samples/demo-authorizationserver/src/main/java/sample/config/DefaultSecurityConfig.java`
- 作用：
  - 开启 `formLogin`
  - 指定登录页 `/login`
  - 提供内存用户 `user1/password`

### 登录页页面

- 文件：`samples/demo-authorizationserver/src/main/java/sample/web/LoginController.java`
- 文件：`samples/demo-authorizationserver/src/main/resources/templates/login.html`

### consent 页面

- 文件：`samples/demo-authorizationserver/src/main/java/sample/web/AuthorizationConsentController.java`
- 文件：`samples/demo-authorizationserver/src/main/resources/templates/consent.html`

本次 HAR 中虽然没走到 consent，但如果是首次授权，通常会经过这里。

## Spring Authorization Server 核心源码映射

### `OAuth2AuthorizationEndpointFilter`

文件：

- `oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/OAuth2AuthorizationEndpointFilter.java`

作用：

- 拦截 `/oauth2/authorize`
- 把请求转换成认证对象
- 调用 `AuthenticationManager`
- 如果需要 consent，则跳到 consent 页
- 如果授权通过，则重定向回客户端 `redirect_uri`

### `OAuth2AuthorizationCodeRequestAuthenticationProvider`

文件：

- `oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationCodeRequestAuthenticationProvider.java`

作用：

- 校验授权请求参数
- 校验客户端与 `redirect_uri`
- 判断用户是否已登录
- 判断是否需要 consent
- 生成 authorization code
- 保存授权记录

## 时序图

这里把原先的一张大图拆成两张：

- 第一张只看浏览器可见的外部协议跳转
- 第二张只看客户端回调后的内部处理链

这样更容易把：

- 浏览器跳转
- 授权服务器分支
- 客户端内部 `code -> token -> id_token` 校验

分开理解。

### 图 1：浏览器可见的外部跳转

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant C as demo-client:8080
    participant A as authorization-server:9000

    B->>C: GET /
    C-->>B: 302 /oauth2/authorization/messaging-client-oidc

    B->>C: GET /oauth2/authorization/messaging-client-oidc
    C-->>B: 302 /oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&state=clientState&nonce=clientNonce

    B->>A: GET /oauth2/authorize?...&state=clientState&nonce=clientNonce
    alt 授权服务器当前没有登录会话
        A-->>B: 302 /login
        B->>A: GET /login
        A-->>B: 200 login.html
        B->>A: POST /login (username=user1, password=password, _csrf=...)
        A-->>B: 302 /oauth2/authorize?...&continue
        B->>A: GET /oauth2/authorize?...&continue
        A-->>A: 继续处理刚才被登录中断的授权请求
    else 授权服务器当前已有登录会话
        A-->>A: 跳过 /login，直接继续处理授权请求
    end

    A-->>A: 校验 client_id / redirect_uri / scope
    alt 首次授权，需要 consent
        A-->>B: 302 /oauth2/consent?scope=openid%20profile&client_id=messaging-client&state=consentState
        B->>A: GET /oauth2/consent?...&state=consentState
        A-->>B: 200 consent.html
        B->>A: POST /oauth2/authorize (client_id, state=consentState, scope=profile, _csrf)
        A-->>A: 自动补回 openid scope
        A-->>A: 生成 authorization code
        A-->>B: 302 /login/oauth2/code/messaging-client-oidc?code=...&state=clientState
    else 当前 scopes 已授权过，不再展示 consent
        A-->>A: 直接生成 authorization code
        A-->>B: 302 /login/oauth2/code/messaging-client-oidc?code=...&state=clientState
    end

    B->>C: GET /login/oauth2/code/messaging-client-oidc?code=...&state=clientState
    C-->>B: 302 /?continue

    B->>C: GET /?continue
    C-->>B: 302 /index

    B->>C: GET /index
    C-->>B: 200 index.html
```

这张图里有两个对理解 OIDC 很关键的参数：

- `state`
  - 客户端生成
  - 用于把授权请求和授权响应关联起来，并防止 CSRF
- `nonce`
  - 客户端生成
  - 授权服务器后续会把它放进 `id_token`
  - 客户端在回调后再据此校验 `id_token` 是否属于本次登录

需要注意的是：

- 授权服务器先判断“当前用户是否已经登录”
  - 未登录：先跳 `/login`
  - 已登录：直接继续授权判断
- 对已经登录的情况来说，后面仍然还会继续判断是否需要 consent
- `POST /oauth2/token`
- token 响应里的 `access_token` / `id_token` / `refresh_token`

都不在浏览器 HAR 中出现，因为这部分发生在客户端服务端内部。

### 图 2：客户端回调后的内部处理链

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant C as demo-client:8080
    participant S as session / saved auth request
    participant A as authorization-server:9000

    B->>C: GET /login/oauth2/code/messaging-client-oidc?code=...&state=clientState
    C->>S: 取回最初保存的 OAuth2AuthorizationRequest
    S-->>C: clientState + clientNonce + registrationId
    C-->>C: 校验回调 state 是否等于最初保存的 clientState
    C->>A: POST /oauth2/token (服务端内部，HAR 不可见)
    A-->>A: 生成 access_token
    A-->>A: 生成 id_token
    A-->>A: 生成 refresh_token（若当前策略发放）
    A-->>A: 将 clientNonce 放入 id_token claims
    A-->>C: access_token + id_token (+ refresh_token)
    C-->>C: 校验 id_token 签名
    C-->>C: 校验 iss / aud / exp / iat
    C-->>C: 校验 id_token.nonce 是否等于最初保存的 clientNonce
    C-->>C: 构造 OidcUser
    C-->>C: 保存 OAuth2AuthorizedClient(access_token / id_token / refresh_token)
    C-->>C: 建立本地登录会话(SecurityContext)
    C-->>B: 302 /?continue
```

这张图里最关键的几个点是：

- 客户端不是只校验 `code`
  - 还会先校验回调里的 `state`
- `id_token` 不是随便接收后直接使用
  - 客户端会做 OIDC 校验
  - 尤其会校验 `id_token.nonce == clientNonce`
- token 响应通常包含：
  - `access_token`
  - `id_token`
  - `refresh_token`（是否发放取决于服务端策略）

其中：

- `access_token`
  - 后续主要用于调用资源服务
- `id_token`
  - 主要用于确认当前登录用户身份，并构造 `OidcUser`
- `refresh_token`
  - 主要用于后续续期 access token

## 关键判断总结

这次 HAR 可以得出以下判断：

1. 这是 **OIDC Login / Authorization Code** 流程，不是 password grant。
2. 浏览器提交的 `user1/password` 只是授权服务器本地登录表单。
3. `demo-client` 是 OAuth2 Client，`demo-authorizationserver` 是 Authorization Server。
4. 授权码由授权服务器在 `/oauth2/authorize` 阶段生成。
5. `/oauth2/token` 调用发生在 `demo-client` 服务端，因此 HAR 看不到。
6. 两个 HAR 一起说明：首次授权会出现 `/oauth2/consent`，后续同 scope 再登录时可以直接回调客户端。
7. consent 页提交时只看到 `scope=profile` 是正常行为，因为 `openid` 在该流程里会被自动处理而不是要求用户勾选。
8. consent 页使用的是授权服务器内部生成的 `consent state`，最终回调给客户端时会恢复原始授权请求的 `state`。

## 一句话总结

这两份 HAR 合起来展示的是：

`浏览器访问 demo-client -> demo-client 发起 OIDC 授权请求 -> 授权服务器要求用户表单登录 -> 首次授权时先进入 consent，后续已授权时可直接签发 code -> demo-client 在服务端换 token -> 浏览器回到 demo-client 首页`

而不是：

`浏览器直接用用户名密码向 token endpoint 申请 access token`


## 三个Token 示例

### id_token

```text
id_token -> eyJraWQiOiJlNDliN2ZmOC04YTk2LTRhOGQtODJlOS00YjFmNzlhMDI4ZWUiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMSIsImF1ZCI6Im1lc3NhZ2luZy1jbGllbnQiLCJhenAiOiJtZXNzYWdpbmctY2xpZW50IiwiYXV0aF90aW1lIjoxNzc2NjE4MTUwLCJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjkwMDAiLCJleHAiOjE3NzY2MTk5NTQsImlhdCI6MTc3NjYxODE1NCwibm9uY2UiOiIyRHVYUW9IT2tGVUpIbnpmWWd4QTFMRjRvN0I3bWwzVWFiY1o1SERPVkpnIiwianRpIjoiZDRmOTRjMjUtZmYxYy00YzM3LWI4N2UtYmI2NzJmMGJkZjYwIiwic2lkIjoiNTVKdHVYOE0tNFRpUkZOblFHVUEtUDB5YU9nbHFwRWM4Q3llWUFxbEVMSSJ9.QQ-z_UdyR5pBAvuss_LCpYsU22ZvKXaUd20QtpKXGQggtdPP2ihjw6Uwzb1v7fFc4tMjo5lDNnIv-TVhxNe8QA_OsOCiKCKVtzc9EQgLlNJQweN1Va9vyeB5wrLV2DW1ZQCLFDeA8c9BGp3j-pn68ZE1N3ppEmMB1T9_s7m5_DPS8snLHhGE8XcBrxpK3-9gM5lTCbGzSlGVdb4y8rfTPBKTakqRZlKolumae8ZcIA8nd6I1ovGP1zOJeRaIHqVUt4es0IHYYJqC45NRcZwP934JoOE4hwBkhkG07HjYdM2V3NfoKX9kmdb-3UqDIQoG6Ukmwn0EpQmE3Xl6wwFCOg
```

```json
{
  "sub": "user1",
  "aud": "messaging-client",
  "azp": "messaging-client",
  "auth_time": 1776618150,
  "iss": "http://localhost:9000",
  "exp": 1776619954,
  "iat": 1776618154,
  "nonce": "2DuXQoHOkFUJHnzfYgxA1LF4o7B7ml3UabcZ5HDOVJg",
  "jti": "d4f94c25-ff1c-4c37-b87e-bb672f0bdf60",
  "sid": "55JtuX8M-4TiRFNnQGUA-P0yaOglqpEc8CyeYAqlELI"
}
```

### access_token

```text
eyJraWQiOiJlNDliN2ZmOC04YTk2LTRhOGQtODJlOS00YjFmNzlhMDI4ZWUiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMSIsImF1ZCI6Im1lc3NhZ2luZy1jbGllbnQiLCJuYmYiOjE3NzY2MTgxNTQsInNjb3BlIjpbIm9wZW5pZCIsInByb2ZpbGUiXSwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdDo5MDAwIiwiZXhwIjoxNzc2NjE4NDU0LCJpYXQiOjE3NzY2MTgxNTQsImp0aSI6IjUzN2ViMzE0LTE5MTItNGI0NC04NzcxLTYwYjBkMDYwMWM0OCJ9.H0VqRNgTuA9zKo4g5ax2JASiD-4Rvj2tbEz4QhxD43A7YdkBGEqPG2_yi8qgZufJQw0UCxnfaC10clmlhOoTlQdlucpZ0OmfPvqQh0vS5i0n_yyuIWb11u3ZlG43PmuMe5t6Fe3EpIphyl55rDIoruYsnC0FBhXfp4TpioDIskp-R8l6PZ1HhOjHjTNPLRp0I8MxzrXpalWV_NEvs7We7Stbd0QYzEOZD5nX25H_FWj6RdXnKss_pI2Lg5cYVbI45_vt-eQ-H9u66J-pNq36oXYzK9JUfs9-RzNe9RxL2m0UeIMMs33jP-wKafvqQNocTlGQmufN-bDnqFZsBtYzjw
```

```json
{
  "header" : {
    "kid" : "e49b7ff8-8a96-4a8d-82e9-4b1f79a028ee",
    "alg" : "RS256"
  },
  "payload" : {
    "sub" : "user1",
    "aud" : "messaging-client",
    "nbf" : 1776618154,
    "scope" : [ "openid", "profile" ],
    "iss" : "http://localhost:9000",
    "exp" : 1776618454,
    "iat" : 1776618154,
    "jti" : "537eb314-1912-4b44-8771-60b0d0601c48"
  },
  "signature" : "H0VqRNgTuA9zKo4g5ax2JASiD-4Rvj2tbEz4QhxD43A7YdkBGEqPG2_yi8qgZufJQw0UCxnfaC10clmlhOoTlQdlucpZ0OmfPvqQh0vS5i0n_yyuIWb11u3ZlG43PmuMe5t6Fe3EpIphyl55rDIoruYsnC0FBhXfp4TpioDIskp-R8l6PZ1HhOjHjTNPLRp0I8MxzrXpalWV_NEvs7We7Stbd0QYzEOZD5nX25H_FWj6RdXnKss_pI2Lg5cYVbI45_vt-eQ-H9u66J-pNq36oXYzK9JUfs9-RzNe9RxL2m0UeIMMs33jP-wKafvqQNocTlGQmufN-bDnqFZsBtYzjw"
}
```

### refresh_token

```text
sog-3YNUE9UTf_7FJxv_fFcEjqKslr-7NuYMKy0b8fmlzMvbY-z3EdEQvjv0SOYU0ckmCgHWxlqFR1jFhltESgYuXdU-V2pRsm4rM4p6AgDOzVeMCcJsTzYvJm-DoUXT
```


http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid profile&state=1_vng4LjGenQvAood3QzwwHkJ0qkuIiF8zmYDYm0faQ=&redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc&nonce=njMuC02y22tdYtDSV7bcgA6wgYqgHQAZjQ3ow2UiZOQ
http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid profile&state=1_vng4LjGenQvAood3QzwwHkJ0qkuIiF8zmYDYm0faQ=&redirect_uri=http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc&nonce=njMuC02y22tdYtDSV7bcgA6wgYqgHQAZjQ3ow2UiZOQ&continue
