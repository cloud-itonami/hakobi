(ns hakobi.probe
  "Challenger-canary probe — the structural experiment for the top of the
  measurement ladder (:challenger-signed, weight 0.95).

  A challenger canary is a request a challenger injects that a relay/cache
  CANNOT distinguish from user traffic, but that the challenger can verify
  end-to-end because it holds the signing key. A delivery claim backed by a
  verified canary has measured bytes/latency that the claimant could not
  fabricate alone — hence the 0.95 weight vs :relay-chain-signed 0.7.

  This probe demonstrates the structure deterministically (no network): the
  canary rides a delivery-claim window and its verified receipt upgrades the
  claim's measurement-source. What it does NOT do is measure a real network —
  that is the measurement-path ADR's job. OBSERVATION ONLY."
  (:require [clojure.string :as str]
            [hakobi.kernel :as k]))

(defn make-canary
  "A canary request: unique nonce + challenge token + the answer the
  challenger expects to find (echo target + timestamp window)."
  [{:keys [nonce challenge echo-target window-start window-end]}]
  {:canary/nonce nonce
   :canary/challenge challenge
   :canary/echo-target echo-target
   :canary/window-start window-start
   :canary/window-end window-end})

(defn verify-receipt
  "Challenger-side verification: the receipt must echo the challenge inside
  the window. Returns :verified | :expired | :mismatch. No clocks — the
  window check is against a caller-supplied now (deterministic, G4)."
  [{:keys [canary/nonce canary/challenge canary/echo-target
           canary/window-start canary/window-end]} receipt now]
  (let [rn   (str (:nonce receipt ""))
        rch  (str (:challenge receipt ""))
        ret  (str (:echoed-target receipt ""))]
    (cond
      (or (nil? now)
          (< now window-start)
          (> now window-end))          :expired
      (not= rn (str nonce))            :mismatch
      (not= rch (str challenge))       :mismatch
      (not= ret (str echo-target))     :mismatch
      :else                            :verified)))

(defn upgrade-claim
  "A verified canary receipt upgrades the claim's measurement-source from
  :relay-chain-signed (path proven only) to :challenger-signed (challenger
  measured it). Returns the claim with the upgraded source, or the claim
  unchanged when the receipt is not verified — fail closed."
  [claim receipt verdict]
  (if (= :verified verdict)
    (assoc claim :measurement-source :challenger-signed)
    claim))

(defn analyze-with-canary
  "End-to-end structure check: analyze claims where the one carrying a
  verified canary receipt has been upgraded. Returns the kernel result."
  [claims now]
  (let [verified? (fn [c]
                    (let [r (:canary-receipt c)]
                      (when r
                        (= :verified (verify-receipt (:canary c) r now)))))
        upgraded  (mapv (fn [c]
                          (if (verified? c)
                            (upgrade-claim c (:canary-receipt c) :verified)
                            c))
                        claims)]
    (k/analyze upgraded)))
