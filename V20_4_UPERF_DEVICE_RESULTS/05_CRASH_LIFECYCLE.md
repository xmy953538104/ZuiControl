# Crash Lifecycle

Result: **PLANNED FAULT INJECTION NOT EXECUTED.**

The service failed naturally during startup before a stable Uperf tree existed. Normal worker
recovery 10/10, wrapper-PID stability, FIFO EOF recovery, and explicit stop/start were therefore
not tested. The natural failure showed that the wrapper exits status `1` when its SELinux-blocked
uptime read fails and init repeatedly restarts the complete service.

No crash was intentionally injected. No runtime state was repaired.

Evidence: [01_BOOT_GATE.md](01_BOOT_GATE.md).
