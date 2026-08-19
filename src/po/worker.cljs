(ns po.worker
  "Cloudflare Worker の入口。**この repo で唯一 Request/Response に触る層。**

  ここには判断を置かない —— どのハンドラが答えるかは `po.route/dispatch` が
  決め、ページの中身は `po.view` が組む。どちらも `.cljc` なので、ブラウザも
  ビルドも無しにテストできる。

  `wrangler.jsonc` の `main` は `../../dist/worker.js` を指し、それはこの
  名前空間をコンパイルしたものである。移行前は SvelteKit のビルド出力を指して
  いて、読み手が開く `src/app.ts` はどの bundle にも入っていなかった
  （docs/adr/0001）。

  `aget` を使うのは `:advanced-optimization` 下で env のキーが潰れないため
  （先例 `listingops.edge.worker` と同じ約束）。"
  (:require [po.route :as route]
            [po.view :as view]
            [shadow.resource :as rc]
            [clojure.string :as str]))

(def ^:private dds-css
  "DADS の CSS はビルド時に bundle へ焼く。外部リクエストゼロが design system
  の方針で、Worker から resource を読む経路も無い。"
  (rc/inline "jp_go_dds/dds.css"))

(defn- ->response [body {:keys [status content-type cache extra]}]
  (js/Response.
   body
   #js {:status status
        :headers (clj->js (merge {"content-type" content-type
                                  "cache-control" (or cache "no-store")}
                                 extra))}))

(defn- json [body status]
  (->response (js/JSON.stringify (clj->js body))
              {:status status :content-type "application/json; charset=utf-8"}))

(defn- env->map
  "env の **キーだけ** を keyword で拾う。値はページにも応答にも出さない。"
  [env]
  (if env
    (into {} (map (fn [k] [(keyword k) (aget env k)])) (js/Object.keys env))
    {}))

(defn- cors-headers []
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST,OPTIONS"
   "access-control-allow-headers" "content-type,authorization"
   "access-control-max-age" "86400"})

(defn- proxy-xrpc
  "XRPC を AgentGateway MCP router へ中継する。移行前に deploy されていた
  SvelteKit の `/xrpc/[...path]` と同じ形（jsonrpc の封筒に包み、
  `result` / `structuredContent` を剥がし、`cache-control: no-store` で返す）。"
  [req env nsid]
  (let [e (env->map env)
        url (route/mcp-router-url e)]
    (-> (.json req)
        (.catch (fn [_] #js {}))
        (.then
         (fn [input]
           (js/fetch url
                     #js {:method "POST"
                          ;; 受け取った header を渡す。この repo は
                          ;; `authorization` だけを名指しで拾い直していたので、
                          ;; 認証は通っていたが**それ以外の header は全部消えて
                          ;; いた**（route/drop-headers）。
                          :headers (clj->js (route/relay-headers
                                             (map (fn [pair] [(aget pair 0) (aget pair 1)])
                                                  (es6-iterator-seq (.entries (.-headers req))))
                                             nsid))
                          :body (js/JSON.stringify
                                 #js {:jsonrpc "2.0"
                                      :id (.randomUUID js/crypto)
                                      :method "tools/call"
                                      :params #js {:name nsid :arguments input}})})))
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              (let [payload (try (when (seq text) (js/JSON.parse text))
                                                 (catch :default _ text))
                                    clj-payload (js->clj payload :keywordize-keys true)]
                                (if-not (.-ok resp)
                                  (json {:error "MCP router request failed"
                                         :upstream clj-payload}
                                        (.-status resp))
                                  (let [{:keys [ok? value error upstream]}
                                        (route/unwrap-mcp clj-payload)]
                                    (if ok?
                                      (json (or value {}) 200)
                                      (json {:error error :upstream upstream} 502))))))))))
        (.catch (fn [e']
                  ;; 到達できなかったことを 200 で隠さない。移行時点で
                  ;; mcp.etzhayyim.com は A レコードを返さないので、これは
                  ;; 想像上の経路ではなく今日の既定の結末である。
                  (json {:error "MCP router unreachable"
                         :detail (str (.-message e'))
                         :url url}
                        502))))))

(defn- page-response [env]
  (let [e (env->map env)]
    (->response
     (view/render {:css dds-css
                   :title (:APP_DISPLAY_NAME e)
                   :description (:APP_DESCRIPTION e)
                   :routes route/routes
                   :vars (sort (keys e))
                   :capabilities (route/decode-capabilities (:APP_CAPABILITIES e))
                   :mcp-url (route/mcp-router-url e)})
     {:status 200
      :content-type "text/html; charset=utf-8"
      :cache "public, max-age=60"})))

(defn- health-response [env]
  (let [e (env->map env)
        caps (route/decode-capabilities (:APP_CAPABILITIES e))]
    (json (cond-> {:ok true
                   :app "po"
                   :runtime "cljs"
                   :actor "did:web:po.etzhayyim.com"
                   :bpmn "bpmn/po.bpmn"
                   :routes (mapv :route/path route/routes)}
            (:ok? caps) (assoc :capabilities (:value caps))
            ;; 読めなかったことを「0 件」に化けさせない。
            (not (:ok? caps)) (assoc :capabilities-unavailable (:reason caps)))
          200)))

(defn fetch-handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        {:keys [action nsid allow reason]} (route/dispatch (.-method req) path)]
    (case action
      :page   (page-response env)
      :health (health-response env)
      :xrpc   (proxy-xrpc req env nsid)
      :cors-preflight (->response nil {:status 204 :content-type "text/plain"
                                       :extra (cors-headers)})
      :bad-request (json {:error reason} 400)
      :method-not-allowed (->response (js/JSON.stringify #js {:error "Method Not Allowed"})
                                      {:status 405
                                       :content-type "application/json; charset=utf-8"
                                       :extra {"allow" allow}})
      (json {:error "Not Found"
             :routes (mapv (fn [r] (str (str/upper-case (name (:route/method r)))
                                        " " (:route/path r)))
                           route/routes)}
            404))))

(def handler #js {:fetch fetch-handler})
