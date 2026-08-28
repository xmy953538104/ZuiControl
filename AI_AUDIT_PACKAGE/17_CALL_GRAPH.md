# 核心调用关系

## P0：焦点场景到刷新率

```mermaid
sequenceDiagram
  participant WM as DisplayContent/WindowManager
  participant H as ZuiControlHooks
  participant S as ZuiControlService
  participant P as profiles.prop
  participant D as DisplayManagerInternal
  participant V as DisplayModeDirector vote
  participant ST as Settings state

  WM->>H: setFocusedApp(record, displayId)
  H->>S: onFocusedAppChanged(package, displayId)
  S->>S: raw → transient filter → current/last
  S->>P: lookup displayHz/fpsCap
  S->>S: map Hz to real Display.Mode + debounce
  alt DMI path succeeds
    S->>D: setDisplayProperties(...)
  else fallback
    S->>V: updateVote(priority=8, mode range)
  end
  S->>ST: publish raw/current/last/active/status
```

代码：`ZuiControlHooks.onFocusedAppChanged()` → `ZuiControlService.onFocusedAppChanged()/resolveSceneLocked()/applyTargetLocked()/publishState()`。失败结果保存在 `mLastError` 并通过 `state()` 返回；但 `publishState()` 自身失败会静默。

## P0：App/QS 到刷新率 profile

```mermaid
flowchart TD
  E[Activity 选择 Hz / QS 点击] --> C[ZuiControlClient]
  C --> M[android.zui.ZuiControlManager]
  M --> B[ServiceManager: zui_control]
  B --> A[ZuiControlService.enforceCaller]
  A -->|UID package + cert 通过| T[onTransact]
  A -->|失败| R0[ok=0 / SecurityException]
  T --> Q{命令}
  Q -->|set profile| SP[validate + saveProfiles AtomicFile]
  Q -->|QS cycle| LS[lastNonTransientScene]
  LS --> SP
  SP --> AP[apply target display mode]
  AP --> R1[Binder reply + published state]
```

客户端 `ZuiControlClient.call()` 当前把 `ok=0` 误判为成功，返回链的展示语义有缺陷，但服务端鉴权仍执行。

## P0：Uperf 请求、优先级和即时性

```mermaid
sequenceDiagram
  participant UI as MainActivity/QuickService
  participant R as ZuiControlRequest
  participant SET as Settings.System single slot
  participant D as zui_controld (1s loop)
  participant F as vendor mode files
  participant U as Uperf frontend/core

  UI->>R: global/app/restart command
  R->>SET: requestId|cmd|... (processing)
  loop every ~1 second
    D->>SET: process_settings_request()
  end
  D->>D: validate command/package/mode
  D->>F: atomic-ish write cur/perapp
  D->>D: effective = screen-off > exact > global
  D->>U: write cur_powermode frontend
  D->>SET: terminal ACK + health/effective/source
  loop every 200 ms until timeout
    R->>SET: read matching terminal ACK
  end
  R-->>UI: success/failure text
```

实测：有效变化约 0.97–2.07 秒。若鸣潮有 exact `performance`，修改 global `fast` 不改变鸣潮有效档；修改鸣潮 exact 才改变。

## P0：真实游戏性能链冲突

```mermaid
flowchart LR
  G[启动鸣潮] --> Scene[ZuiControl current scene]
  Scene --> Exact[exact=performance]
  Exact --> U[Uperf CPU power model/WALT/core_ctl]
  G --> GH[ZUI GameHelper onGameAppStart]
  GH --> PP[com.zui.pp PerformanceConnect]
  PP --> LC[Stock LimitConfig]
  LC --> CPU[OEM CPU bounds]
  LC --> GPU[KGSL GPUMin=9 / GPUMax=5]
  PP --> TH[thermal-engine game case]
  U --> CPU
```

结果不是单一策略：Uperf 与 OEM 同时进入 CPU 路径，GPU 仍由 OEM 约束。`uperf-sm8650.json` 无 KGSL 模块，因此无法从 Uperf 四档解释 500–629 MHz。

## P0：asoulOpt 内置启动

```mermaid
flowchart TD
  I[Android init] --> P[zui_scheduler_prepare.sh]
  P --> D[/data/vendor/zui_control/asoul/asopt.conf]
  P --> L[/data/vendor/asopt.conf symlink]
  I --> A[/system/bin/AsoulOpt]
  L --> A
  A --> T[embedded package/thread table]
  T --> S[affinity / WALT per-task boost]
  Z[zui_controld health every ~20s] --> A
  Z -->|missing| C[ctl.start zui_asoulopt]
```

外部 conf 提供 `mode=0/rt=0/opt`，不提供任意新包/线程规则。新游戏扩展受闭源内嵌表限制。

## 日志导出

`MainActivity.exportLogs()` → `ZuiControlRequest(CMD_EXPORT_LOGS)` → Settings request → `zui_controld.process_settings_request()` → 汇总 `/data/vendor/zui_control/log`/状态 → 写 `zui_control_log_export` → App 分享。原始设备 dump 不进入本审查源码包。
