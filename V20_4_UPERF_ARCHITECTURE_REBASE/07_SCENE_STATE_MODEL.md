# 07 Uperf scene state model

Refresh authority is unchanged and remains focused-window based. Uperf gets a separate event source:
the assignment of `ActivityTaskSupervisor.mTopResumedActivity` in
`updateTopResumedActivityIfNeeded`. The hook dispatches the exact `ActivityRecord` to the existing
ZuiControl worker. There is no polling, dumpsys, shell query, Accessibility or App-side service.

## Selection

```text
screen not interactive       -> powersave
screen interactive + exact rule for top-resumed package -> exact mode
screen interactive + no exact rule                     -> global mode
```

Only powersave, balance, performance and fast are valid. Exact rules remain the existing user-app
contract. System packages are not newly admitted.

## Event semantics

| Transition | Authority/result |
|---|---|
| Game exact fast -> Video | Video becomes top-resumed; no rule -> global |
| Game exact fast -> Home | Home becomes top-resumed; no rule -> global |
| Game + QS/SystemUI overlay | Android top-resumed remains Game -> fast, no flap |
| Game -> ZuiControl Activity | ZuiControl becomes top-resumed -> global |
| IME window only | does not feed Uperf; top-resumed Activity remains authority |
| freeform/split | framework-selected top-resumed pane owns exact rule |
| inactive visible/resumed pane | does not own exact rule |
| PiP visible but not top-resumed | does not own exact rule |
| screen off/on | powersave, then recompute from retained top-resumed package |

The old feeds from refresh focus/window/IME state are removed only from `UperfScenePolicy`; the
Refresh state machine and its fields are not changed. Same desired mode remains deduplicated before
the property write. State output exposes `uperfScenePackage` separately from Refresh
`currentScenePackage` so future evidence cannot conflate the two authorities.
