<p align="center">
  <img src="logo.png" width="280" alt="Mc-Mail">
</p>

<h1 align="center">McMail</h1>

<p align="center">
  一个给你的 Forge Mod 提供统一化邮箱 API 的前置 Mod
</p>

<p align="center">
  <a href="https://github.com/yumi-furry/McMail/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.20.1-green.svg" alt="Minecraft">
  <img src="https://img.shields.io/badge/Forge-47.4.23-orange.svg" alt="Forge">
  <img src="https://img.shields.io/badge/Java-17-red.svg" alt="Java">
  <img src="https://img.shields.io/badge/side-SERVER-lightgrey.svg" alt="Server Only">
</p>

---

## 简介

McMail 是一个**仅服务端**运行的 Forge 模组，集中管理 IMAP/SMTP 邮件配置，向其他 Mod 提供简洁的异步发信 API。管理员通过配置文件或游戏内命令完成邮箱设置后，任何 Mod 都可以一行代码发送邮件——无需各自重复配置。

**特性：**

- 单 jar 部署，已内嵌 Jakarta Mail，服务器只需放入 `mods/` 目录
- 游戏内命令热配置，密码 Base64 存储、回显脱敏
- 全部邮件 IO 在专用守护线程池异步执行，不阻塞主线程
- 内置现代化 HTML 测试邮件，一键验证配置是否正确
- `MailApi` 供其他 Mod 调用，支持纯文本与 HTML 邮件

---

## 目录

- [部署](#部署)
- [配置文件说明](#配置文件说明)
- [游戏内命令](#游戏内命令)
- [典型邮箱配置示例](#典型邮箱配置示例)
- [API 供其他模组调用](#api-供其他模组调用)
- [安全与脱敏](#安全与脱敏)
- [自行构建](#自行构建)
- [许可证](#许可证)

---

## 部署

将构建产物 `mcmail-1.0.0-all.jar`（约 739 KB）放入服务器的 `mods/` 目录。

服务器只需这一个文件，已内嵌 Jakarta Mail 与 Activation 库，无需额外依赖。

首次启动后自动生成配置文件：

```
config/mcmail-common.toml
```

---

## 配置文件说明

文件路径：**`config/mcmail-common.toml`**

共 18 个配置项，分三大块：

### general — 总开关与调试

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `general.enabled` | 布尔 | `true` | 邮件总开关。`false` 时所有测试/发信请求直接返回"邮件功能未启用"，不联网 |
| `general.debug` | 布尔 | `false` | 是否在服务端控制台输出 Jakarta Mail 详细调试日志 |
| `general.timeoutMs` | 整数 | `10000` | SMTP/IMAP 连接超时（毫秒），最小值 `1` |

### smtp — 发信服务器

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `smtp.host` | 字符串 | `""` | SMTP 服务器地址，如 `smtp.163.com` |
| `smtp.port` | 整数 | `587` | SMTP 端口 |
| `smtp.username` | 字符串 | `""` | 发信账号（完整邮箱地址） |
| `smtp.password` | 字符串 | `""` | 发信密码/授权码。**Base64 存储**，回显/日志一律脱敏为 `******` |
| `smtp.fromAddress` | 字符串 | `""` | 邮件 `From` 地址，通常与 username 相同 |
| `smtp.useTls` | 布尔 | `true` | 是否启用 STARTTLS（端口 587 通常需要） |
| `smtp.useSsl` | 布尔 | `false` | 是否直接使用 SSL 连接（端口 465 需要） |

### imap — 收信服务器

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `imap.host` | 字符串 | `""` | IMAP 服务器地址，如 `imap.163.com` |
| `imap.port` | 整数 | `993` | IMAP 端口 |
| `imap.username` | 字符串 | `""` | 收信账号 |
| `imap.password` | 字符串 | `""` | 收信密码/授权码（同样 Base64 存储、脱敏回显） |
| `imap.useTls` | 布尔 | `true` | 是否启用 STARTTLS |
| `imap.useSsl` | 布尔 | `true` | 是否直接使用 SSL（端口 993 通常需要） |
| `imap.folder` | 字符串 | `"INBOX"` | 测试登录时打开的文件夹 |

> **提示**：直接编辑配置文件后，执行 `/mcmail reload` 即可热重载，无需重启服务器。

---

## 游戏内命令

所有命令前缀为 `/mcmail`，**需要权限等级 4**（OP/控制台）。

普通玩家无法解析、Tab 补全不可见。

### 查看与修改配置

```
/mcmail list
```
列出当前全部配置项及其值（密码类显示为 `******`）。

```
/mcmail get <key>
```
查看单个配置项。例如 `/mcmail get smtp.host`。

```
/mcmail set <key> <value>
```
修改单个配置项并**立即写盘**。

- 布尔值：只接受 `true` / `false`
- 整数值：必须 `>= 1`
- 密码类（以 `.password` 结尾）：**Base64 编码存储**，玩家执行时会收到聊天提示——"设置已保存。请注意：聊天日志可能记录了你输入的原文。"

### 热重载

```
/mcmail reload
```
从磁盘重新读取 `config/mcmail-common.toml`，覆盖内存中的当前值。

### 连接测试

```
/mcmail test smtp
```
异步测试 SMTP 登录，结果回显到执行者。

```
/mcmail test imap
```
异步测试 IMAP 登录并只读打开 `imap.folder` 指定文件夹。

```
/mcmail test send <收件人邮箱>
```
异步发送一封 **HTML 测试邮件**到指定地址。

- 主题固定为 `McMail Test`
- 正文为现代化卡片式 HTML：Mc-Mail logo + 发送时间信息框 + GitHub 按钮
- logo 以 CID 附件形式内嵌，不依赖任何外链图床

---

## 典型邮箱配置示例

### QQ 邮箱

```toml
[smtp]
    host = "smtp.qq.com"
    port = 587
    username = "你的QQ号@qq.com"
    fromAddress = "你的QQ号@qq.com"
    useTls = true
    useSsl = false

[imap]
    host = "imap.qq.com"
    port = 993
    username = "你的QQ号@qq.com"
    useTls = true
    useSsl = true
    folder = "INBOX"
```

### 163 邮箱

```toml
[smtp]
    host = "smtp.163.com"
    port = 465
    username = "你的邮箱@163.com"
    fromAddress = "你的邮箱@163.com"
    useTls = false
    useSsl = true

[imap]
    host = "imap.163.com"
    port = 993
    username = "你的邮箱@163.com"
    useTls = false
    useSsl = true
    folder = "INBOX"
```

> **注意**：QQ 邮箱、163 邮箱等需要使用**授权码**代替登录密码。授权码在邮箱网页版设置 → 账户 → SMTP/IMAP 中生成。

---

## API 供其他模组调用

```java
import com.example.mcmail.api.MailApi;
import com.example.mcmail.mail.MailResult;
import java.util.concurrent.CompletableFuture;

// 异步发送纯文本邮件
CompletableFuture<MailResult> future = MailApi.sendMail(
    "recipient@example.com",   // 收件人
    "邮件主题",                // 主题
    "邮件正文内容"             // 正文
);

// 异步发送 HTML 邮件（logo 自动以 CID 附件内嵌，HTML 中用 <img src="cid:mcmail-logo"> 引用）
CompletableFuture<MailResult> htmlFuture = MailApi.sendHtmlMail(
    "recipient@example.com",
    "HTML 邮件主题",
    "<html><body><h1>你好</h1></body></html>"
);

// 处理结果（非阻塞）
future.thenAccept(result -> {
    if (result.success()) {
        System.out.println("发送成功: " + result.message());
    } else {
        System.err.println("发送失败: " + result.message());
    }
});
```

- 邮件实际发送在**守护线程池**（2 线程）中执行，不阻塞服务器主线程
- `MailResult` 为不可变记录：`record MailResult(boolean success, String message)`
- 若 `general.enabled=false`，返回的 `MailResult` 为失败，message 为 "邮件功能未启用"

### 作为依赖引入

其他 Mod 只需 `compileOnly` 依赖 McMail 的 jar（运行时由服务端安装 McMail 提供）：

```groovy
dependencies {
    compileOnly files('libs/mcmail-1.0.0.jar')
}
```

---

## 安全与脱敏

| 场景 | 行为 |
|------|------|
| 密码落盘 | Base64 编码存储于 `mcmail-common.toml` |
| 游戏内回显 | `/mcmail list` / `/mcmail get` 显示 `******` |
| 服务端日志 | 不输出明文密码 |
| 明文兼容 | 手改配置文件写入明文密码时，自动识别并转为 Base64 落盘 |
| 玩家设置密码 | 聊天栏提示风险，建议在控制台或直接编辑配置文件设置 |

---

## 自行构建

```bash
# 需要 JDK 17
git clone https://github.com/yumi-furry/McMail.git
cd McMail
./gradlew build
```

构建产物位于 `build/libs/`：

| 文件 | 说明 |
|------|------|
| `mcmail-1.0.0-all.jar` | 完整部署包（含 Jakarta Mail），服务器使用此文件 |
| `mcmail-1.0.0.jar` | 瘦包（不含依赖），供其他 Mod 编译期引用 |

---

## 生命周期

| 事件 | 行为 |
|------|------|
| 服务端启动完成 | 初始化邮件线程池，读取配置 |
| 服务端关闭中 | 关闭线程池，等待最多 2 秒未完成任务 |
| 配置热写（`/mcmail set`） | 修改后立即 `modConfig.save()` 写盘 |
| 配置热读（`/mcmail reload`） | 从磁盘重新加载并覆盖内存值 |

---

## 许可证

本项目采用 [MIT License](LICENSE)。

---

<p align="center">
  <sub>Mod ID: <code>mcmail</code> · 包名: <code>com.example.mcmail</code> · 作者: <a href="mailto:3783260249@qq.com">yumi-furry</a></sub>
</p>
