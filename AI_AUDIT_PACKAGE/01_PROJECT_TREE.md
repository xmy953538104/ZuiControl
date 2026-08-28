# 项目树与扫描范围

本次读取 91 个 Git 跟踪文件；除两个 ELF 二进制无法以源码形式读取外，其余文本文件均完整扫描。仓库内没有 AIDL 和 native 源码目录。

```text
ZuiControl/
├─ .github/workflows/           # Android/9008 工具 CI
├─ app/
│  ├─ build.gradle.kts
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/com/zui/zuicontrol/  # App、QS、请求客户端
│     │  └─ res/                       # 图标、通知布局、字符串/主题
│     └─ test/                         # 请求协议 JVM 单测
├─ framework-stubs/             # App 编译期 android.zui API stub
├─ framework_patch/
│  ├─ src/framework/            # framework API
│  ├─ src/services/             # system_server Hook/Service
│  └─ stubs/                    # 独立 javac 验证 stub
├─ payload/
│  ├─ patches/                  # file/property/service contexts 与 CIL
│  └─ system/
│     ├─ bin/                   # uperf、AsoulOpt、wrapper、daemon
│     └─ etc/                   # init、权限、默认配置、Uperf JSON
├─ scripts/                     # 构建、payload、framework patch、验包、9008
├─ tools/                       # AsoulOpt 配置路径定长 patch
├─ docs/                        # 当前交接、流程说明和历史资料
├─ build.gradle.kts
└─ settings.gradle.kts
```

## 目录职责与重要性

| 目录 | 文件数 | 重要性 | 审计说明 |
| --- | ---: | --- | --- |
| `app` | 29 | P0/P1 | 用户控制面、请求协议、QS/通知和 Manifest |
| `framework_patch` | 9 | P0 | 刷新率唯一 owner、Binder 鉴权和 display vote |
| `framework-stubs` | 2 | P2 | 仅编译期 API，不进入运行时 |
| `payload` | 20 | P0 | init、daemon、二进制、Uperf 模型、SELinux |
| `scripts` | 8 | P1 | ROM 合入、验证、封包/刷写自动化 |
| `.github` | 2 | P1 | CI 构建与 qdlrs 工具构建 |
| `docs` | 15 | P2/P3 | 当前主交接及历史记录；旧 P2/P3 仅作历史 |
| `tools` | 1 | P1 | 修改闭源 AsoulOpt 内嵌路径 |

## 未纳入源码包的内容

构建缓存、`.git`、`.gradle`、`build`、`.idea`、`local.properties`、签名文件/密码、工作区镜像、设备 dump、APK 中间产物和用户私人文件均排除。设备日志只用于形成事实结论，没有放入源码包。
