# Review 2026-09-28 chore/fork-setup-and-security（Qwen3-ASR + VAD 分段 + 詞彙）

- Reviewer: gpt-6-sol, reasoning effort medium (codex exec review, read-only sandbox)
- 範圍: `ebeae7f..HEAD`（ad49a8e Qwen3-ASR 與錄音中分段轉錄、f8c53a8 詞彙表與取代規則，及修正 commit）

## Findings

| # | 輪次 | severity | 位置 | 摘要 | triage | 理由 |
|---|---|---|---|---|---|---|
| 1 | 1 | P2 | `SegmentedTranscription.kt` merge | 前段以英文標點結尾時不加空格（`Hello.How are you?`） | 接受 | 下一段以英數開頭、前段以任何 ASCII 字元結尾時加空格；全形標點與中文仍不加。新增測試（9e09d00） |
| 2 | 1 | P2 | `WhisperAccessibilityService.kt` 後處理 | 開啟 LLM 整理時，取代規則對原文與整理結果各套一次，規則可串接（`foo=>bar`、`bar=>baz`） | 接受 | 規則只套用於原始轉錄（LLM 看到的已是修正後的詞），整理結果只做繁體轉換（9e09d00） |
| 3 | 1 | P2 | `WhisperAccessibilityService.kt` 錄音開始 | 錄音中切換模型會釋放分段轉錄器仍在用的模型，後續片段失敗 | 接受 | `LocalTranscriber` 加入使用計數（`acquire`/`releaseUse`），切換模型時延後到最後一個錄音結束才釋放（9e09d00） |
| 4 | 2 | P2 (major) | `WhisperAccessibilityService.kt` 後備轉錄 | 錄音中切換模型且 VAD 無片段時，後備整段轉錄改用了「目前」的模型 | 接受 | 後備轉錄移入 `SegmentedTranscription.finish()`，使用該次錄音保留的模型，完成後才釋放（e1442bf） |

## 修正後狀態

- 已達 2 輪上限。第 2 輪唯一的 finding（#4）為單點修正且已本地驗證，未再跑第 3 輪。
- Deterministic checks：`./gradlew testDebugUnitTest assembleDebug lintDebug` 全綠（50 tests；lint 0 errors / 23 warnings，無新增）。
- 實機（S23 Ultra）量測：Qwen3-ASR RTF 約 0.30–0.39；有停頓的 7 秒口述在停止後 0.25–0.64 秒出字；10 秒不停頓仍需約 3.8 秒。
- 剩餘風險（需實機驗證）：
  - 詞彙表對 Qwen3-ASR 辨識的實際改善幅度；詞彙過多時可能超過 `max_total_len`（512 token）
  - 錄音中切換模型的延後釋放邏輯（#3、#4）無法以單元測試覆蓋（需 native 模型），尚未實機觸發
  - 錄音中切換模型期間，新舊兩個模型會同時佔用記憶體（Qwen3-ASR 約 1 GB）
