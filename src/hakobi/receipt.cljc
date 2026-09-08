(ns hakobi.receipt
  "hakobi receipt composer — the settlement-side projection of verified
  claims (ADR-2809051659 D3/D5 implementation, first tranche).

  Takes kernel verdict rows and composes, per VERIFIED claim only:

    1. a countersignature-ready transfer record — CIDv1-shaped claim ID as
       the transfer ID (engi's separation pattern: the carrier signs its own
       settlement context; the facilitator later verifies without keys)
    2. the VDT lower-bound proof bundle the receipt rides on (challenge +
       token + verdict), so a verifier can re-evaluate the chain without
       trusting the claimant

  G1 enforcement is structural: a claim whose verdict is not :verified has
  NO transfer record composed — :bytes-reward cannot exist through this
  path because this is the only path that composes one. G4 holds: pure
  functions, no keys, no I/O. Signing is a caller-injected function.

  OBSERVATION + VERIFICATION ONLY. A delivery map, never a market signal."
  (:require [kotoba.lang.text :as str]
            [hakobi.kernel :as k]
            [hakobi.vdt :as vdt]))

;; ── transfer-ID derivation ──────────────────────────────────────────────────

(defn transfer-id
  "CIDv1/raw/sha2-256-shaped transfer ID derived from the claim's
  double-count-key (the segment nonce + receiver — the unique delivery
  fact). Deterministic: same key → same ID, so replay is visible as a
  duplicate ID. This is the ID the x402 settlement context binds to."
  [claim]
  (let [dk (str (:double-count-key claim))]
    (if (str/blank? dk)
      nil
      ;; base32-lower of the FNV-1a chain digest over the key — a stable,
      ;; content-derived identifier in CIDv1 lexicographic territory.
      ;; Not a security hash: collision-resistance lives at the content
      ;; layer (BLAKE3-addressed blobs), same split as hakobi.vdt.
      (let [n   (count dk)
            h1  (volatile! 2166136261)
            h2  (volatile! 2166136261)]
        (dotimes [i n]
          (let [c (.charCodeAt dk i)]
            (vswap! h1 (fn [a] (mod (* a 16777619) 4294967296)))
            (vswap! h1 bit-xor c)
            (vswap! h2 (fn [a] (mod (* a 16777619) 4294967296)))
            (vswap! h2 bit-xor (mod (* c (inc i)) 256))))
        (let [enc (fn enc [n acc]
                    (if (zero? n)
                      (if (str/blank? acc) "0" acc)
                      (recur (quot n 32)
                             (str (nth "0123456789abcdefghijklmnopqrstuv" (mod n 32)) acc))))]
          (str "b" (enc @h1 "") (enc @h2 "")))))))

;; ── the composed receipt ────────────────────────────────────────────────────

(defn compose-receipt
  "A settlement receipt for ONE claim row + its double-count-key. Returns
  nil unless the row is :verified — the G1 gate lives here, at the only
  place a transfer record is born. `dck` is the claim's :double-count-key
  (the segment nonce::receiver — the row itself does not carry it, by
  design: the kernel row is the judgment, the key is the identity).
  `sign-fn` is injected (G4): (sign-fn payload) -> signature string; when
  nil the receipt carries no signature and remains verifiable by content
  alone (carrier signs at submission time)."
  [claim dck sign-fn]
  (let [vd    (or (:verdict claim) (get claim "verdict"))
        score (get claim "useful_delivery_score" 0)]
    (when (and (= vd :verified) (pos? score))
      (let [tid (transfer-id {:double-count-key dck})
            payload {:hakobi.receipt/transfer-id tid
                     :hakobi.receipt/claim-id (get claim "id")
                     :hakobi.receipt/delivery-class (get claim "delivery_class")
                     :hakobi.receipt/bytes-delivered (get claim "bytes_delivered")
                     :hakobi.receipt/latency-ms-claimed (get claim "latency_ms_claimed")
                     :hakobi.receipt/verification-confidence (get claim "verification_confidence")
                     :hakobi.receipt/useful-delivery-score score}]
        (cond-> payload
          sign-fn (assoc :hakobi.receipt/carrier-signature (sign-fn payload)))))))

(defn compose-all
  "Receipts for every verified claim in a kernel analyze result, paired
  with the ORIGINAL claims (rows do not carry :double-count-key — the
  kernel row is the judgment, the key is the identity; the caller zips
  them in the same order `analyze` preserves). Unverified claims compose
  NOTHING (not even a zero record): the settlement surface only ever sees
  earned delivery."
  [analysis claims sign-fn]
  (->> (map vector (get analysis "rows") claims)
       (keep (fn [[row c]] (compose-receipt row (:double-count-key c) sign-fn)))
       (into [])))

;; ── the projection rows (kotobase datom 面, ADR-2809051659 D2) ──────────────

(defn projection-rows
  "Datom-plane projection rows for a kernel analyze result. One row per
  claim (verified AND rejected — the map includes refusals), string-keyed
  to match the edn-query.cljs bare-string attribute convention. Bytes
  bodies are NEVER included: only counts, class, verdict, and the CID
  reference slots (:vdt-cid / :canary-cid) the caller fills after PUT
  /ipld. Source plane stays the claims EDN in Git; this is the read model."
  [analysis]
  (mapv (fn [r]
          {"hakobi/claim-id" (get r "id")
           "hakobi/delivery-class" (get r "delivery_class")
           "hakobi/verdict" (name (get r "verdict"))
           "hakobi/verified" (= :verified (get r "verdict"))
           "hakobi/bytes-delivered" (get r "bytes_delivered")
           "hakobi/verification-confidence" (get r "verification_confidence")
           "hakobi/useful-delivery-score" (get r "useful_delivery_score")})
        (get analysis "rows")))
