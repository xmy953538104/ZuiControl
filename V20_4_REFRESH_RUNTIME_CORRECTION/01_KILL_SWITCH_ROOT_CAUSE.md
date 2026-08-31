# Kill Switch Root Cause

结论：

```text
KILL_SWITCH_ROOT_CAUSE_CONFIRMED=RAW_SETPROP_DOES_NOT_REPORT_PROCESS_SYSPROP_CHANGE
```

## AOSP Android 14 语义

本结论按目标同代官方 AOSP `android-14.0.0_r75` 核对：

- [`SystemProperties.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r75/core/java/android/os/SystemProperties.java)：`set()` 只进入 `native_set()`；`addChangeCallback()` 注册当前进程的 callback；`reportSyspropChanged()` 才是显式报告入口。
- [`android_os_SystemProperties.cpp`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r75/core/jni/android_os_SystemProperties.cpp)：`native_set` 只写 property area；`native_report_sysprop_change` 才调用 `report_sysprop_change()`。
- [`libutils/misc.cpp`](https://android.googlesource.com/platform/system/core/+/refs/tags/android-14.0.0_r75/libutils/misc.cpp)：callback vector 是 process-local，report 只遍历当前进程注册项。
- [`IBinder.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r75/core/java/android/os/IBinder.java) 与 [`Binder.cpp`](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-14.0.0_r75/libs/binder/Binder.cpp)：标准 `SYSPROPS_TRANSACTION` 为 `0x5f535052` / `1599295570`，native Binder 路径调用 process-local report。
- [`SystemPropPoker.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r75/packages/SettingsLib/src/com/android/settingslib/development/SystemPropPoker.java)：官方先例通过 Binder transaction 主动 poke；它不是 property-area 自动广播。

因此，共享 property 值已经跨进程可见，不等于另一个进程内的 Java callback 已执行。

## 当前真机可逆实验

设备仍为 fixed RunId `20260831134511`，Android 14/API 34，`system_server` PID 全程为 `2635`。临时建立 `com.zui.notes=90` 后执行：

| 阶段 | 首/末观察 | 结果 |
|---|---:|---|
| raw `setprop ...refresh.disable 1` | 末样本 `2127.214ms` | property=1；service 仍 mask0/false；render90、peak、AppRequest 均 owned |
| `service call zui_control 1599295570` | 首样本 `59.772ms` | mask2/true；render released；peak restored；AppRequest `requested:propertyDisable` |
| raw `setprop ...refresh.disable 0` | 末样本 `883.891ms` | property=0；service 仍 mask2/true，ownership 仍释放 |
| 第二次标准 poke | 首样本 `56.350ms` | mask0/false；render90、peak、AppRequest 重建；applyCount +1 |

关键证据：

- [`02_after_setprop_before_poke.csv`](raw/sysprop_poke_20260831/02_after_setprop_before_poke.csv)
- [`03_after_poke.csv`](raw/sysprop_poke_20260831/03_after_poke.csv)
- [`05_restore_before_poke.csv`](raw/sysprop_poke_20260831/05_restore_before_poke.csv)
- [`06_restore_after_poke.csv`](raw/sysprop_poke_20260831/06_restore_after_poke.csv)
- [`08_command_transcript.txt`](raw/sysprop_poke_20260831/08_command_transcript.txt)
- [`09_final_restored_baseline.txt`](raw/sysprop_poke_20260831/09_final_restored_baseline.txt)

`service call` 显示 `Parcel(Error ... "Not a data message")` 不代表 side effect 失败：Java Binder 返回后，Android native Binder 仍处理标准 sysprop transaction。成功判据只能是 service state，而不是 CLI Parcel 文本。

实验结束后两个 disable property 均恢复0，临时 Notes profile 已删除，Launcher/default120 已恢复，`system_server` PID仍为2635。

## 排除项

- property 写入失败：排除，`getprop` 稳定读到1/0。
- callback 未注册：排除，标准 poke 后立即执行。
- release 状态机本身失败：排除，poke 后 vote/peak/AppRequest ownership按设计释放。
- SELinux 阻断读取：排除，worker在 poke 后读到mask2。
