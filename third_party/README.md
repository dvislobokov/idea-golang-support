# third_party

## delve

`delve/` holds the sources of [delve](https://github.com/go-delve/delve) **v1.27.2** (the tag, with `vendor/`), as plain files of this
repository — not a git submodule, so a ZIP of the repository from GitHub and a shallow clone carry them too. The build packs them into `delve/` of
the plugin (`build.gradle.kts`: `go.mod`, `go.sum`, `LICENSE`, `cmd/`, `pkg/`, `service/`, `vendor/`, without tests and fixtures) together with
`SOURCE-HASH`; the plugin builds `dlv` from them with the user's `go` on the user's machine (`GoBundledDelve`). Nothing is built here: the build of
the plugin needs neither `go` nor network for this.

Update to another release:

```sh
git clone --depth 1 --branch v1.28.0 https://github.com/go-delve/delve.git /tmp/delve
rm -rf third_party/delve && cp -r /tmp/delve third_party/delve && rm -rf third_party/delve/.git third_party/delve/.github
```

Then change the version here, in `NOTICE.md` and in `docs/guide.html` (Debugger section), run `GoBundledDelveTest` and `buildPlugin`.
License: `delve/LICENSE` (MIT), also listed in `NOTICE.md`.
