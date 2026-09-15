# 全天录音 · 云端转写总结系统

华强北方形安卓表（Noemie OS / 安卓 9）全天环境录音 → 充电时上传 PikPak → 阿里云函数计算定时转写总结 → 邮件推送 + 网页端查看。

```
手表(录音) ──充电上传──▶ PikPak WebDAV ◀──读写── 阿里云FC(转写+总结) ──▶ QQ邮箱
                              ▲
                              └──读写── 网页控制台(你的服务器)：模型设置 / 总结历史 / 日历
```

---

## 一、项目结构

```
all-day-recorder/   手表端安卓工程（纯 Android SDK，零第三方依赖，可离线构建）
cloud-pipeline/     云端转写总结流水线（Python，可跑阿里云FC / 自建服务器 / Docker）
web/                网页控制台（Flask + Docker，模型设置 / 总结历史 / 日历）
README.md           本手册
```

---

## 二、手表端：构建与侧载 APK

### 1. 构建（电脑上装 Android Studio）

1. 电脑安装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17）。
2. 打开工程目录 `all-day-recorder/`，等 Gradle 同步完成（首次会联网下载 Android 插件，之后离线也行）。
3. 菜单 `Build → Build APK(s)`。
4. 产物在 `all-day-recorder/app/build/outputs/apk/debug/app-debug.apk`。

> 提示：工程已设 `minSdk 28`（安卓9）、`targetSdk 28`，专为你的 Noemie OS 表优化。

### 2. 侧载到手表的几种方式（任选其一）

**方式 A：ADB（最稳）**
1. 手表「设置」里连点系统版本号 7 次开启「开发者选项」，打开「ADB/USB 调试」。
2. 手表与电脑用数据线连接（或同一 WiFi 下用 `adb connect 手表IP:5555`）。
3. 命令行执行：
   ```bash
   adb install app-debug.apk
   ```

**方式 B：手表直接装**
- 把 APK 用网盘/微信传到手表，在手表文件管理器里点开 APK 安装（需允许「未知来源」）。

**方式 C：手机装 APK 推送工具**
- 手机上装「Wear Installer / Easy Fire Tools」，与手表连同一 WiFi，推送 APK。

### 3. 首次使用（关键！）

1. 打开「全天录音」App，**允许麦克风权限**。
2. 点「设置」填入 PikPak WebDAV 的地址/用户名/密码/目录（见下文第四节）。
3. 回主界面点「开始录音」。

### 4. 防止被杀后台（必须做，否则全天录音会断）

Noemie OS 会激进杀后台，请按顺序做：

1. 手表「设置 → 应用/电池 → 全天录音」→ 关闭「省电限制」，设为「无限制/允许后台」。
2. 手表「设置 → 电池」→ 关闭「省电模式」。
3. 主界面点「开始录音」后，下拉通知栏应看到「全天录音中」的常驻通知，说明前台服务已保活。
4. 建议「设置 → 应用 → 全天录音 → 自启动」打开（如手表有该选项），配合开机自启。

---

## 三、手表端：界面与行为

主界面从上到下：

- **状态区**：实时显示录音状态（● 录音中 / ❚❚ 已暂停 / ○ 已停止）、已录时长、今日段数、待上传段数、占用空间、上次上传结果。
- **主控制按钮**：开始录音 → 暂停 → 继续（循环切换）。
- **停止录音**：结束本次会话（暂停后也可停止）。
- **立即上传**：手动触发上传。
- **录音文件**：查看本地录音列表，可单段删除或清空全部。
- **设置**：WebDAV 账号 + 行为选项。

| 项目 | 行为 |
|---|---|
| 录音 | 16kHz 单声道 AAC（约 0.5MB/分），每 10 分钟自动切一段 `.m4a`（时长可调） |
| 音质 | 省电档 / 高音质档，可在设置里切换 |
| 分段时长 | 5 / 10 / 15 / 30 分钟可选 |
| 暂停/继续 | 暂停时结束当前段并停止拾音，继续时开新段 |
| 存储 | 存手表应用私有目录，上传成功后自动删除，不撑爆存储 |
| 上传 | **插上充电器时自动触发**（可在设置里关闭），全部传到 PikPak 指定目录 |
| 仅 WiFi | 可勾选「仅 WiFi 上传」省流量 |
| 上传反馈 | 通知栏显示上传进度，完成后主界面显示「成功 N 段 / 失败 M 段」 |
| 开机 | 默认开机自启录音（可在设置里改） |
| 保活引导 | 设置里有「电池优化白名单」按钮，一键跳转关闭省电限制 |
| 息屏录音 | 前台服务 + 唤醒锁 + 电池白名单检测；未加白名单会在主界面提示「⚠ 未加入电池白名单，息屏可能停止录音」 |
| 低电量保护 | 电量 < 15% 自动停止录音并通知，避免把手表录到关机 |
| 存储保护 | 占用超 80% 告警提醒；可开「自动清理最旧录音」兜底 |
| 录音自愈 | 看门狗每 5 分钟自检，录音服务被系统杀掉后自动拉起 |
| 上传重试 | 充电中自动重试未上传文件；失败会显示具体原因（账号错/网络断等） |

---

## 四、PikPak WebDAV 开启（会员功能）

1. 电脑浏览器登录 [PikPak 网页版](https://mypikpak.com)。
2. 进入「设置 → 访问与集成（Access & Integrations）→ WebDAV」，开启并「创建凭证」。
3. 记下三个信息：
   - 地址：`https://dav.mypikpak.com`
   - 用户名（WebDAV 专用，通常是你邮箱）
   - 密码（WebDAV 独立密码，**不是**登录密码）
4. 把这三项填进手表 App 的「设置」；目录填 `watch-recordings`（会在你网盘根目录下新建此文件夹）。

> **上传自测**：填好后先在手表主界面点「立即上传」，录一小段，去 PikPak 网页版看 `watch-recordings` 文件夹里是否出现 `.m4a` 文件。若能看到即打通。若 PikPak WebDAV 在你账号上不支持写入（早期版本只读），改用坚果云 WebDAV（免费版也稳定支持上传，地址 `https://dav.jianguoyun.com/dav/`），其余配置不变。

---

## 五、通义千问 API Key（转写+总结）

1. 打开[阿里云百炼](https://bailian.console.aliyun.com/)，注册并开通「百炼大模型服务」。
2. 在「API-KEY 管理」创建 API Key（形如 `sk-xxxx`）。
3. 转写默认走 **DashScope 文件转写**（模型 `paraformer-v2`），支持**说话人分离 + 时间戳**；总结用 `qwen-plus`。
4. 把这些填进 `cloud-pipeline/config.yaml`，**或部署网页控制台后在网页端「模型设置」里填**（网页端保存到网盘，流水线运行时自动读取，优先级高于 config.yaml）。

> **模型选择**：网页端「模型设置」提供下拉选择——转写可选 `paraformer-v2` / `sensevoice-v1` / `qwen-audio-3.0-asr-flash-filetrans` / `qwen-audio-asr`，总结可选 `qwen-plus` / `qwen-max` / `qwen-turbo` 等，也可自定义输入任意模型名。
>
> **说话人 + 时间戳**：默认开启（走 DashScope 文件转写接口）。注意模型只能自动区分「说话人 1 / 2 / 3…」，**无法识别具体是谁**；要识别具体身份需另建声纹库。
>
> **纯文本降级**：如不需要说话人/时间戳，可在网页端把「转写方式」改为 OpenAI 兼容接口（走 `/audio/transcriptions`，纯文本，无说话人分离）。

---

## 六、QQ 邮箱 SMTP 授权码

1. 登录 QQ 邮箱网页版 →「设置 → 账户」。
2. 开启「IMAP/SMTP 服务」，按提示发送短信获取「授权码」（一串字母，**不是** QQ 密码）。
3. 填入 `config.yaml` 的 `email.password`。

---

## 七、云端部署（阿里云函数计算 FC）

### 方式 A：控制台手动部署（推荐新手）

1. 登录[阿里云函数计算控制台](https://fcnext.console.aliyun.com/)，选择地域（如杭州）。
2. 「创建函数」→ 运行时选 **Python 3.10**，函数名 `recorder-summary`。
3. 上传代码包：把 `cloud-pipeline/` 里的 `pipeline.py`、`handler.py`、`requirements.txt`、`config.yaml` 四个文件**打包成 zip 上传**。
4. 配置：
   - 入口函数：`handler.handler`
   - 超时时间：`300` 秒
   - 内存：`512 MB`
   - 环境变量：`CONFIG_PATH = /code/config.yaml`
5. 创建**定时触发器**：触发方式「定时触发器」，Cron 填 `0 0 22 * * * *`（每天 22:00，阿里云为「秒 分 时 日 月 星期 年」7 段）。
6. 部署后点「测试运行」验证能否正常出结果。

### 方式 B：Serverless Devs 命令行部署

```bash
# 先填好 cloud-pipeline/config.yaml
cd cloud-pipeline
s deploy        # 使用 deploy/s.yaml 的配置（含每天22:00定时触发）
```

### 方式 C：自建服务器 / Docker（不想用阿里云时）

```bash
docker build -t recorder-summary cloud-pipeline
docker run -d --restart=always \
  -e LOOP_INTERVAL=3600 \
  -v $(pwd)/cloud-pipeline/config.yaml:/code/config.yaml \
  recorder-summary
```

或直接在常开的 Windows/Linux 机器上：
```bash
pip install -r cloud-pipeline/requirements.txt
python cloud-pipeline/main.py cloud-pipeline/config.yaml --loop
```

---

## 八、网页端部署（你的服务器，Linux + Docker）

在浏览器里改大模型设置（含模型下拉选择 + 说话人分离开关）、查看总结历史、按日历浏览、**查看每天的逐字转写原文（带说话人 + 时间戳）**。

1. 上传 `web/` 目录到服务器（如 `/opt/recorder-web`）。
2. 编辑 `docker-compose.yml`，填 `WEBDAV_*`、`WEB_PASSWORD`（访问密码）、`SECRET_KEY`。
3. 启动：
   ```bash
   cd /opt/recorder-web
   docker compose up -d --build
   ```
4. 浏览器访问 `http://服务器IP:8000`，输入密码登录。

详细说明见 `web/README.md`。数据约定：模型设置存网盘 `_settings.json`，总结存 `summaries/YYYY-MM-DD.md`（与云端流水线共享）。

> 建议用 Nginx / Caddy 反代 + HTTPS 暴露，或至少限制端口访问。

---

## 九、端到端联调清单

1. ✅ 手表开始录音，主界面显示「● 正在录音」+ 常驻通知。
2. ✅ 插充电器，手表「正在上传录音」通知出现，PikPak 里出现 `.m4a`。
3. ✅ 阿里云 FC 手动触发一次，控制台日志无报错。
4. ✅ 收到 QQ 邮箱的「【每日录音总结】」邮件，内容为当天对话要点。
5. ✅ 网页端登录后，「模型设置」能保存成功。
6. ✅ 网页端「总结历史 / 日历」能看到当天总结。

---

## 十、常见问题

| 问题 | 处理 |
|---|---|
| 录音隔一会儿就停 | 检查第 2.4 节「防止被杀后台」，务必关闭省电限制 |
| 上传失败/通知一闪而过 | 检查 WebDAV 账号密码、目录是否正确；确认是 PikPak 会员 |
| 转写报 401 | `config.yaml` 的 api_key 填错，或未开通百炼服务 |
| 邮件发不出 | QQ 邮箱要用「授权码」而非密码；端口 465 走 SSL |
| 转写文字对不上 | 换转写模型，`asr.model` 试 `paraformer-v2` / `sensevoice-v1` |
| 说话人分不出来 | 确认网页端「转写方式」选 DashScope 文件转写，且勾选「说话人分离」 |
| 网页端看不到转写原文 | 需等流水线跑过后才会生成 `transcripts/` 原文；手动触发一次 FC |
| 总结太长被截断 | 代码已自动分段总结，无需处理 |
| 网页端提示「未配置 WEB_PASSWORD」 | `docker-compose.yml` 里 `WEB_PASSWORD` 环境变量没设 |
| 网页端改设置后流水线没生效 | 流水线在下次运行时才读取；手动触发一次 FC 验证 |

---

## 十一、已知限制

- **通话录音不可用**：安卓 9 对通话录音限制严格，本方案仅录环境声/对话。
- **说话人分离 ≠ 身份识别**：模型只能区分「说话人 1/2/3」，无法知道具体是谁（需另建声纹库）。
- **PikPak WebDAV 依赖会员**：免费账号无法开启 WebDAV。
- **Noemie OS 保活需手动配合**：即使做了前台服务，个别 ROM 仍可能杀进程，需按第 2.4 节设置。
- **转写有一定成本**：全天录音量大，通义千问按量计费（说话人分离 + 时间戳的 paraformer-v2 按音频时长计费），请留意用量。
