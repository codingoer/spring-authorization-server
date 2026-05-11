# 授权流程 Scope 的调用

调研各大平台登录授权 scope 如何定义。以下内容优先依据题目内给出的官方链接，个别缺失处再用站内公开索引补充。

为满足排版一致性，所有平台统一使用以下三级目录：

- `### 官方链接`
- `### Scope 调研`

`### Scope 调研` 下统一使用相同四级标题。

## 抖音

### 官方链接

- [登录与授权](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/sdk/mobile-app/permission/overall-permission?is_new_connect=0&is_new_user=0)
- [网页获取openid](https://developer.open-douyin.com/docs/resource/zh-CN/dop/ability/opensdk/user-authorization/get-login-openid)
- [授权概述](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/sdk/mobile-app/permission/overall-permission)
- [获取用户唯一标识](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/account-permission/get-related-id)
- [获取用户公开信息](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/account-permission/get-account-open-info)
- [抖音授权码](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/account-permission/douyin-get-permission-code)
- [抖音静默获取授权码](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/account-permission/douyin-default-get-permission-code)

### Scope 调研

#### 是否区分静默授权和非静默授权

区分。除扫码、端内授权页、H5 常规授权页等**显式授权**外，平台还提供**静默授权**能力：在开放平台为应用开通 `login_id` 权限后，可在限定场景（常见为**抖音端内可打开的 H5**）通过**静默授权码**接口，使用 `scope=login_id` 获取 `code` 并换 `open_id` 等标识，**不经用户在当前步骤再次确认**（与“网页/扫码走授权页、必选 `user_info` + `optionalScope`”的链路不同）。

官方整体仍强调场景合规、按需申请，不建议无故强拉授权；但**不能**概括成“没有静默授权”，`login_id` 即对应静默链路。

#### Scope 示例与命名方式

- 静默授权（静默授权码）：`scope=login_id`（须已申请 `login_id` 权限；见静默获取授权码文档中的域名与端内限制）
- 显式授权（常规授权码）：必选 `scope=user_info`（及按需的可选 scope）
- 可选 scope（显式授权页）：`optionalScope=friend_relation,1,message,0`
- 命名风格：小写英文 + 下划线，多个 scope 用英文逗号分隔

#### Scope 划分与完整清单

在题目给定文档、常规/静默授权码页面中，能明确确认的 scope 有：

- `login_id`：静默授权码能力；用于在限定场景下获取 `code` 并换取用户在当前应用下的标识（如 `open_id`），不等同于读取昵称头像等资料能力
- `user_info`：获取用户公开信息，如昵称、头像
- `friend_relation`：粉丝/关系判断能力
- `message`：私信能力

其划分方式是“静默身份票据 + 基础公开资料 + 扩展能力”。不是极细粒度资源级拆分，而是按平台能力模块拆分；静默层仅解决**端内无打扰换标识**，资料与其他能力仍走显式授权或接口能力。

#### 硬性要求与授权策略

- 需要先在开放平台申请对应 scope 使用权限（含 `login_id`、静默链路所需的域名与端内打开等约束，以静默获取授权码文档为准）
- **显式授权**：必选 scope 放在 `scope` 参数中，多个用英文逗号分隔；可选 scope 放在 `optionalScope` 中，并附带默认勾选位：`1` 为勾选，`0` 为不勾选
- `redirect_uri` 必须是 `https`，且必须与应用配置的回调地址匹配
- `redirect_uri` 不支持自定义 query 参数
- **静默授权**：走专用静默授权码 URL 与参数约定；换得的 `access_token` 仅承载已开通且与本次 `scope` 匹配的权限边界，并不等于自动获得 `user_info` 等资料类能力

#### 真实业务场景

- 端内场景先稳定拿到 `open_id`、做账号绑定或免打扰登录态：在合规前提下使用 `login_id` 静默授权码
- 登录后需要昵称、头像等公开资料：再走 `user_info` 等显式授权（或相应接口能力）
- 内容/私域运营：在已登录基础上追加 `friend_relation`
- 客服、通知、私信触达：追加 `message`

#### 设计规则、实际用法、优缺点

抖音的设计重点除了 `user_info` 与 `optionalScope` 的“必选/可选”分层外，还有 **静默（`login_id`）与显式授权页** 的分工：端内可先低打扰拿标识，需要资料或扩展能力再走授权页。

- 优点：`login_id` 支持端内静默换标识，减少不必要弹窗
- 优点：显式链路仍支持渐进式授权（必选 + 可选 scope）
- 优点：强场景约束，比较符合最小权限原则
- 缺点：官方 scope 文档较分散，完整枚举不集中
- 缺点：对回调地址和接入姿势限制较严格

#### 粒度原则与平台特点

整体偏中粒度。`login_id` 是**静默层**标识能力；`user_info` 属于较粗的资料粒度；`friend_relation`、`message` 则按能力模块细分。平台特点是“端内可先静默标识，再按资料与业务能力扩权”，显式链路通过 `optionalScope` 贴近业务场景。

#### 一句话总结

抖音同时具备 **静默标识（`login_id`）** 与 **显式授权页（`user_info` + 可选能力）**：前者侧重端内无打扰换 `open_id` 等，后者按场景渐进拿资料与扩展能力，而不是单一“只有弹窗授权”模型。

## 快手

### 官方链接

- [开放平台](https://open.kuaishou.com/doc/docs-page)
- [网站应用](https://open.kuaishou.com/platformDocs/develop/web-app.html)
- [公开信息](https://open.kuaishou.com/platformDocs/openAbility/userInformation/publicInformation.html)

### Scope 调研

#### 是否区分静默授权和非静默授权

不区分真正意义上的静默/非静默授权。官方网站应用文档提供的是“网页登录后授权模式”和“手机扫码授权模式”，都需要显式登录或扫码确认，本质上是交互式授权。

#### Scope 示例与命名方式

- `scope=user_info`
- `scope=user_info,user_video_publish`
- 其他官方能力文档中还能看到 `user_video_info`、`user_video_mp_plc`
- 命名风格：小写英文 + 下划线，多个 scope 用英文逗号分隔

#### Scope 划分与完整清单

基于题目给定链接及官方站内公开文档，当前可明确确认的 scope 有：

- `user_info`：获取用户公开信息
- `user_video_publish`：发布视频相关能力
- `user_video_info`：查询用户作品列表
- `user_video_mp_plc`：在用户视频上挂载小程序挂件

快手的 scope 按“用户信息能力/视频能力”来拆，属于中粒度能力型 scope，不是按字段级拆分。

#### 硬性要求与授权策略

- 注册应用时要先申请对应权限 scope
- 用户请求某个 scope 前，应用必须已申请且审核通过，否则会报 `INVALID_SCOPE`
- 多个 scope 用英文逗号分隔
- `redirect_uri` 必须与注册时的回调地址保持 schema 和子域名一致
- 静态二维码场景下，`redirect_uri` 必须是公网可访问地址
- 官方明确要求开发者先调试通过再上线

#### 真实业务场景

- 只做账号登录与用户资料展示：`user_info`
- 帮用户从第三方站点直发作品到快手：`user_video_publish`
- 做创作者后台或选号工具：`user_video_info`
- 做小程序挂件运营：`user_video_mp_plc`

#### 设计规则、实际用法、优缺点

快手把 scope 设计成“能力型开关”，先让网站应用拿到基础登录，再按视频、挂件等具体能力追加。

- 优点：命名直观，业务含义清晰
- 优点：登录、扫码、静态二维码三种接入模式清楚
- 缺点：完整 scope 总表不够集中，部分能力散在不同文档
- 缺点：权限申请和审核前置，接入成本高于纯开放式平台

#### 粒度原则与平台特点

整体偏中粒度，既不是只给一个超大 scope，也没有细到字段级。平台特点是“登录授权和内容能力强绑定”，特别适合创作者、视频发布、挂件联动场景。

#### 一句话总结

快手的 scope 是典型的能力型权限模型，登录拿基础资料，视频和挂件能力再单独授权。

## 京东

### 官方链接

- [京东VOP授权](https://vop.jd.com/doc/guide?id=a3f98cc6-0eca-483d-b459-fd30c224ae4d)
- [京东商家开放平台](https://open.jd.com/v2/#/doc/begin-guide?listId=1900)
- [授权](https://open.jd.com/v2/#/doc/dev-guide?listId=1908)

### Scope 调研

#### 是否区分静默授权和非静默授权

不区分。题目给出的京东 VOP 官方授权文档走的是企业用户登录授权流程，本质是显式授权，没有像微信/支付宝那种“静默拿标识”和“非静默拿资料”的双层 scope 体系。

#### Scope 示例与命名方式

- 授权 URL 示例：`scope=snsapi_base`
- 命名风格：固定字面量，风格与微信类似，但京东 VOP 文档里它是固定值

#### Scope 划分与完整清单

在题目给出的京东 VOP 官方文档中，scope 只有一个明确值：

- `snsapi_base`：固定值，用于企业用户授权并换取 `access_token`

也就是说，京东 VOP 在当前官方文档下并不是一个“多 scope 菜单”，而是“固定 scope + 接口权限另管”的模型。后续真正能调用哪些接口，更多受应用审核、接口权限、企业授权关系控制。

#### 硬性要求与授权策略

- `response_type` 固定为 `code`
- `scope` 必填，且固定为 `snsapi_base`
- `redirect_uri` 必须与应用配置一致
- `code` 有效期 5 分钟，只能拿来换 token
- `access_token` 有效期 24 小时
- 官方建议每隔 6 到 8 小时主动申请或刷新 token
- 新申请 token 服务每 24 小时调用上限 3000 次
- 同一 token 每月刷新上限 500 次

#### 真实业务场景

- B2B 采购/供应链对接
- 企业采购账号授权后调用订单、售后、账号数据等接口
- ISV 帮企业用户对接京东 VOP 开放接口

#### 设计规则、实际用法、优缺点

京东 VOP 的设计思路不是把 scope 做细，而是把授权简化成“企业用户是否允许该应用代表自己调用平台接口”。

- 优点：授权模型简单，易于企业集成
- 优点：接入方不需要管理很多 scope 组合
- 缺点：scope 粒度很粗，最小权限表达能力弱
- 缺点：权限控制更多落在平台审核和接口授权关系上，可见性较差

#### 粒度原则与平台特点

极粗粒度。`scope` 更像授权流程的开关，而不是资源访问面的精细表达。平台特点是“token 授权粗，接口权限细”，适合强平台治理的企业开放平台。

#### 一句话总结

京东 VOP 的 scope 基本是固定占位符，真正的权限控制重点不在 scope，而在企业授权关系和接口准入。

## 支付宝

### 官方链接

- [用户登录授权](https://opendocs.alipay.com/open/d3dde1bb_alipay.user.info.auth?scene=common&pathHash=cba29c3c)

### Scope 调研

#### 是否区分静默授权和非静默授权

明确区分。

- `auth_base`：静默授权，自动跳转回调页，主要用于拿 `userId`
- `auth_user`：非静默授权，需要用户手动同意，适合获取头像、昵称等基本资料

#### Scope 示例与命名方式

- 示例：`["auth_base"]`
- 也可按同一参数模型传 `["auth_user"]`
- 命名风格：小写英文 + 下划线
- 参数形态不是单个字符串，而是 `scopes` 数组

#### Scope 划分与完整清单

根据题目所给官方文档，APP 支付宝登录场景下的 scope 清单为：

- `auth_base`：静默获取进入页面用户的 `userId`
- `auth_user`：获取用户基本信息，如头像、昵称等

支付宝是典型的“两段式”授权设计：先低打扰识别用户，再在需要时提升到资料授权。

#### 硬性要求与授权策略

- `scopes` 是必填参数
- `state` 是必填，且只允许 base64 字符，长度不超过 100
- 建议用 `state` 做 CSRF 防护
- 这是页面跳转接口，服务端一般通过 SDK 的 `pageExecute` 生成跳转内容
- 第三方代理调用场景可带 `app_auth_token`

#### 真实业务场景

- 只做账号识别、会员绑定、免注册登录：`auth_base`
- 需要昵称、头像做欢迎页、会员资料展示：升级到 `auth_user`

#### 设计规则、实际用法、优缺点

支付宝的 scope 设计非常克制，只有两个核心等级，强调先低权限完成登录，再在确有必要时升级授权。

- 优点：用户容易理解，授权链路短
- 优点：最小权限原则落实得比较清楚
- 缺点：表达能力有限，无法像广告/内容平台那样细分能力
- 缺点：如果业务需要更多用户侧能力，仍要借助其他产品能力而不是继续扩 scope

#### 粒度原则与平台特点

偏粗粒度，但分层明确。`auth_base` 和 `auth_user` 本质是“身份识别层”和“资料读取层”。平台特点是标准化强、心智简单、静默与非静默边界清晰。

#### 一句话总结

支付宝把 scope 压缩成“静默识别”和“显式资料授权”两层，是最经典的渐进式授权模型之一。

## 百度

### 官方链接

- [Oauth接入指南](https://openauth.baidu.com/doc/doc.html)

### Scope 调研

#### 是否区分静默授权和非静默授权

不区分专门的静默/非静默 scope。百度 OAuth 仍然是用户登录并授权后拿 `code`，只是通过 `force_login`、`confirm_login` 等参数控制交互方式，不能等同于微信/支付宝那种静默 scope。

#### Scope 示例与命名方式

- 授权请求：`scope=basic mobile`
- 官方参数说明：scope 以空格分隔
- 当前接入页明确可填：`basic`、`mobile`
- token 返回示例里还出现了 `basic email`

#### Scope 划分与完整清单

在题目给出的官方接入页中，能明确确认的 scope 有：

- `basic`
- `mobile`

另外，官方 token 返回示例中出现 `basic email`，说明百度历史上存在更细的权限项，但当前接入页没有再给出一张集中完整总表。因此百度的 scope 文档清晰度弱于微信、支付宝、微博。

#### 硬性要求与授权策略

- `scope` 可选；不传时表示请求默认权限
- 多个 scope 用空格分隔
- `redirect_uri` 必须与安全设置或应用域名规则匹配
- `state` 建议携带，用于防 CSRF
- `code` 10 分钟有效，只能用一次
- `refresh_token` 有效期 10 年

#### 真实业务场景

- 百度账号登录第三方网站
- 获取基础用户信息做账号绑定
- 某些场景下申请手机号类能力或移动端登录能力

#### 设计规则、实际用法、优缺点

百度 OAuth 更像传统账号登录开放能力，scope 不是产品设计重点，而是作为补充权限参数存在。

- 优点：基础接入简单，默认权限路径清楚
- 优点：支持较长的 refresh_token 生命周期
- 缺点：scope 总表不够清晰，文档存在历史痕迹
- 缺点：不容易直观看出每个 scope 对应的业务能力边界

#### 粒度原则与平台特点

整体偏粗粒度，且平台更强调账号登录流程，而不是精细 scope 管理。特点是“默认权限优先、scope 补充表达”，比较传统。

#### 一句话总结

百度 OAuth 的重点在账号登录而不在 scope 体系，scope 能用但不算这个平台最清晰的权限表达方式。

## 微博

### 官方链接

- [授权机制](https://open.weibo.com/wiki/%E6%8E%88%E6%9D%83%E6%9C%BA%E5%88%B6)
- [授权scope说明](https://open.weibo.com/wiki/Scope)
- [获取用户的联系邮箱](https://open.weibo.com/wiki/2/account/profile/email)

### Scope 调研

#### 是否区分静默授权和非静默授权

不区分静默和非静默 scope。微博是在标准 OAuth 登录授权之上，通过是否附带 scope，决定用户是否还要看到额外的高级权限确认项。

#### Scope 示例与命名方式

- 单个：`scope=email`
- 多个：`scope=email,live_create`
- 命名风格：小写英文，多个 scope 用英文逗号分隔

#### Scope 划分与完整清单

微博官方 scope 页给出的清单是：

- `all`：申请下列所有 scope
- `email`：获取用户联系邮箱
- `invitation_write`：邀请发送接口
- `follow_app_official_microblog`：授权时引导关注应用官方微博
- `live_create`：获取用户推流地址，用于直播

微博的 scope 不是“基础登录必须项”，而是“附加能力项”。

#### 硬性要求与授权策略

- 需要先具备对应接口使用权限，否则返回 `10014`
- 用户拒绝带 scope 的授权时，会返回 `10032`
- 可一次申请多个 scope，用英文逗号分隔
- 某些 scope 需要额外开通，比如直播能力

#### 真实业务场景

- 邮箱找回、账号补全：`email`
- 社交裂变：`invitation_write`
- 拉新关注：`follow_app_official_microblog`
- 直播工具接入：`live_create`

#### 设计规则、实际用法、优缺点

微博把基础 OAuth 和增值能力解耦，scope 更多是对高级场景做“能力附加”。

- 优点：附加能力和基础登录分离，用户更容易理解
- 优点：scope 数量不多，维护成本低
- 缺点：能力面较窄，不适合做复杂权限组合
- 缺点：部分 scope 不直接对应通用 API，可读性一般

#### 粒度原则与平台特点

整体偏粗粒度，但功能导向很强。微博的 scope 更像“高级功能包”，而不是 API 资源访问矩阵。

#### 一句话总结

微博的 scope 是在基础 OAuth 之外附加少量高级能力，简单直接，但不追求精细化权限表达。

## 微信

### 官方链接

- [网页授权](https://developers.weixin.qq.com/doc/service/guide/h5/auth.html)
- [微信登录](https://developers.weixin.qq.com/doc/oplatform/Website_App/WeChat_Login/Wechat_Login.html)

### Scope 调研

#### 是否区分静默授权和非静默授权

明确区分，但要分产品线看：

- 服务号 H5 网页授权：`snsapi_base` 是静默授权，`snsapi_userinfo` 是非静默授权
- 网站应用微信登录：`snsapi_login` 是网站扫码/快速登录 scope，本身不属于静默授权

#### Scope 示例与命名方式

- 服务号网页授权：`scope=snsapi_base`、`scope=snsapi_userinfo`
- 网站应用登录：`scope=snsapi_login`
- 命名风格：`snsapi_` 前缀 + 能力名

#### Scope 划分与完整清单

结合题目给定的两份微信官方文档，可确认的 scope 清单为：

- `snsapi_base`：静默拿 `openid`、可换取 `access_token`/`refresh_token` 并校验授权
- `snsapi_userinfo`：显式授权后可调用 `/sns/userinfo` 获取用户资料
- `snsapi_login`：网站应用微信登录专用 scope

微信的 scope 不是一套通吃，而是“不同产品线各有自己的固定 scope 集合”。

#### 硬性要求与授权策略

- 服务号网页授权仅支持已认证服务号，账号类型不对会报错
- 网站应用登录要求网站应用已审核通过
- `redirect_uri` 必须和审核/配置的授权域名匹配
- 网站应用场景下目前仅填写 `snsapi_login`
- `state` 建议携带，用于防 CSRF
- `refresh_token` 有效期 30 天

#### 真实业务场景

- 公众号内 H5 页面静默识别用户：`snsapi_base`
- 公众号活动页要昵称、头像：`snsapi_userinfo`
- PC 网站扫码登录、桌面端快速登录：`snsapi_login`

#### 设计规则、实际用法、优缺点

微信的特点是把“公众号 H5 授权”和“开放平台网站登录”明确分离，scope 简单但产品边界非常重要。

- 优点：静默与非静默边界清楚，使用心智稳定
- 优点：不同产品线的授权目的明确
- 缺点：同样叫“微信授权”，但 scope 来自不同产品线，最容易混淆
- 缺点：账号资质限制很强，接入门槛高

#### 粒度原则与平台特点

整体偏粗粒度，但产品分层极清楚。微信不是靠很多 scope 表达复杂权限，而是靠账号类型、产品线、固定 scope 组合控制能力开放。

#### 一句话总结

微信的 scope 不多，但产品线边界极强: 公众号 H5 用 `snsapi_base`/`snsapi_userinfo`，网站登录用 `snsapi_login`。

## 小红书

### 官方链接

- [scope权限说明](https://ad-market.xiaohongshu.com/docs-center?bizType=943&articleId=3195)
- [授权流程](https://ad-market.xiaohongshu.com/docs-center?bizType=944&articleId=2603)

### Scope 调研

#### 是否区分静默授权和非静默授权

不区分静默/非静默。小红书开放平台这里是广告/账户类 OAuth2.0 授权，广告主在授权页显式确认后返回 `auth_code`，再换 `access_token`。

#### Scope 示例与命名方式

- 原始 scope 示例：`["report_service","ad_query","ad_manage","account_manage"]`
- 授权 URL 示例：`scope=%5B%22report_service%22,%22ad_query%22,%22ad_manage%22,%22account_manage%22%5D`
- 命名风格：小写英文 + 下划线
- 与常见平台不同，小红书这里的 `scope` 参数实际按数组字面量再做一次 URL 编码

#### Scope 划分与完整清单

题目给定的官方 scope 说明页给出的完整清单为：

- `report_service`：获取账户报表信息
- `ad_query`：查询推广计划、推广单元、推广创意
- `ad_manage`：创建和修改推广计划、推广单元、推广创意
- `account_manage`：账户预算、流水、余额等账户管理能力

这是非常典型的广告平台 scope 设计：读、写、报表、账户分别拆开，粒度明显比社交登录平台更细。

#### 硬性要求与授权策略

- 授权 URL 中要带 `appId`、`scope`、`redirectUri`、`state`
- `scope` 参数需要按要求进行 URL 编码
- `redirectUri` 也需要 URL 编码，并与应用申请时配置一致
- `auth_code` 有效期 10 分钟
- `access_token` 有效期 1 天
- `refresh_token` 有效期 30 天
- 每次刷新会生成新的 `access_token` 和 `refresh_token`，旧 token 会被替换，必须保存最新值

#### 真实业务场景

- BI/投放报表平台：`report_service`
- 广告资产查询、自动巡检：`ad_query`
- 自动化投放、批量建计划：`ad_manage`
- 财务、预算、对账：`account_manage`

#### 设计规则、实际用法、优缺点

小红书 scope 是明显的业务操作型设计，不只是“读资料/拿身份”，而是把广告业务动作拆开授权。

- 优点：权限最小化做得好，读写边界明确
- 优点：很适合投放系统、报表系统、代运营平台分模块授权
- 缺点：接入和调试复杂，URL 编码要求不够直观
- 缺点：scope 多时，授权组合和 token 管理成本更高

#### 粒度原则与平台特点

偏细粒度，且是“资源域 + 动作”导向的广告权限模型。平台特点是 scope 真正参与后端能力控制，而不是只做登录装饰参数。

#### 一句话总结

小红书的 scope 最接近企业广告平台权限模型，把报表、查询、投放、账户管理拆成了可独立授权的细粒度能力。


## QQ

### 官方链接

- [QQ互联首页](https://connect.qq.com/)
- [QQ互联应用管理](https://connect.qq.com/manage.html#/)
- [腾讯应用开放平台](https://app.open.qq.com/p/developer/team_manage/info)
- [腾讯应用开放平台网站接入](https://wikinew.open.qq.com/index.html#/iwiki/877911779)

### Scope调研


