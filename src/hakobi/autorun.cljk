(ns hakobi.autorun
  "hakobi verify heartbeat — deterministic, idempotent-by-content analysis
  over the claims directory (mio's methods/autorun.cljc pattern).

  Reads every .edn file under claims/, flattens the claim vectors, runs the
  kernel, and renders a report. No wall clock, no randomness, no I/O beyond
  reading the claims (the same G7-style discipline mio's autorun follows:
  deterministic, caller supplies inputs)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hakobi.kernel :as k]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(defn- read-claims-dir
  [dir]
  (let [files (->> (.readdirSync fs dir)
                   (filter #(str/ends-with? % ".edn"))
                   (sort))]
    (mapcat #(edn/read-string (.readFileSync fs (str dir "/" %) "utf8")) files)))

(defn render
  "Report string for an analysis result — deterministic."
  [res]
  (let [lines [(str "hakobi verify: claims=" (get res "claims")
                    " verified=" (get res "verified_count")
                    " verified_bytes_total=" (get res "verified_bytes_total"))]
        per-row (map (fn [r]
                       (str "  " (get r "id")
                            " verdict=" (name (get r "verdict"))
                            " score=" (get r "useful_delivery_score")))
                     (get res "rows"))]
    (str/join "\n" (into lines per-row))))

(defn run
  "Analyze all claims under claims-dir (default ./claims). Returns the report
  string. Pure given the directory contents."
  ([claims-dir] (run claims-dir true))
  ([claims-dir print?]
   (let [claims (read-claims-dir claims-dir)
         res (k/analyze claims)
         report (render res)]
     (when print? (println report))
     report)))
