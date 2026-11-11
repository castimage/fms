#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真实栈 RS256 认证与代表性业务链路验收脚本。

脚本经网关访问部署后的测试机，验证登录、RS256 令牌、业务接口、refresh 旋转、
旧 refresh 重放、签名篡改、HS256/RS512 伪造和登出黑名单。账号口令从环境变量
读取，不写入仓库。

环境变量：
  FMS_E2E_BASE_URL          默认 https://localhost:8443
  FMS_E2E_LOGIN_NAME        默认 operator01
  FMS_E2E_PASSWORD          必填
  FMS_E2E_COMPANY_ID       默认测试公司 UUID
  FMS_E2E_PRIVATE_KEY_PATH 默认 deploy/secrets/jwt_private.pem
  FMS_E2E_VERIFY_TLS       1 时校验证书，默认自签测试不校验
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
from urllib.parse import quote

import requests
import urllib3
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def decode_segment(token, index):
    part = token.split(".")[index]
    part += "=" * (-len(part) % 4)
    return json.loads(base64.urlsafe_b64decode(part.encode("ascii")).decode("utf-8"))


def compact(header, payload, signature):
    return ".".join([b64url(json.dumps(header, separators=(",", ":")).encode()),
                     b64url(json.dumps(payload, separators=(",", ":")).encode()),
                     b64url(signature)])


def load_private_key(path):
    with open(path, "rb") as fh:
        return serialization.load_pem_private_key(fh.read(), password=None)


class E2E:
    def __init__(self, base, login_name, password, company_id, private_key_path, verify_tls):
        self.base = base.rstrip("/")
        self.login_name = login_name
        self.password = password
        self.company_id = company_id
        self.private_key_path = private_key_path
        self.verify = verify_tls
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": "FMS-E2E-RS256/1.0",
            "Content-Type": "application/json",
        })
        self.passed = 0
        self.failed = 0

    def call(self, method, path, token=None, body=None):
        headers = {}
        if token:
            headers["Authorization"] = "Bearer " + token
        return self.session.request(
            method, self.base + path, headers=headers, json=body,
            verify=self.verify, timeout=20)

    def check(self, name, condition, detail=""):
        if condition:
            self.passed += 1
            print("PASS", name, detail)
        else:
            self.failed += 1
            print("FAIL", name, detail)

    def run(self):
        health = self.call("GET", "/healthz")
        self.check("healthz", health.status_code == 200, str(health.status_code))

        login = self.call("POST", "/api/auth/login", body={
            "loginName": self.login_name,
            "password": self.password,
        })
        login_json = login.json()
        data = login_json.get("data") or {}
        access = data.get("accessToken") or ""
        refresh = data.get("refreshToken") or ""
        self.check("login", login.status_code == 200 and login_json.get("code") == 0,
                   "status=%s code=%s" % (login.status_code, login_json.get("code")))
        self.check("access.header.alg", bool(access) and decode_segment(access, 0).get("alg") == "RS256",
                   decode_segment(access, 0).get("alg") if access else "missing")

        dashboard = self.call(
            "GET", "/api/rpt/dashboard?companyId=" + quote(self.company_id), access)
        self.check("scenario.dashboard", dashboard.status_code == 200 and
                   dashboard.json().get("code") == 0, str(dashboard.status_code))

        scenarios = [
            ("总账凭证", "/api/gl/vouchers?page=1&size=1"),
            ("资金流水", "/api/cash/transactions?page=1&size=1"),
            ("报销单", "/api/exp/reimbursements?page=1&size=1"),
            ("预算计划", "/api/budget/plans?page=1&size=1"),
        ]
        for name, path in scenarios:
            result = self.call("GET", path, access)
            self.check("scenario." + name, result.status_code == 200 and
                       result.json().get("code") == 0, str(result.status_code))

        refreshed = self.call("POST", "/api/auth/refresh", body={"refreshToken": refresh})
        refreshed_json = refreshed.json()
        new_data = refreshed_json.get("data") or {}
        new_refresh = new_data.get("refreshToken") or ""
        self.check("refresh", refreshed.status_code == 200 and refreshed_json.get("code") == 0,
                   "status=%s code=%s" % (refreshed.status_code, refreshed_json.get("code")))

        replay = self.call("POST", "/api/auth/refresh", body={"refreshToken": refresh})
        self.check("refresh.old.replay", replay.status_code == 401,
                   "status=%s code=%s" % (replay.status_code, replay.json().get("code")))

        tampered = access[:-1] + ("a" if access[-1:] != "a" else "b")
        tamper_resp = self.call("GET", "/api/rpt/dashboard?companyId=" + quote(self.company_id), tampered)
        self.check("access.tampered", tamper_resp.status_code == 401,
                   str(tamper_resp.status_code))

        header = {"alg": "HS256", "typ": "JWT"}
        payload = {"sub": data.get("user", {}).get("id", "00000000-0000-7000-0000-000000000001"),
                   "role": "operator", "exp": int(time.time()) + 3600,
                   "iss": "fms", "aud": "fms-gateway", "type": "access"}
        signing_input = b64url(json.dumps(header, separators=(",", ":")).encode()) + "." + \
            b64url(json.dumps(payload, separators=(",", ":")).encode())
        hs_sig = hmac.new(b"forged-secret", signing_input.encode(), hashlib.sha256).digest()
        hs_token = signing_input + "." + b64url(hs_sig)
        hs_resp = self.call("GET", "/api/rpt/dashboard?companyId=" + quote(self.company_id), hs_token)
        self.check("access.hs256", hs_resp.status_code == 401, str(hs_resp.status_code))

        key = load_private_key(self.private_key_path)
        rs_header = {"alg": "RS512", "typ": "JWT"}
        rs_input = b64url(json.dumps(rs_header, separators=(",", ":")).encode()) + "." + \
            b64url(json.dumps(payload, separators=(",", ":")).encode())
        rs_sig = key.sign(rs_input.encode(), padding.PKCS1v15(), hashes.SHA512())
        rs_token = rs_input + "." + b64url(rs_sig)
        rs_resp = self.call("GET", "/api/rpt/dashboard?companyId=" + quote(self.company_id), rs_token)
        self.check("access.rs512", rs_resp.status_code == 401, str(rs_resp.status_code))

        logout = self.call("POST", "/api/auth/logout", access,
                           {"accessToken": access, "refreshToken": new_refresh})
        self.check("logout", logout.status_code == 200 and logout.json().get("code") == 0,
                   str(logout.status_code))
        after_logout = self.call(
            "GET", "/api/rpt/dashboard?companyId=" + quote(self.company_id), access)
        self.check("access.after.logout", after_logout.status_code == 401,
                   str(after_logout.status_code))

        print("SUMMARY pass=%d fail=%d" % (self.passed, self.failed))
        return 1 if self.failed else 0


def main():
    parser = argparse.ArgumentParser(description="FMS RS256 E2E")
    parser.add_argument("--base-url", default=os.getenv("FMS_E2E_BASE_URL", "https://localhost:8443"))
    parser.add_argument("--login-name", default=os.getenv("FMS_E2E_LOGIN_NAME", "operator01"))
    parser.add_argument("--company-id", default=os.getenv(
        "FMS_E2E_COMPANY_ID", "00000000-0000-7000-0000-000000000010"))
    parser.add_argument("--private-key", default=os.getenv(
        "FMS_E2E_PRIVATE_KEY_PATH", "deploy/secrets/jwt_private.pem"))
    parser.add_argument("--verify-tls", action="store_true",
                        default=os.getenv("FMS_E2E_VERIFY_TLS", "0") == "1")
    args = parser.parse_args()
    password = os.getenv("FMS_E2E_PASSWORD")
    if not password:
        print("FMS_E2E_PASSWORD 未设置", file=sys.stderr)
        return 2
    if not args.verify_tls:
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
    if not os.path.isfile(args.private_key):
        print("私钥不存在: " + args.private_key, file=sys.stderr)
        return 2
    runner = E2E(args.base_url, args.login_name, password, args.company_id,
                 args.private_key, args.verify_tls)
    return runner.run()


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
