#!/usr/bin/env nbb
;; hakobi test runner — same contract as the superproject's
;; scripts/nbb-run-tests.cljs (ADR-2607173000): exit 0 iff fail+error == 0.
;;
;; Usage (from this repo):
;;   nbb --classpath src:test bin/run-tests.cljs hakobi.kernel-test
;;
;; nbb measured gotcha (2026-09-04): `require` inside `doseq`/`let` does NOT
;; resolve async loads — the top-level `(apply require [...])` form does.
;; Keep the require at top level or the namespace "won't be found" at
;; run-tests despite printing `Testing <ns>`.
(require '[clojure.test :as t]
         '[clojure.string :as str])

(def ^:private argv (vec *command-line-args*))

(when (empty? argv)
  (binding [*out* *err*]
    (println "usage: nbb --classpath src:test bin/run-tests.cljs <ns>…"))
  (.exit (.-process js/globalThis) 2))

(def ^:private nss (mapv symbol argv))

(apply require nss)

(let [{:keys [fail error]} (apply t/run-tests nss)
      bad (+ (or fail 0) (or error 0))]
  (.exit (.-process js/globalThis) (if (pos? bad) 1 0)))
