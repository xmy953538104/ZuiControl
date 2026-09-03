#!/usr/bin/env python3
import argparse
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile


def run(cmd, cwd=None):
    print("+", " ".join(str(x) for x in cmd))
    subprocess.run([str(x) for x in cmd], cwd=cwd, check=True)


def collect_java(root):
    return [str(p) for p in sorted(root.rglob("*.java"))]


def rm_tree(path):
    if not path.exists():
        return

    def tolerate_disappearing_children(func, target, exc_info):
        if isinstance(exc_info[1], FileNotFoundError):
            return
        try:
            os.chmod(target, 0o700)
            func(target)
        except FileNotFoundError:
            return

    shutil.rmtree(path, onerror=tolerate_disappearing_children)
    if path.exists():
        raise RuntimeError(f"failed to remove stale build directory: {path}")


def compile_sources(repo, mi_root, build_dir, source_dir, dex_name):
    android_jar = mi_root / "work" / "android-sdk" / "platforms" / "android-35" / "android.jar"
    d8 = mi_root / "work" / "android-sdk" / "build-tools" / "36.0.0" / "d8.bat"
    stubs_src = repo / "framework_patch" / "stubs"
    stubs_classes = build_dir / f"{dex_name}_stubs"
    classes = build_dir / f"{dex_name}_classes"
    dex_dir = build_dir / f"{dex_name}_dex"
    for p in (stubs_classes, classes, dex_dir):
        rm_tree(p)
        p.mkdir(parents=True, exist_ok=True)

    run([
        "javac", "-source", "8", "-target", "8", "-encoding", "UTF-8",
        "-cp", str(android_jar), "-d", stubs_classes,
        *collect_java(stubs_src),
    ])
    run([
        "javac", "-source", "8", "-target", "8", "-encoding", "UTF-8",
        "-cp", f"{android_jar};{stubs_classes}", "-d", classes,
        *collect_java(source_dir),
    ])
    program_classes = [str(p) for p in sorted(classes.rglob("*.class"))]
    if not program_classes:
        raise RuntimeError(f"javac did not produce class files under {classes}")
    run([
        d8, "--release", "--min-api", "35", "--lib", android_jar,
        "--classpath", stubs_classes, "--output", dex_dir,
        *program_classes,
    ])
    dex = dex_dir / "classes.dex"
    if not dex.exists():
        raise RuntimeError(f"d8 did not produce {dex}")
    out = build_dir / dex_name
    shutil.copy2(dex, out)
    return out


def rewrite_zip(src, dst, replacements):
    tmp = dst.with_suffix(dst.suffix + ".tmp")
    if tmp.exists():
        tmp.unlink()
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
        skip = set(replacements)
        for item in zin.infolist():
            if item.filename in skip:
                continue
            zout.writestr(item, zin.read(item.filename))
        for name, path in replacements.items():
            zout.write(path, name)
    tmp.replace(dst)


def patch_text_file(path, replacements):
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in replacements:
        if old not in text:
            raise RuntimeError(f"pattern not found in {path}: {old[:80]!r}")
        text = text.replace(old, new, 1)
    if text != original:
        path.write_text(text, encoding="utf-8")


def patch_services_smali(dec_dir):
    zui_service = dec_dir / "smali_classes3" / "com" / "zui" / "server" / "performance" / "ZuiPerformanceService.smali"
    display_content = dec_dir / "smali_classes3" / "com" / "android" / "server" / "wm" / "DisplayContent.smali"
    activity_task_supervisor = dec_dir / "smali_classes3" / "com" / "android" / "server" / "wm" / "ActivityTaskSupervisor.smali"

    zui_text = zui_service.read_text(encoding="utf-8")
    if "Lcom/zui/server/control/ZuiControlService;->start" not in zui_text:
        patch_text_file(zui_service, [
            ("""    invoke-virtual {p0, v0, v1}, Lcom/android/server/SystemService;->publishBinderService(Ljava/lang/String;Landroid/os/IBinder;)V

    return-void
""",
             """    invoke-virtual {p0, v0, v1}, Lcom/android/server/SystemService;->publishBinderService(Ljava/lang/String;Landroid/os/IBinder;)V

    iget-object v0, p0, Lcom/zui/server/performance/ZuiPerformanceService;->mContext:Landroid/content/Context;

    invoke-static {v0}, Lcom/zui/server/control/ZuiControlService;->start(Landroid/content/Context;)V

    return-void
"""),
        ])

    display_text = display_content.read_text(encoding="utf-8")
    if "Lcom/zui/server/control/ZuiControlHooks;->onFocusedAppChanged" not in display_text:
        patch_text_file(display_content, [
            ("""    .line 4423
    invoke-virtual {p0}, Lcom/android/server/wm/DisplayContent;->getInputMonitor()Lcom/android/server/wm/InputMonitor;
""",
             """    .line 4423
    invoke-virtual {p0}, Lcom/android/server/wm/DisplayContent;->getDisplayId()I

    move-result v0

    invoke-static {p1, v0}, Lcom/zui/server/control/ZuiControlHooks;->onFocusedAppChanged(Lcom/android/server/wm/ActivityRecord;I)V

    invoke-virtual {p0}, Lcom/android/server/wm/DisplayContent;->getInputMonitor()Lcom/android/server/wm/InputMonitor;
             """),
        ])

    display_text = display_content.read_text(encoding="utf-8")
    if "Lcom/zui/server/control/ZuiControlHooks;->onFocusedWindowChanged" not in display_text:
        patch_text_file(display_content, [
            ("""    .line 4241
    iput-object v0, p0, Lcom/android/server/wm/DisplayContent;->mCurrentFocus:Lcom/android/server/wm/WindowState;

    const-string v7, "ZuiIms"
""",
             """    .line 4241
    iput-object v0, p0, Lcom/android/server/wm/DisplayContent;->mCurrentFocus:Lcom/android/server/wm/WindowState;

    const/4 v7, 0x0

    if-eqz v0, :zui_control_window_focus

    invoke-virtual {v0}, Lcom/android/server/wm/WindowState;->getOwningPackage()Ljava/lang/String;

    move-result-object v7

    :zui_control_window_focus
    invoke-virtual {p0}, Lcom/android/server/wm/DisplayContent;->getDisplayId()I

    move-result v8

    invoke-static {v7, v8}, Lcom/zui/server/control/ZuiControlHooks;->onFocusedWindowChanged(Ljava/lang/String;I)V

    const-string v7, "ZuiIms"
"""),
        ])

    display_text = display_content.read_text(encoding="utf-8")
    if "Lcom/zui/server/control/ZuiControlHooks;->onImeVisibilityChanged" not in display_text:
        patch_text_file(display_content, [
            ("""    :cond_1
    iget-object p1, p0, Lcom/android/server/wm/WindowContainer;->mWmService:Lcom/android/server/wm/WindowManagerService;

    iget-object p1, p1, Lcom/android/server/wm/WindowManagerService;->mAccessibilityController:Lcom/android/server/wm/AccessibilityController;
""",
             """    :cond_1
    const/4 v1, 0x0

    iget-object p1, p0, Lcom/android/server/wm/DisplayContent;->mImeControlTarget:Lcom/android/server/wm/InsetsControlTarget;

    if-eqz p1, :zui_control_ime_visibility

    invoke-static {}, Landroid/view/WindowInsets$Type;->ime()I

    move-result v2

    invoke-interface {p1, v2}, Lcom/android/server/wm/InsetsControlTarget;->isRequestedVisible(I)Z

    move-result v1

    :zui_control_ime_visibility
    const/4 p1, 0x0

    if-eqz v1, :zui_control_ime_dispatch

    iget-object v2, p0, Lcom/android/server/wm/DisplayContent;->mInputMethodWindow:Lcom/android/server/wm/WindowState;

    if-eqz v2, :zui_control_ime_dispatch

    invoke-virtual {v2}, Lcom/android/server/wm/WindowState;->getOwningPackage()Ljava/lang/String;

    move-result-object p1

    :zui_control_ime_dispatch
    iget v2, p0, Lcom/android/server/wm/DisplayContent;->mDisplayId:I

    invoke-static {p1, v1, v2}, Lcom/zui/server/control/ZuiControlHooks;->onImeVisibilityChanged(Ljava/lang/String;ZI)V

    iget-object p1, p0, Lcom/android/server/wm/WindowContainer;->mWmService:Lcom/android/server/wm/WindowManagerService;

    iget-object p1, p1, Lcom/android/server/wm/WindowManagerService;->mAccessibilityController:Lcom/android/server/wm/AccessibilityController;
"""),
        ])

    supervisor_text = activity_task_supervisor.read_text(encoding="utf-8")
    top_resumed_hook = (
        "Lcom/zui/server/control/ZuiControlHooks;->onTopResumedActivityChanged("
        "Lcom/android/server/wm/ActivityTaskSupervisor;"
        "Lcom/android/server/wm/ActivityRecord;)V"
    )
    if top_resumed_hook not in supervisor_text:
        patch_text_file(activity_task_supervisor, [
            ("""    iput-object v1, p0, Lcom/android/server/wm/ActivityTaskSupervisor;->mTopResumedActivity:Lcom/android/server/wm/ActivityRecord;

    if-eqz v1, :cond_4
""",
             """    iput-object v1, p0, Lcom/android/server/wm/ActivityTaskSupervisor;->mTopResumedActivity:Lcom/android/server/wm/ActivityRecord;

    invoke-static {p0, v1}, Lcom/zui/server/control/ZuiControlHooks;->onTopResumedActivityChanged(Lcom/android/server/wm/ActivityTaskSupervisor;Lcom/android/server/wm/ActivityRecord;)V

    if-eqz v1, :cond_4
"""),
        ])

    supervisor_text = activity_task_supervisor.read_text(encoding="utf-8")
    authority_method = (
        ".method public getZuiControlTopResumedActivity()"
        "Lcom/android/server/wm/ActivityRecord;"
    )
    if authority_method not in supervisor_text:
        supervisor_text = supervisor_text.rstrip() + """

.method public getZuiControlTopResumedActivity()Lcom/android/server/wm/ActivityRecord;
    .locals 3

    iget-object v0, p0, Lcom/android/server/wm/ActivityTaskSupervisor;->mService:Lcom/android/server/wm/ActivityTaskManagerService;

    iget-object v0, v0, Lcom/android/server/wm/ActivityTaskManagerService;->mGlobalLock:Lcom/android/server/wm/WindowManagerGlobalLock;

    invoke-static {}, Lcom/android/server/wm/WindowManagerService;->boostPriorityForLockedSection()V

    monitor-enter v0

    :try_start_zui_control
    iget-object v1, p0, Lcom/android/server/wm/ActivityTaskSupervisor;->mRootWindowContainer:Lcom/android/server/wm/RootWindowContainer;

    invoke-virtual {v1}, Lcom/android/server/wm/RootWindowContainer;->getTopDisplayFocusedRootTask()Lcom/android/server/wm/Task;

    move-result-object v1

    if-eqz v1, :zui_control_no_top_root

    invoke-virtual {v1}, Lcom/android/server/wm/Task;->getTopResumedActivity()Lcom/android/server/wm/ActivityRecord;

    move-result-object v2

    goto :zui_control_authority_done

    :zui_control_no_top_root
    const/4 v2, 0x0

    :zui_control_authority_done
    monitor-exit v0
    :try_end_zui_control
    .catchall {:try_start_zui_control .. :try_end_zui_control} :catchall_zui_control

    invoke-static {}, Lcom/android/server/wm/WindowManagerService;->resetPriorityAfterLockedSection()V

    return-object v2

    :catchall_zui_control
    move-exception p0

    :try_start_zui_control_exit
    monitor-exit v0
    :try_end_zui_control_exit
    .catchall {:try_start_zui_control_exit .. :try_end_zui_control_exit} :catchall_zui_control

    invoke-static {}, Lcom/android/server/wm/WindowManagerService;->resetPriorityAfterLockedSection()V

    throw p0
.end method
"""
        activity_task_supervisor.write_text(supervisor_text, encoding="utf-8")


def patch_framework(args):
    repo = pathlib.Path(__file__).resolve().parents[1]
    mi_root = repo.parent
    unpack = pathlib.Path(args.unpack).resolve()
    fw_dir = unpack / "system_a" / "system" / "framework"
    framework_jar = fw_dir / "framework.jar"
    services_jar = fw_dir / "services.jar"
    if not framework_jar.exists() or not services_jar.exists():
        raise SystemExit(f"Missing framework jars under {fw_dir}")

    build_root = mi_root / "work" / "zui_control_framework_patch"
    build_root.mkdir(parents=True, exist_ok=True)
    build_dir = pathlib.Path(tempfile.mkdtemp(prefix="run_", dir=str(build_root)))

    framework_dex = compile_sources(
        repo, mi_root, build_dir,
        repo / "framework_patch" / "src" / "framework",
        "classes6.dex",
    )
    services_extra_dex = compile_sources(
        repo, mi_root, build_dir,
        repo / "framework_patch" / "src" / "services",
        "classes4.dex",
    )

    framework_out = build_dir / "framework.jar"
    shutil.copy2(framework_jar, framework_out)
    rewrite_zip(framework_out, framework_out, {"classes6.dex": framework_dex})

    apktool = mi_root / "tools" / "apktool.jar"
    dec_dir = build_dir / "services_dec"
    rm_tree(dec_dir)
    run(["java", "-jar", apktool, "d", "-f", "-o", dec_dir, services_jar])
    patch_services_smali(dec_dir)
    rebuilt_services = build_dir / "services_rebuilt.jar"
    if rebuilt_services.exists():
        rebuilt_services.unlink()
    run(["java", "-jar", apktool, "b", dec_dir, "-o", rebuilt_services])
    services_out = build_dir / "services.jar"
    shutil.copy2(rebuilt_services, services_out)
    rewrite_zip(services_out, services_out, {"classes4.dex": services_extra_dex})

    if not args.dry_run:
        shutil.copy2(framework_out, framework_jar)
        shutil.copy2(services_out, services_jar)
    print(f"patched framework.jar -> {framework_jar}")
    print(f"patched services.jar -> {services_jar}")


def main():
    parser = argparse.ArgumentParser(description="Patch ZuiControl framework/service jars in an unpacked image tree.")
    parser.add_argument("--unpack", required=True, help="Unpacked image root containing system_a/system/framework")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    patch_framework(args)


if __name__ == "__main__":
    main()
