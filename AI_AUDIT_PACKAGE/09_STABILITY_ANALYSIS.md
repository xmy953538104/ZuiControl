# 稳定性分析

## 高风险异常路径

| 等级 | 文件/方法 | 问题 | 触发 | 后果/恢复现状 |
| --- | --- | --- | --- | --- |
| P1 | `ZuiControlService.publishState()` 655–672 | `catch (Throwable)` 后完全忽略 | Settings provider/权限/系统异常 | raw/current/screen 静默陈旧，daemon 选错档；无日志/重试 |
| P1 | `ZuiControlService.loadProfiles()` 504 起 | 广义异常只保留 lastError/默认状态 | 文件截断、格式损坏、I/O | profile 可能丢失或回默认；无隔离坏文件/恢复副本测试 |
| P1 | `ZuiControlClient.call()` 46–53 | `ok=0` 被 `startsWith("ok")` 判成功 | Binder 返回业务错误 | UI 假成功，用户误判配置已生效 |
| P1 | `MainActivity.runCommand()` 595 起 | 裸线程无 lifecycle cancel | 旋转、退出、进程回收、并发点击 | dialog/Activity 泄漏、过期 UI 回调、命令覆盖 |
| P1 | `zui_controld` Settings 单槽 | 无队列/事务锁 | QS 与 UI 同时发命令 | 前一请求可能从未被 daemon 读取，只能超时 |
| P1 | DisplayModeDirector 反射 | 私有字段/构造器绑定 ROM | OTA/framework 变化 | 刷新率 apply 失败；DMI fallback 覆盖度依 ROM |
| P1 | AsoulOpt/Uperf 预编译 ELF | 无源码测试/崩溃细节 | 内部 bug、新 kernel/proc 变化 | init 重启但可能循环；无法根因修复 |

## catch/retry 审查

- `ZuiControlService` 多处捕获 `Throwable`，这是保护 system_server 不崩溃的合理动机，但 `publishState()` 完全无日志使失败不可诊断。
- App 多数 API 用 `runCatching`/catch 转换成文案；可避免 crash，但也容易把具体 `SecurityException/DeadObjectException` 压成模糊错误。
- daemon 大量 `command || true` 让主循环不中断；短暂错误可自愈，永久错误会固定 1 秒重复且没有 backoff。
- Uperf wrapper 在子进程退出后固定 `sleep 1` 重启；没有指数退避或崩溃次数熔断。
- init 自身负责 service restart；没有错误次数 kill switch 自动触发。手动 kill switch 属性存在，但故障自动降级未验证。

## 场景风险

### Crash/NPE/IllegalState

Kotlin 主要使用非空/`runCatching`，未发现明确必现 NPE。system_server 反射、Parcel transaction 和 mode 对象最可能抛出运行时异常；service 广泛捕获以避免 system_server crash，但可能静默失效。

### ANR

App shell/ACK 工作在裸线程，直接 UI ANR 风险较低。更值得关注的是 system_server focus 回调路径：任何慢 I/O/反射/Settings provider 阻塞都会影响关键线程，当前未做 systrace/高频焦点压测。

### Service 死亡/Boot 未启动

init 重启策略完整，真机三个核心进程存在。但未执行冷启动 50 次、故意 kill/崩溃风暴、data 配置损坏和 selinux denial 故障注入，不能宣称多轮长稳。

### 配置损坏

刷新率使用 AtomicFile；Uperf/asoul 文本有默认回退和 prepare，优于无恢复。但没有 schema version、checksum、坏文件 quarantine 或自动备份，且没有全套真机损坏注入测试。

### Android/ROM 版本变化

`PatchZuiControlFramework.py` 匹配特定 smali 形态，遇到变化会 fail closed；运行时 DisplayModeDirector 反射、AsoulOpt `/proc` 假设和 Uperf sysfs 节点都与 072 紧耦合。当前只支持指定 ROM，不能外推。

### userId/多用户

刷新率/调度 scene 和 Settings 状态没有完整 userId 维度；多用户、工作资料和同包不同 user 的行为未处理，也未测试。

## 未验证稳定性项目

Doze 长待机、低内存、system_server 重启、Settings provider 故障、权限撤销、配置损坏、快速 1000 次 App 切换、并发 QS/UI、Uperf/asoul 崩溃风暴、长期温度/功耗和冷启动 A/B 均未完成。本次短测仅证明请求语义和 OEM 冲突，不代表长时间稳定。
