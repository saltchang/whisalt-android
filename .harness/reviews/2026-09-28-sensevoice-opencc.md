# Review 2026-09-28 chore/fork-setup-and-security（SenseVoice + OpenCC）

- Reviewer: gpt-6-sol, reasoning effort medium (codex exec review, read-only sandbox)
- 範圍: `c7acc32..HEAD`（4abd4d7 SenseVoice 中文模型 + opencc-js s2twp Kotlin 移植；修正 commit 見下）

## Findings

| # | severity | 位置 | 摘要 | triage | 理由 |
|---|---|---|---|---|---|
| 1 | P2 | `ChineseConverter.kt:77-78` | 只看整段文字有無假名來決定是否轉換：中文夾一個假名就整段不轉；純漢字日文（写真）會被轉成繁體 | 接受 | 改用 SenseVoice 回報的語言標籤（`OfflineRecognizerResult.lang`，格式 `<\|zh\|>`，已對照模型 `tokens.txt` 確認）：`ja`/`ko` 一律不轉，其他語言有漢字就轉；無語言資訊（雲端轉錄）時沿用假名／諺文判斷。新增測試涵蓋兩個反例 |

## 修正後狀態

- 第 2 輪（修正跨 3 檔且改變行為，依規則必跑）：未發現可確認的錯誤。
- Deterministic checks：`./gradlew testDebugUnitTest assembleDebug lintDebug` 全綠（37 tests；lint 0 errors / 23 warnings，無新增）。
- 轉換正確性依據：測試預期值由打包的 opencc-js 1.4.1 詞典逐詞查得，非由實作輸出反推；未能以原版 opencc-js 產生對照答案（執行外部下載的 JS 被安全規則阻擋）。
- 剩餘風險（需實機驗證）：
  - SenseVoice 在 S23 Ultra 上的實際準確度與延遲
  - 雲端轉錄（無語言標籤）時，中文夾假名的少見情況仍會跳過轉換
  - 粵語（`yue`）輸出也會轉成台灣繁體
