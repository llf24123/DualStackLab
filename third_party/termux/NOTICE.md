# Termux terminal libraries

This project uses only `terminal-view` and `terminal-emulator` from
https://github.com/termux/termux-app at commit
`8629e632fcb95da272221be327db653fb24befe9`.
It does not include the Termux app or termux-shared module.

The release AARs are published by JitPack under version `8629e632fc`,
classifier `release`. The upstream build uses Android NDK 29.0.14206865.
Their 64-bit native ELF LOAD segments support 16 KB page alignment.
See the delivery evidence for checks of the final APK.

`terminal-view` and `terminal-emulator` Java sources are built locally from
the corresponding modules. The emulator's native libraries are copied
unchanged from the pinned AAR. No local NDK is required.

DualStackLab modifications (October 13, 2026), limited to TerminalSession.java:
- Use independently duplicated file descriptors for the reader and writer.
- Poll the PTY reader and wait for both process exit and reader completion
  before draining the remaining queue, cleaning up, and notifying the UI.
- Continue draining all readable bytes after parent exit without a fixed
  wall-clock cutoff. An idle poll after parent exit ends reading when a
  detached descendant retains the PTY. Continuously producing descendants
  can keep the session open until explicit stop or natural process exit.
- Dispatch copied input bytes in order on a background executor with a
  1 MiB pending-byte limit. Blocking PTY stdin no longer blocks the UI thread.
The original source remains in the complete upstream archive below.

Upstream declares GPL-3.0-only for the repository, with an Apache-2.0
exception for Android Terminal Emulator code in these two modules.
Keep upstream copyright headers and license notices. The complete pinned
upstream source archive, upstream license statement, and both license
texts accompany this personal research project. This notice does not
relicense upstream code.

JitPack artifact URLs:

- https://jitpack.io/com/github/termux/termux-app/terminal-view/8629e632fc/terminal-view-8629e632fc-release.aar
- https://jitpack.io/com/github/termux/termux-app/terminal-emulator/8629e632fc/terminal-emulator-8629e632fc-release.aar

Source archive:
https://codeload.github.com/termux/termux-app/zip/8629e632fcb95da272221be327db653fb24befe9

No code or resources from MT Manager are bundled.

The terminal-view module uses sources from the same 8629e632fc revision. Local changes keep text selection separate from body touch gestures and localize its actions.
