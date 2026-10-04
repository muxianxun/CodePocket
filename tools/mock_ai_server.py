"""Mock OpenAI-compatible server for testing the CodePocket AI panel.

Run:  python mock_ai_server.py
Then on the PC:  adb reverse tcp:8080 tcp:8080
In the app settings: Base URL = http://127.0.0.1:8080/v1 , model = mock , key = anything
"""

import json
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8080

REPLY = """好的，这是一个 Python 版的计算器骨架：

```python
def calc(expr: str) -> float:
    allowed = set("0123456789+-*/(). ")
    if not set(expr) <= allowed:
        raise ValueError("非法字符")
    return eval(expr, {"__builtins__": {}}, {})


if __name__ == "__main__":
    print(calc("1 + 2 * 3"))
```

要点：
1. 用白名单过滤字符，避免直接 eval 任意代码。
2. 真正的项目应换成 AST 解析。
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            request = json.loads(raw)
        except Exception:
            request = {}

        model = request.get("model", "?")
        messages = request.get("messages", [])
        last_user = ""
        for message in reversed(messages):
            if message.get("role") == "user":
                last_user = message.get("content", "")
                break

        print(f"[mock] POST {self.path}  model={model}  messages={len(messages)}  user={last_user[:60]!r}", flush=True)
        auth = self.headers.get("Authorization", "")
        print(f"[mock] Authorization header: {auth[:20]}{'...' if auth else ' (none)'}", flush=True)

        self._send(
            200,
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion",
                "model": model,
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": REPLY},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 50, "total_tokens": 60},
            },
        )

    def do_GET(self):
        self._send(200, {"status": "ok", "hint": "POST /v1/chat/completions"})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"mock OpenAI server on http://127.0.0.1:{PORT}/v1  (POST /v1/chat/completions)", flush=True)
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
