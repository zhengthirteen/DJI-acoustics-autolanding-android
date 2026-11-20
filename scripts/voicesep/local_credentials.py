"""Load local device credentials through SMD's existing loopback API.

Only the ignored secrets.properties file holds the password. Nothing here starts
sampling, changes device settings or exposes credentials through the LAN gateway.
"""

from __future__ import annotations

import ipaddress
import json
import os
import stat
from dataclasses import dataclass, field
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

PROJECT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = PROJECT / "android-sdk-v5-as/secrets.properties"


class CredentialsError(RuntimeError):
    pass


@dataclass(frozen=True)
class Credentials:
    host: str
    username: str
    password: str = field(repr=False)


def read_local_credentials(path: Path = DEFAULT_CONFIG):
    path = Path(path)
    if not path.exists():
        return None
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() in {"SMD_SSH_HOST", "SMD_SSH_USERNAME", "SMD_SSH_PASSWORD"}:
            values[key.strip()] = (
                value if key.strip() == "SMD_SSH_PASSWORD" else value.strip()
            )
    password = values.get("SMD_SSH_PASSWORD", "")
    if not password:
        return None
    if os.name != "nt" and stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise CredentialsError(
            "本机凭据文件应仅当前用户可读写，请将 secrets.properties 权限设为600。"
        )
    host = values.get("SMD_SSH_HOST", "192.168.3.102")
    username = values.get("SMD_SSH_USERNAME", "root")
    try:
        address = ipaddress.ip_address(host)
        if address.version != 4 or not address.is_private:
            raise ValueError("private IPv4 required")
    except ValueError:
        raise CredentialsError("SMD_SSH_HOST 必须是设备的私有IPv4地址。") from None
    if (
        not username
        or len(username) > 80
        or any(ord(c) < 32 for c in username + password)
    ):
        raise CredentialsError("本机设备凭据格式无效。")
    return Credentials(host, username, password)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_into_smd(path: Path = DEFAULT_CONFIG, origin="http://127.0.0.1:8012"):
    """Return False when unconfigured/busy; keep device operations explicitly manual."""
    credentials = read_local_credentials(path)
    if credentials is None:
        return False, "未配置本机SMD凭据，可在原网页手动输入。"
    target = urlsplit(origin)
    if (
        target.scheme != "http"
        or target.hostname != "127.0.0.1"
        or target.username
        or target.password
        or target.query
        or target.fragment
        or target.path not in ("", "/")
        or target.port is None
    ):
        raise CredentialsError("凭据只能发送到本机127.0.0.1上的SMD服务。")
    origin = origin.rstrip("/")
    opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(route, payload=None, token=None):
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json", "Cache-Control": "no-store"}
        if token:
            headers["X-SMD-Token"] = token
        try:
            with opener.open(
                Request(origin + route, data=data, headers=headers), timeout=3
            ) as response:
                body = response.read(256 * 1024 + 1)
            if len(body) > 256 * 1024:
                raise ValueError("response too large")
            return json.loads(body)
        except (HTTPError, URLError, OSError, ValueError):
            # Do not print server response bodies: validation errors may echo submitted input.
            raise CredentialsError(
                "本机SMD凭据接口请求失败；请检查8012服务，未输出凭据。"
            ) from None

    bootstrap = request("/api/bootstrap")
    if (
        not isinstance(bootstrap, dict)
        or bootstrap.get("application_id") != "smd-array-recorder"
    ):
        raise CredentialsError("本机端口不是预期SMD服务，未发送凭据。")
    status = bootstrap.get("status", {})
    if not isinstance(status, dict) or status.get("host") != credentials.host:
        raise CredentialsError("原SMD设备地址与本机凭据配置不一致，未发送凭据。")
    if status.get("operation_busy") or status.get("state") not in (
        "disconnected",
        "error",
    ):
        return False, "SMD正在操作/采集，保留当前会话；空闲后可运行 load-credentials。"
    token = bootstrap.get("token")
    if not isinstance(token, str) or not token:
        raise CredentialsError("SMD会话令牌缺失，未发送凭据。")
    result = request(
        "/api/credentials",
        {
            "username": credentials.username,
            "password": credentials.password,
            "remember": False,
        },
        token,
    )
    if not isinstance(result, dict) or result.get("saved") is not False:
        raise CredentialsError("SMD凭据接口返回不符合预期；未声称已加载。")
    return (
        True,
        "SMD本机凭据已加载（密码不回显）；网页密码框留空即可校准/准备，不要勾选Windows加密记住。",
    )
