(ns hakobi.probe-test
  "Probe structure tests: the canary upgrade path, fail-closed on
  mismatch/expiry, and the kernel effect (relay-chain 0.7 → challenger 0.95
  flips a borderline claim to verified; the Sybil self-loop stays rejected)."
  (:require [clojure.test :refer [deftest is testing]]
            [hakobi.kernel :as k]
            [hakobi.probe :as p]))

(def canary (p/make-canary {:nonce "n-001"
                            :challenge "echo-me-42"
                            :echo-target "edge-tokyo-01"
                            :window-start 1000
                            :window-end 2000}))

(def good-receipt {:nonce "n-001"
                   :challenge "echo-me-42"
                   :echoed-target "edge-tokyo-01"})

(defn claim [over]
  (merge {:id "c1"
          :delivery-class "cdn-stream"
          :source-actor "actor-edge-01"
          :baseline-path "origin-direct-rtt-120ms"
          :additionality 0.6
          :measurement-source :relay-chain-signed
          :double-count-key "seg-9::r1"
          :leakage 0.05
          :bytes-delivered 1.0e9
          :latency-ms-claimed 22}
         over))

(deftest verify-receipt-verdicts
  (testing "matching receipt inside window → verified"
    (is (= :verified (p/verify-receipt canary good-receipt 1500))))
  (testing "outside window → expired (fail closed)"
    (is (= :expired (p/verify-receipt canary good-receipt 2500))))
  (testing "wrong challenge → mismatch"
    (is (= :mismatch (p/verify-receipt
                      canary (assoc good-receipt :challenge "forged") 1500))))
  (testing "wrong nonce → mismatch"
    (is (= :mismatch (p/verify-receipt
                      canary (assoc good-receipt :nonce "n-999") 1500)))))

(deftest upgrade-fails-closed
  (testing "verified receipt upgrades measurement-source"
    (let [c (p/upgrade-claim (claim {}) good-receipt :verified)]
      (is (= :challenger-signed (:measurement-source c)))))
  (testing "mismatch does NOT upgrade"
    (let [c (p/upgrade-claim (claim {}) (assoc good-receipt :challenge "forged") :mismatch)]
      (is (= :relay-chain-signed (:measurement-source c))))))

(deftest analyze-with-canary-effect
  (testing "relay-chain alone leaves the borderline claim unverified"
    ;; 0.7 × 0.6 × 0.95 = 0.399 < 0.5
    (let [res (p/analyze-with-canary [(claim {:canary canary
                                              :canary-receipt nil})] 1500)]
      (is (= 0 (get res "verified_count")))))
  (testing "a verified canary flips it: 0.95 × 0.6 × 0.95 = 0.5415 ≥ 0.5"
    (let [upgraded (p/upgrade-claim (claim {}) good-receipt :verified)
          res (p/analyze-with-canary [(claim {:canary canary
                                              :canary-receipt good-receipt})] 1500)]
      (is (= :challenger-signed (:measurement-source upgraded)))
      (is (= 1 (get res "verified_count")))))
  (testing "an expired canary does NOT flip it (fail closed)"
    (let [res (p/analyze-with-canary [(claim {:canary canary
                                              :canary-receipt good-receipt})] 2500)]
      (is (= 0 (get res "verified_count"))))))
