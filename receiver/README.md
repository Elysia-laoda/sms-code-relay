# 副卡短信转发 · 主端（sms-recv）

主机（Android 11+）上的接收端：**从副机收验证码 → 高优先级通知 + 悬浮窗 + 一键复制**，并有本地记录可回溯。

配套的发送端是 `D:\dshproject\sms-relay`（副端）。上位文档：`D:\备用\SMS转发系统-开发交接文档..md`。

---

## 一、两条接收通道

| 通道 | 原理 | 适用 |
|---|---|---|
| **邮箱轮询**（主用） | 副端把「发件人｜验证码｜SIM｜正文」用邮件发出，本端按间隔 POP3 拉取 | 两台机在**任何网络**下都可用（各自有网即可），不经第三方服务器，只经你自己的邮箱 |
| 局域网直推 | 副端 webhook POST 到本机 `http://<本机IP>:8788/ingest` | 两台机**同一 Wi-Fi** 时零延迟零费用 |

两条通道汇入同一个处理链：落库 → 高优先级通知（带「复制验证码」按钮）→ 悬浮窗（盖在游戏画面上，可拖动、可点复制、N 秒自动消失）。

**为什么不用短信通道**：ColorOS（OPPO Reno5 Pro 5G / Android 13）对第三方 App 发短信会**逐条弹系统确认框**（`Permission Intercept`），不点即拒；`cmd role add-role-holder android.app.role.SMS` 也被系统拒绝（应用未声明默认短信应用所需组件）。邮箱通道绕开了这条 ROM 策略。

---

## 二、目录

```
sms-recv/
├─ app/src/main/java/com/elysia/smsrecv/
│   ├─ App.kt / Build0.kt / Logx.kt / Fmt.kt     入口、版本、日志、时间格式
│   ├─ Model.kt / Db.kt / Prefs.kt               记录模型、SQLite（code/log/mail_seen）、设置
│   ├─ SmsIn.kt / SmsReceiver.kt                 入口收口 + 短信广播（识别 [转发] 前缀）
│   ├─ MailPoller.kt                             邮箱通道：POP3(SSL) 拉取 + UIDL 去重
│   ├─ HttpServer.kt / RecvService.kt            局域网接收端 + 常驻前台服务
│   ├─ Notifier.kt / Overlay.kt / CopyReceiver.kt 高优先级通知 / 悬浮窗 / 复制动作
│   ├─ NetUtil.kt / BootReceiver.kt              取本机局域网 IP / 开机自启
│   └─ MainActivity.kt                           状态、设置、记录、日志单页
├─ app/src/debug/                                只进 debug 包的 adb 测试口
└─ dist/                                         交付 APK
```

---

## 三、构建

与副端同一套工具链：Gradle 9.5.0（wrapper）+ AGP 9.3.1 + JDK 17（`gradle.properties` 里 `org.gradle.java.home` 钉死）+ compileSdk 35；零第三方依赖。

```powershell
cd D:\dshproject\sms-recv
.\gradlew.bat :app:assembleRelease      # 产物 app/build/outputs/apk/release/app-release.apk
```

签名 key：`D:\android\keys\smsrecv-release.jks` + `.properties`（口令只在该文件里），生成脚本 `tools\Make-SmsRecvKey.ps1`。

---

## 三之二、参考项目与出处

功能面（收推送 / 通知 / 悬浮展示）不新，主要参考同上位项目 **副端仓库** 里的调研：核心参照是 [pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder)（主力参考）与 [bogkonstantin/android_income_sms_gateway_webhook](https://github.com/bogkonstantin/android_income_sms_gateway_webhook)（极简 webhook 骨架）。**未复制任何代码**。

本端自己的东西：邮箱轮询（POP3，UIDL 去重 + 前缀识别）、局域网 HTTP 接收端、悬浮窗/高优先级通知的展示链，以及「非自己产生的邮件绝不删除」这条数据安全约束。

---

## 四、配置（首次）

1. **邮箱通道**（推荐）：
   - 副端与主端填**同一个邮箱**；副端要 SMTP 授权码，主端要 POP3 授权码（QQ 邮箱两处是**同一个授权码**）。
   - QQ 邮箱开启位置：mail.qq.com → 设置 → 账户 → 「POP3/IMAP/SMTP…服务」→ 开启 **POP3/SMTP 服务** → 生成授权码。
   - 本端默认服务器 `pop.qq.com:995`（SSL）；自建/联调可在设置里改地址与端口，并可关掉 TLS（`mail_tls`）。
2. **局域网通道**（可选）：主端点「启动服务」后，状态行显示本机 IP 与端口（默认 8788），把 `http://<IP>:8788/ingest` 填进副端的 webhook 地址即可。
3. **悬浮窗权限**：首页「② 悬浮窗权限」（`SYSTEM_ALERT_WINDOW`），否则只弹通知不显示悬浮窗。
4. **保活**：把本应用加入省电白名单与自启动白名单（vivo：设置 → 电池 → 后台高耗电 → 允许；设置 → 应用与权限 → 自启动）。

---

## 五、已验证的行为（2026-10-14）

| 项 | 读数 |
|---|---|
| 邮箱通道离线端到端 | 副端 SMTP → 邮箱 → 本端 POP3：`邮箱通道取回 1 封` → `收到验证码 code=112233` → `已弹通知：验证码 112233` |
| 局域网直推 | `POST /ingest` 回 `{"ok":true,"got":1}`，记录与通知同步出现 |
| 悬浮窗 | 截图确认：`246810 / SIM1(中国移动) 10086 / 复制 ×` 覆盖在应用之上 |
| 通知 | `pkg=com.elysia.smsrecv channel=code_alert` 高优先级横幅，带「复制验证码」动作 |
| 前缀过滤 | 非 `[转发]` 开头的短信被忽略（`短信不是转发格式，忽略`） |

---

## 六、踩过的坑（都写在代码注释里了）

1. **`createSocket(host, port)` 的 TCP connect 没有超时**：对端黑洞化（隧道还在、监听器重启）时线程会永久阻塞且不报错——轮询线程就是这么悄无声息死掉的。正解：先 `Socket().connect(addr, 8000)` 再包 SSL。
2. **新装的 App 处于 stopped 状态**：`adb shell am broadcast` 打不进去（receiver 收不到，也没有报错），必须先用 `am start` 启动一次。调试期踩了两次。
3. `SSLSocketFactory.getDefault()` 返回的是 `SocketFactory`，要 `as SSLSocketFactory` 才能用 4 参数 `createSocket(socket, host, port, autoClose)`。
4. vivo（FuntouchOS）与 OPPO（ColorOS）一样会拦「广播唤醒没在跑的 App」，所以两端都必须靠前台服务常驻 + 开机自启（并把应用加进 ROM 白名单）。
5. 这台 vivo 的 logcat 基本只留 E 级：不要靠 logcat 验收，用应用内的「日志」区（写本地库）与截图。

---

## 七、后续可做

- 图标伪装 / 隐藏（验收项 7）。
- 通知里直接显示验证码同时自动复制到剪贴板（目前是手动点「复制验证码」）。
- 邮箱通道改用 IMAP（多设备读取时更规范）；POP3 目前是取走即删。
