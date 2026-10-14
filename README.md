# 副卡短信转发系统 · sms-code-relay

> 把「插在老人机 / 备用机上的副卡」收到的验证码，送到你手边这台手机上——**不建服务器、不要求两台机同一 Wi-Fi、不经任何第三方服务器**。
>
> 一对 Android 应用，纯 Kotlin、**零第三方依赖**，各约 1500~1700 行、670 KB。附带一份国内网络与国产 ROM 的实测结论。

---

## 一、这是什么 / 什么场景

副卡（收验证码那张卡）插在另一台手机里——常见的是家里长辈的手机，或者一台常年插电的备用机。你在自己的手机上登录某个服务，验证码却发到了那台机器上。

本项目的做法：**副机上一个发送端**（收短信 → 按规则过滤 → 落盘排队 → 按通道发出），**主机上一个接收端**（收码 → 高优先级通知 + 悬浮窗覆盖在游戏/全屏应用之上 + 一键复制）。

两端的取舍都围绕一个约束：**不想为了收一条验证码去租服务器、也不想把验证码交给第三方**。

---

## 二、系统构成

| 目录 | 角色 | 跑在哪 | 主要能力 |
|---|---|---|---|
| `sender/` | 副端（发送） | 副机（Android 8+，实测 Android 13 / ColorOS） | 短信接收（长短信拼接、双卡归属）、规则过滤（包含词/排除词/4~8 位数字兜底）、SQLite 落盘队列、指数退避重试、多通道适配器、前台服务 + 开机自启 + JobScheduler 看护、单页设置与记录界面 |
| `receiver/` | 主端（接收） | 主机（实测 Android 11） | 邮箱轮询（POP3 + UIDL 去重）、局域网 HTTP 接收端（自带极简 HTTP 服务）、高优先级横幅通知（带「复制验证码」）、**悬浮窗**（可拖动、N 秒自动消失）、本地记录与日志 |
| `tools/` | 联调与验证 | PC | mock SMTP+POP3、webhook 接收端、两端数据库导出脚本 |

两端的**共用约定**：转发正文一律是 `前缀 + 发件人|验证码|SIM标签|正文`（默认前缀 `[转发]`），主端按前缀识别，两个入口（邮件 / HTTP）共用同一个解析器。

---

## 三、为什么不是"又一个 SmsForwarder"

功能面（收短信 → 过滤 → 队列重试 → 多通道转发 → 保活）**不是新问题**，[pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder) 早就做得更全。若要现成可用，**请优先用它**。

本仓的价值在两处：**一是把通道选型这件事按实测证据说清楚**，二是**一个零依赖、可读可改的参考实现**。

### 3.1 通道选型：实测数据（2026-10，两台手机：ColorOS 13 / Android 13 与 Android 11 / 国产 ROM）

| 通道 | 实测结果 | 结论 |
|---|---|---|
| 第三方 App 直接发短信 | 国产 ROM（ColorOS 13.1）对非系统短信应用**逐条弹系统确认框**（窗口名 `Permission Intercept`：『「短信转发」想要发送短信到 …』[取消][允许] 带倒计时，不点即拒）；`cmd role add-role-holder android.app.role.SMS` 授角色被系统拒绝 | **不可用**（除非接管整个短信应用，且不保证生效） |
| 局域网 HTTP 直推 | 两端都实现，单机回环实测 `{"ok":true,"got":1}` | 可用，但**要求两台机同一 Wi-Fi** |
| ntfy.sh / broker.emqx.io | 两台手机 **ping 不通** | 排除 |
| Bark(api.day.app) / Server酱 / 企业微信机器人 | 可达 | **只能发不能拉**，接收端取不回；且经第三方 | 排除 |
| PushDeer(api2.pushdeer.com) | 可达，有 `/message/list` 可拉 | 可用，但需从它的客户端取 pushkey | 备选 |
| **邮箱（SMTP 发 + POP3 拉）** | `smtp.qq.com` / `pop.qq.com` 两台机都可达 | **采用**：不经第三方服务器，任何网络下都能配对 |

### 3.2 邮箱即中转（本仓的主要设计点）

把**用户自己的邮箱**当中转：发送端用 SMTP 发一封给自己（正文是 `前缀 + 发件人|验证码|SIM|正文`，base64 编码），接收端用 POP3 轮询取走。

关键细节：**前缀识别**（只处理自己发的）+ **UIDL 去重**（见过的不重取）+ **只删自己那一封**（其余邮件一律不动——这条是踩过事故后立的规矩，见 §6）。

### 3.3 国产 ROM 的实测行为（都会影响"能不能一直活着"）

| 现象 | 原始证据 |
|---|---|
| `adb install` 卡死 | ColorOS 把 adb 安装接到「电脑端安装」通道，install session 停在 `mCommitted=true / mSealed=true / mSessionApplied=false`，界面显示「安装失败」，**之后每次安装都排在这一堆会话后面一起卡**（重启也可能清不掉）。可靠路径：手机端侧载（文件管理点 APK） |
| 广播唤不醒没在跑的 App | `OplusAppStartupManager: sikp broadcast for proc does not exist, pkg:…, action:android.provider.Telephony.SMS_RECEIVED` |
| 开机广播被压后投递 | 系统 15:00:28 post，App **15:04:17** 才收到（约 3 分 49 秒）；落在窗口内的短信被 ROM 丢弃 |
| shell 权限被收走 | `pm grant` 报缺 `GRANT_RUNTIME_PERMISSIONS`、`settings put global` 报缺 `WRITE_SECURE_SETTINGS` |
| 保活开关默认全关 | 需要手动打开「允许应用自启动 / 允许完全后台行为 / 允许应用关联启动」，否则服务不会自己回来 |

---

## 四、快速开始

### 4.1 构建

环境：JDK 17、Android SDK（platform 35 / build-tools 35）、Gradle 由 wrapper 提供（9.5.0）、AGP 9.3.1（**AGP 9 自带 Kotlin 编译，工程里不需要 kotlin-android 插件**）。

```bash
cd sender   && ./gradlew :app:assembleRelease
cd receiver && ./gradlew :app:assembleRelease
```

本机 java 若是较新版本（如 25），在 `gradle.properties` 里用 `org.gradle.java.home` 把 Gradle 钉到 JDK 17。

### 4.2 签名

两端都从**工作区之外**的 properties 文件读签名（工程里不留任何口令），文件不存在时退回 debug 签名以便直接出包：

- `sender` 读 `D:/android/keys/smsrelay-release.properties`（可用环境变量 `SMSRELAY_KEYSTORE_PROPS` 覆盖）
- `receiver` 读 `D:/android/keys/smsrecv-release.properties`（可用 `SMSRECV_KEYSTORE_PROPS` 覆盖）
- 生成 key 的脚本见各目录 `tools/Make-*Key.ps1`（注意：`java.util.Properties` 把反斜杠当转义，路径要写双反斜杠）

### 4.3 安装（国产 ROM 请读第 3.3 节）

1. 把 APK 放进手机存储，用**文件管理**点它安装（不要指望 `adb install`，ColorOS 上会卡）。
2. 打开 App，授予短信/电话/通知权限；接收端额外授予**悬浮窗**权限。
3. 在系统设置里把应用加入**自启动 / 后台白名单**。

### 4.4 配置

| 端 | 填什么 |
|---|---|
| sender | 通道选「邮箱通道」→ 发件邮箱（QQ 邮箱）+ **SMTP 授权码**（邮箱网页设置里开启 POP3/SMTP 服务后生成）→ 保存 |
| receiver | 同一个邮箱 + **POP3 授权码**（与 SMTP 同一个码）→ 打开「邮箱轮询」→ 保存并启动服务 |

---

## 五、实测验收（2026-10-14，真机）

| 项 | 读数 |
|---|---|
| 真机端到端 | 副机收到短信 → 发送端 SMTP 发出 → 邮箱 → 接收端 POP3 取回 → 记录 + 横幅通知 + 悬浮窗；一个轮询周期内到位（默认 20 秒，本次 26 秒） |
| 过滤 | 正例 4 条转发；反例 2 条不转（`无关键词且无验证码数字`、`命中排除词:退订`） |
| 重试 | 通道指向死端口：`转发失败…30 秒后重试` → 30 秒后自动成功；跨进程重启后队列里未发出的条目也被补发 |
| 开机存活 | 重启后 3 分 49 秒由开机广播自动拉起服务（全程无手动操作）；窗口期内到达的短信会被 ROM 丢弃 |
| 端到端时延（局域网通道） | 同机「收到 → 已转发」16~28 ms |

详细的原始读数与判据对照见 `docs/`。

---

## 六、踩过的坑（都写进代码注释与 docs 了）

1. **`createSocket(host, port)` 的 TCP connect 没有超时**：对端黑洞化（隧道还在、监听器重启）时线程**永久阻塞且一句报错都不打**——轮询线程就这么静默死掉。正解：先 `Socket().connect(InetSocketAddress(host, port), 8000)`，再 `createSocket(socket, host, port, true)` 包 SSL。
2. **POP3 轮询误删用户其他邮件**：初版把「不是转发格式」的邮件也执行了 `DELE`，等于把用户邮箱里的其他来信一并删掉。规矩：**删除动作只能作用于自己产生的数据**；其余一律「只记 UIDL 见过，绝不动」。
3. **新装的 App 处于 stopped 状态**：`adb shell am broadcast` 打不进它的接收器，且没有任何报错；调试期需先 `am start` 一次。
4. **别靠 logcat 验收国产 ROM**：缓冲区常常只留 E 级；本项目的做法是**应用内自带日志区**（写本地库）+ 数据库导出脚本。

---

## 七、已知限制

- **双卡**：只在单卡（SIM1）环境验证过，SIM2 路径做了兜底但未实测。
- **图标伪装 / 勿扰策略**：未实现（适合老人机的隐藏图标、通知静默等）。
- **邮箱通道的时延**下界是轮询间隔（可调 5~600 秒）；要更快请用局域网通道。
- **POP3 是「取走即删」语义**：多客户端同时读同一个邮箱时慎用（IMAP 更合适）。
- **不上应用商店**：读取/转发短信属于敏感权限，商店必拒；本项目定位侧载自用。

---

## 八、参考项目与出处（Prior art）

设计阶段的调研对象，**只参考架构思路，未复制任何代码**：

| 项目 | 参考了什么 |
|---|---|
| [pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder)（[Gitee 镜像](https://gitee.com/pp/SmsForwarder)） | **主要参考**：监控短信/来电/通知并按规则转发到多种通道的整体形态；「接收与上报解耦 / 通道适配器 / 失败落盘重试」三条设计原则。需要现成方案请优先用它 |
| [bogkonstantin/android_income_sms_gateway_webhook](https://github.com/bogkonstantin/android_income_sms_gateway_webhook) | 极简「收到短信 → POST webhook」骨架 |
| [eye-solution/sms-forwarder](https://github.com/eye-solution/sms-forwarder) | 手机 ↔ 自建服务端的形态 |
| [ma-weihao/MessageForward](https://github.com/ma-weihao/MessageForward) | 「短信互转」路线 |
| [imTigger/SMS2Telegram](https://github.com/imTigger/SMS2Telegram) | SMS → IM 机器人的通道形态 |
| [ihciah/AndroidSMSRelay](https://github.com/ihciah/AndroidSMSRelay) | 需 root 的 adb 读短信路线（未采用） |

各项目许可证以各自仓库为准。本仓为独立实现。

---

## 九、合规与免责

- **只在你拥有、或已获明确授权（例如家人的手机）的设备上使用**。转发他人短信可能违法。
- 验证码属于敏感凭据：一旦经手本工具，请确保**通道两端都是你自己的设备或你自己的账号**（默认设计就是把中转放在你自己的邮箱里）。
- 本项目按 MIT 许可「原样」提供，不对误用、数据丢失或任何后果负责。

---

# English

**SMS code relay — a pair of Android apps that move verification codes from a secondary phone to the phone in your hand.** No server, no shared Wi-Fi requirement, no third-party relay: the transport is *your own mailbox* (SMTP out from the sender, POP3 polling on the receiver).

- `sender/` — runs on the phone that holds the SIM: receives SMS, filters by rules, queues locally with exponential backoff, and forwards through a pluggable channel (email / webhook / Bark / local notification).
- `receiver/` — runs on your phone: polls the mailbox (POP3 + UIDL dedupe) or listens for LAN pushes, then shows a high-priority notification plus a **floating overlay** that sits on top of full-screen apps, with one-tap copy.
- `tools/` — mock SMTP+POP3, a webhook receiver, and DB dump scripts for offline end-to-end testing.

Pure Kotlin, **zero third-party dependencies** (hand-written minimal HTTP server, SMTP client and POP3 client), ~1500-1700 lines and ~670 KB per app.

### Measured findings worth reading before you build your own

1. **Third-party SMS sending is unusable on Chinese OEM ROMs**: ColorOS 13.1 shows a per-message system confirmation (window title `Permission Intercept`) and rejects `cmd role add-role-holder android.app.role.SMS`. Hence the mailbox transport.
2. **Reachability from two Chinese mobile lines**: `smtp.qq.com` / `pop.qq.com` / `api.day.app` / `qyapi.weixin.qq.com` / `gitee.com` are reachable; `ntfy.sh`, `broker.emqx.io`, `pushplus.plus` are **not** (ping fails outright). Bark / ServerChan / WeCom bots can only push — a receiver app cannot pull from them.
3. **ROM behaviours that break keep-alive**: `adb install` wedges on ColorOS (install session committed but never applied); broadcasts do not cold-start a stopped app (`OplusAppStartupManager: sikp broadcast for proc does not exist`); `BOOT_COMPLETED` is delivered ~3m49s late, so codes arriving in that window are dropped by the ROM.
4. **Two bugs worth avoiding**: `SSLSocketFactory.createSocket(host, port)` performs a TCP connect with **no timeout** — a black-holed peer hangs the polling thread forever with no error. And never `DELE` messages in a POP3 poller that you did not produce yourself.

### Build

```bash
cd sender   && ./gradlew :app:assembleRelease
cd receiver && ./gradlew :app:assembleRelease
```

JDK 17 + Android SDK 35 + AGP 9.3.1 (AGP 9 has built-in Kotlin support; no `kotlin-android` plugin needed). Use `org.gradle.java.home` in `gradle.properties` if your default JDK is newer.

### Credits

Functionally this is not a new problem: see [pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder) first — it is far more complete. This repository only borrows architectural ideas (no code copied); the transport survey, the mailbox-as-relay design, the zero-dependency implementation and the measured findings above are ours.

### Disclaimer

Use it only on devices you own or are explicitly authorised to use. Verification codes are credentials: keep both ends of the channel on your own devices/accounts. MIT licensed, provided as is.
