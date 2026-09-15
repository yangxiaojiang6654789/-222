# -*- coding: utf-8 -*-
"""
全天录音云端流水线核心逻辑：
  1. 从网盘读取模型设置 _settings.json（网页端可改），覆盖本地默认
  2. 从 WebDAV（PikPak）拉取当天未处理的录音
  3. 语音转写（默认 DashScope 文件转写：说话人分离 + 时间戳；可切 OpenAI 兼容接口）
  4. 逐字转写原文保存到网盘 transcripts/YYYY-MM-DD.json（供网页端查询）
  5. 用 /chat/completions 汇总成每日总结，写回网盘 summaries/YYYY-MM-DD.md + SMTP 发邮件
状态保存在网盘目录下的 _state.json 中，天然幂等、可断点续跑。
"""
import datetime
import json
import os
import re
import smtplib
import time
from email.header import Header
from email.mime.text import MIMEText

import requests
import yaml

SUMMARY_SYSTEM = "你是一名专业的会议纪要助手，擅长从一天的通话与对话录音中提炼重要信息，输出结构清晰、重点突出、可直接阅读的中文总结。"

SUMMARY_PROMPT = """请根据下面当天全部对话的转写文字，整理出一份「每日重要对话总结」，要求：

1. **今日要点**：用 3-6 条列出当天发生的最重要的事、讨论的核心议题。
2. **关键对话摘要**：按主题分组，简述每个主题讨论了什么、结论是什么。
3. **待办与承诺**：列出所有明确提到的待办事项、约定、承诺、截止时间。
4. **风险与提醒**：指出需要注意、可能遗漏或有风险的事项。

说明：转写文字已按「[时间] 说话人N: 内容」的格式标注了说话人和时间，总结时可适当引用「大约几点、谁说了什么」以增强信息量。要求用中文，条理清晰，如果某类没有内容就写「无」。直接输出总结正文，不要客套。"""


class Config:
    def __init__(self, d):
        self.d = d or {}

    def get(self, *path, default=None):
        cur = self.d
        for p in path:
            if isinstance(cur, dict) and p in cur:
                cur = cur[p]
            else:
                return default
        return cur

    def apply(self, section, values):
        """用网盘 settings 覆盖某一段配置（如 asr/llm）"""
        if values:
            self.d.setdefault(section, {}).update(values)


def load_config(path):
    with open(path, "r", encoding="utf-8") as f:
        return Config(yaml.safe_load(f))


class WebDAV:
    def __init__(self, base_url, username, password, folder):
        self.base_url = (base_url or "").rstrip("/")
        self.auth = (username, password)
        self.folder = (folder or "").strip("/")

    def _url(self, rel=None):
        u = "%s/%s" % (self.base_url, self.folder)
        if rel:
            u += "/" + rel.lstrip("/")
        return u

    def _propfind_names(self, rel=None):
        r = requests.request("PROPFIND", self._url(rel), auth=self.auth,
                             headers={"Depth": "1"}, timeout=60)
        r.raise_for_status()
        hrefs = re.findall(r"<[^>]*href[^>]*>(.*?)</[^>]*href>", r.text, flags=re.I | re.S)
        names = []
        for href in hrefs:
            n = requests.utils.unquote(href.strip()).rstrip("/").split("/")[-1]
            if n:
                names.append(n)
        return names

    def list(self):
        """列出目录下所有 .m4a 文件名"""
        names = self._propfind_names(None)
        return sorted(set(n for n in names if n.endswith(".m4a")))

    def list_dir(self, rel):
        """列出子目录下所有条目名（排除子目录自身）"""
        names = self._propfind_names(rel)
        self_name = (rel or "").strip("/").split("/")[-1]
        return sorted(set(n for n in names if n != self_name))

    def mkcol(self, rel):
        """创建子目录（已存在则忽略）"""
        try:
            r = requests.request("MKCOL", self._url(rel), auth=self.auth, timeout=30)
            r.raise_for_status()
        except Exception:
            pass  # 405 已存在等错误，均忽略

    def download(self, name):
        r = requests.get(self._url(name), auth=self.auth, timeout=180)
        r.raise_for_status()
        return r.content

    def read_text(self, rel):
        r = requests.get(self._url(rel), auth=self.auth, timeout=60)
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return r.text

    def write_text(self, rel, text):
        data = text.encode("utf-8")
        r = requests.put(self._url(rel), data=data, auth=self.auth,
                         headers={"Content-Type": "text/markdown; charset=utf-8"}, timeout=60)
        r.raise_for_status()

    def read_json(self, name):
        try:
            r = requests.get(self._url(name), auth=self.auth, timeout=60)
            if r.status_code == 200:
                return json.loads(r.text)
        except Exception:
            pass
        return {}

    def write_json(self, name, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        r = requests.put(self._url(name), data=data, auth=self.auth,
                         headers={"Content-Type": "application/json"}, timeout=60)
        r.raise_for_status()


def transcribe_openai(cfg, audio_bytes, filename):
    """OpenAI 兼容 /audio/transcriptions（纯文本，无说话人/时间戳）"""
    base = cfg.get("asr", "base_url", default="").rstrip("/")
    url = "%s/audio/transcriptions" % base
    headers = {"Authorization": "Bearer %s" % cfg.get("asr", "api_key", default="")}
    data = {"model": cfg.get("asr", "model", default="qwen-audio-asr")}
    if cfg.get("asr", "language"):
        data["language"] = cfg.get("asr", "language")
    files = {"file": (filename, audio_bytes, "audio/mp4")}
    r = requests.post(url, headers=headers, data=data, files=files, timeout=300)
    r.raise_for_status()
    return r.json().get("text", "").strip()


def _dashscope_base(cfg):
    b = cfg.get("asr", "base_url", default="").strip()
    return (b or "https://dashscope.aliyuncs.com").rstrip("/")


def _dashscope_upload(cfg, audio_bytes, filename):
    """上传音频到 DashScope 临时存储，返回 oss:// URL"""
    base = _dashscope_base(cfg)
    api_key = cfg.get("asr", "api_key", default="")
    model = cfg.get("asr", "model", default="paraformer-v2")
    r = requests.get(base + "/api/v1/uploads",
                     headers={"Authorization": "Bearer " + api_key},
                     params={"action": "getPolicy", "model": model}, timeout=60)
    r.raise_for_status()
    d = r.json()["data"]
    key = "%s/%s" % (d["upload_dir"].rstrip("/"), filename)
    files = {
        "OSSAccessKeyId": (None, d["oss_access_key_id"]),
        "Signature": (None, d["signature"]),
        "policy": (None, d["policy"]),
        "x-oss-object-acl": (None, d.get("x_oss_object_acl", "private")),
        "x-oss-forbid-overwrite": (None, d.get("x_oss_forbid_overwrite", "true")),
        "key": (None, key),
        "success_action_status": (None, "200"),
        "file": (filename, audio_bytes, "application/octet-stream"),
    }
    r = requests.post(d["upload_host"], files=files, timeout=180)
    if r.status_code != 200:
        raise Exception("dashscope upload failed: %s" % r.text[:200])
    return "oss://" + key


def _dashscope_transcribe_segments(cfg, audio_bytes, filename):
    """DashScope 文件转写：说话人分离 + 时间戳，返回 segments 列表"""
    base = _dashscope_base(cfg)
    api_key = cfg.get("asr", "api_key", default="")
    model = cfg.get("asr", "model", default="paraformer-v2")

    oss_url = _dashscope_upload(cfg, audio_bytes, filename)

    parameters = {}
    if cfg.get("asr", "diarization", default=True):
        parameters["diarization_enabled"] = True
    if cfg.get("asr", "speaker_count"):
        parameters["speaker_count"] = int(cfg.get("asr", "speaker_count"))
    if cfg.get("asr", "timestamps", default=True):
        parameters["timestamp_alignment_enabled"] = True
    lang = cfg.get("asr", "language")
    if lang:
        parameters["language_hints"] = [lang]

    headers = {
        "Authorization": "Bearer " + api_key,
        "Content-Type": "application/json",
        "X-DashScope-Async": "enable",
        "X-DashScope-OssResourceResolve": "enable",
    }
    body = {"model": model, "input": {"file_urls": [oss_url]}, "parameters": parameters}
    r = requests.post(base + "/api/v1/services/audio/asr/transcription",
                      headers=headers, json=body, timeout=60)
    r.raise_for_status()
    task_id = r.json()["output"]["task_id"]

    result_url = None
    for _ in range(120):
        time.sleep(5)
        q = requests.get(base + "/api/v1/tasks/" + task_id,
                         headers={"Authorization": "Bearer " + api_key}, timeout=30)
        q.raise_for_status()
        out = q.json()["output"]
        status = out.get("task_status")
        if status == "SUCCEEDED":
            result_url = out["results"][0]["transcription_url"]
            break
        if status in ("FAILED", "CANCELED"):
            raise Exception("dashscope transcribe task %s" % status)
    if not result_url:
        raise Exception("dashscope transcribe task timeout")

    tr = requests.get(result_url, timeout=60)
    tr.raise_for_status()
    return _parse_segments(tr.json())


def _parse_segments(tj):
    segs = []
    for tr in tj.get("transcripts", []):
        for s in tr.get("sentences", []):
            spk = s.get("speaker_id", s.get("speaker", s.get("spk", "0")))
            text = (s.get("text") or "").strip()
            if not text:
                continue
            segs.append({
                "speaker": str(spk),
                "start": int(s.get("begin_time", 0) or 0),
                "end": int(s.get("end_time", 0) or 0),
                "text": text,
            })
    return segs


def transcribe_segments(cfg, audio_bytes, filename):
    """按 asr.provider 分发，统一返回 segments 列表"""
    provider = cfg.get("asr", "provider", default="dashscope")
    if provider == "openai":
        text = transcribe_openai(cfg, audio_bytes, filename)
        return [{"speaker": "0", "start": 0, "end": 0, "text": text}] if text else []
    return _dashscope_transcribe_segments(cfg, audio_bytes, filename)


def fmt_ts(ms):
    s = int(ms) // 1000
    return "%02d:%02d" % (s // 60, s % 60)


def segments_to_text(segments):
    lines = []
    for seg in segments:
        lines.append("[%s] 说话人%s: %s" % (fmt_ts(seg["start"]), seg["speaker"], seg["text"]))
    return "\n".join(lines)


def summarize(cfg, text):
    """OpenAI 兼容 /chat/completions"""
    base = cfg.get("llm", "base_url", "").rstrip("/")
    url = "%s/chat/completions" % base
    headers = {
        "Authorization": "Bearer %s" % cfg.get("llm", "api_key", ""),
        "Content-Type": "application/json",
    }
    body = {
        "model": cfg.get("llm", "model", "qwen-plus"),
        "messages": [
            {"role": "system", "content": SUMMARY_SYSTEM},
            {"role": "user", "content": SUMMARY_PROMPT + "\n\n当天对话转写如下：\n\n" + text},
        ],
        "temperature": 0.3,
    }
    r = requests.post(url, headers=headers, json=body, timeout=300)
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


def summarize_chunked(cfg, transcript, max_chars=12000):
    """超长文本分段总结后再合并，避免超出模型上下文"""
    if len(transcript) <= max_chars:
        return summarize(cfg, transcript)
    chunks = []
    cur = ""
    for line in transcript.split("\n"):
        if len(cur) + len(line) > max_chars and cur:
            chunks.append(cur)
            cur = ""
        cur += line + "\n"
    if cur:
        chunks.append(cur)
    partials = []
    for i, ch in enumerate(chunks):
        partials.append(summarize(cfg, "（第 %d/%d 部分）\n%s" % (i + 1, len(chunks), ch)))
    return summarize(cfg, "以下是分段摘要，请合并成一份完整、不重复的每日总结：\n\n" + "\n\n".join(partials))


def send_email(cfg, subject, body):
    host = cfg.get("email", "smtp_host")
    port = int(cfg.get("email", "smtp_port", 465))
    user = cfg.get("email", "username")
    pw = cfg.get("email", "password")
    to = cfg.get("email", "to")

    msg = MIMEText(body, "plain", "utf-8")
    msg["Subject"] = Header(subject, "utf-8")
    msg["From"] = user
    msg["To"] = to

    if port == 465:
        s = smtplib.SMTP_SSL(host, port, timeout=30)
    else:
        s = smtplib.SMTP(host, port, timeout=30)
        s.starttls()
    s.login(user, pw)
    s.sendmail(user, [to], msg.as_string())
    s.quit()


def run(cfg):
    wd = WebDAV(
        cfg.get("webdav", "base_url"),
        cfg.get("webdav", "username"),
        cfg.get("webdav", "password"),
        cfg.get("webdav", "folder"),
    )

    # 从网盘读取模型设置（网页端可改），覆盖本地默认
    settings = wd.read_json("_settings.json")
    if settings.get("asr"):
        cfg.apply("asr", settings["asr"])
    if settings.get("llm"):
        cfg.apply("llm", settings["llm"])

    # 确保总结目录存在
    wd.mkcol("summaries")

    state = wd.read_json("_state.json")
    processed = set(state.get("processed", []))

    files = wd.list()
    new_files = [f for f in files if f not in processed]
    if not new_files:
        return {"status": "no_new_files", "files": 0}

    all_segments = []
    failed = []
    for name in new_files:
        try:
            data = wd.download(name)
            segs = transcribe_segments(cfg, data, name)
            for s in segs:
                s["file"] = name
            all_segments.extend(segs)
            processed.add(name)
        except Exception as e:
            failed.append(name)
            print("transcribe failed %s: %s" % (name, e))

    state["processed"] = sorted(processed)
    wd.write_json("_state.json", state)

    if not all_segments:
        return {"status": "transcribed_nothing", "files": 0, "failed": failed}

    date = datetime.datetime.now().strftime("%Y-%m-%d")

    # 保存逐字转写原文（带说话人+时间戳），供网页端查询
    saved_transcript = True
    try:
        wd.mkcol("transcripts")
        wd.write_json("transcripts/%s.json" % date, {"date": date, "segments": all_segments})
    except Exception as e:
        saved_transcript = False
        print("save transcript failed: %s" % e)

    transcript = segments_to_text(all_segments)
    summary = summarize_chunked(cfg, transcript)

    # 写回总结，供网页端查看历史
    saved = True
    try:
        wd.write_text("summaries/%s.md" % date, summary)
    except Exception as e:
        saved = False
        print("save summary failed: %s" % e)

    subject_tpl = cfg.get("email", "subject", default="【每日录音总结】{date}")
    subject = subject_tpl.replace("{date}", date)
    try:
        send_email(cfg, subject, summary)
    except Exception as e:
        print("send email failed: %s" % e)

    return {"status": "ok", "files": len(new_files), "failed": failed,
            "segments": len(all_segments), "summary_len": len(summary),
            "saved": saved, "saved_transcript": saved_transcript}
