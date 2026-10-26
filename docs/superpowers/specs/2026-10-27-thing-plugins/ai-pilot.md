# AI pilot, a small on-device model with app abilities as tools

Part of [Thing plugins](README.md). Proposal, 2026-10-27. Reference project [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) (Apache-2.0, commit `8f5f2a0`, Android only, no iOS source published). Hugging Face gating was tested anonymously on 2026-10-27.

Goal. A pilot page where the smallest model that works answers questions about the user's Things and runs app abilities as tools. Everything stays on the phone. The model explains numbers, the app computes and draws them.

## 1. Engines

| Engine | Android | iOS | Tools | Notes |
| --- | --- | --- | --- | --- |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) 0.18.0 | `com.google.ai.edge.litertlm:litertlm-android`, stable | Swift package, Early Preview | yes on both, `@Tool` in Kotlin, `Tool` protocol in Swift | no KMP artifact. No NPU on iOS |
| Apple [Foundation Models](https://developer.apple.com/documentation/foundationmodels) | | iOS 26, Apple Intelligence iPhones | native `Tool` protocol | 4096 token context, nothing to download |
| Gemini Nano, ML Kit Prompt API beta | 2024 and later flagships only | | none documented | foreground only, quotas. Not the OnePlus 7 Pro |
| MediaPipe LLM Inference iOS | | maintenance only | | skip |
| llama.cpp KMP bindings | community | community | grammar JSON only | skip for now |

Recommendation.
- Android uses LiteRT-LM. iOS uses Foundation Models when available, otherwise LiteRT-LM Swift with the same `.litertlm` file. The app targets iOS 18.5, so Foundation Models is weak linked behind `#available(iOS 26, *)`.
- Both run in manual tool call mode (`automaticToolCalling = false`), so one shared loop in common code validates every call and owns confirmation.
- CPU is the default backend for LiteRT-LM until a dated phone run says otherwise. A third party eval saw numbers mangled in tool arguments on Mali GPU ([forum](https://discuss.ai.google.dev/t/gemma-4-e2b-litert-lm-0-10-x-on-mali-gpu-patterns-from-a-structured-tool-calling-eval/143976)).

## 2. Models

| Model | Download | Peak RAM | Tools | Licence and access |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B ([card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)) | 2.59 GB | about 0.6 GB CPU on iPhone 17 Pro, 1.7 GB CPU on S26 Ultra | general, the only model in Gallery's agent chat | Apache-2.0, not gated |
| FunctionGemma 270M ([card](https://huggingface.co/google/functiongemma-270m-it)) | 289 MB | about 0.5 GB | only after fine tuning on our tool set, as in Google's [mobile actions guide](https://ai.google.dev/gemma/docs/mobile-actions) (58% to 85%) | Gemma Terms, gated (401 anonymously). We would host our derivative with the Gemma notice and carry the prohibited use policy into our terms |

Start with Gemma 4 E2B to prove the tool surface. Fine tune FunctionGemma 270M on our tools as the small default once the tool set settles.

## 3. Shared design

- `:core:pilot` (commonMain) holds `PilotTool(name, description, schema, effect, capability, handler)`, a `ToolRegistry`, the `Proposal` queue, result summarisers and the `LlmEngine` port (`availability()`, `startSession(tools, instructions)`, `send()` as a flow of text and tool call events).
- `:feature:pilot` is the Compose page for both phones. Engines are built in `app/` and `ios/`. On iOS each Foundation Models `Tool` conformance forwards to the shared Kotlin handler, one narrow function.
- Handlers bind to shared controllers and ports (`:core:sync`, `:core:history`, `:core:publicfeed`, settings), never to Android only `DeviceFacade`.
- `ThingKind` gains `val tools: List<PilotTool>`. A tool is offered only when its `Capability` is in `capabilities(thing)`, so a new plugin adds tools as data plus at most one operation id. Tools ship in the binary, never in the model download (App Store guideline 2.5.2).

## 4. Tool surface

Effects are R (read only), L (local action, one tap Run chip) and D (device changing, propose only).

| Tool | Backed by | Effect |
| --- | --- | --- |
| `list_devices()` | `DevicesController.kt` | R |
| `latest(h)`, `today_summary(h, metric?)` | `TodayModel.kt`, `TodaySoFarFlow.kt` | R |
| `history(h, metric, range)` gives min, mean, max, last, trend, at most 12 points | `HistorySeriesReader.kt` | R |
| `metrics(h)`, also the enum source for other tools | `Metrics.kt` | R |
| `sync_status(h)` | `SyncProgress.kt` | R |
| `sync(h)`, `stop_sync(h)` | `TodayController.kt` | L |
| `cell_summary(cell, prop, window)`, `place_cell(query, res<=8)` | `Feed.kt`, `Places.kt`, `H3.kt` | R, uses the feed switch |
| `explain_scale(prop, value?)` | `HealthScale.kt` | R |
| `get_settings()`, `set_units()`, `set_detail()` | `SettingsController.kt` | R, L |
| `storage()`, `files(h)` | `SettingsController.kt`, `FilesController.kt` | R |
| `propose_export`, `propose_remove_old`, `propose_public_sensors` | same | L with confirmation |
| `device_config(h)` as an allowlisted DTO | `DeviceConfigModel.kt` | R |
| `propose_wifi`, `propose_lan`, `propose_location`, `propose_flush`, `propose_reboot`, `propose_rotate_token`, `propose_clear_bonds`, `propose_pair_none`, `propose_forget` | `DeviceConfigController.kt`, `DevicesController.kt` | D |

Never tools. `join_wifi` (carries a PSK), token fetch, raw debug commands, anything that takes or returns coordinates.

## 5. Safety rules

- A `propose_*` tool returns `{proposal, summary}` and does nothing. The page shows a card with the existing dialog strings. A tap calls the existing request then `confirm()` path, which rechecks `canDisrupt`. The model has no confirm tool. D tools never get an always allow.
- Results are allowlisted DTOs, never `ConfigSnapshot` (it carries `pin`, SSID, IP and MAC). Bluetooth addresses become session handles (`d1`). Places become an H3 cell inside the tool, capped at res 8.
- A contract test in `:core:thing:testkit` fails if any DTO field is named pin, psk, token, ssid, lat or lng.
- `PrivateRead` and `PublishPolicy.Never` kinds (health) add no tools until the owner decides.
- Prompts, transcripts and results live in memory only, never logged or saved. Unknown or invalid calls are dropped without running anything.
- Answers stay on WHO thresholds, never medical advice.

## 6. Prompt budget

- Two stage router, as Gallery's `loadSkill`. Stage 1 offers about 7 group names (devices, now, history, discover, files, settings, device) and `open_group(name)`. Stage 2 injects that group's specs only, at most 5.
- The group for the current screen is preloaded, and the current device handle is filled in.
- Descriptions are at most 20 tokens. Params are enums, the metric list comes from the Thing's template, so the model cannot invent ids.
- Kotlin summarisers keep each result near 60 tokens of rounded numbers with unit and step word. Simple level omits WHO wording. Older turns shrink to one line and context resets when the group changes.

## 7. Model download and the network rule

- A new network use, so it needs an owner decision and an AGENTS.md change. Proposed shape follows.
- A Download button on the pilot page shows the exact size and suggests Wi-Fi (App Store guideline 4.2.3). A Settings switch for model downloads, off by default, separate from the feed switch. A Delete model action.
- One fixed HTTPS host, anonymous GET, no identifying headers. Size and SHA-256 pinned in the app. Download to a temporary file and rename only when both match, like synced files.
- Android stores it in `noBackupFilesDir`. iOS sets `NSURLIsExcludedFromBackupKey`, as `IosFiles.kt` does.
- Store hosted options exist ([Play AI packs](https://developer.android.com/google/play/on-device-ai), beta, 1.5 GB each, and Apple Background Assets).

## 8. Store impact

- Data processed only on the device is not collected for Play Data safety and the App Store label, so both answers stay as long as nothing leaves the phone.
- Play's AI generated content policy may apply if chat is central. Add a Report action on each answer that opens a prefilled email or issue only after the user confirms. Apple guideline 1.2 asks for the same reporting and a contact.
- Rerun the age rating questionnaires. Health wording may count as medical information.
- Check whether LiteRT-LM ships its own privacy manifest.

## 9. Code worth copying from Gallery

- `CustomTask` plugin registered with Hilt `@IntoSet` (`customtasks/common/CustomTask.kt`, `mobileactions/MobileActionsModule.kt`).
- `ToolsProvider` and `ToolDefinition` for runtime tool sets.
- `DefaultAgentRuntimeExecutor` as a `callbackFlow` of token, error and cancel events.
- `LlmChatModelHelper` for backend choice and conversation reset.
- `DownloadWorker` for resumable downloads with Range.
- `MobileActionsScreen` for showing which tools the model called.

## 10. Owner questions

1. Accept a model download as a new network use behind its own switch?
2. Fine tune and host FunctionGemma 270M ourselves (source.coop alongside the feed), or ship Gemma 4 E2B only?
3. Does Smart Sync count as a one tap local action, given it also sets the device clock?

## 11. First step

1. `:core:pilot` with the registry, router and summarisers for the R tools, tested on the host with a scripted fake engine.
2. Android LiteRT-LM adapter on CPU with Gemma 4 E2B, behind a Developer level switch, downloaded by hand for now.
3. Dated OnePlus run of tool accuracy and latency in `docs/status.md`. Then iOS Foundation Models.
