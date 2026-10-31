# Changelog

## [0.8.0](https://github.com/cgoIT/faktura/compare/v0.7.0...v0.8.0) (2026-10-31)


### Features

* add mail recipients to customers for the send dialog ([3b95d68](https://github.com/cgoIT/faktura/commit/3b95d6865fe854b0e7f26b3af59d34524a809083))
* choose the mail template by the tone set on the customer ([faa9147](https://github.com/cgoIT/faktura/commit/faa9147424e855b54fca4ccce5154f38c6bbe9fb))


### Bug Fixes

* keep the typed recipients when switching the mail template ([da72d2c](https://github.com/cgoIT/faktura/commit/da72d2c275a14e635b0e2393d87b5b010501dae4))

## [0.7.0](https://github.com/cgoIT/faktura/compare/v0.6.0...v0.7.0) (2026-10-31)


### Features

* add a detail page for dunning letters with PDF and mail sending ([63a1b06](https://github.com/cgoIT/faktura/commit/63a1b0670ab734e10276a0d5b14f77cb18699286))
* add links to MCP results ([483428f](https://github.com/cgoIT/faktura/commit/483428f9bff312c3074f55d959963f843a5c037d))
* use icon buttons for actions in table rows ([f0548ce](https://github.com/cgoIT/faktura/commit/f0548cee7ec3322bdb4c052c0f254799f739acab))


### Bug Fixes

* keep the payment state badge inside its table cell ([ac11f2e](https://github.com/cgoIT/faktura/commit/ac11f2e08156ccf8fc87f0766bbb543390c87b79))

## [0.6.0](https://github.com/cgoIT/faktura/compare/v0.5.0...v0.6.0) (2026-10-27)


### Features

* write rolling log files to the data volume ([f8241ab](https://github.com/cgoIT/faktura/commit/f8241abee92afa7dd38b6f5f247e0dee8acd6da6))

## [0.5.0](https://github.com/cgoIT/faktura/compare/v0.4.0...v0.5.0) (2026-10-27)


### Features

* accept the MCP token in the X-MCP-Token header ([6e1046d](https://github.com/cgoIT/faktura/commit/6e1046d082b2d8efc92690e21a2c13ce621a4d3f))
* add MCP server for reading data and creating drafts ([60f0ac5](https://github.com/cgoIT/faktura/commit/60f0ac580cbb6867dfab16c6431292cc8f1aa7ec))
* copy button for the new MCP token and connection steps for Claude Desktop and Claude Code ([58a1079](https://github.com/cgoIT/faktura/commit/58a1079312cb7f258238beeec78ea23aecec53cd))
* let MCP drafts set payment term and total discount ([4ed963d](https://github.com/cgoIT/faktura/commit/4ed963db1e56f0cee9009398a16bffdd42bf00d4))
* make the MCP token header name configurable ([86a3a32](https://github.com/cgoIT/faktura/commit/86a3a32544060815ee517aa34a2aa56b67fbbdd4))
* manage MCP tokens in the web interface (expiry, revoke, scope) ([1ff40cb](https://github.com/cgoIT/faktura/commit/1ff40cbc0b513690619c3cd7df4107bda0ab016a))
* MCP server for AI assistants with admin-managed tokens ([ad16639](https://github.com/cgoIT/faktura/commit/ad1663929833fe499a1367a167b155511bed6ac5))
* refuse MCP without a sign-in mode ([4b49a8c](https://github.com/cgoIT/faktura/commit/4b49a8ce2b570864505ec309d60a858d388dd192))
* show a short confirmation next to each copy button ([c01b068](https://github.com/cgoIT/faktura/commit/c01b0680a07fdf00d035c38415b0a3083963ccd5))
* tell the assistant to ask when a requested detail has no tool parameter ([c256335](https://github.com/cgoIT/faktura/commit/c256335bb748bce47ba9c4116cead07364c94fa5))
* token list with a collapsible guide, separate create form, unlimited tokens ([55e03e2](https://github.com/cgoIT/faktura/commit/55e03e28ee7c6df2e352724027ca2d2fc55fa8ce))


### Bug Fixes

* review findings for the MCP branch (dev base-url, access tests, fixed-width times, style) ([9d0e096](https://github.com/cgoIT/faktura/commit/9d0e096244b4080b368b67121a5be748e6555546))

## [0.4.0](https://github.com/cgoIT/faktura/compare/v0.3.0...v0.4.0) (2026-10-27)


### Features

* add {jahr_kurz} number placeholder, edit the next counter on the numbering page ([471ae29](https://github.com/cgoIT/faktura/commit/471ae29b30c30ef53a2c087d94a73618566631bf))


### Bug Fixes

* send credentials with the manifest request so SSO proxies do not redirect it ([7df839c](https://github.com/cgoIT/faktura/commit/7df839c841612bdd4e9d6f5259e06387d376986c))

## [0.3.0](https://github.com/cgoIT/faktura/compare/v0.2.1...v0.3.0) (2026-10-27)


### Features

* add favicon, logo mark and PWA manifest, write the name in lower case ([3056ee2](https://github.com/cgoIT/faktura/commit/3056ee29695f0b9efcdcdd69d4d49c416bd92ef7))

## [0.2.1](https://github.com/cgoIT/faktura/compare/v0.2.0...v0.2.1) (2026-10-24)


### Bug Fixes

* archive dunnings with unsafe characters in their number ([086f381](https://github.com/cgoIT/faktura/commit/086f3813c508a15f57f57af56b9e74af46a47667))
* **ci:** use a lower-case image name for signing and attestation ([9dd9abf](https://github.com/cgoIT/faktura/commit/9dd9abf18c37a7957066bd640a08d064c6e7cf6e))
* create the directory of the SQLite database at startup ([91a692c](https://github.com/cgoIT/faktura/commit/91a692cad565551927763ae8331fd779bf9524ee))
* safe archive names for dunnings, listen on 127.0.0.1 by default ([bbcd6ea](https://github.com/cgoIT/faktura/commit/bbcd6ea8dabe19b0595fbed24e58fa133266c280))
* **security:** listen on 127.0.0.1 by default outside the container ([ea1c356](https://github.com/cgoIT/faktura/commit/ea1c356cea58e32f81a02e356543f95bbf11f831))

## [0.2.0](https://github.com/cgoIT/faktura/compare/v0.1.0...v0.2.0) (2026-10-23)


### Features

* faktura version in the banner and a log summary of the effective configuration ([f76e757](https://github.com/cgoIT/faktura/commit/f76e7576f1103699343dbb9be884c5636c6b3bda))
* initial public release ([e7f412c](https://github.com/cgoIT/faktura/commit/e7f412c535abb127c45b96afd9ff67302f70863d))
* startup banner ([2839a6a](https://github.com/cgoIT/faktura/commit/2839a6a9d8941639f9185df6a09923ca9c81a85b))
