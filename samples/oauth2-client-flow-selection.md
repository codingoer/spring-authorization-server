# 客户端授权流程选择

## 核心结论

使用新授权服务后的最终目标，是业务系统不再依赖 `ctoken-service` 的 opaque token。

新授权服务基于 Spring Authorization Server，负责标准 OAuth2/OIDC 授权流程和 JWT token 颁发。现有 `ctoken-service` 中与密码、验证码、微信授权码等相关的登录校验逻辑，可以迁移或复用为授权服务器的用户认证适配逻辑。最终目标下，`ctoken-service` 不再继续作为 token 颁发服务。

不过从迁移节奏看，可以增加一个过渡阶段：公司 App / 官网仍调用现有 `ctoken-service` 登录接口，但 `ctoken-service` 登录成功后先签发 ctoken JWT。后续再把公司 App / 官网切到授权服务器的 `authorization_code + PKCE`，由 Spring Authorization Server 颁发 SAS JWT。

流程上应理解为：

```text
客户端
  -> 跳转到新授权服务器 /oauth2/authorize
  -> 授权服务器展示 /login
  -> 用户输入账号密码
  -> 授权服务器调用现有用户中心/CUR 校验密码
  -> 登录成功后继续 authorization_code 流程
  -> /oauth2/token 由 Spring Authorization Server 产生 JWT access_token / refresh_token / id_token
```

关键点：

- `ctoken-service` 最终可以下线，或只在过渡期保留。
- 最终目标下，JWT token 应由新的 Spring Authorization Server 颁发。
- 过渡阶段可以让 `ctoken-service` 先签发 ctoken JWT，但这不是 OAuth2/OIDC 标准化改造。
- 现有 `ctoken-service` 里的密码、验证码、微信授权码等校验逻辑，可以作为用户认证适配逻辑迁移到授权服务器。
- 不建议把现有模式改造成 OAuth2 `password grant`。
- Web、App、小程序等有用户参与的客户端，都应走 `authorization_code + PKCE`。

## 流程选择原则

客户端授权流程不应按“外部 / 内部”区分，而应按两个问题区分：

1. 是否有用户参与登录。
2. 客户端是否能安全保管密钥。

统一原则：

```text
有用户登录：authorization_code + PKCE
无用户参与的系统间调用：client_credentials
不要使用 password grant
```

## 客户端类型与授权流程

| 客户端类型 | 推荐流程 | 说明 |
|------------|----------|------|
| 外部 Web 后端应用 | `authorization_code + PKCE` | 服务端可再使用 `client_secret`，PKCE 仍建议开启 |
| 公司内部 Web 后端应用 | `authorization_code + PKCE` | 和外部 Web 一样，只是 client、scope、审批策略不同 |
| 外部 App | `authorization_code + PKCE` | App 不能安全保存 `client_secret`，PKCE 必须使用 |
| 公司内部 App | `authorization_code + PKCE` | 即使是内部 App，也不能信任客户端本地密钥 |
| 小程序 | `authorization_code + PKCE` | 类似公共客户端，不应使用 password grant |
| SPA | 优先 BFF + `authorization_code + PKCE` | 不建议把 token 长期暴露在浏览器 |
| 第三方服务端系统 | `client_credentials` | 无用户参与，服务端到服务端调用 |
| 内部系统间调用 | `client_credentials` | 无用户上下文，使用应用身份 token |

## Web 后端应用与 App 的区别

Web 后端应用可以安全保存客户端密钥，因此 token endpoint 阶段除了 PKCE，还可以使用客户端认证。

```text
Web 后端应用 = authorization_code + PKCE + client authentication
```

其中 client authentication 可以是：

- `client_secret_basic`
- `client_secret_post`
- `private_key_jwt`
- mTLS

App、小程序、纯前端等公共客户端无法安全保存 `client_secret`，因此只能依赖 PKCE 保护授权码流程。

```text
App / 小程序 / 公共客户端 = authorization_code + PKCE，无 client_secret
```

## 与 client_credentials 的边界

`client_credentials` 只用于没有用户参与的系统间调用。

典型场景：

- 第三方服务端系统调用开放 API
- 内部系统间调用
- 闸机、批处理、对账、服务账号调用

这类 token 表示应用身份，不表示某个 C 端用户。

因此：

- 不应包含 `openid`、`profile`、`phone`、`email` 等用户身份 scope。
- 不应访问需要用户上下文的数据。
- 应使用应用身份类业务 scope，例如 `partner.ticket.read`、`partner.order.write`。

## 与 ctoken-service 的关系

现有 `ctoken-service` 是自研 opaque token 服务，默认生成 simple opaque token，并通过 MySQL + Redis 保存和校验 token。

新授权服务上线后，推荐关系如下：

| 项 | 处理方式 |
|----|----------|
| 最终 token 颁发 | 由 Spring Authorization Server 负责 |
| 最终 token 形态 | 新服务颁发 JWT access token / refresh token / id_token |
| 过渡阶段 | `ctoken-service` 可先从 opaque token 改为 ctoken JWT |
| ctoken JWT 定位 | 仍是现有登录体系的 token 形态升级，不是 OAuth2/OIDC |
| issuer | ctoken JWT 与 SAS JWT 应使用不同 issuer，资源侧支持多 issuer |
| 用户密码校验 | 授权服务器调用现有用户中心/CUR |
| `ctoken-service` 的密码/验证码/微信校验逻辑 | 可迁移或复用为授权服务器认证适配逻辑 |
| 旧 opaque token | 不迁移；用户重新登录后获取 ctoken JWT 或 SAS JWT |
| OAuth2 `password grant` | 不建议使用 |

## 一句话总结

内部和外部的 Web、App、小程序，只要有用户参与登录，最终都应统一走 `authorization_code + PKCE`；系统间调用才走 `client_credentials`。迁移期间可以先让 `ctoken-service` 从 opaque token 过渡到 ctoken JWT，但最终 token 颁发方应切到 Spring Authorization Server。
