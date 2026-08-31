# Final Artifact ART Gate

状态：`PASS_PRE_FLASH`。

最终候选：

```text
RunId=20260831170720
source_commit=146e096c6a6bc8b3fee60349b856990fd9fb68d2
ci_run_id=33375509612
super_sha256=dc4fd4bc3e288aa26e80cf382db62211f488e1b74c7cb8767b2d3f9f5f2c269d
services_jar_sha256=0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3
PRE_FLASH_READY=YES
FLASHED=NO
```

永久gate结果：

1. final SHA host：V20.4 `39/39`、V20.3B `5/5`、Java 8/javac、D8、Python compile、PowerShell parser与diff check均PASS；
2. CI `33375509612` 成功，head SHA与source commit精确相等；apktool/smali rebuild PASS；
3. final `super.img` reverse extraction PASS，base verifier `ok=true`，V20.4 `marker_count=56`；
4. final-super `services.jar` 主机SHA与推入设备临时目录后的SHA均为 `0b7bb46c...`；其 `classes4.dex` 为 `ae961e05...`；
5. 目标设备 `/data/local/tmp/v20_4_art_20260831170720` 的ART/dex2oat verifier：`DEX_RC=0`、`GATE_RC=0`、stdout/stderr为空，前后system_server PID均为2635；logcat记录ISA/zipalign warning以及live boot oat与候选新增framework DEX不匹配后转入imageless verify，没有 `VerifyError`、verification rejection或hard/soft verifier failure；
6. final split CIL以final-super提取的8个输入和目标live `/system/bin/secilc`，按AOSP Android 14 r75 `-m -M true -G -N -c 30 -f /sys/fs/selinux/null` 编译：`SECILC_RC=0`、`GATE_RC=0`、stderr为空；
7. system policy+mapping sidecar `465d1220...` 与ODM旧precompiled sidecar `889ba377...` 不同，boot会拒绝旧precompiled policy并走上述fallback compile；
8. final init RC SHA `0161a998...` 在一次性Ubuntu GHA中使用Android CI BuildId `16200779` 官方 `host_init_verifier` exact-file执行：exit 0、stdout/stderr为空；该覆盖只证明最终RC单文件grammar/builtin/user/property-context解析，不冒充五分区全树PASS。shell entrypoint与SELinux runtime边界由独立final CIL/device gate覆盖。

最小证据：[`final_super_receipt`](raw/final_super_receipt_20260831170720.txt)、[`ART gate`](raw/final_art_gate_20260831170720/)、[`policy gate`](raw/final_policy_gate_20260831170720/)、[`init exact-file gate`](raw/final_init_gate_20260831170720/)、[`build provenance`](raw/build_20260831170720/)。

不得以apktool build或smali assemble单独替代ART bootability proof。`PASS_PRE_FLASH`只表示技术候选可交人工审核；它不表示已获刷机授权，也不表示Boot Hard Gate或correction device matrix已PASS。
