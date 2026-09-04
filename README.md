# 運び hakobi — Proof of Useful Delivery kernel

**運び hakobi は、network delivery domain の「有用な配送」を検証ゲートで判定し、
検証された分だけを報酬basis にする pure kernel です。** 名前は機能を示さない
metaphor 名（運び = carrying/delivery）なので、この冒頭が名乗りです。

 mio（澪 — Proof of Useful Flow、Energy Order Protocol ADR-2606211200）の
§9 検証ゲート構造を network delivery domain へ移植したものです
（ADR-2609041345）。5 階層マップの L5 — 「需要地点へ高速・高品質に届けたか」
の trustless 証明 — に対応します。

## 問題

「X bytes を relay した」は署名連鎖で証明できる（NKN PoR）。「endpoint まで
届いた」は nonce-bound segment receipts で証明できる（AIOZ PoD）。しかし
**「本当に需要された data を、信頼できる測定で、循環トラフィック無しに
届けた」** — ここに Sybil farming が住んでいる。bytes だけを報酬basis にすると
自己トラフィックで回収できる。だから hakobi では、配送 claim が `:verified`
（したがって報酬に届く）のは **5 つの検証事実を全部運んで conf が閾値を越える
ときだけ**:

| 事実 | 意味 | 棄却 → |
|---|---|---|
| `:baseline-path` | 差分の測定基準となる反実仮想経路 | `:insufficient-evidence` |
| `:additionality` ≥ 0.3 | 本物の受信者の需要（自分で生成した traffic でない） | `:insufficient-evidence` |
| `:measurement-source` | 信頼できる測定（self-report 単独では閾値に届かない） | `:insufficient-evidence` |
| `:double-count-key` 一意 | 同一 segment nonce を二重計上しない | `:rejected-double-count` |
| `:leakage` ≤ 0.5 | 循環/Sybil トラフィックの割合 | `:rejected-circular-traffic` |

`verification-confidence = measurement-weight × additionality × (1 − leakage)`
`useful-delivery-score = bytes × confidence` — **`:verified` でなければ 0**。

## 不変条件（tests が証明する）

- **G1** 報酬は **生 bytes からは決して**生まれない — 検証された有用配送だけ。
  `:bytes-reward` 属性は存在しない（PoR → PoUD の pivot。mio の G1 移植）
- **G3** 配送記録は事実であって取引シグナルではない（`:trade`/`:signal`/
  価格予測属性は emit しない）。latency は claim として記録されるだけで、
  報酬入力には測定ゲートを通ってしか入らない
- **G4** kernel は鍵を持たず I/O をしない。EDN claim の上の純関数。
  提出は operator/actor、kernel は判定と accounting だけ

## 使い方

```bash
nbb --classpath src:test bin/run-tests.cljs hakobi.kernel-test
```

```clojure
(require '[hakobi.kernel :as k])
(k/analyze [claim ...])   ;; verdicts + per-class aggregates + verified bytes total
```

## 出所と隣接面

- 移植元: `orgs/cloud-itonami/mio`（§9 検証ゲート・閾値・棄却順序を一貫移植）
- 観測面: zeta bot（`90-docs/business/zeta-l5-delivery-observations/`）が
  NKN/AIOZ/Filecoin の動向を追い、本 kernel の材料を供給する
- OBSERVATION + VERIFICATION ONLY. 配送の地図であって市場シグナルではない
