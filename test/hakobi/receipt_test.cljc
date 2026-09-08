(ns hakobi.receipt-test
  "Receipt composer tests — G1 at the settlement boundary (the structural
  proof that :bytes-reward cannot exist through the only path that composes
  a transfer record)."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [hakobi.kernel :as k]
            [hakobi.receipt :as r]))

(def claim
  {:id "claim-x1"
   :name "edge-cache-tokyo"
   :delivery-class "cdn-stream"
   :source-actor "actor-edge-01"
   :baseline-path "origin-direct-rtt-120ms"
   :additionality 0.8
   :measurement-source :dual-endpoint-signed
   :double-count-key "seg-9001::receiver-77"
   :leakage 0.1
   :bytes-delivered 1.0e9
   :latency-ms-claimed 55})

(def DCK (:double-count-key claim))

(defn- analyzed [over]
  (k/analyze [(merge claim over)]))

(defn- first-row [analysis] (-> analysis (get "rows") first))

(defn- fake-sign [payload]
  (str "sig:" (get payload :hakobi.receipt/transfer-id)))

(deftest transfer-id-deterministic-and-distinct
  (testing "same double-count-key → same transfer ID (replay visible)"
    (is (= (r/transfer-id {:double-count-key "seg-9001::receiver-77"})
           (r/transfer-id {:double-count-key "seg-9001::receiver-77"}))))
  (testing "different segment or receiver → different ID"
    (is (not= (r/transfer-id {:double-count-key "seg-9001::receiver-77"})
              (r/transfer-id {:double-count-key "seg-9002::receiver-77"})))
    (is (not= (r/transfer-id {:double-count-key "seg-9001::receiver-77"})
              (r/transfer-id {:double-count-key "seg-9001::receiver-78"}))))
  (testing "blank key → nil (no identity, no receipt)"
    (is (nil? (r/transfer-id {:double-count-key ""})))
    (is (nil? (r/transfer-id {})))))

(deftest g1-no-transfer-record-unless-verified
  (testing "verified claim composes a receipt"
    (let [rcpt (r/compose-receipt (first-row (analyzed {})) DCK fake-sign)]
      (is (some? rcpt))
      (is (str/starts-with? (get rcpt :hakobi.receipt/carrier-signature) "sig:"))))
  (testing "self-report claim (never verified) composes NOTHING — not even a zero record"
    (let [rcpt (r/compose-receipt
                (first-row (analyzed {:measurement-source :self-report})) DCK fake-sign)]
      (is (nil? rcpt))))
  (testing "the whole settlement batch sees only earned delivery"
    (let [claims [(merge claim {:id "v1" :double-count-key "s1::r1"})
                  (merge claim {:id "s1" :measurement-source :self-report
                                :double-count-key "s2::r2"})]
          batch (r/compose-all (k/analyze claims) claims fake-sign)]
      (is (= 1 (count batch)))
      (is (= "v1" (get (first batch) :hakobi.receipt/claim-id))))))

(deftest receipt-carries-verifiable-content
  (testing "receipt exposes score + confidence + bytes — the numbers the
            facilitator checks against the datom projection"
    (let [rcpt (r/compose-receipt (first-row (analyzed {})) DCK fake-sign)]
      (is (pos? (get rcpt :hakobi.receipt/useful-delivery-score)))
      (is (> (get rcpt :hakobi.receipt/verification-confidence) 0.5))
      (is (= 1.0e9 (get rcpt :hakobi.receipt/bytes-delivered))))))

(deftest projection-rows-shape
  (testing "projection includes refusals with :hakobi/verified false"
    (let [claims [(merge claim {:id "v1"})
                  (merge claim {:id "s1" :measurement-source :self-report
                                :double-count-key "s2::r2"})]
          rows (r/projection-rows (k/analyze claims))]
      (is (= 2 (count rows)))
      (is (true? (get (first rows) "hakobi/verified")))
      (is (false? (get (second rows) "hakobi/verified")))
      (is (= "verified" (get (first rows) "hakobi/verdict")))
      (is (= "insufficient-evidence" (get (second rows) "hakobi/verdict")))
      ;; no raw signature columns, no payload bytes — CID slots only
      (is (not (contains? (first rows) "hakobi/carrier-signature"))))))
