# -*- coding: utf-8 -*-
"""阿里云函数计算（FC）入口"""
import os

from pipeline import load_config, run


def handler(event, context):
    path = os.environ.get("CONFIG_PATH", "/code/config.yaml")
    cfg = load_config(path)
    return run(cfg)
