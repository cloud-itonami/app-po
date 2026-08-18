# operator-quickstart

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 10 分。
Cloudflare のアカウントが要るのは §5（deploy）だけである。

**出力はすべて実際に walk した結果である。** 走らせていないものは走らせていないと
書く。

## 0. 前提

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| java + clojure | `clojure --version` | ビルド時のみ |
| wrangler | `npx --yes wrangler --version` | §4.6 と §5 のみ |

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/app-po.git
cd app-po
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという別の
答えで、「検査して問題なし」と混ぜない。

実際の出力:

```
error: could not read IPC response
SCANNED	19
PASS	tracked-files	expected=19	actual=19
PASS	preserved-bytes	expected=7622	actual=7622
PASS	preserved-files-unchanged	expected=[]	actual=[]
PASS	removed-by-migration-absent	expected=[]	actual=[]
PASS	production-ts-files	expected=0	actual=0
PASS	production-svelte-files	expected=0	actual=0
PASS	production-canonical-files	expected=4	actual=4
PASS	wrangler-main	expected="../../dist/worker.js"	actual="../../dist/worker.js"
PASS	declared-vars	expected=8	actual=8
PASS	declared-routes	expected=2	actual=2
PASS	no-stale-assets-binding	expected=true	actual=true
PASS	no-adapter-compat-flags	expected=true	actual=true
PASS	app-framework	expected="cljs-esm-worker"	actual="cljs-esm-worker"
PASS	build-fails-on-warnings	expected=true	actual=true
PASS	shadow-output-dir	expected="dist"	actual="dist"
PASS	shadow-export	expected=po.worker/handler	actual=po.worker/handler
PASS	shadow-builds-that-main	expected=true	actual=true
PASS	page-renders-route-table	expected=true	actual=true
PASS	page-renders-env-capabilities	expected=true	actual=true
PASS	capabilities-distinguish-unreadable-from-empty	expected=true	actual=true
OK	every claim in README.md and docs/operator-quickstart.md holds
```

この検査には移行の不変条件が入っている: TypeScript / Svelte が戻っていないこと
（撤去した 12 パスの不在 + 言語別の総数）、`wrangler.jsonc` の `main` が shadow の
出力先を指していること、**ビルドが warning で落ちる設定であること**（これが無いと
「ビルドが通った」は何も意味しない。§4 の注を読むこと）、ページが route 表と env から
描かれていること、capability の「読めなかった」と「0 件」が別々に扱われていること。

`shadow-cljs.edn` に関する 3 つの claim は **EDN として構造で読んでいる**。
最初は部分文字列で見ていたが、`:warnings-as-errors` を設定から消す mutation を
当てても緑のままだった —— このファイルの冒頭コメントが同じ文字列を含んでおり、
検査はそれに当たっていたからである。**コメントが満たせる検査は散文についての
検査である。**

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので、nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'po.route-test)
(run-tests 'po.route-test)
EOF
npx --yes nbb --classpath "$CP" /tmp/run.cljs
```

実際の出力:

```
Testing po.route-test

Ran 7 tests containing 33 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**空の nsid だけ** 400 にする（`/xrpc/a/b` は
移行前の rest パラメータと同じく中継する）、MCP router の URL 解決（空白だけの
設定は未設定として扱う）、`result` / `structuredContent` の剥がし方、
**`APP_CAPABILITIES` の未設定 / 壊れた JSON / 配列でない / 0 件を別々に扱うこと**、
そして**ページが route 表から描かれること**（固定値を焼いていたら落ちる）。

## 3. ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/render.cljs <<'EOF'
(require '["node:fs" :as fs] '[po.view :as view] '[po.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs "/tmp/po-page.html"
    (view/render {:css css :routes route/routes
                  :title "Purchase Orders"
                  :vars [:APP_NANOID :APP_UI_TYPE]
                  :capabilities (route/decode-capabilities "[\"createPo\",\"approvePo\"]")
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}))
  (println "ok"))
EOF
DDS="$K/jp-go-digital-design-system" npx --yes nbb --classpath "$CP" /tmp/render.cljs

cd $K/design-quality && npx --yes nbb -m design-quality.cli score /tmp/po-page.html --min 95
```

実際の出力（末尾）:

```
  100.00  /tmp/po-page.html

aggregate: 100.00

findings (headroom-first):
  (none — converged)
gate: aggregate 100.00 >= min 95.00 -> PASS
```

## 4. bundle をビルドする

**「ビルドが通った」を信じる前に。** `shadow-cljs.edn` は
`:compiler-options {:warnings-as-errors true}` を持っている。**これが無いと
このゲートは何も検査しない** —— 実測 2026-08-18、存在しない関数を呼ぶよう
`worker.cljs` を書き換えて `release` を回したところ、shadow-cljs は
`(55 files, 1 compiled, 1 warnings, 16.62s)` と表示して **exit 0 で
`dist/worker.js` を書き出した**。その bundle は import した瞬間に壊れ、
`smoke-worker.cljs` が `UNDETERMINED could not exercise the bundle` (exit 2) で
捕まえた。**「ビルドが緑」は「bundle が動く」ではない。**

flag を足したあと同じ書き換えを当て直すと、ビルドは **exit 1** で止まり
（`ExceptionInfo: Use of undeclared Var po.route/dispatch-typo`）、
`dist/worker.js` は書き換えられなかった。無改変では
`(55 files, 0 compiled, 0 warnings, 40.98s)` で通る。**両方向を見てある。**

**高負荷ビルドは workspace 全体で同時 1 本に制限されている**（superproject
`CLAUDE.md` の resource governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
ls -la dist/worker.js
```

lock を他セッションが持っていると `resource-guard: build is already running
(pid=…)` と言って止まる。**これはエラーではなく順番待ちである。迂回しない** ——
この walk では 34 回連続で
待たされた（45 秒ごとに再試行）。

実際の出力（末尾）:

```
[:worker] Compiling ...
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 99.52s)
```

（`dist/worker.js` は 247,140 バイト。上は初回。ソースを 1 本直しての 2 回目は
`(55 files, 2 compiled, 0 warnings, 40.22s)` で 247,374 バイトだった。）

この walk では guard の順番待ちが**実測で 40 分以上**あった（並行して別 repo の
同種の移行が同じ build slot を取り合っていた —— lock を持っていたのは
`cloud-murakumo` → `app-lo-cljs` → `app-analytics-cljs` と移った）。**待つのが正しい。**

## 4.5 ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。テスト（§2）はソースの判断を
固定するが、bundle が本当に Worker の形で答えるかは言えない —— export の形、
`:advanced-optimization` 下で env のキーが潰れないか、`shadow.resource/inline` で
焼いた CSS は、どれもビルドを通って初めて存在する。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

実際の出力:

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
PASS	GET / is html	expected=true	actual=true
PASS	page advertises /health	expected=true	actual=true
PASS	page advertises /xrpc/:nsid	expected=true	actual=true
PASS	page shows a var key	expected=true	actual=true
PASS	page renders the display-name it was handed	expected=true	actual=true
PASS	page renders the relay target it was handed	expected=true	actual=true
PASS	page hides non-display var values	expected=false	actual=false
PASS	page advertises capability createPo	expected=true	actual=true
PASS	page advertises capability recordReceipt	expected=true	actual=true
PASS	page advertises capability listReceipts	expected=true	actual=true
PASS	page carries the design system	expected=true	actual=true
PASS	GET /health status	expected=200	actual=200
PASS	health names its routes	expected=true	actual=true
PASS	health names its capabilities	expected=true	actual=true
PASS	health hides non-display var values	expected=false	actual=false
PASS	health hides display var values too	expected=false	actual=false
PASS	POST /xrpc/ status	expected=400	actual=400
PASS	POST /xrpc/a/b status	expected=400	actual=400
PASS	OPTIONS preflight	expected=204	actual=204
PASS	unknown path	expected=404	actual=404
PASS	404 lists the real routes	expected=true	actual=true
PASS	wrong method	expected=405	actual=405
PASS	405 carries allow	expected="GET"	actual="GET"
PASS	/_app/meta not carried over	expected=404	actual=404
PASS	unreachable MCP router is 502	expected=502	actual=502
PASS	502 names the url it tried	expected=true	actual=true
OK	the built bundle answers as the route table says
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない）。
壊れた bundle でも exit 2 を返すが、文言が違う（`no bundle at …` と
`could not exercise the bundle: …`）ので読み分けられる。

この検査が **落ちること**も確かめてある: worker.cljs が env の値をページに
渡すよう書き換えると `FAIL page hides non-display var values` で exit 1 になり、
`page renders the display-name it was handed` は緑のまま残る（2 つの印が
独立に効いている）。

## 4.6 compatibility_flags 無しで workerd が動くことを確かめる

移行で `nodejs_compat` / `nodejs_als` を撤去した（`@sveltejs/adapter-cloudflare`
のためのもので、shadow-cljs の `:esm` bundle は node の組み込みも
AsyncLocalStorage も使わない）。**これは推測ではなく、flag 無しの状態で実際に
workerd を起動して確かめてある。** `wrangler dev` は deploy ではない。

```bash
cd "$REPO/appview/po-mcp-component"
npx --yes wrangler dev --port 8799 --ip 127.0.0.1 &
# ("Ready on http://127.0.0.1:8799" が出たら。--local は wrangler 4 では既定)
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8799/
curl -s http://127.0.0.1:8799/health
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8799/xrpc/
```

実際の出力:

```
$ npx --yes wrangler dev --port 8799 --ip 127.0.0.1
 ⛅️ wrangler 4.69.0
Your Worker has access to the following bindings:
env.APP_CAPABILITIES ("["createPo","listPos","getPo","approv...")   Environment Variable   local
env.APP_DESCRIPTION  ("po — Purchase Order & Supply Chain Pl...")   Environment Variable   local
env.APP_DISPLAY_NAME ("Purchase Orders")                            Environment Variable   local
env.APP_FRAMEWORK    ("cljs-esm-worker")                            Environment Variable   local
env.APP_NANOID       ("c11ertj5")                                   Environment Variable   local
env.APP_PERFORMER_TYPE ("service")                                  Environment Variable   local
env.APP_UI_TYPE      ("yoro")                                       Environment Variable   local
env.AGENTGATEWAY_MCP_ROUTER_URL ("https://mcp.etzhayyim.com/xrpc/com.et...")  Environment Variable   local
⎔ Starting local server...
[wrangler:info] Ready on http://127.0.0.1:8799

GET /            -> 200 text/html; charset=utf-8
GET /health      -> 200
  body: {"ok":true,"app":"po","runtime":"cljs","actor":"did:web:po.etzhayyim.com","bpmn":"bpmn/po.bpmn","routes":["/","/health","/xrpc/:nsid"],"capabilities":["createPo","listPos","getPo","approvePo","listSuppliers","createSupplier","recordReceipt","listReceipts"]}
POST /xrpc/      -> 400
POST /xrpc/a/b   -> 400
OPTIONS /xrpc/x  -> 204
GET /nope        -> 404
POST /health     -> 405
GET /_app/meta   -> 404
POST /xrpc/com.etzhayyim.apps.po.listPos -> 502
  body: {"error":"MCP router unreachable","detail":"internal error; reference = 3kmtntgknp950532m9nrkpna","url":"https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}

page bytes: 83046
page renders real routes: 1 hit(s) for /xrpc/:nsid
capabilities from env:    1 hit(s) for recordReceipt
design system in bundle:  71 hit(s) for dads-table
wrangler log: no error, no exception, no "nodejs_compat" complaint
```

## 5. deploy

```bash
cd "$REPO/appview/po-mcp-component"
npx wrangler deploy
```

**この walk では deploy していない。** 移行は出荷ではないからで、実行結果を
ここに書けないのはそのためである。

deploy する前に知っておくこと: **route が指すホストは解決しない**
（`po.etzhayyim.com` / `c11ertj5.etzhayyim.com` とも NXDOMAIN、2026-08-18 実測）。
deploy が成功しても誰も到達できない。`/xrpc/` の中継先 `mcp.etzhayyim.com` も
同様なので、到達できたとしても中継は **502 を返す**（成功と同じ形で隠さない）。

superproject の deploy guard は `origin/main` を含む checkout からの deploy しか
許さない点も併せて注意（`.claude/hooks/wrangler-deploy-main-sync-guard.cljs`）。

## 6. ここに無いもの

- **`/xrpc/com.etzhayyim.apps.po.*` の dispatcher 中継**と **`/_app/meta`** ——
  移行前の `src/app.ts` にあり、どこにも deploy されていなかった経路。宛先が
  NXDOMAIN で binding も `wrangler.jsonc` に無いので**持ち越していない**
  （README の「持ち越さなかったもの」）
- **業務ロジックそのもの**（AgentGateway MCP の先、pod 側 LangServer にある）
- **`MIGRATION-TODO.md` の substrate-boundary 憲章レビュー**（未実施）
- **決済経路**（USDC + ERC-4337。この repo に存在しない）
