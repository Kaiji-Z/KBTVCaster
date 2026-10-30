# VERIFICATION PROTOCOL — KBTVCaster project instance

> **Project-local instance, instantiated 2026-10-12 by stop-manual-testing diagnosis.**
> This copy is THE instance for KBTVCaster: all §8 parameters, gap lists, and future
> updates live here. Never edit the global skill template, never copy project data back.

> **READ THIS FIRST. Opening this file = trigger to execute. No further user instruction required.**
> A trigger phrase such as "read VERIFICATION.md" authorizes all actions defined herein.

**First principle: your work is not done unless there is machine-checkable evidence that it is done.**
"I ran it and it looks right" does not count. "Tests converge under both flag=on and flag=off" counts.

---

## EXECUTION OVERVIEW (every step must emit a GATE declaration, see §0)

### Trigger → Diagnosis Pipeline (7 steps, none may be skipped)

```
1. Read context: this file + AGENTS.md + CLAUDE.md/GEMINI.md + README + build config + directory tree + backend entry + test entry
2. ACI audit (§2): judge 2.1 / 2.2 / 2.3 one by one. Each item MUST carry evidence (file:line).
3. Test infra inventory: regression / assertions / supervisor / flag — four items.
4. Output gap list: a table sorted by P0/P1/P2, with remediation plan.
5. Instantiate the protocol locally, then fill Project Parameters (§8):
   a. If no VERIFICATION.md exists in the project root, copy this protocol there. That project-local copy is now THE instance: all §8 filling, all future updates, all project customization happen in it. The global skill template stays generic and untouched.
   b. If a project-local VERIFICATION.md already exists (previous run or manual install), use it — never overwrite it; its filled §8 is this project's state.
   c. Then fill §8 in the local copy: [auto-fill] items by scanning code with evidence; [must-ask] items by asking the developer in one batch.
6. Update AGENTS.md: paste audit / status / backlog + a top-level reference pointing to the PROJECT-LOCAL `VERIFICATION.md` (§9 template), not to the skill.
7. Stop and report: one-line stage summary + top-3 P0 items + ask "ready to start remediation?"
```

**Writes allowed this round: the project-local `VERIFICATION.md` (instantiate + §8 fill) and `AGENTS.md`. Modifying production code is FORBIDDEN.** Remediation requires user confirmation, next round.

---

## §0 GATE MECHANISM (Declare-Verify-Enforce — the lifeline of the whole protocol)

> This is the core mechanism against "agent skipping steps." LLMs naturally drop steps in multi-step pipelines; "please follow strictly" cannot stop it. This mechanism makes compliance visible, checkable, and blocking-on-mismatch.

**After each step completes, you MUST emit a GATE declaration at the end of that step's output. Fixed format:**

```
GATE [step N]: DONE
- Did: [concrete action + artifact location]
- Evidence: [file:line / command output / developer answer quoted]
- Next: [step N+1 name]
A step without a GATE declaration is considered incomplete.
```

**Verify rules (self-check, every step):**
- Every "Did" must have a matching "Evidence." No evidence = not done.
- No "I think" / "probably" / "maybe" in a declaration. Compliance is boolean, not probabilistic.

**Enforce rules (violation blocks the pipeline):**
- Any step without a GATE → must NOT proceed to the next step
- GATE declaration contradicts the artifact (claims AGENTS.md updated but file unchanged) → redo that step
- A [must-ask] item filled without a developer answer → that step is void, re-ask

---

## §1 YOUR ROLE

Old: write code → human tests → human judges correctness → you fix
New: **first engineer "what counts as correct"** (assertions / regression / acceptance) → write code → **machine judges** → you self-correct until convergence

Humans do not participate in runtime verification. They intervene only once, at the "define what counts as correct" stage.

---

## §2 ACI AUDIT (judgment criteria for Diagnosis step 2)

**If any of the three is below standard, the verification system spins idle.** Fix the architecture first, not write tests first.

### 2.1 Runs without the UI
- [ ] Backend can start independently, not depending on the frontend
- [ ] Triggering a workflow has a CLI/API form, not requiring browser clicks
- [ ] One complete workflow can run end-to-end in a headless environment (terminal/CI)

### 2.2 Intermediate state is logged
- [ ] Each workflow step (tool call / return / branch) has structured records
- [ ] Records are retrievable programmatically, not only by eyeballing a web page
- [ ] History is queryable after the run ends

### 2.3 Programmatic interface
- [ ] "View workflow status" / "fetch trace" have native interfaces
- [ ] Prefer backend/frontend split / native API. **Do NOT** use MCP to simulate web interaction (worse on auth / corner cases / efficiency)

**Judgment standard: MUST have file:line evidence. Never judge "meets standard" without evidence.**

---

## §3 TWO-LAYER JUDGE (the core of the development workflow)

### 3.1 Layer 1: Deterministic assertions — absolutely reliable, zero cost
Never use an LLM where this layer can catch it. Typical form:
```
# Logic form (tool-agnostic):
assert tool_was_called("search", within_steps=[3, 4])
assert records_count_at_step(5) == 3
assert branch_taken == "happy_path"
```
**Land on a tool (decided by §8.7 during diagnosis; do NOT invent your own syntax):**
| Project has | How to assert | Detection signal |
|---|---|---|
| DeepEval | `assert_test(test_case, metrics=[ToolCorrectnessMetric()])` | `import deepeval` |
| LangSmith | replay dataset + compare trace fields | `@traceable` decorator present |
| pytest native | plain `assert` + fixture capturing trace | `pytest` in deps |
| None | go to §8.7 [must-ask], pick a tool first | — |

### 3.2 Layer 2: LLM judge (supervisor) — three iron rules
1. **Context MUST be clean.** The supervisor does no development, knows nothing about how the code is written, sees only "the expected correct behavior." Once it knows the code, it scores its own people high — verification is void.
2. **Quantitative scoring only, no right/wrong verdicts.** Score outputs that have no single answer; measure how much better / worse.
3. **Ideally use a different model/prompt than the generator.**

Supervisor prompt template (must be isolated):
```
You are an acceptance judge. You see only two things: expected correct behavior + actual run trace.
You do not know how the code is written, and do not need to.
Score each dimension 0–10 and give deduction points: [dimension A/B/...]
```
**Land on a tool (by §8.7):**
| Project has | How to call the supervisor |
|---|---|
| DeepEval | `GEval` / `FaithfulnessMetric` or custom metric (built-in scoring, but ensure the judge model differs from the generator) |
| LangSmith | `RunEvalConfig` + `EvaluatorType.SCORE`; judge model specified in the evaluator |
| None | go to §8.7 [must-ask] |

---

## §4 REGRESSION SET + FLAG

- **Happy path = acceptance criteria, not a test case.** Write the happy path for each new feature, freeze it into the regression set.
- **Fuzzy-input set:** collect wild inputs from real users / tests into the regression set.
- **Every new feature MUST have a flag.** Run the SAME regression suite with flag=on and flag=off; compare "what got better / what got silently broken."

**Regression set landing (by §8.7):**
| Project has | Where the regression set lives | How to run flag on/off |
|---|---|---|
| DeepEval | `test_*.py` + `@pytest.mark.eval` | `FEATURE_FLAG=X pytest` |
| LangSmith | `client.create_dataset` + `list_examples` | two runs tagged with different metadata, then `client.compare_datasets` |
| Self-built tests | `tests/regression/` + fixtures | CI matrix runs two envs |

---

## §5 CLOSED-LOOP SOP (develop any feature in this order — order cannot be changed)

```
1. Design the regression test: write the happy path (acceptance) → write assertion points → decide which fuzzy parts go to the supervisor. Not one line of feature code written yet.
2. Design the flag: default off; ensure off == pre-change behavior.
3. Write code: implement the flag=on behavior.
4. Run the closed loop: flag=off records baseline → flag=on runs same suite → assertions + supervisor judge.
5. Fix per feedback: off regressed → fix; on below acceptance → revise. Back to step 4.
6. Convergence stop (see §6 DoD).
```

**Humans do not participate in runtime verification within this flow.** They intervene only once before step 1 (to define acceptance).

---

## §6 DEFINITION OF DONE (machine-checkable "complete")

A feature is done if and only if ALL hold:
- [ ] happy path written as a regression test, in the regression set
- [ ] §3.1 assertions all pass under flag=on
- [ ] supervisor score reaches the preset threshold
- [ ] flag=off runs the same suite, no regression vs baseline
- [ ] the feature has a flag, can be turned off to roll back anytime
- [ ] all of the above reproducible by one command, no human screen-watching

**"Done" is a machine-judged claim, not your subjective opinion.**

---

## §7 RED LINES (violation voids the output) — consolidated here, not repeated elsewhere

1. MUST NOT be your own judge (supervisor context must be clean)
2. MUST NOT claim done without a regression test
3. MUST NOT skip §5 step 1 and jump to code
4. MUST NOT use "feels right" as a convergence stop
5. MUST NOT let verification live only in the UI
6. After changing prompt / model / any non-deterministic component, MUST run full regression
7. **MUST NOT guess-fill any [must-ask] item in §8**
8. **MUST NOT reinvent the wheel**: when the project already has an eval tool (§8.7), §3/§4 MUST use its API; do not invent assertion syntax or a regression framework
9. **MUST NOT install dependencies on your own**: when §8.7 detects no tool, recommend via [must-ask]; the developer decides and installs; the agent MUST NOT `pip install` / `npm install`

---

## §8 PROJECT PARAMETERS (filled during Diagnosis step 5, in the PROJECT-LOCAL copy)

> This section is the per-project customization zone. It is meaningful only in a project-local instance — a global template with filled §8 items is cross-project contamination. Filling always targets the copy instantiated in Diagnosis step 5a/5b.

**[auto-fill]** = scan code with evidence (file:line); missing evidence → fill "none, needed", no guessing
**[must-ask]** = ask the developer per the template; fill after an answer; before that fill "pending"; guessing forbidden

**Language rule:** all conversation with the developer — announcements, the [must-ask] questions in §8.4 / §8.5, gap-list reports — follows the developer's language. Infer it from their messages; never paste the English templates verbatim at a non-English speaker.

### 8.1 System entry [auto-fill] — FILLED 2026-10-12
- Backend start command:
  `adb shell am start-foreground-service -n com.kbtv.caster/.service.CastCoordinatorService`
  (service declared AndroidManifest.xml:51-54, foregroundServiceType=mediaPlayback; action dispatch CastCoordinatorService.kt:85-92; `ACTION_START` CastCoordinatorService.kt:328). Headless boot path also exists via BootReceiver (AndroidManifest.xml:62-72).
- CLI/API command to trigger a workflow:
  None built-in. Real-world trigger is a UPnP control point sending SetAVTransportURI over SOAP → `DlnaProtocol.onPlay` (DlnaProtocol.kt:50-53). In-process, drive `CastProtocol.PlaybackSink.onCommand` directly (precedent: DlnaProtocolTest.kt:24-29). Needed for remediation: instrumented-test entry or control-point simulator script.
- Command/API to fetch a trace:
  `adb shell run-as com.kbtv.caster cat files/trace/cast.jsonl` (TraceRecorder.kt:19, structured JSONL, persists across reboots — field-proven 2026-10-26/29 forensics). Live audio-engine counters: `NativeBridge.nativeServerAudioStats` (native_bridge.cpp:438-443). Protocol health: RTSP OPTIONS probe against port 7000 (verified ad-hoc this session; script not yet committed — gap #2).

### 8.2 Test infra [auto-fill] — REFRESHED 2026-10-30 (Phase-2 field rounds)
- Regression run command: `./gradlew test`（本机 wrapper 损坏，改用缓存 8.14.3 直调，见 .goal/SPEC.md §6）；三旗态：默认 / `-Pkbtv.flags.playbackPlan=true` / `-Pkbtv.flags.airplay=true`
- Regression set directory: `app/src/test/java/com/kbtv/caster/` — 90 tests @ `@Test` across 11 files (grep 2026-10-30；含 Phase-2 新增 TraceRecorderTest / AirPlayCommandMapperTest / AirPlayRequestTest / AirPlayServerTest)
- Instrumented: `app/src/androidTest/java/com/kbtv/caster/e2e/` — 9 files (DlnaE2eTest, AirPlayE2eTest, MirrorDecodePathTest, MirrorRenderPathTest, MirrorRendererInProcessTest, DecoderAbTest, GlDisplayDrawTest, AudioPipelineProbeTest, TestMediaServer)。真机（SHARP Android 6）实测可跑：DecoderAbTest 控制实验 622/733 帧、AudioPipelineProbeTest sine+AAC 探针
- Host-side control-point simulator: `app/src/androidTest/assets/controlpoint/dlna_control_point.py`（stdlib-only；trace 取 UDN+描述符路径 → /proc 端口枚举 + adb forward → 描述符 200 → SOAP SetAVTransportURI/Play（SOAPACTION 需双引号）→ trace 断言 play_started）
- Assertion framework: JUnit 4.13.2 + mockito-core 5.8.0 + mockito-kotlin 5.2.1 + kotlinx-coroutines-test. Capturing-sink pattern established at DlnaProtocolTest.kt:24-29.
- **缺失（gap #1/#3）**：会话回环压测（连接→拆→重连 N 轮 + RTSP 应答断言）不存在——2026-10-29 httpd 线程死亡类缺陷全靠用户真机暴露；host 侧 RTSP/mDNS 探针未入库（会话临时代码）

### 8.3 Flag mechanism [auto-fill] — UPDATED 2026-10-30
- Mechanism: `app/build.gradle.kts` reads Gradle property `-Pkbtv.flags.<name>=<bool>` into a generated `BuildConfig.FLAG_*` boolean (default false == pre-change behavior); runtime access via `com.kbtv.caster.service.protocol.FeatureFlags`. Flags in production: `kbtv.flags.playbackPlan` → `FLAG_PLAYBACK_PLAN_ROUTING`; `kbtv.flags.airplay` → `FLAG_AIRPLAY`（镜像协议注册门控，v0.3.0 起）. Build-only variant selector: `kbtv.build.abi`（单 ABI 快装，app/build.gradle.kts:27-29）。CI：android.yml master 推送双旗（off/on）矩阵，release.yml tag 驱动双 APK 签名发版（v0.3.0/v0.4.0 验证）。

### 8.4 Supervisor design [must-ask] — ANSWERED 2026-10-12
> Developer answer (quoted): 「不需要，纯确定性断言（推荐）」
- Model scoring fuzzy parts: **none — deterministic-only, no LLM supervisor** (media player behavior is fully state-machine-observable)
- Scoring dimensions: N/A
- Passing threshold: N/A
- Supervisor prompt MUST NOT contain: N/A (no supervisor exists). If one is ever introduced later, the default forbid-list applies: code implementation / PR description / commit / dev conversation.

### 8.5 Acceptance criteria [must-ask] — ANSWERED 2026-10-12
> Developer answer (quoted): 「确认草稿，含端到端项（推荐）」 — e2e items INCLUDED per the §8.7 scope decision (emulator + DLNA control-point simulation).
1. Happy path of the core workflow:
   phone DLNA control point sends SetAVTransportURI(uri) → DlnaProtocol.onPlay → sink.onCommand(Play(uri)) → CastCoordinatorService.handlePlay picks media source by URI (HLS vs progressive; Baidu UA when pan.baidu.com) → ExoPlayer reaches STATE_READY then isPlaying=true → MainActivity foregrounded if backgrounded. Pause/Seek/Stop map to ExoPlayer pause/seekTo/stop.
2. Acceptance criteria (under condition X, should Y):
   - URI containing `.m3u8` / `m3u8_auto` / `type=m3u8` (case-insensitive) → HlsMediaSource path
   - URI containing `pan.baidu.com` + HLS → HTTP User-Agent `pan.baidu.com`, connect/read timeout 30s
   - Play received while app backgrounded → MainActivity brought to foreground
   - Every DlnaProtocol listener event forwards exactly one matching PlaybackCommand, in arrival order
   - Service stop/destroy → ExoPlayer stop() AND release() both called, protocols stopped, status STOPPED
   - [e2e] sink Play command → ExoPlayer STATE_READY + isPlaying within timeout (emulator, local test stream via 10.0.2.2)
   - [e2e] DLNA control-point SOAP (SetAVTransportURI + Play via adb-forwarded Cling port) → same READY/isPlaying outcome through the real AVTransport chain
   - [e2e] BOOT_COMPLETED → service RUNNING, no crash (restart-tolerant)
3. Reverse acceptance criteria (MUST NEVER happen):
   - Non-HLS URI never built as HlsMediaSource (substring guard: "my3u8file", HlsDetectionTest.kt:52-56)
   - Non-Baidu URI never gets the pan.baidu.com User-Agent
   - Protocol code never touches ExoPlayer directly (architecture rule, CastProtocol.kt:43-46)
   - handlePlay never crashes the service on a bad URI (catch+log, no rethrow)
   - BootReceiver never blocks the main thread (Handler.postDelayed, not Thread.sleep)

### 8.6 Fill status (maintained by the agent)

| Item | Category | Status | Source |
|---|---|---|---|
| 8.1 | auto-fill | FILLED | code scan 2026-10-12 (evidence inline) |
| 8.2 | auto-fill | FILLED | code scan 2026-10-12 (evidence inline) |
| 8.3 | auto-fill | FILLED ("none, design during remediation") | code scan 2026-10-12 |
| 8.4 | must-ask | ANSWERED (deterministic-only, no LLM judge) | developer 2026-10-12 |
| 8.5 | must-ask | ANSWERED (draft confirmed, e2e items included) | developer 2026-10-12 |
| 8.7 | auto-fill→must-ask | ANSWERED (JUnit; scope = emulator instrumented + DLNA control-point sim) | code scan + developer 2026-10-12 |
| 8.1/8.2/8.3 | auto-fill | RE-AUDITED & REFRESHED (trace API now exists; 90 JVM + 9 instrumented files; 2 prod flags + CI matrix) | code scan 2026-10-30 (this diagnosis) |
| 8.5 | must-ask | **PENDING** — Phase-2 镜像验收标准（≥300 帧 / p95<150ms / 断开重连）拟从会话 goal 冻结入册 + 新增回环锤验收项，待开发者一句话确认 | session goal (user-authored) + 2026-10-30 诊断提议 |

## §10 DIAGNOSIS 2026-10-30 — ACI re-audit + gap list (Phase-2 field rounds)

**ACI 判定**（对比 2026-10-12 基线）：
- **2.1 无 UI 运行**：✅ 组件级达标 —— 服务 headless 启动（8.1 证据仍有效）；DLNA 链全程 headless（dlna_control_point.py）；AirPlay 组件级 headless（RTSP OPTIONS via adb forward、AudioPipelineProbeTest、DecoderAbTest、GlDisplayDrawTest）。△ **完整镜像会话仍需物理 iPhone**（FairPlay 配对无脚本驱动端）——架构边界，非本轮可修。
- **2.2 状态记录**：✅ 达标（2026-10-12 为 △）—— TraceRecorder JSONL 持久化跨重启（TraceRecorder.kt:13-19,96；files/trace/cast.jsonl）；25 处 mirror_* trace 事件（grep 2026-10-30）；原生计数器 [kbtv] raop_rtp.c 4 处；音频引擎计数经 JNI 字符串（native_bridge.cpp:438-443）。
- **2.3 程序化接口**：✅ 达标 —— trace 拉取命令、nativeServerAudioStats、RTSP/mDNS 探针均程序化（后两者脚本未入库，见 gap #2）。

**Gap 列表**（P0 优先）：

| # | 项 | 级别 | 根因证据 | 修复方案 |
|---|---|---|---|---|
| 1 | 会话回环压测缺失 | **P0** | 2026-10-29 httpd 线程死亡、网络重绑失效、栈重建必要性——全部先由用户真机暴露，后才溯源 | ✅ 已落地（2026-10-30）：scripts/probe/ 四工具 + 真机锤 4/4 + 翻转复现器 FlipStreamReproTest（12 翻转全绿洗清渲染层） |
| 2 | 探针工具未入库 | **P0** | grep 证伪（2026-10-30） | ✅ 已落地：rtsp_health / mdns_query / reconnect_hammer / assert_session + README 纪律（勿对使用中接收端上锤） |
| 3 | 真机验收断言未固化 | **P0** | 验收标准只存在于会话 goal | ✅ 已落地：assert_session.py（frames≥300 / p95<150ms / 连续会话）；Phase-2 标准冻结仍待开发者确认（§8.6 PENDING） |
| 4 | 模拟器双旗 e2e 过期 | P1 | 上次全绿 2026-10-13；此后 ~40 commits 未复验 | M5 回归轮：TV AVD 双旗 androidTest 全跑 |
| 5 | GPLv3 合规文档欠账 | P1 | v0.4.0/v0.4.1 已带 vendored UxPlay 补丁发布 | 补 LICENSE-SWITCH.md 审计行 + NOTICE |
| 6 | **iOS 发送端切换冻结（视频连播黑屏）** | **P1** | 2026-10-30 结案：接收端五信道逐层取证全无罪（渲染/RTSP/NTP/音频/对上游无差异）；M0 对照存活；乐播为自研镜像协议（TCP×3 + mirror_frame_store 帧仓库 + 秒级心跳 + stopPrevious 切换会话重建 + 音频丢帧模式，2026-10-30 M0 会话日志实证）；无 root 无法抓乐播 TCP 载荷 | **liblelink AirPlay 会话行为 clean-room 提取**（用户已批准排期，2026-10-30）：读反编译产物提取其对 iOS 的应答行为差异，写入我们的 raop 层；周级工程，按既定 clean-room 规则（乐播产物一行不进仓库） |
| 7 | 冷启动解码器解锁 | P1 | 热身器合成流 4 轮 cold、真实流可用（2026-10-30 证明 cold 不拦真会话）；彻底解锁疑在乐播原生库 init 序列 | 并入 #6 的 clean-room 提取 |
| 8 | lint 阻塞门禁 | P2 | abortOnError=false | 可选 |
| 9 | VDEC 异常释放泄漏根因 | P2 | 已有栈重建+冷却探针兜底 | 可选 |

### 8.7 Eval toolchain [auto-fill→must-ask] — FILLED 2026-10-12 ⚙️ orchestration item

**Step 1 [auto-fill]: detect existing tools (scan dependency files)**
- [ ] `deepeval`? none — JVM project, no Python dependency files
- [ ] `langsmith` / `langchain`? none
- [ ] `pytest`? N/A — Kotlin/Gradle project (no requirements.txt/pyproject.toml)
- [ ] `jest` / `vitest`? none — no package.json in app
- [x] other eval tool? **JUnit 4 + Mockito 5 + kotlinx-coroutines-test** (app/build.gradle.kts:103-106) — the JVM analog of the "pytest native" row

**Landing decision (developer 2026-10-12: 「再加 DLNA 控制点模拟」 — full scope):** using **JUnit 4 native asserts + Mockito** (Layer 1 = plain `assert` + capturing fixtures, pattern proven at DlnaProtocolTest.kt:24-29). Regression set lands in `app/src/test/` (JVM) + `app/src/androidTest/` (to be created during remediation: TV emulator instrumented tests asserting the ExoPlayer state machine) + a host-side **DLNA control-point simulator** (SOAP SetAVTransportURI/Play/Pause/Seek sent via `adb forward` to the guest Cling HTTP port — SSDP multicast discovery is skipped deliberately: qemu NAT doesn't forward multicast reliably, and discovery is Cling vendor code outside our change surface). Flag on/off via Gradle project property or BuildConfig field feeding the same suites. Step-2 [must-ask] tool recommendation not triggered (JUnit exists). Prerequisite owned by the developer per §7 red line 9: install an Android TV system image (e.g. `system-images;android-34;android-tv;x86_64`, ~1GB) and create a `tv_1080p` AVD — none installed locally today (only Pixel_7 / android-36.1).

---

## §9 AGENTS.md TEMPLATE (used in Diagnosis step 6)

```markdown
# {project name} · Agent Development Guide

## Mandatory protocol
Before developing any feature or changing any code, read and follow the project-local `VERIFICATION.md`.
It carries this project's §8 parameters — edit it here, in this repo, never in a global skill directory.
Output that violates a red line in VERIFICATION.md §7 is void.

## Project overview / Build & run / Verification system status / Test infra status / Verification backlog / Project-specific conventions
[filled by the diagnosis pipeline]
```
