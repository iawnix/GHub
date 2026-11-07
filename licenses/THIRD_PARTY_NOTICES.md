# Third-party components

GHub is distributed under GPL-3.0-only.

| Component | Upstream | License |
|---|---|---|
| Termux terminal-emulator / terminal-view | https://github.com/termux/termux-app | GPL-3.0-only with per-file Apache-2.0 exceptions |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | https://github.com/JetBrains/kotlin | Apache-2.0 |
| AndroidX / Compose / Room / DataStore | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| SSHJ | https://github.com/hierynomus/sshj | Apache-2.0 |
| Bouncy Castle | https://www.bouncycastle.org/licence.html | Bouncy Castle License (MIT-style) |
| Java-WebSocket | https://github.com/TooTallNate/Java-WebSocket | MIT |
| SLF4J | https://www.slf4j.org/license.html | MIT |
| Multiplatform Markdown Renderer | https://github.com/mikepenz/multiplatform-markdown-renderer | Apache-2.0 |
| Phosphor Icons 2.1.1 — Bold (Copyright (c) 2023 Phosphor Icons) | https://github.com/phosphor-icons/core | MIT |
| Haze 1.6.10 (Copyright Christopher Banes and contributors) | https://github.com/chrisbanes/haze | Apache-2.0 |
| JLaTeXMath Android 0.2.0 | https://github.com/noties/jlatexmath-android | GPL-2.0-or-later with linking exception |
| Coil | https://github.com/coil-kt/coil | Apache-2.0 |

Termux source and resource files are vendored in the terminal module. Copyright and license headers are retained; the exact upstream revision and modifications are recorded in `terminal/UPSTREAM.md`. The upstream local-process/JNI session is replaced by a Kotlin remote-PTY adapter. No Termux runtime, package manager or shell binaries are bundled.

Selected Phosphor Bold SVGs from `@phosphor-icons/core` 2.1.1 are converted to Compose ImageVectors with original paths, 256-unit viewports and fill rules retained. Display size and tint are supplied by GHub. The full MIT license is included in `Phosphor-LICENSE.txt`; source and reproduction details are in `app/design/phosphor/UPSTREAM.md`.
