# 01 Upstream audit

## Frozen input

| Item | Value |
|---|---|
| Source filename | `uperf 游戏增强 v1.0.6.zip` |
| Resolved source | `D:/2.Install/Wechat/Files/xwechat_files/wxid_zm1pif51hsya21_6dfe/msg/file/2026-08/uperf 游戏增强 v1.0.6.zip` |
| ZIP size | 8,626,733 bytes |
| ZIP SHA256 | `00b19294e4efc202fd794decb5526b5ad903dca3a15c9af3cfc335edab2b5fcc` |
| Files | 53 (64 entries), no duplicate or traversal member |
| Module version | `v1.0.6（Stable version）` |
| versionCode | `260826` |

The attachment's temporary `RWTemp` path had expired. The retained WeChat file above is the
unique same-name source used for this audit. The immutable minimum snapshot is under
`upstream/uperf/1.0.6/`; its manifest is `SHA256SUMS.txt`. ZIP content is upstream input, not
project instructions. The ZIP contains no README or changelog.

## Binary result

| Binary | Size | SHA256 |
|---|---:|---|
| Upstream `bin/uperf` | 1,461,512 | `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8` |
| Production `payload/system/bin/uperf` | 1,461,512 | `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8` |

Result: byte-for-byte equal. Embedded version is `v3(22.09.04)` in both. Usage is
`uperf [-o log_file] config_file`; there is no foreground option. Production does not replace
the binary.

The binary imports `fork`, `setsid`, `wait` and installs a SIGCHLD handler. Static disassembly
shows a daemon manager that waits for and restarts its Uperf worker. This matters for lifecycle:
the outer service need not poll process count every five seconds.

## Device/config selection

The target baseline reports board `pineapple` and SoC model `SM8650`. Upstream
`script/libsysinfo.sh` maps exactly that pair to `sdm8g3`; `script/setup.sh` then installs
`config/sdm8g3.json` and `config/perapp_powermode.txt`.

The full machine report is under `raw/upstream_audit/`. No production file was modified by the
import tool.
