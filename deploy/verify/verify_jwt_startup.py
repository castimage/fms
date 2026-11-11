#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JWT 启动失败进程级验收。

在已部署 Docker 的主机运行，拉取网关与后端镜像后注入坏密钥，确认进程在监听端口
前以非零码退出。后端环境从运行中的 fms-backend-1 读取，避免手工拼接数据库变量。

用法：
  python deploy/verify/verify_jwt_startup.py
可选环境变量：
  FMS_RGW_IMAGE           默认 fms_rgw:0.2.2
  FMS_BACKEND_IMAGE       默认从 fms-backend-1 镜像读取
  FMS_BACKEND_CONTAINER   默认 fms-backend-1
"""

import os
import subprocess
import sys
import tempfile


def run(cmd, timeout=90):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, timeout=timeout)


def docker_output(cmd):
    result = run(cmd)
    if result.returncode != 0:
        raise RuntimeError("命令失败: %s\n%s" % (" ".join(cmd), result.stdout))
    return result.stdout.strip()


def main():
    gateway_image = os.getenv("FMS_RGW_IMAGE", "fms_rgw:0.2.2")
    container = os.getenv("FMS_BACKEND_CONTAINER", "fms-backend-1")
    backend_image = os.getenv("FMS_BACKEND_IMAGE") or docker_output([
        "docker", "inspect", "-f", "{{.Config.Image}}", container])
    failures = []

    bad_key = "-----BEGIN PUBLIC KEY-----bad-----END PUBLIC KEY-----"
    gateway = run([
        "docker", "run", "--rm", "--network", "none",
        "-e", "FMS_RGW_JWT_PUBLIC_KEY=" + bad_key,
        "-e", "FMS_RGW_SIGN_SECRET=startup-test-sign-secret-0123456789abcdef",
        gateway_image,
    ])
    if gateway.returncode == 0 or "JWT 公钥解析失败" not in gateway.stdout:
        failures.append("网关坏公钥未按预期退出: rc=%s out=%s" %
                        (gateway.returncode, gateway.stdout[-300:]))
    else:
        print("PASS gateway.bad_public_key rc=%s" % gateway.returncode)

    env_text = docker_output([
        "docker", "inspect", "-f", "{{range .Config.Env}}{{println .}}{{end}}", container])
    tmp = tempfile.NamedTemporaryFile("w", suffix=".env", delete=False, encoding="utf-8")
    try:
        tmp.write(env_text + "\n")
        tmp.close()
        backend = run([
            "docker", "run", "--rm", "--network", "none",
            "--env-file", tmp.name,
            "-e", "FMS_JWT_PRIVATE_KEY_PATH=/run/secrets/no-such-jwt-private.pem",
            "-e", "FMS_JWT_PUBLIC_KEY_PATH=/run/secrets/no-such-jwt-public.pem",
            backend_image,
        ], timeout=120)
    finally:
        os.unlink(tmp.name)

    if backend.returncode == 0 or ("JWT 私钥加载失败" not in backend.stdout and
                                   "fms.jwt.private-key-path 未配置" not in backend.stdout):
        failures.append("后端坏私钥未按预期退出: rc=%s out=%s" %
                        (backend.returncode, backend.stdout[-300:]))
    else:
        print("PASS backend.bad_private_key rc=%s" % backend.returncode)

    if failures:
        for item in failures:
            print("FAIL", item)
        return 1
    print("SUMMARY startup checks passed")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
