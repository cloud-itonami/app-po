(ns po.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [po.route :as route]
            [po.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (is (= :not-found (:action (route/dispatch "GET" "/nope"))))
  (testing "移行前に app.ts が持っていた /_app/meta は持ち越していない"
    (is (= :not-found (:action (route/dispatch "GET" "/_app/meta"))))))

(deftest dispatch-xrpc
  (testing "単一セグメントの nsid だけ通す"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.po.createPo"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.po.createPo"))))
  (testing "nsid が無い / 多段は 400。前方一致で素通ししない"
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc/"))))
    ;; 多段は移行前の rest パラメータと同じく転送する。絞るのは方針変更で
    ;; あって移行ではない（route.cljc の xrpc-nsid docstring）。
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :method-not-allowed (:action (route/dispatch "GET" "/xrpc/x"))))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x"
         (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う"
    (is (= "https://b.example"
           (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                  :MCP_ROUTER_URL "https://b.example"})))))

(deftest capabilities-distinguish-absent-broken-and-empty
  (testing "wrangler.jsonc が実際に渡す形"
    (is (= {:ok? true :value ["createPo" "listPos"]}
           (route/decode-capabilities "[\"createPo\",\"listPos\"]"))))
  (testing "0 件は 0 件として ok"
    (is (= {:ok? true :value []} (route/decode-capabilities "[]"))))
  (testing "未設定・空白・壊れた JSON・配列でない、は ok? false（0 件と混ぜない）"
    (is (false? (:ok? (route/decode-capabilities nil))))
    (is (false? (:ok? (route/decode-capabilities "   "))))
    (is (false? (:ok? (route/decode-capabilities "[not json"))))
    (is (false? (:ok? (route/decode-capabilities "{\"a\":1}"))))
    (testing "理由はそれぞれ別の文言（読み手が区別できること）"
      (is (not= (:reason (route/decode-capabilities nil))
                (:reason (route/decode-capabilities "[not json")))))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest page-shows-the-real-routes
  (testing "ページは route 表から描く。0 を焼かない（docs/adr/0001 の欠陥）"
    (let [html (view/render {:css "/*x*/" :routes route/routes
                             :title "Purchase Orders"
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :capabilities (route/decode-capabilities
                                            "[\"createPo\",\"approvePo\"]")
                             :mcp-url "https://mcp.example/x"})]
      (doseq [r route/routes]
        (is (str/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "createPo"))
      (is (str/includes? html "https://mcp.example/x"))
      (is (not (str/includes? html "No public route is declared"))))))

(deftest page-does-not-report-unreadable-as-zero
  (testing "capability を読めなかったときと 0 件は、ページ上で別の文になる"
    (let [base {:css "/*x*/" :routes route/routes :vars [] :mcp-url "https://m/x"}
          unreadable (view/render (assoc base :capabilities
                                         (route/decode-capabilities "[not json")))
          empty' (view/render (assoc base :capabilities
                                     (route/decode-capabilities "[]")))]
      (is (str/includes? unreadable "読み取れなかった"))
      (is (not (str/includes? empty' "読み取れなかった")))
      (is (str/includes? empty' "0 件")))))

(deftest relay-headers-forwards-what-it-received
  (testing "移行前は host を削るだけで、authorization も上流へ届いていた"
    (let [h (route/relay-headers [["Host" "x.example"]
                                  ["Authorization" "Bearer t"]
                                  ["Content-Length" "9"]
                                  ["Content-Encoding" "gzip"]
                                  ["X-Trace" "abc"]]
                                 "com.a.b")]
      (is (= "Bearer t" (get h "authorization"))
          "authorization が落ちている —— preflight はこれを許可すると言っている")
      (is (= "abc" (get h "x-trace"))
          "呼び手が付けた header が落ちている")
      (is (nil? (get h "host")) "host は宛先が変わるので渡さない")
      (is (nil? (get h "content-length")) "body を詰め直すので元の長さは嘘になる")
      (is (nil? (get h "content-encoding")) "body を詰め直すので元の encoding も嘘になる")
      (is (= "application/json" (get h "content-type")))
      (is (= "com.a.b" (get h "x-etzhayyim-xrpc-method")))
      (is (= "cljs-worker" (get h "x-etzhayyim-bff"))))))
