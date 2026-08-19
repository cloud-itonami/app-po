(ns po.route
  "どのハンドラが答えるか — データと、それを決める純関数。

  これが `.cljs` でなく `.cljc` なのは意図的である。edge worker のうち検査に
  値するのは経路の判断であり、ここならブラウザもビルドもネットワークも無しに
  テストできる。`po.worker` がこの repo で唯一 Request/Response に触る層で、
  そこには「このファイルが既に決めたこと」以外を置かない。

  ingress capability が qualify した時（`:native-aot`/`:wasm-aot` は今日まだ
  pending — ADR-2606290000）に最初に `.kotoba` へ移るのもここである。route 表は
  スカラと文字列の上の判断であり、それがちょうど移行を生き延びる形だからだ。"
  (:require [clojure.string :as str]))

(def routes
  "公開面をデータとして持つ。ランディングページは **この値** を描くので、
  実際に在る route とページが宣伝する route がずれる余地が無い。

  移行前の `+page.svelte` は `routeCount: 0` / `routes: []` / `vars: []` を
  literal で持っており、同じディレクトリの `wrangler.jsonc` が route 2 本と
  var 8 個を宣言していることに気づけなかった。docs/adr/0001 参照。"
  [{:route/path "/"           :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"     :route/method :get  :route/kind :json
    :route/doc "生存確認。deploy された面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を AgentGateway MCP router へ中継する"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。**空文字だけが nil**。

  移行前の SvelteKit の `[...path]` は rest パラメータで、`/xrpc/a/b` を
  nsid 「a/b」として上流へ流していた。ここもそう振る舞う。

  NSID は定義上ドット区切りの単一セグメントなので 400 にする方が厳密である
  —— という判断は妥当だが、**この移行でそれを決めない**。同じ上流に中継する
  同型 appview が 111 本あり、そのうち 4 本は転送、1 本（ここ）だけが 400 と
  いう状態になっていた。検証を足すなら 111 本に対する 1 つの決定として行い、
  移行の commit に紛れ込ませない。揃えた先は多数派ではなく**移行前の挙動**で
  ある。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:health` / `:xrpc` / `:cors-preflight` / `:not-found` /
  `:method-not-allowed` / `:bad-request` のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (str/starts-with? p "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? p "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid p)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})
      (= p "/")       (if (= m :get)
                        {:action :page}
                        {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → AgentGateway MCP router の URL。末尾スラッシュは落とす。

  既定値をここに焼くのは、**どこへ行くのかを 1 箇所で読めるようにする**ため。
  移行前の `+server.ts` と同じ既定・同じ解決順（AGENTGATEWAY_MCP_ROUTER_URL →
  MCP_ROUTER_URL → 既定）を保つ。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(defn decode-capabilities
  "`APP_CAPABILITIES`（wrangler が JSON 文字列で渡す）→ この actor が名乗る
  capability の一覧。

  **「設定されていない」「壊れている」「0 件」を同じ値で返さない。** これは
  この移行が殺しに来た欠陥そのもの（superproject ADR-2608136000: 測れなかった
  検査が、測って問題が無かった検査と同じ値を返す）である。移行前のページは
  `No public route is declared` と書き、それが『調べたが無かった』のか
  『そもそも見ていない』のかを読み手に区別させなかった。"
  [s]
  (if-not (and (string? s) (seq (str/trim s)))
    {:ok? false :reason "APP_CAPABILITIES が env に無い"}
    #?(:cljs (try
               (let [v (js->clj (js/JSON.parse s))]
                 (if (vector? v)
                   {:ok? true :value (mapv str v)}
                   {:ok? false :reason "APP_CAPABILITIES が JSON 配列ではない"}))
               (catch :default _
                 {:ok? false :reason "APP_CAPABILITIES が JSON として読めない"}))
       :default {:ok? false :reason "この runtime に JSON reader が無い"})))

(def ^:private drop-headers
  "上流へ渡さない header。

  `host` —— 移行前の SvelteKit route も削っていた（宛先が変わるので嘘になる）。
  `content-length` / `content-encoding` —— body を JSON-RPC の封筒に詰め直す
  ので、元の長さもエンコーディングも当てはまらない。

  **それ以外は全部渡す。** 移行前は `new Headers(request.headers)` から host を
  削るだけで、`authorization` も上流に届いていた。移行で 3 つの header を新規に
  作る形にしたとき、それが黙って消えていた —— しかも preflight は
  `access-control-allow-headers: content-type,authorization` と許可を宣言した
  ままだったので、ブラウザには送ってよいと言いながら捨てていたことになる。

  **この repo に限っては、その一段だけ形が違った。**同型 21 repo の多くは 3 つを
  新規に作るだけだったが、`po.worker/proxy-xrpc` は `authorization` を名指しで
  読み直して付け直していた。したがってここで実際に消えていたのは
  `authorization` **以外の全部**である。実測 2026-08-19: 移行前の形に戻すと
  `x-trace` の assertion だけが赤くなり、`authorization` の assertion は緑の
  ままだった（一般形の 3-header に戻すと両方が赤くなる）。直した内容は同じでも、
  失われていたものは repo ごとに違う。"
  #{"host" "content-length" "content-encoding"})

(defn relay-headers
  "受け取った header を、上流へ渡す形にする。`in` は [[k v] …] の列。

  ここが `.cljc` にあるのは、これがビルドもブラウザも無しに固定できる**判断**
  だからである。`js/Headers` を worker 側で組み立てる形にすると、何が渡って
  何が落ちるかを述べたテストが書けない —— そしてこの欠陥は、まさに誰も
  『何が転送されるか』を訊かなかったから 21 repo で生き延びた。"
  [in nsid]
  (into {"content-type" "application/json"
         "x-etzhayyim-bff" "cljs-worker"
         "x-etzhayyim-xrpc-method" nsid}
        (comp (remove (fn [[k _]] (contains? drop-headers (str/lower-case k))))
              (map (fn [[k v]] [(str/lower-case k) v])))
        in))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  移行前の `+server.ts` と同じ剥がし方。`{:error …}` は呼び出し側が 502 に
  するので、ここでは判定だけ返す。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false
     :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))
