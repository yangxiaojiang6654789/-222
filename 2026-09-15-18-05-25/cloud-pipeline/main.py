# -*- coding: utf-8 -*-
"""本地 / 常驻服务器运行入口

用法：
  python main.py config.yaml --once    # 跑一次后退出
  python main.py config.yaml --loop    # 循环跑（适合自建服务器，间隔由 LOOP_INTERVAL 控制，默认 3600 秒）
"""
import os
import sys
import time

from pipeline import load_config, run


def main():
    config_path = sys.argv[1] if len(sys.argv) > 1 else "config.yaml"
    mode = sys.argv[2] if len(sys.argv) > 2 else "--once"
    cfg = load_config(config_path)

    if mode == "--loop":
        interval = int(os.environ.get("LOOP_INTERVAL", "3600"))
        while True:
            try:
                print(run(cfg))
            except Exception as e:
                print("error:", e)
            time.sleep(interval)
    else:
        print(run(cfg))


if __name__ == "__main__":
    main()
