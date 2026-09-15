FROM python:3.11-slim

WORKDIR /code
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 常驻循环模式；如需定时触发，可改用 FC 定时触发器
CMD ["python", "main.py", "config.yaml", "--loop"]
