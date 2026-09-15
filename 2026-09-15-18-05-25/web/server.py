# -*- coding: utf-8 -*-
"""
全天录音 · 网页控制台
- 模型设置（读写网盘 _settings.json）
- 每日总结历史（读网盘 summaries/*.md）
- 日历视图
数据全部存放在 PikPak WebDAV，本服务无本地数据库、无状态。

环境变量（见 .env.example）：
  WEBDAV_URL / WEBDAV_USER / WEBDAV_PASS / WEBDAV_FOLDER
  WEB_PASSWORD / SECRET_KEY / PORT
"""
import json
import os
import re
from functools import wraps

import requests
from flask import (Flask, abort, jsonify, redirect, render_template,
                   request, session, url_for)

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY") or "please-set-a-random-secret-key"


# ---------- WebDAV 客户端 ----------

class Dav:
    def __init__(self):
        self.base = os.environ.get("WEBDAV_URL", "").rstrip("/")
        self.auth = (os.environ.get("WEBDAV_USER", ""),
                     os.environ.get("WEBDAV_PASS", ""))
        self.folder = os.environ.get("WEBDAV_FOLDER", "watch-recordings").strip("/")

    def _url(self, rel=None):
        u = "%s/%s" % (self.base, self.folder)
        if rel:
            u += "/" + rel.lstrip("/")
        return u

    def list_names(self, rel=None):
        r = requests.request("PROPFIND", self._url(rel), auth=self.auth,
                             headers={"Depth": "1"}, timeout=60)
        r.raise_for_status()
        hrefs = re.findall(r"<[^>]*href[^>]*>(.*?)</[^>]*href>", r.text, flags=re.I | re.S)
        names = []
        for h in hrefs:
            n = requests.utils.unquote(h.strip()).rstrip("/").split("/")[-1]
            if n:
                names.append(n)
        return names

    def read(self, rel):
        r = requests.get(self._url(rel), auth=self.auth, timeout=60)
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return r.text

    def write(self, rel, data):
        r = requests.put(self._url(rel), data=data, auth=self.auth,
                         headers={"Content-Type": "text/plain; charset=utf-8"}, timeout=60)
        r.raise_for_status()

    def read_json(self, rel):
        try:
            r = requests.get(self._url(rel), auth=self.auth, timeout=60)
            if r.status_code == 200:
                return r.json()
        except Exception:
            pass
        return {}

    def write_json(self, rel, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        r = requests.put(self._url(rel), data=data, auth=self.auth,
                         headers={"Content-Type": "application/json"}, timeout=60)
        r.raise_for_status()

    def mkcol(self, rel):
        try:
            r = requests.request("MKCOL", self._url(rel), auth=self.auth, timeout=30)
            r.raise_for_status()
        except Exception:
            pass


def dav_client():
    return Dav()


# ---------- 鉴权 ----------

def login_required(view):
    @wraps(view)
    def wrapper(*args, **kwargs):
        if not session.get("authed"):
            return redirect(url_for("login"))
        return view(*args, **kwargs)
    return wrapper


def api_login_required(view):
    @wraps(view)
    def wrapper(*args, **kwargs):
        if not session.get("authed"):
            return jsonify({"error": "unauthorized"}), 401
        return view(*args, **kwargs)
    return wrapper


@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        pwd = request.form.get("password", "")
        expect = os.environ.get("WEB_PASSWORD", "")
        if not expect:
            return render_template("login.html", error="服务端未配置 WEB_PASSWORD，请先设置环境变量")
        if pwd == expect:
            session["authed"] = True
            return redirect(url_for("index"))
        return render_template("login.html", error="密码错误")
    return render_template("login.html", error=None)


@app.route("/logout")
def logout():
    session.pop("authed", None)
    return redirect(url_for("login"))


# ---------- 页面 ----------

@app.route("/")
@login_required
def index():
    return render_template("index.html")


# ---------- 模型设置 API ----------

DEFAULT_SETTINGS = {
    "asr": {
        "provider": "dashscope",        # dashscope=说话人+时间戳 | openai=纯文本
        "base_url": "https://dashscope.aliyuncs.com",
        "api_key": "",
        "model": "paraformer-v2",
        "language": "zh",
        "diarization": True,
        "speaker_count": 0,
        "timestamps": True,
    },
    "llm": {"base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1", "api_key": "", "model": "qwen-plus"},
}


@app.route("/api/settings", methods=["GET"])
@api_login_required
def get_settings():
    s = dav_client().read_json("_settings.json")
    for k, v in DEFAULT_SETTINGS.items():
        s.setdefault(k, v)
    return jsonify(s)


@app.route("/api/settings", methods=["POST"])
@api_login_required
def save_settings():
    data = request.get_json(force=True)
    if not isinstance(data, dict):
        return jsonify({"error": "invalid"}), 400
    dav = dav_client()
    dav.write_json("_settings.json", data)
    return jsonify({"ok": True})


# ---------- 总结历史 API ----------

@app.route("/api/summaries", methods=["GET"])
@api_login_required
def list_summaries():
    try:
        names = dav_client().list_names("summaries")
    except Exception:
        names = []
    dates = sorted([n[:-3] for n in names if n.endswith(".md")], reverse=True)
    return jsonify({"dates": dates})


@app.route("/api/summaries/<date>", methods=["GET"])
@api_login_required
def get_summary(date):
    if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
        abort(400)
    content = dav_client().read("summaries/%s.md" % date)
    if content is None:
        return jsonify({"error": "not found"}), 404
    return jsonify({"date": date, "content": content})


@app.route("/api/transcripts/<date>", methods=["GET"])
@api_login_required
def get_transcript(date):
    """逐字转写原文（带说话人 + 时间戳）"""
    if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
        abort(400)
    data = dav_client().read_json("transcripts/%s.json" % date)
    return jsonify(data)


@app.route("/api/health")
def health():
    return jsonify({"ok": True})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8000")))
