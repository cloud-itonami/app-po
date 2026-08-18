#!/usr/bin/env nbb
;; smoke-worker — 実際にビルドされた bundle を import して叩く。
;;
;; ここが「deploy される成果物」に触る唯一の検査である。テスト
;; (test/po/route_test.cljc) はソースの判断を固定するが、bundle が本当に
;; Worker の形で答えるかは言えない —— export の形、shadow の
;; :advanced-optimization（env のキーが潰れないか）、`shadow.resource/inline`
;; で焼いた CSS は、どれもビルドを通って初めて存在する。
;;
;; Usage:  nbb scripts/smoke-worker.cljs [<dist/worker.js>]
;; Exit:   0 全て期待どおり · 1 期待と違う · 2 判定できなかった（bundle が無い等）

(require '["node:fs" :as fs] '["node:path" :as path] '["node:url" :as url]
         '[clojure.string :as str])

(def bundle
  "ESM の import は相対パスを package 名と読むので、必ず絶対パスに直してから
  file:// URL にする（`dist/worker.js` をそのまま渡すと『Cannot find package
  dist』になる。実測）。"
  (let [a (first (remove #(str/starts-with? % "--") *command-line-args*))]
    (.resolve path (or a "dist/worker.js"))))

(def failures (atom []))
(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" label
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))))

(when-not (.existsSync fs bundle)
  (println (str "UNDETERMINED\tno bundle at " bundle))
  (println "Refusing to report a pass: build it first (see docs/operator-quickstart.md §4).")
  (js/process.exit 2))

(def shown
  "公開表示のために **出ることが正しい** 値の印。APP_DISPLAY_NAME と
  APP_DESCRIPTION はページの見出しと説明文そのものになる。"
  "SHOWN-4b2d91")

(def hidden
  "**出てはいけない** 値の印。実在しそうな値（wrangler の APP_UI_TYPE は
  \"yoro\"）だと二つの問題がある: 他の文言と偶然一致しうるし、引用符ごと探すと
  renderer が `\"` を `&quot;` に escape するので **決して一致しない** ——
  つまり検査が構造的に落ちなくなる。app-ongakuka の移行で実際にこれを踏んだ
  ので、印を使う。"
  "HIDDEN-7c1a4e")

(def env
  "wrangler.jsonc が宣言している 8 つの var。値は印に差し替えるが、**出るべき
  ものと出てはいけないものを別の印にする** —— 一律に『値は出さない』と検査すると、
  ページが意図して出している表示名や説明文まで違反として数えてしまい、逆に
  『全部隠す』ように直せば描くべき事実が消える。AGENTGATEWAY_MCP_ROUTER_URL と
  APP_CAPABILITIES は形が意味を持つので本物を渡す。"
  #js {"APP_NANOID" hidden
       "APP_DISPLAY_NAME" (str "Purchase Orders " shown)
       "APP_DESCRIPTION" (str "po — " shown)
       "APP_FRAMEWORK" hidden
       "APP_PERFORMER_TYPE" hidden
       "APP_UI_TYPE" hidden
       "APP_CAPABILITIES" "[\"createPo\",\"listPos\",\"getPo\",\"approvePo\",\"listSuppliers\",\"createSupplier\",\"recordReceipt\",\"listReceipts\"]"
       "AGENTGATEWAY_MCP_ROUTER_URL" "https://mcp.example.invalid/xrpc/x"})

(defn- call
  ([h method p] (call h method p nil))
  ([h method p init]
   (let [req (js/Request. (str "https://po.etzhayyim.com" p)
                          (clj->js (merge {:method method} init)))]
     (-> (js/Promise.resolve ((.-fetch h) req env #js {}))
         (.then (fn [res] (-> (.text res)
                              (.then (fn [body] {:status (.-status res)
                                                 :ct (.get (.-headers res) "content-type")
                                                 :allow (.get (.-headers res) "allow")
                                                 :body body})))))))))

(-> (js/import (.-href (.pathToFileURL url bundle)))
    (.then
     (fn [m]
       (let [h (.-default m)]
         (check! "default export has fetch" true (fn? (.-fetch h)))
         (-> (js/Promise.all
              #js [(call h "GET" "/") (call h "GET" "/health")
                   (call h "POST" "/xrpc/") (call h "POST" "/xrpc/a/b")
                   (call h "OPTIONS" "/xrpc/x")
                   (call h "GET" "/nope") (call h "POST" "/health")
                   (call h "GET" "/_app/meta")
                   ;; 実際に中継を試させる。宛先は .invalid（RFC 2606 で解決しないと
                   ;; 定められた TLD）なので必ず到達できない。**到達できないことを
                   ;; 200 で隠さない**ことを、ソースではなく bundle に対して確かめる。
                   ;; この 1 本だけが built bundle の中の crypto.randomUUID /
                   ;; js/fetch / unwrap 経路を通る。
                   (call h "POST" "/xrpc/com.etzhayyim.apps.po.listPos"
                         {:headers {"content-type" "application/json"}
                          :body "{\"limit\":1}"})])
             (.then
              (fn [[page health bad multi pre nf mna meta proxied]]
                (check! "GET / status" 200 (:status page))
                (check! "GET / is html" true (str/includes? (or (:ct page) "") "text/html"))
                ;; ページは route 表から描かれる。表にある path が全部出ていること。
                (doseq [p ["/health" "/xrpc/:nsid"]]
                  (check! (str "page advertises " p) true (str/includes? (:body page) p)))
                ;; env のキーは出す、値は出さない
                (check! "page shows a var key" true (str/includes? (:body page) "APP_NANOID"))
                ;; 出るべき値は出る（ページは渡された事実を描く）
                (check! "page renders the display-name it was handed" true
                        (str/includes? (:body page) shown))
                (check! "page renders the relay target it was handed" true
                        (str/includes? (:body page) "mcp.example.invalid"))
                ;; 出てはいけない値は出ない
                (check! "page hides non-display var values" false
                        (str/includes? (:body page) hidden))
                ;; APP_CAPABILITIES は env から読んで描く（ページに焼かない）
                (doseq [c ["createPo" "recordReceipt" "listReceipts"]]
                  (check! (str "page advertises capability " c) true
                          (str/includes? (:body page) c)))
                ;; DDS の CSS が bundle に焼かれている
                (check! "page carries the design system" true
                        (str/includes? (:body page) "dads-table"))
                (check! "GET /health status" 200 (:status health))
                (check! "health names its routes" true
                        (str/includes? (:body health) "/xrpc/:nsid"))
                (check! "health names its capabilities" true
                        (str/includes? (:body health) "approvePo"))
                (check! "health hides non-display var values" false
                        (str/includes? (:body health) hidden))
                (check! "health hides display var values too" false
                        (str/includes? (:body health) shown))
                ;; nsid 無し / 多段の XRPC は 400。前方一致で素通ししない
                (check! "POST /xrpc/ status" 400 (:status bad))
                (check! "POST /xrpc/a/b status" 400 (:status multi))
                (check! "OPTIONS preflight" 204 (:status pre))
                (check! "unknown path" 404 (:status nf))
                (check! "404 lists the real routes" true
                        (str/includes? (:body nf) "POST /xrpc/:nsid"))
                (check! "wrong method" 405 (:status mna))
                (check! "405 carries allow" "GET" (:allow mna))
                ;; app.ts の経路は持ち越していない（README の「持ち越さなかったもの」）
                (check! "/_app/meta not carried over" 404 (:status meta))
                ;; 中継先へ到達できないとき 502。200 でも 404 でもない。
                (check! "unreachable MCP router is 502" 502 (:status proxied))
                (check! "502 names the url it tried" true
                        (str/includes? (:body proxied) "mcp.example.invalid"))
                (let [f @failures]
                  (if (seq f)
                    (do (println (str "FAILED\t" (count f) " check(s): " (str/join ", " f)))
                        (js/process.exit 1))
                    (do (println "OK\tthe built bundle answers as the route table says")
                        (js/process.exit 0))))))))))
    (.catch (fn [e]
              (println (str "UNDETERMINED\tcould not exercise the bundle: " (.-message e)))
              (js/process.exit 2))))
