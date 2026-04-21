# Samples - 授权流程源码分析

## OIDC 登录授权

### 访问客户端首页

> `GET http://127.0.0.1:8080/` 302 ==>
>
> `GET http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc`

```markdown
http://127.0.0.1:8080/
  │  Spring Security AuthorizationFilter 先于 Controller 执行
  │  "/" 匹配 anyRequest().authenticated()，用户未认证
  │  ExceptionTranslationFilter → 触发 OAuth2 登录
  │  loginPage = "/oauth2/authorization/messaging-client-oidc"
  ▼  302
http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc
```

```java
.authorizeHttpRequests(authorize ->
    authorize
        .requestMatchers("/jwks", "/logged-out").permitAll()
        .anyRequest().authenticated()
)
.oauth2Login(oauth2Login ->
    oauth2Login.loginPage("/oauth2/authorization/messaging-client-oidc"))
```

- `/` 不在 permitAll() 列表中，匹配 anyRequest().authenticated()
- 用户未认证 → Spring Security 需要触发登录
- 因为配置了 oauth2Login 且显式指定了登录path，直接302

### 发起授权

> `GET http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc` 302 ==>
>
> `GET http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&stage=....`

```markdown
http://127.0.0.1:8080/
  │  Spring Security AuthorizationFilter 先于 Controller 执行
  │  "/" 匹配 anyRequest().authenticated()，用户未认证
  │  ExceptionTranslationFilter → 触发 OAuth2 登录
  │  loginPage = "/oauth2/authorization/messaging-client-oidc"
  ▼  302
http://127.0.0.1:8080/oauth2/authorization/messaging-client-oidc
  │  OAuth2AuthorizationRequestRedirectFilter 处理
  ▼  302
http://localhost:9000/oauth2/authorize?response_type=code&...
```

- `OAuth2AuthorizationRequestRedirectFilter`

```java
// OAuth2AuthorizationRequestRedirectFilter.java
public static final String DEFAULT_AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization";

protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
    try {
        // 1. 尝试从请求路径解析 authorizationRequest
        OAuth2AuthorizationRequest authorizationRequest = this.authorizationRequestResolver.resolve(request);
        if (authorizationRequest != null) {
            this.sendRedirectForAuthorization(request, response, authorizationRequest);
            return;  // 不再继续 filter chain
        }
        // ...
    }
}
```

- `DefaultOAuth2AuthorizationRequestResolver`

```java
public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    // 1. 从 URL 路径提取 registrationId
    //    路径: /oauth2/authorization/messaging-client-oidc
    //    → registrationId = "messaging-client-oidc"
    String registrationId = resolveRegistrationId(request);

    // 2. 从 ClientRegistrationRepository 查找 ClientRegistration
    ClientRegistration clientRegistration = this.clientRegistrationRepository.findByRegistrationId(registrationId);

    // 3. 解析构建 OAuth2AuthorizationRequest
    return resolve(request, registrationId);
}
```

```markdown
请求: GET /oauth2/authorization/messaging-client-oidc
  │
  ▼
OAuth2AuthorizationRequestRedirectFilter.doFilterInternal()
  │
  ├─ authorizationRequestResolver.resolve(request)
  │     │
  │     ├─ 从 URL 提取 registrationId = "messaging-client-oidc"
  │     │
  │     ├─ ClientRegistrationRepository.findByRegistrationId("messaging-client-oidc")
  │     │     └─ 从 application.yml 配置构建的 InMemoryClientRegistrationRepository 中查找
  │     │        → provider: spring (issuer-uri: http://localhost:9000)
  │     │        → client-id: messaging-client
  │     │        → scope: openid, profile
  │     │        → redirect-uri: http://127.0.0.1:8080/login/oauth2/code/{registrationId}
  │     │
  │     ├─ 获取 authorizationUri:
  │     │     启动时已通过 OIDC Discovery 自动获取:
  │     │     GET http://localhost:9000/.well-known/openid-configuration
  │     │     → authorization_endpoint = "http://localhost:9000/oauth2/authorize"
  │     │
  │     ├─ 构造 OAuth2AuthorizationRequest:
  │     │     authorizationUri = http://localhost:9000/oauth2/authorize
  │     │     clientId = messaging-client
  │     │     redirectUri = http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc
  │     │     scopes = [openid, profile]
  │     │     state = 随机生成
  │     │     nonce = SHA-256(随机nonce) 的 Base64URL (OIDC特有)
  │     │
  │     └─ 返回 OAuth2AuthorizationRequest
  │
  ├─ authorizationRequestRepository.saveAuthorizationRequest(...)
  │     └─ 保存到 HttpSession，用于回调时验证 state
  │
  └─ sendRedirectForAuthorization()
        └─ 302 → http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&state=...&redirect_uri=...&nonce=...

```

### 发起授权到登录页面

> `GET http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&stage=....` 302 ==>
>
> `GET http://localhost:9000/login`

```markdown
GET http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&...
  │
  │  匹配 authorizationServerSecurityFilterChain（@Order(HIGHEST_PRECEDENCE)）
  │  因为 .securityMatcher() 匹配 OAuth2 端点
  │
  │  .anyRequest().authenticated() → 用户未认证
  │  触发 AuthenticationEntryPoint
  │
  │  LoginUrlAuthenticationEntryPoint("/login")
  │  + MediaTypeRequestMatcher(TEXT_HTML) → 浏览器请求匹配
  │
  ▼  302
GET http://localhost:9000/login
  │
  │  不匹配 OAuth2 端点 → 落入 defaultSecurityFilterChain
  │
  │  .requestMatchers("/login").permitAll() → 放行
  │  .formLogin(formLogin -> formLogin.loginPage("/login"))
  │
  ▼
LoginController.login() → 渲染 login.html（用户名/密码表单）

```

- `AuthorizationServerConfig#authorizationServerSecurityFilterChain`

```java
// Redirect to the /login page when not authenticated from the authorization endpoint
// NOTE: DefaultSecurityConfig is configured with formLogin.loginPage("/login")
.exceptionHandling((exceptions) -> exceptions
    .defaultAuthenticationEntryPointFor(
        new LoginUrlAuthenticationEntryPoint("/login"),
        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
    )
)

```

- OAuth2AuthorizationEndpointFilter
- UsernamePasswordAuthenticationFilter
- OAuth2AuthorizationRequestRedirectFilter
- OAuth2LoginAuthenticationFilter

### 完成登录继续授权

> `POST http://localhost:9000/login` 302 ==>
> `GET http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&stage=....&continue`

```markdown
1. GET /oauth2/authorize?response_type=code&client_id=messaging-client&...
   ↓ 用户未认证
2. ExceptionTranslationFilter 捕获 AuthenticationException
   ↓
3. LoginUrlAuthenticationEntryPoint("/login").commence()
   ├── 将 /oauth2/authorize?... 保存到 HttpSessionRequestCache（存入 Session）
   └── 302 重定向 → /login
   ↓
4. /login 匹配 DefaultSecurityConfig 的 SecurityFilterChain
   → permitAll()，渲染 login.html
   ↓
5. 用户提交表单 POST /login
   ↓ UsernamePasswordAuthenticationFilter 认证成功
6. SavedRequestAwareAuthenticationSuccessHandler
   ├── 从 RequestCache 取出 SavedRequest（即 /oauth2/authorize?...）
   └── 302 重定向 → /oauth2/authorize?response_type=code&client_id=xxx&...
   ↓
7. 用户已认证，授权服务器正常处理授权请求

```

- LoginUrlAuthenticationEntryPoint
- SavedRequestAwareAuthenticationSuccessHandler
- OAuth2AuthorizationCodeRequestAuthenticationConverter

登录成功后，UsernamePasswordAuthenticationFilter 将认证后的 Authentication 存入 SecurityContext，而 SecurityContext 被保存到 HttpSession 中。
第二次请求时，浏览器自动带上 Session Cookie，Spring Security 从 Session 恢复 SecurityContext，所以"发现已登录"。

### 发起授权页面

> `GET http://localhost:9000/oauth2/authorize?response_type=code&client_id=messaging-client&scope=openid%20profile&stage=....&continue` 302 ==>
> `GET http://localhost:9000/oauth2/consent?scope=openid profile&client_id=messaging-client&state=Uj0TUWdZ_mbildEnkxZh-I1IbDoP2SW_G4AthuxA0l0=`

- OAuth2AuthorizationEndpointFilter
- OAuth2AuthorizationCodeRequestAuthenticationConverter
- OAuth2AuthorizationCodeRequestAuthenticationProvider

```markdown
Provider 判断需要同意
↓ 返回 OAuth2AuthorizationConsentAuthenticationToken
Filter.sendAuthorizationConsent()
↓ 302 → /oauth2/consent?scope=message.read+message.write&client_id=messaging-client&state=abc

AuthorizationConsentController.consent()
├─ 查询 registeredClient
├─ 查询 authorizationConsent（之前已同意的 scope）
├─ 区分：待同意 scopes vs 已同意 scopes
├─ 为 scope 添加描述（ScopeWithDescription）
└─ 返回 "consent" → Thymeleaf 渲染 consent.html

浏览器呈现：
┌────────────────────────────────────────┐
│        App permissions                 │
│                                        │
│  messaging-client wants to access      │
│  your account user1                    │
│                                        │
│  ☐ message.read                        │
│    This application will be able to    │
│    read your message.                  │
│                                        │
│  ☐ message.write                       │
│    This application will be able to    │
│    add new messages...                 │
│                                        │
│  You have already granted:             │
│  ☑ openid (disabled)                   │
│                                        │
│  [Submit Consent]  [Cancel]            │
└────────────────────────────────────────┘
```

```java
Authentication principal = (Authentication) authorizationCodeRequestAuthentication.getPrincipal();
if (!isPrincipalAuthenticated(principal)) {
    // 未认证 → 返回 isAuthenticated()=false 的 token → Filter 放行给 ExceptionTranslationFilter
    return authorizationCodeRequestAuthentication;
}
```

```markdown
已登录用户访问 /oauth2/authorize?...
    ↓
┌─────────────────────────────────┬──────────────────────────────────────────┐
│ 不需要同意                       │ 需要同意                                  │
│ (已同意过 / consent=false)       │ (首次授权 / consent=true)                 │
├─────────────────────────────────┼──────────────────────────────────────────┤
│ 生成 authorization_code         │ 生成 state，保存 Authorization            │
│ 保存 Authorization              │ 返回 OAuth2AuthorizationConsentAuthToken │
│ 302 → client?code=xxx&state=yyy │ 302 → /oauth2/consent?scope=...&client_id=...&state=... │
└─────────────────────────────────┴──────────────────────────────────────────┘

```

### 同意授权

> `POST http://localhost:9000/oauth2/authorize` 302 ==>
>
> `GET http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc?code=AlbFiG_sAQ0-YnnXnZxO9oNFqNsd2LpQOxK0OU956D2fzFwp-PtdoGaLwzQyturP1_lycvIzX8z6p-cPJe8f7ol7aOtQisdT3E_gaWzmJ7sbT64h9TaySlRgmxpuiMQJ&state=rU_s1EmFN7-kPD3K4pd-L5ASOh5rI_wpRBaqVgcy7Gs%3D`

```markdown
HTTP Request: GET /oauth2/authorize?response_type=code&client_id=xxx&scope=openid
    │
    ▼
OAuth2AuthorizationEndpointFilter.doFilterInternal()
    │
    │ ① this.authenticationConverter.convert(request)
    │    │
    │    ▼
    │  DelegatingAuthenticationConverter
    │    │  遍历内部 Converter 列表：
    │    │  ├─ OAuth2AuthorizationCodeRequestAuthenticationConverter  ← 匹配！
    │    │  │   从 SecurityContextHolder 取 principal
    │    │  │   解析 request 参数 (client_id, scope, state, redirect_uri...)
    │    │  │   返回 OAuth2AuthorizationCodeRequestAuthenticationToken (未认证)
    │    │  └─ OAuth2AuthorizationConsentAuthenticationConverter  ← 不匹配，跳过
    │    │
    │    ▼ 返回 OAuth2AuthorizationCodeRequestAuthenticationToken
    │
    │ ② this.authenticationManager.authenticate(authentication)
    │    │
    │    ▼
    │  ProviderManager (AuthenticationManager 实现)
    │    │  遍历注册的 Provider 列表：
    │    │  ├─ OAuth2AuthorizationCodeRequestAuthenticationProvider
    │    │  │   supports(OAuth2AuthorizationCodeRequestAuthenticationToken) → true ← 匹配！
    │    │  │   authenticate() 内部：
    │    │  │     ├─ 验证 client_id、redirect_uri、scope、grant_type
    │    │  │     ├─ 检查 isPrincipalAuthenticated()
    │    │  │     │   ├─ false → 返回 isAuthenticated=false 的 token
    │    │  │     │   └─ true  → 继续下面
    │    │  │     ├─ 判断是否需要 consent
    │    │  │     │   ├─ 需要 → 返回 OAuth2AuthorizationConsentAuthenticationToken
    │    │  │     │   └─ 不需要 → 生成 authorization_code，保存，返回带 code 的 token
    │    │  │     └─ 返回结果
    │    │  └─ OAuth2AuthorizationConsentAuthenticationProvider
    │    │      supports(OAuth2ConsentAuthenticationToken) → false，跳过
    │    │
    │    ▼ 返回 Authentication 结果
    │
    │ ③ 根据 authenticationResult 类型决定下一步：
    │    ├─ isAuthenticated() == false → filterChain.doFilter() → 触发 AuthenticationEntryPoint → 重定向 /login
    │    ├─ instanceof ConsentAuthToken → sendAuthorizationConsent() → 302 到 /oauth2/consent
    │    └─ 已认证 + 有 authorization_code → sendAuthorizationResponse() → 302 到客户端 callback
    │
    ▼
HTTP Response: 302 Location: http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc?code=xxx&state=yyy
```

### 回到登录首页

> `GET http://127.0.0.1:8080/login/oauth2/code/messaging-client-oidc?code=AlbFiG_sAQ0-YnnXnZxO9oNFqNsd2LpQOxK0OU956D2fzFwp-PtdoGaLwzQyturP1_lycvIzX8z6p-cPJe8f7ol7aOtQisdT3E_gaWzmJ7sbT64h9TaySlRgmxpuiMQJ&state=rU_s1EmFN7-kPD3K4pd-L5ASOh5rI_wpRBaqVgcy7Gs%3D` 302 ==>
>
> `GET http://127.0.0.1:8080/?continue` 302 ==>
>
> `GET http://127.0.0.1:8080/index`

```markdown
拿到 Token Response
  │
  ├─ ① 校验 ID Token 是否存在
  ├─ ② 解析验证 ID Token（JWT 签名验证 + 标准声明校验）
  ├─ ③ 验证 nonce（防重放攻击）
  ├─ ④ 用 Access Token 调用 UserInfo Endpoint 获取用户信息，合并 ID Token claims 生成 OidcUser
  └─ ⑤ 权限映射 + 构建最终 OAuth2LoginAuthenticationToken 返回
```

```markdown
attemptAuthentication() 认证成功
  │
  ▼
AbstractAuthenticationProcessingFilter.successfulAuthentication()
  ├─ 存 SecurityContext → SecurityContextHolder + Session
  ├─ RememberMe 处理
  ├─ 发布认证成功事件
  └─ successHandler.onAuthenticationSuccess()
        │
        ▼
SavedRequestAwareAuthenticationSuccessHandler
  ├─ 从 RequestCache 取 SavedRequest（之前 ExceptionTranslationFilter 保存的）
  ├─ 得到目标 URL: "http://127.0.0.1:8080/?continue"
  └─ redirectStrategy.sendRedirect() → response.sendRedirect() → 302

```

```markdown
用户首次访问 http://127.0.0.1:8080/
  │
  ├─ ExceptionTranslationFilter: 未认证，saveRequest() 保存请求
  │   → 保存 URL 被追加 ?continue → "http://127.0.0.1:8080/?continue"
  │
  ├─ 302 → 授权服务器登录
  │
  ├─ 登录成功回调 OAuth2LoginAuthenticationFilter
  │
  ├─ 认证成功，SavedRequestAwareAuthenticationSuccessHandler
  │   → 取出 SavedRequest 的 redirectUrl = "http://127.0.0.1:8080/?continue"
  │
  ▼ ① 第1次 302 → http://127.0.0.1:8080/?continue

浏览器请求 http://127.0.0.1:8080/?continue
  │
  ├─ RequestCacheAwareFilter 拦截
  │   → 检测到 ?continue 参数
  │   → 从 Session 取出 SavedRequest
  │   → 匹配成功，返回 SavedRequestAwareWrapper（恢复原始请求）
  │   → 移除 Session 中的缓存
  │
  ├─ 后续 Filter 链处理的是恢复后的原始请求 (/)
  │   → 应用自身逻辑（如 welcome-page → /index）
  │
  ▼ ② 第2次 302 → http://127.0.0.1:8080/index

```
