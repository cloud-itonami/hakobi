;#!/usr/bin/env nbb
;; hakobi receipt-projection evidence script — cron agent calls this and
;; reports the printed map. The script holds the judgment; the agent does
;; not compute. Fail-closed: unmeasurable steps report nil/false, never pass.

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def repo (str root "/orgs/cloud-itonami/hakobi"))

(defn clip [s] (subs (str s) 0 (min 200 (count (str s)))))

(defn sh! [cmd opts]
  (try
    (let [res (.execSync cp cmd (clj->js (merge {:cwd repo :encoding "utf8" :timeout 120000} opts)))]
      {:ok true :out (str res)})
    (catch :default e
      {:ok false :err (str (.-message e))})))

(defn read-file? [p]
  (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))

(defn west-pin []
  (try
    (let [m (read-file? (str root "/manifest/west.yml"))
          i (str/index-of m "name: hakobi")
          seg (subs m i (+ i 260))]
      (second (re-find #"revision: ([0-9a-f]{40})" seg)))
    (catch :default _ nil)))

(defn head []
  (let [r (sh! "git rev-parse HEAD")]
    (when (:ok r) (str/trim (:out r)))))

(def suite
  (let [r (sh! "nbb --classpath src:test:scripts bin/run-tests.cljs hakobi.kernel-test hakobi.probe-test hakobi.vdt-test hakobi.receipt-test")]
    {:ran (:ok r)
     :green (and (:ok r) (str/includes? (:out r) "OK"))
     :tail (str/trim (or (last (filter #(str/includes? % "assertions") (str/split (:out r) "\n"))) ""))}))

(defn run-composer-probe []
  (let [probe (str repo "/.receipt-probe.cljs")
        body  (str "(require '[hakobi.kernel :as k] '[hakobi.receipt :as r] '[clojure.edn :as edn])\n"
                   "(def fs2r (js/require \"node:fs\"))\n"
                   "(def claims (edn/read-string (.readFileSync fs2r \"claims/seed.edn\" \"utf8\")))\n"
                   "(def res (k/analyze claims))\n"
                   "(def batch (r/compose-all res claims nil))\n"
                   "(def proj (r/projection-rows res))\n"
                   "(prn {:claims (count claims)\n"
                   "      :verified (get res \"verified_count\")\n"
                   "      :receipts (count batch)\n"
                   "      :proj-rows (count proj)\n"
                   "      :no-sig-cols (not (some #(contains? % \"hakobi/carrier-signature\") proj))})\n")]
    (.writeFileSync fs probe body)
    (let [r (sh! "nbb --classpath src .receipt-probe.cljs")]
      (.unlinkSync fs probe)
      (let [line (last (filter #(str/includes? % "receipts") (str/split (:out r) "\n")))]
        {:ran (:ok r)
         :summary (when line (str/trim line))
         :err (when-not (:ok r) (clip (:err r)))}))))

(def composer (run-composer-probe))

(def pin {:checkout (head) :manifest (west-pin)
          :fresh? (= (head) (west-pin))})

(prn {:script "hakobi-receipt-evidence"
      :suite suite
      :composer composer
      :pin pin})
