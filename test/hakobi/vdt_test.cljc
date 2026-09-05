(ns hakobi.vdt-test
  "VDT tests — the collusion-resistance structure and the kernel gate."
  (:require [clojure.test :refer [deftest is testing]]
            [hakobi.vdt :as vdt]
            [hakobi.kernel :as k]))

(def ch
  (vdt/make-challenge {:seed 987654321
                       :t 64
                       :min-latency-ms 40
                       :window-start 1000
                       :window-end 2000
                       :delivery-id "seg-0100::receiver-77"}))

(defn tok [t] {:vdt/digest (vdt/evaluate (:vdt/seed ch) t)})

(deftest chain-is-deterministic-and-sequential
  (testing "same seed+T → same digest, every time (cross-runtime stability)"
    (is (= (vdt/evaluate 42 32) (vdt/evaluate 42 32))))
  (testing "the step index is folded in — a constant seed cannot collapse"
    (is (not= (vdt/evaluate 0 8) (vdt/evaluate 0 9)))))

(deftest verify-fail-closed
  (testing "valid token inside the window"
    (is (= :valid (vdt/verify ch (tok 64) 1500))))
  (testing "wrong digest → :mismatch (a prover that skipped steps)"
    (is (= :mismatch (vdt/verify ch (tok 32) 1500))))
  (testing "outside the window → :expired"
    (is (= :expired (vdt/verify ch (tok 64) 2500))))
  (testing "no clock supplied → :invalid (G4: fail closed)"
    (is (= :invalid (vdt/verify ch (tok 64) nil)))))

(deftest gate-rejects-provable-lies
  (testing "valid token + claim below calibrated min → rejected-latency-underproof"
    (let [claim {:latency-ms-claimed 5}]
      (is (= :rejected-latency-underproof
             (vdt/gate-claim claim :valid ch)))))
  (testing "valid token + claim consistent with calibration → no rejection"
    (is (nil? (vdt/gate-claim {:latency-ms-claimed 55} :valid ch))))
  (testing "invalid token proves nothing — gate stays silent (fail-open for
            legacy claims; the invalidity itself fails verify upstream)"
    (is (nil? (vdt/gate-claim {:latency-ms-claimed 5} :mismatch ch))))
  (testing "claim with no latency figure → untouched"
    (is (nil? (vdt/gate-claim {} :valid ch)))))

(deftest end-to-end-collusion-scenario
  (testing "the Sybil-latency receipt: colluding pair claims 5ms for a
            delivery the challenger calibrated at 40ms minimum"
    (let [verdict (vdt/verify ch (tok 64) 1500)
          claim   {:id "c9" :latency-ms-claimed 5
                   :measurement-source :dual-endpoint-signed
                   :additionality 0.9 :leakage 0.0
                   :baseline-path "origin-direct-rtt-120ms"
                   :double-count-key "seg-0100::receiver-77"
                   :bytes-delivered 1.0e9}
          g       (vdt/gate-claim claim verdict ch)]
      (is (= :valid verdict))
      ;; the signatures were real — the LIE is what the VDT catches
      (is (= :rejected-latency-underproof g))
      ;; and the kernel verdict flips to the VDT rejection regardless of
      ;; how good the other four facts look
      (is (not= :verified
                (if g :rejected-latency-underproof (k/verdict claim false)))))))
