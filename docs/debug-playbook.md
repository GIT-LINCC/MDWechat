# Android Module Debug Playbook

This project uses a low-token, evidence-first Android debugging loop.

## Local Device

- Device id: `FY243261035B`
- Device family: RedMagic / Nubia `NX789J`
- Android: 15
- Target app: `com.tencent.mm`
- Main module package: `com.lincc.mdwechat`
- Preferred adb path: `C:\Users\lcc\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- LSPosed/Vector CLI path: `/data/adb/lspd/cli`

## Main Commands

Build, install, enable scope, and restart WeChat:

```powershell
pwsh ./tools/android/install.ps1
```

Capture a small evidence bundle:

```powershell
pwsh ./tools/android/capture.ps1
```

Trace external storage creation/deletion around WeChat startup:

```powershell
pwsh ./tools/android/trace-storage.ps1
```

Summarize a raw log:

```powershell
pwsh ./tools/android/summarize-log.ps1 -LogFile .codex-debug/captures/<run>/logcat.txt
```

## Workflow

1. Reproduce once.
2. For UI work, update `click-coordinate-memory.md` before tapping or scripted navigation: page/state, target label, coordinate/ref, expected result, and wait condition.
3. Finish page switches, scrolling, dialogs, or other multi-step operations first; run `capture.ps1` only after the intended page/state is stable.
4. Read only `logcat.filtered.txt`, `module-state.txt`, and the screenshot/XML needed for the current question.
5. If UI XML and logs show the affected view but not how it is drawn or bound, use JADX/apktool before trying more visual tweaks. This is especially useful for custom `onDraw`/`dispatchDraw`, adapter bind logic, recycled row types, resource assignment, and WeChat version-specific branches.
6. If the issue is filesystem or media related, run `trace-storage.ps1`.
7. Use a temporary probe only for one narrow question after static analysis points to a candidate path.
8. Remove temporary probe code before finishing.

## Artifact Rules

- Raw evidence belongs in `.codex-debug/`.
- Do not commit screenshots, UI dumps, logcat files, APKs, JADX output, or trace files.
- Keep WeChat reverse-engineering material under `.codex-debug/`; it is ignored by Git.

## Known Storage Lesson

The intermittent WeChat media issue on 2026-05-08 was caused by the Magisk module `HMAPLUS` deleting `/Android/data/com.tencent.mm` through a root `busybox inotifyd` watcher. MDWechat was not the cause. If a similar issue returns, check HMAPLUS protection list before changing MDWechat code.

## Known Logcat Lesson

On this RedMagic/Nubia device, `logd` can be running while `main/system/crash/kernel` buffers report `0 B readable`. In that state `adb logcat -d` may produce empty files even as `events` remains readable. The device also exposes `persist.sys.logcontrol.run=0` and `com.zte.emode/.base.developers.LogSwitch`, so treat empty logcat as a device log-control state, not automatically as a script failure.

When `capture.ps1` produces empty `logcat.txt`, check `logcat-health.txt` first. Prefer module file logs, UI XML, screenshots, LSPosed CLI logs, inotify/ftrace, or Perfetto when main logcat is unreadable.

On the RedMagic/Nubia `NX789J`, the empty `main/system` logcat state was fixed by starting the vendor log-control path:

```powershell
pwsh ./tools/android/logcat-control.ps1 -Action Enable
```

The successful state observed on 2026-05-08 was:

- `persist.vendor.logcontrol.run=1`
- `persist.sys.ztelog.enable=1`
- `main/system/kernel` buffers report readable bytes
- `CodexLogcatProbe` appears in `adb logcat`

`persist.sys.logcontrol.run` may return to `0` after the one-shot `logcontrol` service runs; that alone did not mean the fix failed.
