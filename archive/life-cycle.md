# Spring OAuth2 完整演进 & 版本适配总结
> 归档至个人学习仓库 study 分支，永久备查；涵盖：旧版废弃、新版标准、JDK/ Boot/Security 严格对应关系

Spring OAuth2 生态经历了**「三合一旧版 → 拆分过渡 → 现代标准」**三个阶段，核心围绕**授权服务器、资源服务器、客户端**三大角色重构。

## 一、核心4个仓库终极定性
| 仓库地址 | 状态 | 核心说明 |
|--------|------|----------|
| `spring-attic/spring-security-oauth` | 废弃归档(EOL) | 初代OAuth2，含旧授权服务器；注解：`@EnableAuthorizationServer`/`@EnableResourceServer` |
| `spring-attic/spring-security-oauth2-boot` | 废弃归档(EOL) | 旧版OAuth2 Boot自动配置，过渡兼容包，已停止维护 |
| `spring-projects/spring-security` | 持续迭代 | 5.2+内置**OAuth2客户端+资源服务器**；6.x彻底剔除旧OAuth2代码 |
| `spring-projects/spring-authorization-server` | 官方主推 | 新一代独立授权服务器，唯一替代旧`spring-security-oauth`的生产方案 |

## 二、OAuth2 三代架构演进
1. **第一代（老旧废弃）**
   Boot 1.x ~ 2.2.x，依赖 `spring-security-oauth`；全套授权/资源/客户端打包，现已EOL，新项目严禁使用。

2. **第二代（过渡阶段）**
   Boot 2.3 ~ 2.6.x；Spring Security 内置 OAuth2 客户端、资源服务器；**无官方内置授权服务器**，老项目可临时沿用旧包，新项目不推荐。

3. **第三代（当前标准·主推）**
   Boot 2.7.x 及以上 / 3.x；
- 客户端/资源服务器：Spring Security 原生内置
- 授权服务器：独立引入 `spring-authorization-server`
  整套为官方现代标准架构。

## 三、版本+JDK+框架适配总表（重点备查）
| Spring Boot 版本 | 强制JDK版本 | Spring Security | OAuth2 最终选型 |
|-----------------|-------------|-----------------|----------------|
| 1.5.x | JDK8 | 4.2.x | 旧：`spring-security-oauth`（废弃） |
| 2.0 ~ 2.2.x | JDK8 | 5.1~5.2.x | 旧：`spring-security-oauth2-boot`（废弃） |
| 2.3 ~ 2.6.x | JDK8 | 5.3~5.6.x | 客户端/资源服：Security内置；授权服：临时用旧包（过渡） |
| 2.7.x | JDK8 | 5.7.x | 全套新架构：内置客户端+资源服 + `spring-authorization-server` |
| 3.0 ~ 3.3+ | JDK17 | 6.0+ | 强制新架构；彻底不兼容所有旧OAuth2归档包 |

## 四、依赖快速复制（生产可用）
### 1、新版 客户端 / 资源服务器（2.7+/3.x通用）
```xml
<!-- OAuth2 客户端 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>

<!-- OAuth2 资源服务器(JWT) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

### 2、新版 授权服务器（唯一推荐）
```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-oauth2-authorization-server</artifactId>
</dependency>
```

### 3、旧版废弃依赖（仅老项目识别，新项目禁止引入）
```xml
<dependency>
    <groupId>org.springframework.security.oauth.boot</groupId>
    <artifactId>spring-security-oauth2-autoconfigure</artifactId>
</dependency>
```

## 五、极简判断口诀（快速自查）
1. 看到 `@EnableAuthorizationServer` → 老旧废弃架构，必须升级
2. Boot 2.7 是分水岭：之前旧OAuth2，之后全用新独立授权服务
3. Boot 3.x 必配 JDK17 + Security6.x，彻底告别所有 attic 归档包
4. 新项目一律：Boot3.2+JDK17+原生OAuth2+authorization-server

## 六、个人备忘
- 禁止在新项目引入 `spring-attic` 下任何OAuth2相关依赖
- 微服务资源服务统一用官方内置 `oauth2ResourceServer` 配置
- 自研授权中心，只允许基于 `spring-authorization-server` 开发
