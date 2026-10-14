# 副卡短信转发 · 副端（sms-relay）

副机（Android 13）上跑的转发 App：**收到短信 → 规则过滤 → 本地落盘排队 → 按通道发出**，发送失败按指数退避重试；常驻前台服务 + 开机自启 + JobScheduler 看护。

上位文档是 `D:\备用\SMS转发系统-开发交接文档..md`（需求、验收清单、风险都出自它）；本仓实现其中的「副端」（副机侧），主机侧接收端尚未开始。

---

## 一、现状（v0.1.0）

| 能力 | 实现位置 | 状态 |
|---|---|---|
| 短信接收（长短信拼接、双卡归属） | `SmsReceiver.kt` / `Ingest.kt` | 已真机验 |
| 规则过滤（包含词 / 排除词 / 数字兜底） | `RuleEngine.kt` | 已真机验 |
| 本地落盘队列 + 指数退避重试（重试窗口 10 分钟） | `Db.kt` / `RelayService.kt` | 已真机验 |
| 通道适配器（webhook / Bark / 本机通知） | `Sinks.kt` | 已真机验 |
| 前台服务 + 常驻通知 + 统计 | `RelayService.kt` | 已真机验 |
| 开机自启 + 15 分钟看护任务 | `BootReceiver.kt` / `WatchdogJobService.kt` | 已真机验 |
| 设置/记录/日志单页 UI | `MainActivity.kt` | 已真机验 |
| debug 专属测试口（adb 注入假短信、改配置） | `src/debug/` | 只进 debug 包 |

技术选型：**零第三方依赖**（只用 Android 框架 API + 平台内置 org.json），APK 683 KB；纯 Kotlin，用 AGP 9 自带的 Kotlin 编译，不额外引 kotlin-android 插件。

---

## 二、目录

```
sms-relay/
├─ app/src/main/java/com/elysia/smsrelay/     源码（Kotlin）
│   ├─ App.kt / Build0.kt / Logx.kt           应用入口、版本、日志
│   ├─ Model.kt / Fmt.kt / Prefs.kt           数据模型、时间格式、设置
│   ├─ Db.kt                                  SQLite：msg（队列+记录）/ log
│   ├─ RuleEngine.kt / SimUtil.kt / Ingest.kt 规则、双卡、入口收口
│   ├─ Sinks.kt                               通道适配器
│   ├─ ServiceController.kt                   起停服务与 JobScheduler
│   ├─ SmsReceiver.kt / BootReceiver.kt       两个广播接收器
│   ├─ RelayService.kt / WatchdogJobService.kt 前台服务 / 看护与重试
│   └─ MainActivity.kt                        设置与自检界面
├─ app/src/debug/                             只编进 debug 包的测试入口
├─ dist/                                      交付 APK（release / debug 各一份）
├─ tools/Make-SmsRelayKey.ps1                 生成 release 签名 key
└─ _test/ _shots/                             开发期脚本与截图（不属交付物）
```

---

## 三、构建

环境（本机已就位）：JDK 17 `D:\android\jdk17`、Android SDK `D:\android\sdk`（platform-35 / build-tools 35.0.0）、Gradle 9.5.0（wrapper 自带）、AGP 9.3.1。

```powershell
cd D:\dshproject\sms-relay
.\gradlew.bat :app:assembleRelease      # 产物 app/build/outputs/apk/release/app-release.apk
```

- `gradle.properties` 里用 `org.gradle.java.home` 把 Gradle 钉到 JDK 17（本机默认 java 是 25，Gradle/AGP 跑不动）。
- 签名口令只存在于工作区之外的 `D:\android\keys\smsrelay-release.properties`；工程里没有任何口令。换 key：改 `SMSRELAY_KEYSTORE_PROPS` 环境变量或 `app/build.gradle` 里的默认路径；重新生成用 `tools\Make-SmsRelayKey.ps1`。
- 重复出包可加 `--offline`（依赖已在 `~/.gradle/caches`）。

---

## 四、真机部署（目标机 ColorOS 13 / Android 13）

1. **侧载**：把 `dist/sms-relay-v0.1.0-release.apk` 放进手机存储，用「文件管理」点开安装。
   ⚠ 本机实测：`adb install` 在 ColorOS 13.1 上走「电脑端安装」通道，会卡死 install session（见第六节），**手机端侧载才是可靠路径**。
2. **运行时权限**：短信、电话（双卡识别）、通知。App 首页「① 授予权限」一键申请。
3. **保活三开关**（ColorOS：设置 → 应用 → 本应用 → 耗电行为控制 / 权限隐私）：
   - 允许应用自启动 = **开启**（不开则开机广播收不到、服务不会自己回来）
   - 允许完全后台行为 = **开启**
   - 允许应用关联启动 = **开启**
4. **电池白名单**：App 首页「② 电池白名单」。
5. 配置通道（第五节），发一条测试短信看「记录」区。

---

## 参考项目与出处（Prior art）

本项目的功能面（收短信 → 规则过滤 → 队列重试 → 多通道转发 → 前台服务保活）**并非新问题**，已有成熟开源实现。以下是设计阶段的调研出处，**只参考了架构思路，未复制任何代码**（本仓全部 Kotlin 从零写就，且零第三方依赖）：

| 项目 | 关系 |
|---|---|
| [pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder)（[Gitee 镜像](https://gitee.com/pp/SmsForwarder)） | **主要参考**：13 种转发通道、规则引擎、服务端+客户端双向模式的整体形态，以及「接收与上报解耦 / 通道适配器 / 失败落盘重试」这几条设计原则。若只需要现成可用，**优先用它**，不必自研。 |
| [bogkonstantin/android_income_sms_gateway_webhook](https://github.com/bogkonstantin/android_income_sms_gateway_webhook) | 极简「收到短信 → POST 到 webhook」的最小骨架参考 |
| [eye-solution/sms-forwarder](https://github.com/eye-solution/sms-forwarder) | 手机 ↔ 自建服务端一条龙的形态参考 |
| [ma-weihao/MessageForward](https://github.com/ma-weihao/MessageForward) | 「短信互转」路线的参考 |
| [imTigger/SMS2Telegram](https://github.com/imTigger/SMS2Telegram)、[Spirit532/SMSForwarder](https://github.com/Spirit532/SMSForwarder) | SMS → Telegram 通道的参考 |
| [ihciah/AndroidSMSRelay](https://github.com/ihciah/AndroidSMSRelay) | 需 root 的 adb 读短信路线（本项目未采用） |

各项目的许可证以各自仓库声明为准；本项目未包含、也未改编它们的任何源码。

### 本仓自己的东西（与上面那些的差异）

1. **通道选型的实测数据**（§通道配置一节）：国内移动网络下 `ntfy.sh` / `broker.emqx.io` 不可达；`api.day.app`(Bark) / Server酱 / 企业微信机器人只能发不能拉；**ColorOS 对第三方 App 发短信逐条弹系统确认**（`Permission Intercept`，`cmd role` 授角色被拒）——这些是排查出来的事实，直接决定了「哪些通道能用」。
2. **邮箱通道（SMTP 发 → 自己邮箱 → POP3 拉）**：把用户自己的邮箱当中转，避开第三方服务器与运营商短信确认，任何网络下都能配对；正文前缀识别 + UIDL 去重 + 只删自己那封。
3. **零依赖实现**：不用 OkHttp/Retrofit/WorkManager，自己写了极简 HTTP 接收端、SMTP 客户端、POP3 客户端（含 base64 正文与 RFC2047 主题解析），APK 683 KB。
4. **可复现的验法与踩坑**：`createSocket` 无 connect 超时导致线程静默假死；POP3 轮询误删用户其他邮件的数据事故与修法；mock SMTP+POP3 + `adb reverse` 的离线端到端验法；debug-only 广播测试口。

---

## 五、通道配置

> **2026-10-14 追加结论**：这台 ColorOS 13.1 上「短信直发」通道**不可用**——ColorOS 对第三方 App 发短信逐条弹系统确认框（`Permission Intercept`），不点即拒；`cmd role add-role-holder android.app.role.SMS` 也被拒（应用未声明默认短信应用所需组件）。因此新增**邮箱通道**（v0.3.0）：把转发内容用邮件发出去，主端 POP3 轮询取走，不受任何 ROM 策略影响，也不依赖第三方服务器。
>
> 实测可达性（两台手机）：`smtp.qq.com` / `imap.qq.com` ✔、`api.day.app` ✔、`qyapi.weixin.qq.com` ✔、`gitee.com` ✔；**`ntfy.sh` / `broker.emqx.io` ✘**（国内网络不通）。


| 通道 | 说明 | 需要填 |
|---|---|---|
| 自建 / 任意 webhook | POST 一段 JSON（见下） | 通道地址；可选共享口令（同时进 `X-Token` 与 `Authorization: Bearer` 头） |
| Bark | GET `{base}/{title}/{body}` | Bark 地址（通常已含 key） |
| 邮箱通道（v0.3.0 新增，推荐） | SMTP(SSL, 默认 smtp.qq.com:465) 发一封邮件，正文是 `[转发]发件人\|验证码\|SIM\|正文`；主端 POP3 轮询取走 | 发件邮箱、SMTP 授权码（收件邮箱留空=发给自己） |
| 短信直发（ColorOS 上不可用） | 把同样格式用短信发给主机号 | 主机号码 |
| 只在本机留通知 | 不外发，用于没有服务端时验证整条链路 | — |

webhook 负载字段：

```json
{"proto":1,"app":"sms-relay","version":"0.1.0","device":"OPPO PDSM00",
 "from":"+8613800000000","body":"您的验证码是 114514…","sim":0,"sim_label":"SIM1(中国移动)",
 "sub_id":1,"code":"114514","ts":1791959156157,"ts_text":"10-14 14:32:30","received_ms":1791959156161}
```

**开发期自测链路**（不依赖局域网、不依赖公网）：PC 上跑 `_test/webhook-server.mjs`（监听 127.0.0.1:8787，把请求逐条落盘），手机侧执行 `adb reverse tcp:8787 tcp:8787`，App 通道地址填 `http://127.0.0.1:8787/ingest`。

---

## 六、ColorOS 上的坑（本机实测，2026-10-14）

1. **`adb install` 会卡死**：ColorOS 把 adb 安装接到自己的「电脑端安装」通道（install session 里 `installerPackageName=pc`），失败时界面显示「安装失败 / 完成」，而 session 停在 `mCommitted=true, mSealed=true, mSessionApplied=false`，后续每次安装都排在这一堆会话后面一起卡。绕法：**改用手机端侧载**。这一条与 APK 本身无关——同一个包手机端能装、adb 端装不上。
2. **shell 被收走了两个权限**：`pm grant` 报 `Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS`；`settings put global …` 报缺 `WRITE_SECURE_SETTINGS`。运行时权限只能在手机上点（App 首页有按钮）。adb 通道正常时可用 `adb install -g` 一次性授予。
3. **保活三开关默认全关**（见第四节第 3 条），首装后必须手动打开；否则重启后服务不会自己回来。
4. **开机广播是延迟投递的**：系统 15:00:28 post，App 15:04:17 才收到（约 3 分 49 秒）。因此**重启后 3~4 分钟内到达的短信会丢**——App 进程还没被拉起，而 ColorOS 不为广播冷启动它，实证：
   ```
   15:03:39.203 W OplusAppStartupManager: sikp broadcast for proc does not exist,
                pkg:com.elysia.smsrelay, action:android.provider.Telephony.SMS_RECEIVED
   ```
   窗口之外一切正常（服务起来后长驻）。缓解：重启后手动开一次 App；或让副机长期不断电。