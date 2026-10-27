# Changelog

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
