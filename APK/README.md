# 安装包

| 文件 | 版本 | 架构 | 大小 | 说明 |
|---|---|---|---|---|
| CodePocket-0.4.0-arm64-debug.apk | 0.4.0 (versionCode 4) | arm64-v8a | 42.8 MB | 真机（绝大多数手机） |

安装：手机上允许「安装未知来源应用」后直接点开 apk。**这是 debug 签名包**，不是上架 Google Play 用的 release 包。

模拟器（x86_64）需要自己构建：
```
./gradlew -Pabis=x86_64 --no-parallel assembleDebug
```

## 构建参数为什么必须带

- `-Pabis=<架构>`：Chaquopy 会为每个 ABI 单独下载约 7 MB 的 Python 运行时，不指定会两个一起下并卡住。
- `--no-parallel`：并行构建会抛 `Current thread does not hold the state lock for root project`。

## 注意

APK 是构建产物，本不该进 Git 历史（每个 43 MB，**提交一次就永久留在历史里**）。
放在这里是为了方便直接下载。如果以后频繁发版，建议改用 GitHub Releases 上传安装包。