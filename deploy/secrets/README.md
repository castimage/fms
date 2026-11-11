# JWT RS256 密钥目录

`jwt_private.pem` 只读挂载到后端，`jwt_public.pem` 只读挂载到网关和后端用于启动期配对校验。私钥不得进入网关、前端或镜像，两个文件都不提交版本库。

生成：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt_private.pem
openssl rsa -in jwt_private.pem -pubout -out jwt_public.pem
sudo chown 10002:10002 jwt_private.pem
chmod 600 jwt_private.pem
chmod 644 jwt_public.pem
```

轮换时同时替换两个文件并重建后端与网关，旧 access token 在过期前仍会被旧公钥拒绝，需接受一次重新登录。
