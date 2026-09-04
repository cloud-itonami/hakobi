(ns hakobi.kernel-test
  "hakobi kernel tests — proving the hard invariants, including the mio
  negative controls transplanted to the delivery domain. Mirrors
  mio's g1-useful-flow-zero-unless-verified suite shape."
  (:require [clojure.test :refer [deftest is testing]]
            [hakobi.kernel :as k]))

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn claim [over]
  (merge {:id "c1"
          :name "edge-cache-tokyo"
          :delivery-class "cdn-stream"
          :source-actor "actor-edge-01"
          :baseline-path "origin-direct-rtt-120ms"
          :additionality 0.8
          :measurement-source :dual-endpoint-signed
          :double-count-key "seg-0001::receiver-77"
          :leakage 0.1
          :bytes-delivered 1.0e9
          :latency-ms-claimed 18}
         over))

(def fully-verified (k/verdict (claim {}) false))

(defn approx
  "float-safe equality (bytes × confidence goes through double math)"
  [a b] (< (Math/abs (- a b)) 1e-6))

;; ── G1: reward never derives from raw bytes ────────────────────────────────

(deftest g1-useful-delivery-zero-unless-verified
  (testing "raw bytes alone (self-report, no demand evidence) earn NOTHING"
    (let [r (k/analyze-claim (claim {:measurement-source :self-report}) false)]
      (is (= :insufficient-evidence (get r "verdict")))
      (is (= 0.0 (get r "useful_delivery_score")))))
  (testing "verified delivery earns bytes × confidence"
    (let [r (k/analyze-claim (claim {}) false)]
      (is (= :verified (get r "verdict")))
      ;; 1e9 × 0.9 × 0.8 × 0.9 = 6.48e8
      (is (> (get r "useful_delivery_score") 0.0))
      (is (approx (* 1.0e9 0.9 0.8 0.9) (get r "useful_delivery_score")))))
  (testing "no :bytes-reward attribute exists in the output shape"
    (is (not (contains? (k/analyze-claim (claim {}) false) "bytes_reward")))))

(deftest g1-only-verified-routes-to-reward
  (testing "every non-verified verdict routes to :review, not :reward"
    (doseq [over [{:leakage 0.9}
                  {:additionality 0.0}
                  {:measurement-source :self-report}]]
      (let [vd (k/verdict (claim over) false)]
        (is (not= :verified vd))
        (is (= :review (k/route vd)))))
    ;; a nil double-count-key is OUTSIDE the gate (mio semantics: absent key =
    ;; no dedup identity), not a rejection
    (is (= :verified (k/verdict (claim {:double-count-key nil}) false)))
    (is (= :reward (k/route fully-verified)))))

;; ── G1b: the five facts are the only path to verified ─────────────────────

(deftest g2-gate-facts
  (testing "missing baseline-path → insufficient evidence"
    (is (= :insufficient-evidence (k/verdict (claim {:baseline-path ""}) false))))
  (testing "no genuine demand (additionality below min) → insufficient evidence"
    (is (= :insufficient-evidence (k/verdict (claim {:additionality 0.29}) false))))
  (testing "unknown measurement source weights 0 → insufficient evidence"
    (is (= :insufficient-evidence (k/verdict (claim {:measurement-source :my-own-vouch}) false))))
  (testing "duplicate segment nonce → rejected-double-count (first rejection)"
    (is (= :rejected-double-count (k/verdict (claim {}) true))))
  (testing "circular traffic above max → rejected-circular-traffic"
    (is (= :rejected-circular-traffic (k/verdict (claim {:leakage 0.51}) false))))
  (testing "confidence just below threshold does NOT verify"
    ;; dual-endpoint (0.9) × add 0.6 × (1-0.05) = 0.513 → verify
    (is (= :verified (k/verdict (claim {:additionality 0.6 :leakage 0.05}) false)))
    ;; dual-endpoint (0.9) × add 0.6 × (1-0.07) = 0.5022 → verify
    ;; dual-endpoint (0.9) × add 0.55 × (1-0.1) = 0.4455 → NOT
    (is (= :insufficient-evidence
           (k/verdict (claim {:additionality 0.55 :leakage 0.1}) false)))))

(deftest g2b-rejection-order
  (testing "double-count beats leakage beats evidence gaps (mio order)"
    (is (= :rejected-double-count (k/verdict (claim {:leakage 0.9 :baseline-path ""}) true)))
    (is (= :rejected-circular-traffic (k/verdict (claim {:leakage 0.9 :baseline-path ""}) false)))))

;; ── G1c: the self-report Sybil structure ──────────────────────────────────

(deftest self-report-alone-can-never-verify
  (testing "even perfect demand + zero circularity: self-report 0.3 < threshold 0.5"
    (is (= :insufficient-evidence
           (k/verdict (claim {:measurement-source :self-report
                              :additionality 1.0
                              :leakage 0.0}) false)))))

;; ── G3: no market signal ──────────────────────────────────────────────────

(deftest g3-no-trade-no-signal
  (testing "output shape carries no trade/signal/price-forecast keys"
    (let [r (k/analyze-claim (claim {}) false)]
      (doseq [bad ["trade" "signal" "price_forecast" "price-forecast"]]
        (is (not (contains? r bad))))
      (doseq [row (get (k/analyze [(claim {}) (claim {:id "c2"})]) "rows")]
        (is (every? #(not (contains? row %)) ["trade" "signal" "price_forecast"]))))))

;; ── G4: no I/O, no keys, no wall clock in the kernel ─────────────────────

(deftest g4-determinism
  (testing "same input → same output (no Math/random, no wall clock)"
    (let [a (k/analyze [(claim {}) (claim {:id "c2" :double-count-key "seg-0002::r8"})])
          b (k/analyze [(claim {}) (claim {:id "c2" :double-count-key "seg-0002::r8"})])]
      (is (= a b)))))

;; ── cross-claim double-count detection ───────────────────────────────────

(deftest analyze-detects-double-counts-across-claims
  (let [res (k/analyze [(claim {})
                        (claim {:id "c2" :double-count-key "seg-0001::receiver-77"})])]
    (is (= 2 (get res "claims")))
    (is (= 1 (get res "verified_count")))
    (is (= :rejected-double-count
           (get (second (get res "rows")) "verdict")))))

;; ── aggregates ────────────────────────────────────────────────────────────

(deftest aggregates-by-class
  (let [res (k/analyze [(claim {})
                        (claim {:id "c2" :delivery-class "relay-transit"
                                :bytes-delivered 2.0e9
                                :measurement-source :receiver-signed
                                :additionality 0.8
                                :leakage 0.1
                                :double-count-key "seg-0003::r9"})])]
    ;; receiver-signed 0.75 × 0.8 × 0.9 = 0.54 ≥ 0.5 → verified
    (is (= 2 (get res "verified_count")))
    (is (= #{"cdn-stream" "relay-transit"} (set (map #(get % "delivery_class") (get res "by_class")))))
    (let [relay (first (filter #(= "relay-transit" (get % "delivery_class")) (get res "by_class")))]
      (is (= 1 (get relay "verified_count")))
      (is (approx (* 2.0e9 0.75 0.8 0.9) (get relay "verified_bytes_total"))))))
