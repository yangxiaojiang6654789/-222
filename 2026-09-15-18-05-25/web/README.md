# 全天录音 · 网页控制台

在浏览器里查看/修改**大模型设置**（含模型下拉选择 + 说话人分离开关）、查看**每日总结历史**、按**日历**浏览总结、查看**逐字转写原文（带说话人 + 时间戳）**。
数据全部存在 PikPak WebDAV，与云端流水线共享，本服务无数据库、无状态。

## 功能

- **模型设置**：配置 ASR 转写 / LLM 总结的 base_url、api_key、model，支持下拉选择预设模型或自定义输入；可开关「说话人分离」。保存到网盘 `_settings.json`，云端流水线下次运行自动读取。
- **总结历史**：按日期倒序列表，点开看当天总结。
- **日历**：月视图，有总结的日期高亮，点日期查看。
- **转写原文**：在总结详情里点「查看转写原文」，可看当天逐字转写（每条带时间戳 + 说话人标签）。

## 部署（Linux + Docker，推荐）

1. 把本目录上传到服务器（如 `/opt/recorder-web`）。
2. 编辑 `docker-compose.yml`，填入：
   - `WEBDAV_URL/USER/PASS/FOLDER`：PikPak WebDAV 连接信息（与手表端一致）。
   - `WEB_PASSWORD`：网页访问密码（改成一个强密码）。
   - `SECRET_KEY`：随便填一串随机字符串。
3. 启动：
   ```bash
   cd /opt/recorder-web
   docker compose up -d --build
   ```
4. 浏览器访问 `http://服务器IP:8000`，输入密码登录。
5. 建议用 Nginx / Caddy 反代并加 HTTPS，或至少限制端口访问。

## 裸机运行（不装 Docker）

```bash
cd web
pip install -r requirements.txt
export WEBDAV_URL=https://dav.mypikpak.com
export WEBDAV_USER=你的用户名
export WEBDAV_PASS=你的密码
export WEBDAV_FOLDER=watch-recordings
export WEB_PASSWORD=你的网页密码
export SECRET_KEY=随机字符串
gunicorn -w 2 -b 0.0.0.0:8000 server:app
```

## 数据存储约定（与云端流水线一致）

- `watch-recordings/`：手表上传的音频（WebDAV 根目录即此 folder）
- `watch-recordings/_settings.json`：模型设置
- `watch-recordings/summaries/YYYY-MM-DD.md`：每日总结
- `watch-recordings/transcripts/YYYY-MM-DD.json`：逐字转写原文（说话人 + 时间戳）

## 说明

- 密码登录用 Flask session，退出登录后需重新输入密码。
- 首次启动若网页提示「未配置 WEB_PASSWORD」，说明环境变量没设好。
