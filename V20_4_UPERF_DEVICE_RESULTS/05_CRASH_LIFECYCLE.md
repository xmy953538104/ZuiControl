# Crash Lifecycle — HISTORICAL ONLY

> This failed-device result refers to a superseded startup/FIFO candidate.
> FIFO EOF is not a current production lifecycle signal. Current Golden
> semantics are regular startup log plus native subreaper/blocking `waitpid`;
> worker fault and 3/20s storm injection remain backlog.

Result: **PLANNED FAULT INJECTION NOT EXECUTED.**

The service failed naturally during startup before a stable Uperf tree existed. Normal worker
recovery 10/10, wrapper-PID stability, FIFO EOF recovery, and explicit stop/start were therefore
not tested. The natural failure showed that the wrapper exits status `1` when its SELinux-blocked
uptime read fails and init repeatedly restarts the complete service.

No crash was intentionally injected. No runtime state was repaired.

Evidence: [01_BOOT_GATE.md](01_BOOT_GATE.md).
