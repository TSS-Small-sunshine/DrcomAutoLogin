# 交接材料（Android 侧）

2026-09-27 交接快照，覆盖 Android 端（DrcomAutoLogin）与 Windows 端（星尘闪连）的全部 54 项待办。

| 项 | 位置 |
| --- | --- |
| 快照目录 | [`handover/20260927/`](20260927/) —— 入口 [`交接说明.md`](20260927/交接说明.md) |
| 交接包 ZIP（CI 封包） | Release [`handover-20260927`](https://github.com/TSS-Small-sunshine/DrcomAutoLogin/releases/tag/handover-20260927) → `handover-20260927.zip` + `.sha256` |
| 另一份同内容快照 | Windows 仓库 `TSS-Small-sunshine/StardustFlashLink` → `handover/20260927/` |

## 本仓库（Android）的封包通道

| 产物 | workflow | 触发 | 取件 |
| --- | --- | --- | --- |
| APK（assembleRelease + apksigner v1/v2/v3） | `Build APK` | 推 `main` / 手动 | Release `debug` |
| 交接包 ZIP | `Package Handover Bundle` | 推 `handover/**` / 手动 | Release `handover-<快照>` |

```powershell
gh workflow run "Build APK" -R TSS-Small-sunshine/DrcomAutoLogin --ref main
gh release download debug -R TSS-Small-sunshine/DrcomAutoLogin -D .\dist
```

## Android 侧先做什么

1. **P0-5** `campus-network-service/.gitignore`（忽略 `android-signing/`、`*.p12`）—— 5 分钟
2. **P0-4** 轮换签名密钥 + 删 `keystore-credentials.txt` —— 需项目所有者先拍板（会影响存量用户覆盖升级）
3. **P0-6** `PortalLoginActivity` 回 `exported="false"` + 凭据不再走 Intent

> ⚠️ 本仓库为 **public**：材料含修复前的安全细节，请勿在 Release 说明 / PR 描述 / Issue 里展开；P0、P1 完成后可考虑把本目录下架。
