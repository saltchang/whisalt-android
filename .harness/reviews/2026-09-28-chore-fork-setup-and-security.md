# Review 2026-09-28 chore/fork-setup-and-security
- Reviewer: gpt-6-sol, reasoning effort medium (codex exec review)
- 範圍: --base 1cd6d9d（whisper.cpp 整合、PromptEcho、錄音控制列／取消／無語音略過、README、polish 精簡）
## Findings
| # | severity | 位置 | 摘要 | triage | 理由 |
|---|---|---|---|---|---|
| 1 | P2 | app/src/main/kotlin/com/saltchang/whisalt/PromptEcho.kt:22 | 詞彙表只有一個詞、口述也只說該詞時，`outTerms == promptTerms` 把正確轉錄判成回聲而清空 | 接受 | 確認可重現；90%/70% 規則在單詞情境同樣誤判。改為輸出只有單一相異詞時一律不視為回聲，並補測試 |
## 修正後狀態
第 1 輪結束。單點修正、已本地驗證（testDebugUnitTest assembleDebug lintDebug 全綠），依規則省略第 2 輪。剩餘風險：單一詞彙的重複迴圈（如「記憶體、記憶體…」）不再由 isEcho 清除，改由 WhisperCppTranscriber.collapseRepeats（5 次以上重複）處理。
