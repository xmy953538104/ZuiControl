# 测试状态与矩阵

## 已有测试

- JVM unit test：`ZuiControlRequestTest.kt`，5 个测试，覆盖 ACK 解析、精确 requestId/command 匹配、空请求、五字段编码、进度映射。
- shell integration：`scripts/TestZuiControldTransactions.sh`，模拟 Settings/文件/进程，覆盖 Uperf 优先级和请求事务。
- verifier：`VerifyZuiControlFlashPackage.ps1` 做最终包结构、上下文、禁止旧路径/旧逻辑检查。
- instrumentation/UI test：不存在。
- native Uperf/AsoulOpt 单测：仓库没有源码或测试。

## 测试矩阵

| 测试 | 当前是否存在 | 自动/手动 | 预期结果 | 当前结果 |
| --- | --- | --- | --- | --- |
| 正常启动 | 部分 | 真机手动 | service/daemon/Uperf/Asoul 均存在 | 当前设备通过 |
| 开机启动 | 部分历史证据 | 手动 | init 与 Binder 自动恢复 | 本轮未冷启动复测 |
| 重启 | shell health + 历史 | 自动/手动 | 调度核心重启后配置恢复 | shell 事务通过；真机长稳未测 |
| 杀 App 进程 | 架构推导 | 手动 | 核心继续运行 | 未在本轮注入 |
| Force Stop | 无 | 手动 | 核心继续；UI/QS暂不可用 | 未验证 |
| 息屏 | shell 事务 | 自动 | `- powersave` 优先 | 模拟通过 |
| 亮屏 | shell 事务/短测 | 自动/手动 | exact > global | 通过短时语义测试 |
| Doze | 无 | 手动/功耗 | 核心正确且无异常唤醒 | 未验证 |
| 长待机 | 无 | 手动 | 无服务漂移/配置丢失 | 未验证 |
| 高频切换 App | 无 | 自动/手动 | scene 正确、防抖、无 system_server 压力 | 未验证 |
| 内存压力 | 无 | 手动 | App 可回收、核心不丢 | 未验证 |
| system_server 异常 | 无 | 手动 | 重启后 service/profile/display 恢复 | 未验证 |
| 权限缺失 | 无 | 自动/手动 | 明确拒绝、UI不假成功 | 未验证；client 存在假成功 bug |
| 配置损坏 | 部分默认逻辑 | 自动/手动 | 回退、记录、不中断开机 | 未注入验证 |
| Android API/反射失败 | stub 编译 | 自动/故障注入 | fallback 或明确错误 | 仅编译通过，运行失败未测 |
| 多线程竞争 | 无 | 自动 | 并发请求不丢失 | 未验证；单槽设计有风险 |
| 未授权 Binder | 历史真机要求 | 手动 | UID/证书不符拒绝 | 本轮未复测 |
| 切全局档 | 真机短测 | 手动 | 无 exact 时约 1–2 s 生效 | 通过 |
| exact 覆盖全局 | shell + 真机短测 | 自动/手动 | exact 保持优先 | 通过；鸣潮 performance 覆盖 global fast |
| 鸣潮 GPU | 真机短测日志 | 手动 | 单一 owner、档位符合设计 | 失败：OEM 仍限制约 366–629 MHz |
| SELinux enforcing | 真机读取 | 手动 | 无相关 AVC | 当前已知路径无 AVC；未做 secilc |
| 9008 最终包 | verifier/历史 | 自动 | 固定七入口、最终 super 反查通过 | 本轮未制包/未重跑 |

## 当前测试结论

测试足以确认“协议基本可用、请求有终态、优先级实现存在、代码可构建”，不足以确认“多场景、多环境、多轮稳定”。尤其 GPU 调度在真实游戏场景已经暴露 P0 失败，不能标为功能稳定。
