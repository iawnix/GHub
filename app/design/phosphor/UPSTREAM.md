# Phosphor Icons — Bold

Copyright (c) 2023 Phosphor Icons.
Source: https://github.com/phosphor-icons/core
Package: https://www.npmjs.com/package/@phosphor-icons/core/v/2.1.1
Version: 2.1.1, Bold weight.
License: MIT. Full license: `licenses/Phosphor-LICENSE.txt`, also available inside
GHub under open-source licenses.

Only the selected upstream `assets/bold/*-bold.svg` files are vendored, unchanged.
`scripts/generate_icons.py` converts them to cached Compose ImageVectors in
`WorkbenchIcons.kt`, retaining the original paths, 256-unit viewports and fill
rules. Bold geometry comes from the artwork itself; no extra stroke or synthetic
weight is added. Compose supplies display size and theme/disabled tint. Navigation
arrows and the trailing navigation chevron mirror with layout direction; physical
objects do not.

Regenerate offline with `python3 scripts/generate_icons.py`. To fetch sources again,
use `python3 scripts/generate_icons.py --fetch`; the pinned npm tarball is verified
against its SHA-512 integrity hash before reading any files. Normal builds require
neither network access nor code generation. No icon font or private-use Unicode
characters are bundled.
