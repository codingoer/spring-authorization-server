# 开源项目学习 Git 完整流程总结（Fork + 分支管理 + 学习规范）
适用于：Spring Authorization Server 及所有 GitHub 开源项目学习

---

## 一、核心原则（行业标准）
1. **Fork 只拉主分支**：自己仓库只保留默认分支（main），不 Fork 全部分支
2. **远程分工**
    - `origin`：自己的 Fork 仓库（SSH，用于提交自己的代码）
    - `upstream`：官方原仓库（HTTPS，只读拉取最新代码）
3. **学习用分支，不用 Tag**：学习选 `1.5.x` 版本分支，Tag 仅用于固定版本部署
4. **分支隔离**：官方代码不修改，所有学习修改放在自己的 `study` 分支（重点：非必须但强烈建议创建）

---

## 二、完整操作步骤（一步一复制）
### 1. Fork 官方仓库
- 打开项目 GitHub → 点击右上角 **Fork** → 仅创建主分支到自己账号

### 2. 克隆自己的仓库到本地
```bash
git clone git@github.com:你的账号/spring-authorization-server.git
cd spring-authorization-server
```

### 3. 关联官方上游仓库（HTTPS）
```bash
git remote add upstream https://github.com/spring-projects/spring-authorization-server.git
```

### 4. 拉取官方所有分支/Tag
```bash
git fetch upstream
```

### 5. 拉取官方学习分支（如 1.5.x）
```bash
git checkout -b 1.5.x upstream/1.5.x
```

### 6. 创建自己的学习分支（study）
**基于官方稳定分支创建，不直接修改官方代码**

核心说明：拉取官方 1.5.x 分支后，不是必须马上创建 study 分支，但强烈建议创建，具体要求如下：
- 仅查看代码、不做任何修改（不写注释、不改配置、不提交）：可暂时不创建 study 分支

- 只要做学习相关操作（改代码、加注释、写笔记、测试、提交代码）：必须创建 study 分支，避免污染官方分支
```bash
git checkout -b study 1.5.x
```

### 7. 配置当前仓库个人 Git 信息（隔离公司账号）

本地默认是公司 Git 账号，需单独给当前 GitHub 学习仓库配置个人账号（不影响公司项目）：

```shell
git config user.name "Lionel"
git config user.email "codingoer@163.com"
```

---

## 三、日常学习规范（必看）
### 1. 所有学习操作都在 study 分支
- 写笔记、改代码、做测试 → 只在 `study` 分支
- 所有学习操作（写笔记、改代码、做测试）都在 study 分支，不直接修改 1.5.x / main 等官方分支
- 1.5.x 分支仅用于同步官方最新代码，保持干净、原始，作为学习的基准版本
- 不直接修改 `1.5.x` / `main` 等官方分支
- 禁止直接在 1.5.x 分支提交代码：否则下次同步官方代码时极易产生冲突，且无法快速重置回官方原版

### 2. 同步官方最新代码
```bash
# 切换到官方分支 → 更新 → 合并到自己的 study
git checkout 1.5.x
git pull upstream 1.5.x
git checkout study
git merge 1.5.x
```

### 3. 提交自己的学习代码到远程
```bash
git add .
git commit -m "学习笔记：xxx"
git push origin study
```

---

## 四、分支用途说明（清晰不混乱）
| 分支名       | 来源           | 用途                          | 能否修改 |
|------------|--------------|-----------------------------|------|
| main       | 自己 Fork 默认 | 保持干净，同步官方主线              | 否    |
| 1.5.x      | 官方 upstream | 官方稳定版本分支，只读同步           | 否    |
| study      | 基于 1.5.x    | 自己的学习分支，写笔记、改代码、做实验 | 是    |

---

## 五、常用命令速查
```bash
# 查看远程仓库
git remote -v

# 拉取官方最新分支/Tag
git fetch upstream

# 切换分支
git checkout 分支名

# 创建并切换新分支
git checkout -b 新分支名 基准分支

# 配置当前仓库个人信息
git config user.name "GitHub用户名"
git config user.email "GitHub邮箱"

# 查看当前仓库Git配置
git config --get user.name
git config --get user.email

# 提交代码到自己仓库
git add .
git commit -m "备注"
git push origin study
```

---

### 总结
1. **Fork 只留主分支，upstream 用 HTTPS** origin 用 SSH
2. **学习用 1.5.x 分支，不用 Tag**
3. **自己代码全放 study 分支，不污染官方代码**
4. **定期从 upstream 同步最新代码**
