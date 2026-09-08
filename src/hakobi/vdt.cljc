(ns hakobi.vdt
  "VDT — Verifiable Delay Token for delivery-latency LOWER-BOUND proofs.

  The problem: a node and receiver in collusion can sign a receipt saying
  '5 ms' for a delivery that took 100 ms (the Sybil-latency receipt). No
  signature scheme stops the LYING — the numbers are only vouched for.
  A VDF (verifiable delay function) gives the missing half: a LOWER bound on
  wall-clock time. The challenger calibrates a sequential hash-chain of T
  steps whose honest evaluation provably requires at least T-cal ms (measured
  on reference hardware); the prover must evaluate it inside the delivery
  window and the verifier re-evaluates it. If the claimed latency were below
  the calibrated minimum, the prover could not have produced the chain in
  time — claim and proof cannot both be true.

  Four pure operations (G4: no keys, no I/O, no clocks — `now` is always a
  caller-supplied argument):
    - make-challenge: challenger draws a fresh seed for a delivery window
    - evaluate:       prover runs the T sequential steps (hash-chain)
    - verify:         recomputes the chain and compares — fail closed
    - gate-claim:     the KERNEL-side consistency gate: a claim whose
                      :latency-ms-claimed is BELOW the calibrated minimum is
                      :rejected-latency-underproof.

  Honest trade-off (recorded, not hidden): verification cost = evaluation
  cost (T hashes). This is the non-succinct variant of Wesolowski/Pietrzak
  VDFs — acceptable here because a delivery receipt is verified O(1) times,
  not chain-wide. Succinctness is a future ADR, not a silent assumption.
  Chain-step is a pure 32-bit FNV-1a digest. Measured gotcha (2026-09-05):
  a 64-bit FNV variant collapsed to 0 under sci/nbb — JS doubles lose the
  low bits of 1099511628211-multiplication and every state collapsed to 0.
  32-bit arithmetic is exact under double precision, so the digest is
  deterministic and nonzero across runtimes. The digest is a sequencing
  commitment, not a security hash — collision resistance requirements live
  at the content layer (kernel's BLAKE3-addressed claims), not here.

  OBSERVATION + VERIFICATION ONLY. A delivery map, never a market signal."
  (:require [kotoba.lang.text :as str]
            [hakobi.kernel :as k]))

;; ── the sequential chain ────────────────────────────────────────────────────

(def ^:private fnv-prime  16777619)
(def ^:private fnv-offset 2166136261)
(def ^:private fnv-mod    4294967296)

(defn chain-step
  "One sequential step: h = H(h ':' i). The step index is folded in so a
  uniform fixed point cannot collapse the chain. Pure 32-bit FNV-1a over the
  decimal string — deterministic across runtimes (see ns docstring for the
  measured 64-bit collapse)."
  [h i]
  (let [s (str h ":" i)
        f (fn [acc c]
            (-> acc
                (* fnv-prime)
                (bit-xor (.charCodeAt c 0))
                (mod fnv-mod)))]
    (reduce f fnv-offset s)))

(defn evaluate
  "Run the chain T steps sequentially. This is the prover's work — and also
  the verifier's (non-succinct variant; see ns docstring)."
  [seed t]
  (reduce (fn [h i] (chain-step h i)) seed (range t)))

(defn make-challenge
  "Challenger side: bind a fresh seed to a delivery window and the calibrated
  step count. `t` is chosen so that honest sequential evaluation takes at
  least `min-latency-ms` on reference hardware — the calibration itself is
  the measurement-path ADR's job; here it is an argument."
  [{:keys [seed min-latency-ms t window-start window-end delivery-id]}]
  {:vdt/seed seed
   :vdt/t t
   :vdt/min-latency-ms min-latency-ms
   :vdt/window-start window-start
   :vdt/window-end window-end
   :vdt/delivery-id delivery-id})

(defn verify
  "Verifier side: the token is valid iff (1) now is inside the window,
  (2) the submitted digest equals the full sequential re-evaluation.
  Fail closed on any mismatch. Deterministic — no wall clock."
  [challenge token now]
  (let [{:vdt/keys [seed t window-start window-end]} challenge
        {:vdt/keys [digest]} token]
    (cond
      (nil? now)                                   :invalid
      (or (< now window-start) (> now window-end)) :expired
      (not= digest (evaluate seed t))              :mismatch
      :else                                        :valid)))

(defn calibrated-min-latency
  "The lower bound a valid token certifies: the challenger's calibrated
  minimum for this challenge. The claim must be >= this to be consistent."
  [challenge]
  (:vdt/min-latency-ms challenge))

;; ── the kernel-side consistency gate ────────────────────────────────────────

(defn annotate-claim
  "Attach VDT evidence to a delivery claim (pure — returns a new claim).
  `challenge` and `token` ride on the claim; verdict computation stays in
  the kernel."
  [claim challenge token verdict]
  (assoc claim
         :vdt-challenge challenge
         :vdt-token token
         :vdt-verdict verdict))

(def rejection :rejected-latency-underproof)

(defn gate-claim
  "KERNEL-side latency-consistency gate. A claim carrying a VALID VDT whose
  calibrated minimum exceeds its claimed latency is internally contradictory
  → :rejected-latency-underproof (the kernel's sixth verdict, ordered after
  double-count and before circular-traffic — a provable lie outranks a
  suspicion of circularity). A claim with no VDT is untouched — the gate is
  additive, fail-open for legacy claims, fail-closed for contradicted ones."
  [claim vdt-verdict challenge]
  (if (and (= :valid vdt-verdict)
           (some? (:latency-ms-claimed claim))
           (< (:latency-ms-claimed claim)
              (calibrated-min-latency challenge)))
    rejection
    nil))
