# Migration TODO — etzhayyim-project-po

**Status**: 🔄 TRANSFORM — thin-edge appview migrated from etzhayyim archive 2026-06-02.

This is a thin-edge dispatcher (edge-proxy → AgentGateway MCP → pod-side LangServer).
No worker-side RisingWave/fiat dependency; business logic runs in the dispatcher/pod.

**Codemod pending** (substrate-boundary ADR-2605172000 / 2605172100):
- ~~Confirm `DISPATCHER_URL` targets an etzhayyim-substrate dispatcher (kotoba).~~
  **Moot as of 2026-08-18.** `DISPATCHER_URL` was read only by
  `appview/po-mcp-component/src/app.ts`, which was in no bundle and has been
  removed by the ClojureScript migration (`docs/adr/0001`). No file in this
  repository reads that var, and `wrangler.jsonc` never declared it. If the
  dispatcher path is wanted back it returns as a route in `src/po/route.cljc`,
  with a test and a declared binding.
- Any settlement path → USDC + ERC-4337 (no Stripe/fiat). **Still open** — this
  repository has no settlement path at all today.
- appview wiring + `kotoba/` reference slice TBD. **Half done**: the appview is
  wired (ClojureScript → `shadow-cljs :esm` → `dist/worker.js`, which is what
  `wrangler.jsonc`'s `main` points at). The `kotoba/` reference slice is still
  TBD — the entry stays in cljs while kotoba's ingress capability is
  `:native-aot` / `:wasm-aot` pending (ADR-2606290000).

## 2026-08-18 — TypeScript/Svelte → ClojureScript

The appview was migrated; see `docs/adr/0001` and `README.md`. This file is
inherited from the archive and is edited here only where the migration made it
false. The substrate-boundary charter review above is **not** done — nothing in
that migration reviewed it.
