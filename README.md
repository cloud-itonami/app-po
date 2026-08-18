# app-po

**発注（Purchase Order）とサプライチェーンの appview。** `po` は purchase order の
略で、この repo が持つのは**その公開面（appview）だけ**である —— PO の作成・承認・
仕入先登録・入荷記録という業務ロジックそのものは AgentGateway MCP の先（pod 側
LangServer）にあり、ここには無い。ここに在るのは edge の薄い面と、BPMN の
プロセス定義（`bpmn/po.bpmn`）である。

`etzhayyim/root` の `60-apps/etzhayyim-project-po` からの抽出物で、
**2026-08-18 に TypeScript/Svelte から ClojureScript へ移行した**（`docs/adr/0001`）。
このファイルの数値はすべて `scripts/verify-docs-claims.cljs` が tree から
再計算して検査する。

## deploy されるものは、いま読んでいるソースである

```
src/po/route.cljc    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/po/view.cljc     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/po/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js       ← appview/po-mcp-component/wrangler.jsonc の "main" が指すもの
```

`shadow-cljs.edn` は `:compiler-options {:warnings-as-errors true}` を持つ。
**これが無いと「ビルドが通った」は何も検査しない** —— 実測 2026-08-18、存在しない
関数を呼ぶよう `worker.cljs` を書き換えて `release` を回したところ、shadow-cljs は
`1 warnings` と表示して **exit 0 で `dist/worker.js` を書き出し**、その bundle は
import した瞬間に壊れた（`scripts/smoke-worker.cljs` が exit 2 で捕まえた）。
検証器の `build-fails-on-warnings` がこの設定を固定している。

移行前は `main` が `svelte/.svelte-kit/cloudflare/_worker.js`（SvelteKit の
ビルド出力、tree に無い）を指し、読み手が最も application らしいと感じる
`appview/po-mcp-component/src/app.ts` は**どの bundle にも入っていなかった** ——
実測 `grep` で、この tree の中から `app.ts` を参照するファイルは **0 件**だった。
いまは `main` が指す bundle が上のソースからコンパイルされたものなので、その形は
構造的に起こり得ない。`scripts/verify-docs-claims.cljs` が
**shadow の出力先と wrangler の `main` と export の ns 名の 3 つが噛み合って
いること**を検査し、噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、
ingress capability が qualify した時に **最初に `.kotoba` へ移る部分**だからで
ある（入口を当面 cljs に置くのは ADR-2606290000 の判断で、kotoba の ingress は
`:native-aot` / `:wasm-aot` とも pending）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を AgentGateway MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight |

**この表の出所は `po.route/routes` で、ページもそこから描く。** 移行前のページ
（`+page.svelte`）は `routeCount: 0` / `routes: []` / `vars: []` を literal で
持っており、同じディレクトリの `wrangler.jsonc` が route 2 本・var 8 個・
capability 8 個を宣言していることに気づけなかった。いまは route 表も env も
渡す側が持ち、ページは描くだけなので、両者がずれる余地が無い。

`/health` は移行前の `src/app.ts` にもあった経路で、**上流にも binding にも
依存しない**ので持ち越した（`app.ts` は deploy されていなかったので、これは
「復活」ではなく「deploy される面に初めて置いた」である）。

### 意図的に変えた request semantics が 1 つある

移行前の SvelteKit の `/xrpc/[...path]` は rest パラメータなので `/xrpc/a/b` を
nsid `"a/b"` として上流へ流していた。NSID は定義上ドット区切りの**単一
セグメント**なので、cljs 版は多段パスを **400** にする。前方一致で素通しさせない
ためで、`test/po/route_test.cljc` がこれを固定している。それ以外
（jsonrpc `tools/call` の封筒、`result` / `structuredContent` の剥がし方、
`cache-control: no-store`、preflight の 204 とヘッダ、`AGENTGATEWAY_MCP_ROUTER_URL`
→ `MCP_ROUTER_URL` → 既定 の解決順）は移行前と同じである。

上流へ転送するヘッダも変えた: 移行前は受信ヘッダを丸ごと（`host` だけ落として）
転送していたが、cljs 版は `content-type` / `x-etzhayyim-bff` / `x-etzhayyim-xrpc-method`
と、在れば `authorization` だけを送る。`x-etzhayyim-bff` の値は
`sveltekit-edge-bff` から `cljs-worker` に変えた —— BFF が実際にもう SvelteKit
ではないからである。

## いま在るもの — 19 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/po/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/po/route_test.cljc`（7 tests / 33 assertions） |
| ビルド | `deps.edn` / `shadow-cljs.edn` / `.gitignore` |
| 検査 | `scripts/{verify-docs-claims.cljs, smoke-worker.cljs}` |
| Worker 設定 | `appview/po-mcp-component/wrangler.jsonc` |
| actor 記述子 | `appview/po-mcp-component/kotodama.jsonld` |
| プロセス定義 | `bpmn/po.bpmn` |
| 由来・権利・識別 | `NOTICE` / `README.edn` / `migration.edn` / `MIGRATION-TODO.md` |
| 文書 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/0001-*.edn` |

**production の TypeScript は 0 本、Svelte も 0 本、正本言語（`.cljs` / `.cljc`）が
4 本。移行前は TypeScript 5 本 + Svelte 1 本 対 正本 0 本だった**
（production = `scripts/` 以外の追跡ファイル）。この 3 つの数は検証器の claim
なので、TS が戻れば落ちる —— **撤去した 12 パスに戻る場合**
（`removed-by-migration-absent`）も、**別名で入る場合**（`production-ts-files` /
`production-svelte-files`）も、別々の claim が捕まえる。

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム、
skill `kotoba-uiux` が定める新規 UI の base）。色・寸法は `--hig-*` トークン契約
だけで書き、raw hex も px フォントサイズも置かない。app 固有 CSS は 3 行。CSS は
外部リクエストゼロの方針どおり `shadow.resource/inline` で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100（gate 95）**。

### ページが出す env の値は 3 つだけで、それを名指ししている

ページは `APP_DISPLAY_NAME`（見出し）・`APP_DESCRIPTION`（説明文）・
`AGENTGATEWAY_MCP_ROUTER_URL`（中継先）の **値** を描き、他の var は **キー名だけ**
描く。ページ上の注記がその 3 つを名指しする。

最初の版は「キー名のみ。値は出さない。」と書いていたが、見出しがすでに
`APP_DISPLAY_NAME` の値だったので**ページが自分について嘘をついていた**。
`scripts/smoke-worker.cljs` を built bundle に当てた 1 回目がこれを見つけた
（`FAIL page hides var values`）。検査は 2 つの印に分かれていて、出るべき値が
出ること **と** 出てはいけない値が出ないことを別々に見る —— 片方だけだと、
「全部隠す」に直しても緑になってしまい、描くべき事実が消える。

### ページが「読めなかった」を「0 件」に化けさせない

`APP_CAPABILITIES` は wrangler が JSON 文字列で渡す。`po.route/decode-capabilities`
は **未設定 / 壊れた JSON / 配列でない / 0 件**を別々の値で返し、ページはそれを
別々の文で描く。superproject ADR-2608136000 が名指しした class
（測れなかった検査が、測って問題が無かった検査と同じ値を返す）で、移行前の
`No public route is declared` はまさにそれだった —— 調べて無かったのか、
そもそも見ていないのかが読み手に区別できない。

## 持ち越さなかったもの（黙って消していない）

移行前の `appview/po-mcp-component/src/app.ts` にあってどこにも deploy されて
いなかった経路のうち、次は**意図的に移していない**:

| 経路 | 測った理由 |
|---|---|
| `POST\|GET /xrpc/com.etzhayyim.apps.po.*` → dispatcher への中継 | 宛先 `dispatcher.etzhayyim.com` が **NXDOMAIN**（`dig +short` が A も AAAA も返さない、2026-08-18 実測）。加えて経路が読む `DISPATCHER_URL` と `DISPATCHER_INTERNAL_SECRET` は **`wrangler.jsonc` に 1 つも宣言されていない** |
| `GET /_app/meta` | `/health` と同一の payload を返す別名。deploy される面に生存確認の入口を 2 本置く理由が無い（`/health` を持ち越した） |

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`src/po/route.cljc` に足し、テストと binding を伴って戻す。

## 呼び先が 1 つも解決しない（移行では直らない）

2026-08-18 実測（`dig +short`）:

| ホスト | 役割 | A | AAAA |
|---|---|---|---|
| `po.etzhayyim.com` | 公開ホスト（wrangler の route） | **なし** | **なし** |
| `c11ertj5.etzhayyim.com` | 同（nanoid 側） | **なし** | **なし** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **なし** | **なし** |
| `dispatcher.etzhayyim.com` | 持ち越さなかった経路の宛先 | **なし** | **なし** |
| `etzhayyim.com` | apex（参考） | 172.67.179.128 / 104.21.51.111 | あり |

deploy 先も中継先も、いま存在しない。`/xrpc/` は到達できなければ **502 を返す**
—— 成功と同じ形で隠さない。**この移行はそれを直さない。** deploy するか retire
するかは別の決定である。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `fe16fe22` と宣言し、
`:allowed-additions` に `README.edn` と `migration.edn` を持つ。移行後の状態:

- 継承した 5 ファイル（7,622 バイト）は**いまも 1 バイトも変わっていない**
  （`NOTICE` / `README.edn` / `migration.edn` / `kotodama.jsonld` / `po.bpmn`。
  sha256 を検証器に固定してある）
- `wrangler.jsonc` は**意図的に変更**した（`main` の付け替え、消えた SvelteKit
  client を指す `assets` の撤去、`compatibility_flags` の撤去、`.wasm` が
  1 つも無い tree に残っていた `rules` の撤去、`APP_FRAMEWORK` を
  `sveltekit-edge-bff` → `cljs-esm-worker`）。`nodejs_compat` / `nodejs_als` は
  `@sveltejs/adapter-cloudflare` のためのもので、**外した状態の workerd を
  実際に起動して全 route を叩いて確かめた**（`docs/operator-quickstart.md` §4.6。
  9 経路すべてが期待どおりの status を返し、wrangler のログに error は 0 件）
- `MIGRATION-TODO.md` も**意図的に変更**した。`DISPATCHER_URL` を確認せよという
  項目が、その var を読む唯一のファイル（`src/app.ts`）ごと消えて moot に
  なったため。他の項目は開いたまま残してある
- TypeScript / Svelte / npm の 12 ファイルは**移行で撤去**した。検証器はその
  12 パスを名指しで「不在であること」を検査する —— byte 合計は
  「TypeScript が消えた」と言えない

## 残っている欠陥（移行では直っていない）

1. **ホストが 4 つとも NXDOMAIN**（上表）。deploy しても誰も到達できない。
2. **`MIGRATION-TODO.md` の substrate-boundary 憲章レビューが未実施。** 決済経路
   （USDC + ERC-4337）はこの repo に存在しない。`kotoba/` reference slice も TBD。
3. **`/xrpc/` の先が実装されているかを、この repo は知らない。** 中継するだけで、
   8 つの capability が MCP router の向こうで実在するかは検査していない。

## 検証

```bash
npx --yes nbb scripts/verify-docs-claims.cljs .          # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テスト・ビルド・bundle の実行は `docs/operator-quickstart.md`。
