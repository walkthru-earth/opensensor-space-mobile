# AI pilot, a small on-device model with app abilities as tools

Part of [Thing plugins](README.md). Proposal, 2026-10-27. Reference project [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) (Apache-2.0, commit `8f5f2a0`, Android only, no iOS source published). Hugging Face gating was tested anonymously on 2026-10-27.

Goal. A pilot page where the smallest model that works answers questions about the user's Things and runs app abilities as tools. Prompts, answers and tool results stay on the phone. The only exceptions are the model download (section 7) and a report the user reviews and sends (section 8). The model explains numbers, the app computes and draws them.

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
- Every tool call on both platforms goes through one shared `PilotDispatcher.dispatch(call)` in common code. It validates the call, runs R and L tools, and turns D and confirmable L tools into a pending proposal without running them.
  - Android runs LiteRT-LM with `automaticToolCalling = false`. The adapter reads each returned call, passes it to `dispatch`, and sends the result back.
  - Foundation Models has no manual mode. It calls `Tool.call` itself, with tool choice `.allowed`. So each iOS `Tool.call` is only a thin forwarder to the same `dispatch`, and a D tool returns `pending` from there. No iOS tool body runs a device action, and a test on the simulator asserts that.
- CPU is the default backend for LiteRT-LM until a dated phone run says otherwise. A third party eval saw numbers mangled in tool arguments on Mali GPU ([forum](https://discuss.ai.google.dev/t/gemma-4-e2b-litert-lm-0-10-x-on-mali-gpu-patterns-from-a-structured-tool-calling-eval/143976)).

## 2. Models

| Model | Download | Peak RAM | Tools | Licence and access |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B ([card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)) | 2.59 GB | about 0.6 GB CPU on iPhone 17 Pro, 1.7 GB CPU on S26 Ultra | general, the only model in Gallery's agent chat | Apache-2.0, not gated |
| FunctionGemma 270M ([card](https://huggingface.co/google/functiongemma-270m-it)) | 289 MB | about 0.5 GB | only after fine tuning on our tool set, as in Google's [mobile actions guide](https://ai.google.dev/gemma/docs/mobile-actions) (58% to 85%) | Gemma Terms, gated (401 anonymously). We would host our derivative with the Gemma notice and carry the prohibited use policy into our terms |

Start with Gemma 4 E2B to prove the tool surface. Fine tune FunctionGemma 270M on our tools as the small default once the tool set settles.

## 3. Shared design

- `:core:pilot` (commonMain) holds `PilotTool(name, description, schema, effect, capability, handler)`, a `ToolRegistry`, the `Proposal` queue, result summarisers and the `LlmEngine` port (`availability()`, `startSession(tools, instructions)`, `send()` as a flow of text and tool call events).
- `:feature:pilot` is the Compose page for both phones. Engines are built in `app/` and `ios/`.
- iOS `Tool` adapter contract.
  - Arguments are one `@Generable` struct per tool, generated from the same schema as `PilotTool`. The adapter encodes it to a JSON string. Kotlin decodes and validates it.
  - The result is a `String` holding the JSON `ToolResult` (section 5).
  - The Swift adapter is a `Sendable` struct that holds only the tool name and a `@Sendable` closure into Kotlin. It holds no Kotlin object.
  - Foundation Models may run tools concurrently. `dispatch` runs on one serial Kotlin dispatcher (`Dispatchers.Default.limitedParallelism(1)`), so calls run one at a time in arrival order. The closure suspends until its own result is ready.
- Handlers bind to shared controllers and ports (`:core:sync`, `:core:history`, `:core:publicfeed`, settings), never to Android only `DeviceFacade`.
- `ThingKind` gains `val tools: List<PilotTool>`. A tool is offered only when its `Capability` is in `capabilities(thing)`. Those capability ids map from today's `DeviceCapabilities` in [#220](https://github.com/walkthru-earth/opensensor-space-mobile/issues/220). Until then the board kind maps `configurable` to `config` and always offers `live` and `ble-sync`. A null or unknown capability offers no tool, so a new plugin adds tools as data plus at most one operation id. Tools ship in the binary, never in the model download (App Store guideline 2.5.2).

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

Device-changing tools and the confirmation each one reaches. Every one first shows the pilot's proposal card. The second column is what runs after the user accepts it.

| Tool | After the card | Guard |
| --- | --- | --- |
| `propose_reboot` | `requestReboot()` opens `DeviceConfigDialog.Reboot`, then `confirm()` | `canDisrupt` |
| `propose_rotate_token` | `requestRotateToken()`, `DeviceConfigDialog.RotateToken`, `confirm()` | `canDisrupt` |
| `propose_clear_bonds` | `requestForgetBonds()`, `DeviceConfigDialog.ForgetBonds`, `confirm()` | `canDisrupt` |
| `propose_pair_none` | `savePairing(NONE)`, `DeviceConfigDialog.PairingNone`, `confirm()` | `canEdit` |
| `propose_wifi(on)` | `setWifiOn(on)`. Turning Wi-Fi on or off only, never a join | the card is the confirmation, as today's switch tap is |
| `propose_lan(on)` | `setLanOn(on)` | the card is the confirmation |
| `propose_location(cell)` | opens the location form filled with the cell. The user taps Save, `saveLocation()` | the form's own Save |
| `propose_flush` | `TodayController.requestFlush()` | the card is the confirmation |
| `propose_forget` | `DevicesController` opens `ForgetDialog`, then `ConfirmForget` | its existing dialog and test |

Never tools. `join_wifi` (carries a PSK), token fetch, raw debug commands, anything that takes or returns coordinates.

## 5. Safety rules

- Every tool returns one `ToolResult`.

```kotlin
sealed interface ToolResult {
    data class Ok(val data: JsonObject) : ToolResult
    data class Pending(val proposalId: String, val summary: String) : ToolResult  // nothing has happened yet
    data class Rejected(val code: RejectCode) : ToolResult                       // UNKNOWN_TOOL, BAD_ARGS, NOT_ALLOWED, BUSY
}
sealed interface ProposalOutcome {
    data object Done : ProposalOutcome
    data object Declined : ProposalOutcome
    data object Expired : ProposalOutcome
    data class Failed(val code: String) : ProposalOutcome
}
```

- Proposals. A `propose_*` tool returns `Pending` and runs nothing. The instructions tell the model that `Pending` means not done, and to say "waiting for you to confirm". The page shows a card with the existing dialog strings. Accepting runs the path in the table above, and the model has no confirm tool. D tools never get an always allow.
- When the proposal ends, the page sends the outcome to the model as a new turn, `proposal <id> Done`, `Declined`, `Expired` or `Failed`. Only `Done` lets the model say the change happened. A proposal expires when the user leaves the page, starts a new chat or a sync starts. At most one proposal is open, and a second one replaces the first as `Expired`.
- Invalid calls. An unknown tool, bad arguments or a tool not offered for this Thing returns `Rejected(code)` to the model with no side effect, so the loop never waits. After two rejections in a row, the turn ends with a fixed app message, "I couldn't do that, try asking another way", and the group resets.
- Results are fixed `@Serializable` DTOs, one per tool, never `ConfigSnapshot` (it carries `pin`, SSID, IP and MAC). Bluetooth addresses become session handles (`d1`). Places become an H3 cell inside the tool, capped at res 8.
- A contract test in `:core:pilot` walks each DTO's serial descriptor recursively and fails on any field name outside that DTO's allowlist. A second test encodes sample results and fails on values shaped like a MAC, an IP address or a latitude and longitude pair.
- `PrivateRead` kinds (health) add no tools until the owner decides.
- Prompts, transcripts and results live in memory only, never logged or saved, except a report the user sends (section 8).
- Answers stay on WHO thresholds, never medical advice.

## 6. Prompt budget

- Two stage router, as Gallery's `loadSkill`. Stage 1 offers about 7 group names (devices, now, history, discover, files, settings, device) and `open_group(name)`. Stage 2 injects that group's specs only, at most 5.
- The group for the current screen is preloaded, and the current device handle is filled in.
- Descriptions are at most 20 tokens. Params are enums, the metric list comes from the Thing's template, so the model cannot invent ids.
- Kotlin summarisers keep each result near 60 tokens of rounded numbers with unit and step word. Simple level omits WHO wording. Older turns shrink to one line and context resets when the group changes.

## 7. Model download and the network rule

- A new network use, so it needs an owner decision and an AGENTS.md change. Proposed shape follows.
- A Download button on the pilot page shows the exact size and suggests Wi-Fi (App Store guideline 4.2.3). A Settings switch for model downloads, off by default, separate from the feed switch. A Delete model action.
- Only public artifacts are downloaded. One fixed HTTPS host, anonymous GET, no identifying headers, no tokens or sign in. Size and SHA-256 pinned in the app. A gated upstream file such as FunctionGemma is never fetched from the app. If we ship a fine tuned derivative, we host it publicly with the Gemma notice, and the app shows the licence and use policy before Download. Download to a temporary file and rename only when both match, like synced files.
- Android stores it in `noBackupFilesDir`. iOS sets `NSURLIsExcludedFromBackupKey`, as `IosFiles.kt` does.
- Store hosted options exist ([Play AI packs](https://developer.android.com/google/play/on-device-ai), beta, 1.5 GB each, and Apple Background Assets).

## 8. Store impact

- Data processed only on the device is not collected for Play Data safety and the App Store label, so both answers stay as long as nothing leaves the phone.
- Classification is open and must be settled before release. The pilot generates text from user prompts, so Play's [AI-generated content policy](https://support.google.com/googleplay/android-developer/answer/13985936) likely applies. Apple guideline 1.2 covers user generated content and may not apply to answers only the user sees, but plan as if it does.
- Reporting stays in the app. Each answer has a Report action that opens an in-app sheet with a reason list. The sheet shows the exact payload, the question, the answer and the reason, never tool results, device handles or settings. Nothing is sent until the user taps Send. Sending needs one fixed report endpoint, a new network use (owner question 4). Until it exists, Report hides the answer locally and keeps the reason on the phone.
- Filtering. The instructions keep the model on the user's Things, air quality and the app. An output check in Kotlin drops answers that contain links, phone numbers or medical dosing words, and shows a fixed message instead. There is no user to user content, so blocking users does not apply. A published contact address goes in the store listing and in About.
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
4. Accept a fixed report endpoint for AI answer reports, sent only after the user reviews the payload?

## 11. First step

1. `:core:pilot` with the registry, router and summarisers for the R tools, tested on the host with a scripted fake engine.
2. Android LiteRT-LM adapter on CPU with Gemma 4 E2B, behind a Developer level switch, downloaded by hand for now.
3. Dated OnePlus run of tool accuracy and latency in `docs/status.md`. Then iOS Foundation Models.
