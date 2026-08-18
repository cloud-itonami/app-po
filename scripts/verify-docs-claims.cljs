#!/usr/bin/env nbb
;; verify-docs-claims — README.md と docs/operator-quickstart.md が主張する数値・
;; 存在・不在を、tree そのものから再計算して照合する。食い違えば exit 1、
;; tree を読み切れなければ exit 2（0 とも 1 とも別の答え）を返す。
;;
;; 移行前この repo の load-bearing な事実は GAP だった: deploy される Worker は
;; SvelteKit のビルド出力で、application のように読める src/app.ts は
;; どの bundle にも入っていなかった。その gap は閉じたので、claim は **閉じた
;; こと** を主張する。しかも黙って戻らないように書いてある —— TypeScript は
;; byte 合計ではなく **名指しで不在** を検査する。
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> は先頭、既定 ".")
;; Exit:   0 全 claim 成立 · 1 claim が偽 · 2 答えられなかった

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[cljs.reader :as reader]
         '[clojure.string :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/po-mcp-component")

(def claims
  {:tracked-files 19
   :preserved-bytes 7622           ; 由来から 1 バイトも変わっていない 5 ファイル
   :production-ts-files 0
   :production-svelte-files 0
   :production-canonical-files 4
   :declared-vars 8
   :declared-routes 2
   :wrangler-main "../../dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export "po.worker/handler"})

;; 由来から BYTE 単位で変わっていないファイル。wrangler.jsonc と
;; MIGRATION-TODO.md はこの集合に **意図的に入れていない**（移行が両者を偽に
;; したので書き換えた）。中身は下で別に検査する。
(def preserved
  {"NOTICE" "f487a5bfd0c55764ffc608048654dfb3f8f88680c8191dba20047dd9b18f9404"
   "README.edn" "9d82869b89f889c68143d645685f7bd93ec3ba3aef682cf9555670313a540ac5"
   "migration.edn" "aaaedc3538ec72ec29095053e55dff7afc6a1776b8a45d9c7d2048965af8cbcd"
   "appview/po-mcp-component/kotodama.jsonld" "b16a39d8e61005ee1297f9b17468c2d3e0526d40d1153dc81cb59d0c64ea6910"
   "bpmn/po.bpmn" "d472fc8bfecfc86e657b027366fdf33487a1528bdd7e36936688f065a90eb8eb"})

;; 移行が撤去したものを名指しする。byte 合計は「TypeScript が消えた」と言えない。
;; これは言えるし、どれか 1 つでも戻れば落ちる。
(def removed-by-migration
  ["appview/po-mcp-component/src/app.ts"
   "appview/po-mcp-component/package.json"
   "appview/po-mcp-component/package-lock.json"
   "appview/po-mcp-component/vitest.config.ts"
   "appview/po-mcp-component/test/po.test.ts"
   "appview/po-mcp-component/svelte/package.json"
   "appview/po-mcp-component/svelte/src/app.html"
   "appview/po-mcp-component/svelte/src/routes/+page.svelte"
   "appview/po-mcp-component/svelte/src/routes/xrpc/[...path]/+server.ts"
   "appview/po-mcp-component/svelte/svelte.config.js"
   "appview/po-mcp-component/svelte/tsconfig.json"
   "appview/po-mcp-component/svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256")
           (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :preserved-bytes (:preserved-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; TypeScript / Svelte は名指しで不在
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; production source の言語。撤去したパス以外の名前で戻っても落ちる。
    (let [prod (remove #(str/starts-with? % "scripts/") files)]
      (check! :production-ts-files (:production-ts-files claims)
              (count (filter #(str/ends-with? % ".ts") prod)))
      (check! :production-svelte-files (:production-svelte-files claims)
              (count (filter #(str/ends-with? % ".svelte") prod)))
      (check! :production-canonical-files (:production-canonical-files claims)
              (count (filter #(re-find #"\.(cljs|cljc|clj|kotoba)$" %) prod))))

    ;; deploy される bundle は、この tree のソースからビルドされる
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)
          sh (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          ;; 消えた SvelteKit の client dir を指していた assets binding
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          ;; adapter-cloudflare のための compat flag。workerd で無い状態を実測済み
          (check! :no-adapter-compat-flags true (nil? (get j "compatibility_flags")))
          ;; APP_FRAMEWORK が sveltekit のままなら、設定が自分について嘘をつく
          (check! :app-framework "cljs-esm-worker" (get-in j ["vars" "APP_FRAMEWORK"]))
          ;; **shadow-cljs.edn は構造で読む。部分文字列で見ない。**
          ;; 実測 2026-08-18: :warnings-as-errors を :compiler-options から
          ;; 消す mutation を当てたのに検査は緑のままだった —— このファイルの
          ;; 冒頭コメントが「:warnings-as-errors true が要る」と書いており、
          ;; substring 検査はそれに当たっていた。**コメントが満たせる検査は
          ;; 散文についての検査である。**
          (let [cfg (try (reader/read-string sh) (catch :default _ nil))
                b   (get-in cfg [:builds :worker])
                copts (get b :compiler-options)]
            (if (nil? b)
              (undet! "shadow-cljs.edn の :builds :worker を読めなかった")
              (do
                (check! :build-fails-on-warnings true
                        (true? (:warnings-as-errors copts)))
                (check! :shadow-output-dir (:shadow-output-dir claims) (:output-dir b))
                (check! :shadow-export (symbol (:shadow-export claims))
                        (get-in b [:modules :worker :exports (symbol "default")]))
                (check! :shadow-builds-that-main true
                        (str/includes? (or (get j "main") "")
                                       (str (:output-dir b) "/worker.js")))))))))

    ;; ページは route 表を描く（焼いた数ではなく）。ADR-0001 が記録した欠陥は
    ;; route 2 本を宣言する config の隣で literal の `routeCount: 0` を出す
    ;; ページだった。構造で検査する —— 部分文字列の禁止では検査しない
    ;; （app-ongakuka では「routeCount を禁止」した初版が、その欠陥を説明する
    ;; docstring 自身に引っかかった。コメントが落とせる検査は散文についての検査である）。
    (let [v (slurp* "src/po/view.cljc")
          w (slurp* "src/po/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (do
          (check! :page-renders-route-table true
                  (and (str/includes? v "[{:keys [title description routes vars capabilities mcp-url]}]")
                       (str/includes? v "(route-rows routes)")
                       (str/includes? w ":routes route/routes")))
          ;; capability も env から読む。ページに焼かない。
          (check! :page-renders-env-capabilities true
                  (str/includes? w "(route/decode-capabilities (:APP_CAPABILITIES e))")))))

    ;; 「読めなかった」と「0 件」を別の値で返す（superproject ADR-2608136000）
    (let [r (slurp* "src/po/route.cljc")]
      (if (nil? r)
        (undet! "route.cljc unreadable")
        (check! :capabilities-distinguish-unreadable-from-empty true
                (and (str/includes? r ":ok? false :reason \"APP_CAPABILITIES が env に無い\"")
                     (str/includes? r ":ok? false :reason \"APP_CAPABILITIES が JSON として読めない\"")))))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f))))
        (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds")
        (js/process.exit 0))))
