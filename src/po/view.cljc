(ns po.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`（デジタル庁デザインシステム）—— superproject の skill
  `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン契約で
  書き、raw hex も px フォントサイズも置かない。

  **表示する事実はすべて引数で受け取る。ページの中に焼かない。** これは装飾の
  都合ではなく、docs/adr/0001 が記録した欠陥そのものへの答えである —— 移行前の
  `+page.svelte` は `routeCount: 0` / `routes: []` / `vars: []` を literal で
  持っていて、隣の `wrangler.jsonc` が route 2 本・var 8 個・capability 8 個を
  宣言していることに気づけなかった。ここでは route 表も env も渡す側が持ち、
  ページは描くだけなので、両者がずれる余地が無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う（bridge が DADS の上に再定義
  する）。DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge が
  運んでいないトークンは何にも解決しない —— 使うのは運ばれている中だけ。"
  (str/join
   "\n"
   [".po-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".po-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".po-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "po-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn- chips [labels]
  (into [:p] (interpose " " (map (fn [l] (dds/chip-label (str l))) labels))))

(defn- capability-block
  "`po.route/decode-capabilities` の戻り値を描く。

  **「読めなかった」と「0 件だった」を別の文で出す。** 同じ文言にすると、
  ページが env を見ていないことと、env が空であることが読み手から区別できない
  —— 移行前のページがまさにそれだった。"
  [{:keys [ok? value reason]}]
  (cond
    (and ok? (seq value))
    [:div (chips value)
     [:p {:class "po-note"} (str (count value) " 件。env の APP_CAPABILITIES から読んだもので、ページに焼いた値ではない。")]]

    ok?
    [:p {:class "po-note"} "APP_CAPABILITIES は在るが、宣言された capability は 0 件である。"]

    :else
    [:p {:class "po-note"} "capability を読み取れなかった: " (str reason)
     "（0 件と同じ文言にしない）"]))

(defn body
  "opts:
   :title        表示名（env の APP_DISPLAY_NAME、無ければ既定）
   :description  説明（env の APP_DESCRIPTION、無ければ nil）
   :routes       po.route/routes（この Worker が実際に答えるもの）
   :vars         wrangler が渡した env のキー（値は出さない）
   :capabilities po.route/decode-capabilities の戻り値
   :mcp-url      XRPC の中継先（route/mcp-router-url の戻り値）"
  [{:keys [title description routes vars capabilities mcp-url]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 (or title "Purchase Orders"))
    [:p {:class "po-lede"}
     (or description
         "発注（PO）とサプライチェーンの appview。BPMN 駆動の PO 管理・仕入先登録・入荷記録。")]
    [:p {:class "po-note"}
     "業務ロジックそのものは AgentGateway MCP の先（pod 側 LangServer）にあり、"
     "ここには無い。この面が持つのは公開面だけである。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "po-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "名乗っている capability"}
    (capability-block capabilities))

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div (chips (map name vars))
       ;; **この文はページの他の場所と矛盾してはならない。** 上の見出しは
       ;; APP_DISPLAY_NAME の値、その下の説明文は APP_DESCRIPTION の値、
       ;; 直後の中継先は AGENTGATEWAY_MCP_ROUTER_URL の値である。「値は出さない」
       ;; と書くとページが自分について嘘をつく（gate 4 の smoke が実際にこれを
       ;; 捕まえた）。出す 3 つを名指しし、残りはキーだけ、と書く。
       [:p {:class "po-note"}
        "値を出しているのは公開表示のための 3 つだけ —— "
        "APP_DISPLAY_NAME（上の見出し）・APP_DESCRIPTION（その下の説明文）・"
        "AGENTGATEWAY_MCP_ROUTER_URL（下の中継先）。"
        "それ以外はキー名だけで、値は出さない。"]]
      [:p {:class "po-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "po-note"} "XRPC の中継先: "
     [:span {:class "po-mono"} mcp-url]])

   (dds/section
    {:title "現在地"}
    [:p {:class "po-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。"])))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す（ライブラリは I/O を持たない）。"
  [{:keys [css title description] :as opts}]
  (page/->page
   {:title (or title "Purchase Orders")
    :description (or description
                     "発注（PO）とサプライチェーンの appview の公開面。")
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
