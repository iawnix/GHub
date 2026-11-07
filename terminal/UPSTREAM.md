# Termux terminal components

Source: https://github.com/termux/termux-app

Pinned commit: `8629e632fcb95da272221be327db653fb24befe9`.

Only terminal-emulator and terminal-view Java sources are included. TerminalSession is replaced with an SSH-backed adapter; JNI and local subprocess execution are omitted. Original per-file copyright and license headers are retained. Upstream is GPL-3.0-only with Apache-2.0 exceptions documented by Termux.

## Local modifications

- Removed upstream `TerminalSession.java` and the JNI/process bridge. `TerminalSession.kt` implements the same view-facing contract with remote SSH reads, writes and PTY resize callbacks.
- Adjusted the terminal view's generated resource import to `dev.remotegpt.terminal.R`.
- Added `TerminalCallbacks.kt` with default callbacks and explicit clipboard confirmation in the app.
- The adapter bounds pending UI reads and initial output buffering.
- Added a default `TerminalViewClient.onKeyInput()` callback, called after input is consumed, so the app can implement one-shot and locked modifier keys while retaining upstream key encoding.
- Reduced the view's minimum row count from four to two to keep the cursor visible in short viewports, including landscape layouts with the keyboard open.
- Added per-session theme defaults to `TerminalColors`; theme changes preserve remote OSC overrides, and OSC resets restore the latest theme defaults. The app repaints palette changes without resetting scrollback.
- Gradle configuration is maintained by this project; no upstream Termux app, packages or shell process launcher is included.

The upstream license notice is copied to `../licenses/Termux-LICENSE.md`.
