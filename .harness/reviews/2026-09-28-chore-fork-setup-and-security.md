# Review 2026-09-28 chore/fork-setup-and-security

- Reviewer: gpt-5.5 medium (codex exec review, read-only sandbox)
- 範圍: `e6518cd..1858173`（fork 原始狀態 → 改名／安全性修正 0c788a4 + 工具鏈升級 1858173）

## Findings

| # | severity | 位置 | 摘要 | triage | 理由 |
|---|---|---|---|---|---|
| 1 | P1 | `app/build.gradle.kts:1-3` | 移除 `org.jetbrains.kotlin.android` 後 Kotlin 原始碼不會被編譯，APK 缺類別 | 駁回 | AGP 9 內建 Kotlin 編譯，官方遷移指南要求移除該外掛。證據：`app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/saltchang/whisalt/MainActivity.class` 存在；`apkanalyzer dex packages` 列出 `com.saltchang.whisalt.*` 全部類別；28 個 Kotlin 單元測試實際執行並通過 |
| 2 | P2 | `gradlew.bat` | 重新產生的 wrapper 為 CRLF，`git diff --check` 失敗 | 接受 | 新增 `.gitattributes`（`*.bat text eol=crlf`、`/gradlew text eol=lf`、`*.jar binary`）並 renormalize；`git diff --check e6518cd` exit 0。commit 52559a8 |

## 修正後狀態

- 第 1 輪結束；省略第 2 輪：唯一接受的 finding 為單點修正且已本地驗證，未接受 blocker/major。
- Deterministic checks：`./gradlew testDebugUnitTest assembleDebug lintDebug` 全綠（28 tests，lint 0 errors / 23 warnings，皆為既有程式的 i18n/KTX 建議或刻意選擇如 targetSdk 36）；arm64 `.so` ELF LOAD 對齊 0x4000，`zipalign -P 16` 通過。
- 剩餘風險（需實機驗證）：
  - 文字注入只挑焦點節點後，在一般 App 與 Termux 是否仍能貼入
  - Edge-to-edge insets 在實機上的呈現
  - Android Keystore 加密 API key 的讀寫
  - sherpa-onnx 1.13.8 AAR 與四個模型的實際載入與轉錄
