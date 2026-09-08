(ns hakobi.kernel
  "運び hakobi — the Proof-of-Useful-Delivery verification core.

  A port of 澪 mio's §9 verification-gate structure (ADR-2606211200, the Energy
  Order Protocol's Proof-of-Useful-Flow) to the network delivery domain — the
  L5 'Proof of Useful Delivery' layer (ADR-2609041345).

  The problem this gate answers: 'X bytes were relayed' is provable with
  signature receipts (NKN PoR), and 'X bytes reached an endpoint' with
  nonce-bound segment receipts (AIOZ PoD) — but 'a real receiver DEMANDED this,
  it was measured by someone accountable, at a latency someone vouches for,
  and none of the traffic is circular' is where Sybil farming lives. Bytes
  alone reward self-traffic. So a delivery claim reaches :verified (and
  therefore earns) ONLY if it carries all five verification facts AND clears
  the confidence threshold:

    1. :baseline-path      present (the counterfactual path the delta is
                           measured against — direct-origin RTT, etc.)
    2. :additionality      ≥ additionality-min (a genuine receiver wanted this;
                           not traffic the node generated to itself)
    3. :measurement-source a TRUSTED measurement of bytes/latency (self-report
                           alone cannot reach the threshold)
    4. :double-count-key   unique (segment nonce + receiver; a collision with
                           an earlier claim is rejected)
    5. :leakage            ≤ leakage-max (share of claimed traffic that is
                           circular/Sybil — the offset analogue)

  verification-confidence = measurement-weight × additionality × (1 − leakage)
  useful-delivery-score   = bytes × confidence — 0 unless :verified.

  Hard invariants (proven by tests):
    G1  reward NEVER derives from RAW BYTES — only from VERIFIED useful
        delivery. No :bytes-reward attribute exists; useful-delivery-score is
        0 unless :verified, and only :verified claims route to :reward. This
        is the PoR → PoUD pivot (mio's PoW → PoUF, transplanted).
    G3  a delivery record is a FACT, never a trade/price signal or a point
        forecast (no :trade / :signal / price-forecast attribute is emitted).
        Latency figures are recorded as claims, never rewarded directly —
        they enter the score only through the measurement gate.
    G4  kernel holds no keys and does no I/O. Pure functions over EDN claims.
        Operators and actors submit; the kernel only decides and accounts.

  OBSERVATION + VERIFICATION ONLY. A delivery map, never a market signal."
  (:require [kotoba.lang.text :as str]))

;; ── thresholds + the measurement-trust ladder ───────────────────────────────

(def verified-threshold  0.5)   ;; verification-confidence ≥ this (with gates) → :verified
(def additionality-min   0.3)   ;; below this → the delivery would have happened anyway / no real demand
(def leakage-max         0.5)   ;; above this → the claimed traffic is mostly circular

(def measurement-weight
  "Confidence weight per measurement source (network-domain trust ladder).
  self-report ALONE (0.3) cannot reach the verified threshold — a structural
  defence against nodes vouching for their own latency (the Sybil receipt)."
  {:third-party-probe    1.0    ;; independent prober measured the transfer
   :challenger-signed    0.95   ;; a challenger's signed canary rode the delivery
   :dual-endpoint-signed 0.9    ;; sender AND receiver signed the same receipt
   :receiver-signed      0.75   ;; receiver-signed receipt (receiver could be Sybil-owned)
   :relay-chain-signed   0.7    ;; NKN-style relay signature chain (path proven, demand not)
   :self-report          0.3})  ;; the node's own word

;; ── pure analytics ──────────────────────────────────────────────────────────

(defn verification-confidence
  "0..1 — the confidence that the useful delivery is REAL:
     measurement-weight × additionality × (1 − leakage).
  A claim with no trusted measurement, no genuine demand, or fully circular
  traffic → 0."
  [c]
  (let [mw   (get measurement-weight (:measurement-source c) 0.0)
        add  (double (or (:additionality c) 0))
        leak (double (or (:leakage c) 0))]
    (* mw add (- 1.0 leak))))

(defn verdict
  "The verdict for a delivery claim. `dup?` = its :double-count-key was already
  seen. Order of rejection is meaningful (a double-count is rejected before
  anything else; circular traffic before evidence gaps)."
  [c dup?]
  (let [base (:baseline-path c)
        add  (double (or (:additionality c) 0))
        mw   (get measurement-weight (:measurement-source c) 0.0)
        leak (double (or (:leakage c) 0))]
    (cond
      dup?                          :rejected-double-count
      (> leak leakage-max)          :rejected-circular-traffic
      (str/blank? (str base))       :insufficient-evidence
      (< add additionality-min)     :insufficient-evidence
      (zero? mw)                    :insufficient-evidence
      (>= (verification-confidence c) verified-threshold) :verified
      :else                         :insufficient-evidence)))

(defn route
  "Where a claim's outcome is routed. Only :verified delivery earns; everything
  else is returned to the submitting actor for :review."
  [vd]
  (if (= vd :verified) :reward :review))

(defn analyze-claim
  "Per-claim derived observation map (string-keyed, agent-facing). `dup?` is
  supplied by `analyze` (it needs the cross-claim double-count-key view)."
  [c dup?]
  (let [vd     (verdict c dup?)
        conf   (verification-confidence c)
        bytes  (double (or (:bytes-delivered c) 0))
        lat    (:latency-ms-claimed c)]
    {"id" (:id c)
     "name" (:name c)
     "delivery_class" (:delivery-class c)
     "source_actor" (:source-actor c)
     "bytes_delivered" bytes
     ;; G3: latency is a recorded CLAIM, never a reward input. It earns only
     ;; through the measurement gate (measurement-source weight).
     "latency_ms_claimed" lat
     "verification_confidence" conf
     ;; G1: useful-delivery-score (the reward basis) is 0 unless the delivery
     ;; is VERIFIED. It is BYTES × confidence — never raw bytes.
     "useful_delivery_score" (if (= vd :verified) (* bytes conf) 0.0)
     "verdict" vd
     "route" (route vd)
     "sourcing" (or (:sourcing c) :representative)
     "source" (:source c)}))

(defn analyze
  "Full analysis: per-claim verdicts (with cross-claim double-count detection)
  + per-delivery-class aggregates + the org-wide verified delivery total (the
  Flowrate analogue — mio's hashrate of useful flow, here of useful delivery)."
  [claims]
  (let [rows (:rows
              (reduce
               (fn [{:keys [seen rows]} c]
                 (let [k    (:double-count-key c)
                       dup? (boolean (and k (contains? seen k)))]
                   {:seen (if k (conj seen k) seen)
                    :rows (conj rows (analyze-claim c dup?))}))
               {:seen #{} :rows []}
               claims))
        by-class (group-by #(get % "delivery_class") rows)
        classes (vec (for [[cls crows] (sort-by (comp name key) by-class)]
                       {"delivery_class" cls
                        "count" (count crows)
                        "verified_count" (count (filter #(= (get % "verdict") :verified) crows))
                        "verified_bytes_total"
                        (reduce + (map #(get % "useful_delivery_score") crows))}))
        verified-rows (filter #(= (get % "verdict") :verified) rows)]
    {"claims" (count claims)
     "rows" rows
     "by_class" classes
     "verified_count" (count verified-rows)
     "verified_bytes_total"
     (reduce + (map #(get % "useful_delivery_score") verified-rows))}))
