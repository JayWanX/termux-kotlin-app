# termux-kotlin-app：终端状态控制与交互 + AI 辅助层

> 基线 `@4e5b7f77`。本文描述目标设计与落地顺序，不含版本演进。
> `文件:行号` 引用均经实读核对；文末「落地记录」列出实现时的实际取舍。

---

## 0. 定位与铁律

**主线是终端状态控制与交互；AI 是挂在它上面的薄辅助层。**

| 铁律 | 含义 | 检验方式 |
|---|---|---|
| **① 删除即消失** | 删掉 `com.termux.app.ai` 包 + 单处挂载 → 应用照常编译运行 | `scripts/check-layering.sh`（CI 执行） |
| **② 单向依赖** | AI 只读 `TerminalControl`，不碰 `TerminalSession`/`TerminalEmulator` 内部 | 同上，规则 2/3 |
| **③ 核心无 AI** | `TerminalState`/`TerminalControl` 里不出现任何 LLM 概念 | 同上，规则 3 |

上述三条不是口号：`scripts/check-layering.sh` 在 CI 里以 `import` 语句为准做静态检查，注释文字不会误伤。

**当前耦合已经极窄**——`app/` 内只有 3 处引用旧 agent 代码：

```
TermuxApplication.kt:74    startAgentService(context)
TermuxInstaller.kt:236     setupAgentFramework(...)
TermuxInstaller.kt:252     AgentService.startAgentService(...)
```

### 构建现状（已实测）

`gradle :app:compileDebugKotlin --rerun-tasks` **通过**。删除旧代码的理由是**清理负债**，不是修复构建：

- `agents/` 包 44 文件中约 70% 从未被执行
- `LlmModule.kt:28-38` 与 `di/AgentModule.kt:127-151` 重复提供 `LlmConfig`/`LlmProvider` —— 这是**潜伏缺陷**：Dagger 惰性解析，目前没有任何组件请求这两个类型（`IntentRecognizer`/`ReasoningOrchestrator`/`TaskPlanner` 虽 `@Inject` 但它们自身无人请求），所以不报 `DuplicateBindings`。**一旦有人接入就会立刻失败。**
- 145 KB `agents.zip` + 1.1 MB Python 框架进包
- 一个 `START_STICKY` 前台服务常驻通知

> 注：`assembleDebug` 在本机失败于 NDK（`terminal-emulator` 的 `ndk-build` 崩溃，exit `-1073741819`），属本机环境问题，与本文改动无关；Kotlin 编译与 Hilt/KSP 均正常。

---

## 1. 终端状态控制：现状盘查

### 1.1 已有的能力（可直接用，无需新写）

| 能力 | API | 备注 |
|---|---|---|
| 当前会话 | `TermuxActivity.kt:734` `val currentSession: TerminalSession?` | |
| 多会话 | `TermuxShellManager.kt:22` `mTermuxSessions` | |
| 进程存活 | `TerminalSession.kt:249-250` `isRunning`（`mShellPid != -1`） | 最可靠 |
| **退出码** | `TerminalSession.kt:253-254` `val exitStatus: Int` | "仅当 `!isRunning` 时有效" |
| PID | `TerminalSession.kt:272` `getPid()` | |
| **工作目录** | `TerminalSession.kt:275-289` `getCwd()` | 已用 `/proc/<pid>/cwd/` 实现 |
| 写 PTY（原始） | `TerminalSession.kt:161` `write(ByteArray,Int,Int)` | 不做 `\n`→`\r` |
| 写 PTY（粘贴语义） | `TerminalEmulator.kt:1808` `paste(text)` | **自动 `\n`→`\r` + bracketed paste** |
| 发控制键 | `TerminalSession.kt:166` `writeCodePoint(prependEscape, cp)` | |
| 可见屏 | `TerminalEmulator.kt:267` `getScreen()` + `:165-166` `mRows/mColumns` + `TerminalBuffer.kt:56` `getSelectedText(0,0,cols,rows)` | |
| scrollback | `TerminalBuffer.kt:44` `getTranscriptText()` / `ShellUtils.kt:52` | |
| 光标 | `TerminalEmulator.kt:338-339` `getCursorRow()/getCursorCol()` | |
| **输出计数器** | `TerminalEmulator.kt:1730` `getScrollCounter(): Int` | 静默检测基础 |
| **备用屏判定** | `TerminalEmulator.kt:269` `isAlternateBufferActive()` | 判断 vim/less/top |
| **光标键模式** | `TerminalEmulator.kt:365-366` `isKeypadApplicationMode()` / `isCursorKeysApplicationMode()` | 发键前需知 |
| 标题（OSC 0/1/2） | `TerminalEmulator.kt:1346` `setTitle()` → `:1795` `val title` | shell 可主动设置 |
| 会话结束回调 | `TerminalSessionClient.kt:14` `onSessionFinished` | 唯一可靠的"进程死了" |
| 铃（BEL） | `TerminalSessionClient.kt:15` `onBell` | 已接震动/响铃 |
| 会话切换 | `TermuxTerminalSessionActivityClient.kt:231` `setCurrentSession()` | |

### 1.2 三个必须知道的陷阱

**① `onTextChanged` 在后台不回调**

```kotlin
// TermuxTerminalSessionActivityClient.kt:88-92
override fun onTextChanged(changedSession: TerminalSession) {
    if (!mActivity.isVisible) return          // ← 第 89 行
    ...
}
```

Activity 不可见时**直接 return**。后台状态跟踪**不能依赖这个回调**，必须轮询 `getScrollCounter()`。

**② `sendTextToTerminal` 不是公开 API**

`TerminalView.kt:320` 的 `fun sendTextToTerminal(text: CharSequence)` 定义在 `onCreateInputConnection`（`:265`）返回的匿名 `BaseInputConnection`（`:285`）内部，外部拿不到。发文本请用 `TerminalSession.write()` 或 `TerminalEmulator.paste()`。

**③ `onBell` 同样在后台不回调**

`TermuxTerminalSessionActivityClient.kt:157-158` 也是 `if (!mActivity.isVisible) return`。想用 BEL 做"命令完成提醒"，必须自己处理，不能依赖现有回调。

---

## 2. 必要功能（按优先级）

分三级：**P0 不做就没法用**、**P1 做了才谈得上好用**、**P2 锦上添花**。每条都标注了实现代价。

### P0-1 精确的"命令跑完了"判定

**为什么必要**：这是全部价值的地基。没有它，AI 只能盲发命令、用户只能盯屏幕，任何"执行完成后通知你"都不可能。

**分三层，逐层增强**：

#### 层 1：静默检测（必做，0.5 天）

```kotlin
// getScrollCounter() 变化 = 有输出
if (counter != last) → Busy
else if (silence >= 500ms && isRunning) → Idle
if (!isRunning) → Dead(exitStatus)
```

覆盖 `ls`、`git status`、`pkg install`、`cd`。**局限：`sleep 30` 会误判为 Idle。**

#### 层 2：哨兵标记（必做，1 天）

针对 AI 发起的命令，包一层：

```bash
{ <user_command>; }; printf '\n__AI_DONE_%d__\n' $?
```

轮询快照里是否出现 `__AI_DONE_<code>__`。**这样能拿到真实退出码，且不怕静默命令。**

三个必须处理的细节：
- 用 `{ ...; }` 而非 `( ... )`，保证 `cd` 等状态变更生效
- `printf` 前补 `\n`，避免命令无尾换行时标记粘在同一行
- 非零退出码也照常输出（`$?` 直接取，不要 `||`）

副作用：scrollback 多一行噪音。可接受——AI 场景下本就在意可读性。

#### 层 3：前台进程组探测（可选，2 天，收益最大）

**这是解决 `sleep 30` 误判的正解**，而且在 Termux 环境里可行（`getCwd()` 已在用 `/proc/<pid>/`）：

```kotlin
fun foregroundPgid(pid: Int): Int? =
    "ps -o tpgid= -p $pid".runCommand()?.trim()?.toIntOrNull()

fun isForegroundShellOnly(pid: Int): Boolean {
    val fg = foregroundPgid(pid) ?: return false
    return fg == pid   // 前台组就是 shell 自己 → 说明没在跑子命令
}
```

判定升级为：`Idle = 静默 && isForegroundShellOnly(pid)`。

- `sleep 30` → 前台组是 `sleep` 的 pgid ≠ shell pid → **不会误判 Idle** ✅
- `vim` → 同上，且 `isAlternateBufferActive()` 为真，可双重确认 ✅

代价：每次采样多一次 `ps` 进程（约 5-15ms）。**只在实际需要精确定论时调用**（如 `awaitIdle` 的最后一次确认），不要每 120ms 都跑。

> 备选：`android.system.Os` 没有 `tcgetpgrp`，只能走 `ps`。若嫌重，可只在层 1 判定 Idle 之后、层 2 无哨兵时补一次确认。

#### 层 4：shell 集成（可选，需用户配合）

更优雅但需要用户 `.bashrc` 配合。**不推荐**——会给用户增加负担，且层 2/3 已足够。

### P0-2 结构化快照

**为什么必要**：AI 和 UI 都需要"当前终端长什么样"，但两者要的形态不同。

```kotlin
data class TerminalSnapshot(
    val state: TerminalState,
    val visibleText: String,        // 可见屏（用户在看的）
    val scrollbackTail: String,     // scrollback 尾部（历史）
    val cursorRow: Int,
    val cursorCol: Int,
    val rows: Int, val cols: Int,
    val title: String?,
    val cwd: String?,               // getCwd()
    val isAltBuffer: Boolean,       // vim/less 中
    val exitStatus: Int?,           // Dead 时
)
```

**关键设计：快照只给事实，不给判断。** 是否"在提示符"、"要不要等"，是消费方的事。
- UI 用 `visibleText` + `state` 显示状态条
- AI 用格式化后的完整快照作为上下文

字符预算：`visibleText` 取尾部 ≤ 3000，`scrollbackTail` ≤ 1800，总计 ≤ 8000（对齐人类可读上限，也给模型留余地）。

### P0-3 统一的 TerminalControl 入口

**为什么必要**：当前能力散在 `TerminalActivity`、`TerminalSession`、`TerminalEmulator`、`TerminalView` 四个层级，任何新功能（通知、AI、无障碍）都要重新拼装。

```kotlin
// termux-shared/src/main/kotlin/com/termux/shared/terminal/TerminalControl.kt
interface TerminalControl {
    val state: StateFlow<TerminalState>
    fun snapshot(maxChars: Int = 8000): TerminalSnapshot

    suspend fun send(text: String, enter: Boolean = true)
    suspend fun sendKey(key: TerminalKey)
    /** 等 Idle/Dead 或超时；超时也是正常返回值，不抛异常 */
    suspend fun awaitIdle(timeoutMs: Long = 30_000L): TerminalState

    fun sessions(): List<SessionInfo>
    fun selectSession(sessionId: String): Boolean
}
```

**接口放 `termux-shared`**（已依赖 `terminal-emulator`，`ShellUtils.kt:52` 已直接接收 `TerminalSession`），**实现放 `app/.../terminal/`**（需要 `TerminalView` 与 Activity 生命周期）。

### P0-4 可靠的写入路径

**为什么必要**：发命令看起来简单，实际有三个坑。

| 场景 | 正确 API | 原因 |
|---|---|---|
| 发命令（AI / 通知动作） | `TerminalEmulator.paste(text)` | 自动 `\n`→`\r`，处理 bracketed paste（`TerminalEmulator.kt:1815-1818`） |
| 发原始字节 | `TerminalSession.write(ByteArray,Int,Int)` | 不做任何转换 |
| 发特殊键 | `TerminalSession.writeCodePoint(prependEscape, cp)` | 需按 `isCursorKeysApplicationMode()` 决定编码 |

**坑：`\n` vs `\r`。** 终端要 `\r`。`write(String)` 不做转换（`TerminalOutput.kt:9-13` 只做 UTF-8 编码），所以：

```kotlin
// 错误写法
session.write("ls\n")          // shell 收到 \n，多数情况能忍，但 bracketed paste 下会出问题
// 正确写法
session.emulator?.paste("ls")  // 转成 \r，且带 bracketed paste 包装
```

`sendKey` 的编码必须看模式——在 `vim` 里方向键要发 `ESC [ A`（应用模式），在普通 shell 里发 `ESC [ A` 也对，但 `less` 里 `ESC O A` 才是对的。所以：

```kotlin
val emulator = session.emulator ?: return
val code = KeyHandler.getCode(keyCode, 0,
    emulator.isCursorKeysApplicationMode(),
    emulator.isKeypadApplicationMode())
session.write(code)
```

参考 `TermuxTerminalViewClient.kt:326-331` 的现有写法。

### P0-5 Ctrl+C 打断

**为什么必要**：AI 保留能力、用户误操作、命令卡死——都需要它。当前代码里**没有任何 SIGINT 发送路径**（全仓 grep `SIGINT|SIGTERM` 无结果，只有 `TerminalSession.kt:225` 的 `SIGKILL` 用于 kill 会话）。

**实现**：不要直接 `Os.kill(pid, SIGINT)`（会杀掉整个进程组，副作用不可控）。**正确做法是往 PTY 写 `\x03`**：

```kotlin
suspend fun sendKey(key: TerminalKey) {
    when (key) {
        TerminalKey.CTRL_C -> session.writeCodePoint(false, 0x03)  // 走 PTY，由终端驱动分发
        ...
    }
}
```

这与用户按 Ctrl+C 完全等价，语义正确。

### P1-1 命令完成通知

**为什么必要**：这是"终端交互"最刚需的功能，且**不需要 AI**。长任务跑完时敲一下手机就知道。

**触发条件**（组合判定，避免误报）：
```
Dead → 一定通知
Idle && 距上次 Busy ≥ 5s && 当前无前台子进程（层 3）→ 通知
```

**内容**：会话标题 + 退出码（Dead 时）+ 最后一行输出。

**去重**：同一会话 3s 内不重复通知。`onBell` 也可作为额外的触发信号，但**不能只依赖它**（`:158` 后台不回调）。

### P1-2 会话状态条 / 指示器

**为什么必要**：用户切到别的 app 再回来，需要一眼看到"刚才那个 `pkg install` 还在跑吗"。

在 `TermuxActivity` 顶部或会话列表里显示状态点：`Busy` 灰、`Idle` 绿、`Dead` 红。数据直接来自 `TerminalControl.state`。

### P1-3 会话健康度

**为什么必要**：Termux 场景下会话死掉很常见（OOM、进程被杀），但用户往往不知道。

```kotlin
data class SessionInfo(
    val id: String, val title: String,
    val pid: Int, val isRunning: Boolean,
    val exitStatus: Int?,      // Dead 时
    val cwd: String?,          // getCwd()
    val isAltBuffer: Boolean,
)
```

有了 `cwd` 就能做"重开上次目录"——这是很实用的一键恢复。

### P2-1 scrollback 搜索

**为什么必要**：`dmesg` 刷几百行后找错误信息，现在只能靠眼睛。

当前 `terminal-view` **无任何搜索实现**（grep `search` 无结果）。需要新写：在 `TerminalBuffer` 上加 `findInTranscript(pattern): List<Match>`，UI 侧做高亮跳转。

代价较高（1-2 天），但纯终端功能，与 AI 无关，价值独立。

### P2-2 命令历史与结构化回放

需要 hook shell 的 `HISTFILE` 或靠哨兵标记切分输出。价值中等，代价高（要处理多行命令、heredoc）。

### P2-3 会话模板 / 快速启动

预设一组会话（工作区、日志、REPL），一键拉起。属于便利功能。

---

## 3. AI 辅助层：最小可行

### 3.1 工具集

**终端工具（3 个）**

| 工具 | 参数 | 风险 | 依赖 |
|---|---|---|---|
| `read_terminal` | `max_chars?` | SAFE | `snapshot()` |
| `send_command` | `command`、`timeout_ms?` | MUTATING | `send()` + 哨兵拿退出码 |
| `send_key` | `key`（枚举） | SAFE | `sendKey()` |

**文件工具（7 个）**

| 工具 | 参数 | 风险 | 用途 |
|---|---|---|---|
| `read_file` | `path`、`offset?`、`limit?` | SAFE | 带行号读窗口，拒二进制 |
| `list_files` | `path?`、`recursive?`、`max_entries?` | SAFE | 定向，目录带 `/` |
| `edit_file` | `path`、`old_string`、`new_string`、`replace_all?`、`create_if_missing?` | MUTATING | **精确字符串替换** |
| `multi_edit` | `path`、`edits[]` | MUTATING | 单文件批量替换，全有或全无 |
| `write_file` | `path`、`content`、`make_executable?` | MUTATING | 新建 / 整体重写 |
| `move_file` | `source`、`destination`、`overwrite?` | DESTRUCTIVE | 移动 / 重命名 |
| `delete_file` | `path`、`recursive?` | DESTRUCTIVE | 删除，禁删根目录 |

**为什么 `edit_file` 是核心，而不是 `write_file`**

只给“读全文 + 写全文”会强迫模型为了改一行而重述整个文件：慢、贵，且容易在重述时丢内容。所以工具集围绕**局部精确操作**设计：

- `read_file` 返回**带行号的窗口**，大文件用 `offset` / `limit` 分页，而不是全量塞进上下文
- `edit_file` 用精确字符串匹配（对“读完之后文件变了”天然鲁棒）；**匹配到多处时默认报错**，而不是猜一个
- `multi_edit` 让“改五处”只需一次往返，且**全有或全无**——任何一条失败则整体不落地，绝不会留下改了一半的文件

**安全边界**

- 所有文件工具由 `FileSandbox.resolve` 限定在 `ToolContext.fileRoots`（默认 Termux home）；越界返回 `Denied` 而非异常，避免模型反复重试同一路径
- 符号链接单独检查：`getCanonicalPath` 在 Windows 上**不解析符号链接**，仅靠 canonical 会被 `<root>/link-to-outside/file` 绕过；`assertNoEscapingSymlink` 逐个祖先检查 `toRealPath()`
- 每次写入前 `BackupStore` 备份旧内容（每个文件保留 5 份，存在应用缓存而非用户目录）
- `UID` 确认分流：命令与文件编辑**分开**开关（`confirmCommands` / `confirmFileEdits`），因为命令不透明而编辑可回滚，用户可能只想确认其一

### 3.2 结构

```
app/src/main/kotlin/com/termux/app/ai/     ← 约 10 文件
├── AiConfig.kt          # EncryptedSharedPreferences：enabled/apiUrl/apiKey/model/maxRounds
├── OpenAiClient.kt      # OkHttp，Chat Completions + function calling
├── AgentLoop.kt         # 循环
├── Tool.kt              # ToolSpec / ToolResult / ToolRegistry
├── TerminalTools.kt     # 上面 4 个
├── AiPanel.kt           # Compose 面板
└── AiPanelViewModel.kt
```

**不建 Gradle 模块**——为一个辅助功能配置模块依赖 + Hilt 跨模块，会让"解耦"本身变成复杂度。改用单入口 + CI 兜底：

```kotlin
object AiAssistant {
    fun attach(control: TerminalControl, config: AiConfig)
    fun detach()
}
```

```bash
# CI：除挂载点外，禁止其它包导入 ai
! grep -rn "import com.termux.app.ai\." app/src/main/kotlin --include=*.kt \
    | grep -v "TermuxActivity.kt" | grep -q .
```

### 3.3 循环要点

```kotlin
repeat(maxRounds) {                       // 默认 12
    replaceEphemeralSnapshot(messages)    // 快照替换，不是追加
    val res = client.chat(messages, tools.specs())
    if (res.toolCalls.isEmpty()) return res.content
    for (call in res.toolCalls) {
        val ok = !tools.isRisky(call.name) || onConfirm(describe(call))
        val out = if (ok) tools.invoke(call, control) else "用户拒绝"
        messages += Msg.tool(call.id, out)
    }
}
```

必须保留的三个细节（都有踩坑依据）：
- **快照替换而非追加**——否则 12 轮把上下文撑爆
- **历史只留最近 6 轮**
- **system 消息位置归一**：开头连续 system 合并，中间 system 降级为 user（vLLM/Qwen 必需）

### 3.4 依赖

两者当前都不在 `gradle/libs.versions.toml`：

```toml
okhttp = "4.12.0"                 # com.squareup.okhttp3:okhttp
security-crypto = "1.1.0-alpha06" # androidx.security:security-crypto
```

---

## 4. 旧代码处置

`agents/` 框架与本文设计无继承关系，且正在阻塞构建。处置方式：

| 目标 | 范围 |
|---|---|
| Kotlin agent 包 | `app/src/main/kotlin/com/termux/app/agents/`（43 文件） |
| 前台服务 | `app/src/main/kotlin/com/termux/app/AgentService.kt` |
| Manifest | `AndroidManifest.xml:209-217`（receiver）、`:246-254`（service） |
| 启动调用 | `TermuxApplication.kt:74` + `:78-91` |
| 安装器 | `TermuxInstaller.kt:236`、`:1196-1349`（`setupAgentFramework`）、`:249-255` |
| 资产 | `assets/agents.zip`（145 KB） |
| Python 框架 | `agents/`（24 skill / 20 model / 1.1 MB） |
| 文档 | `docs/AGENTS.md`、`docs/AGENT_BOOTSTRAP_INTEGRATION.md`、`docs/plugin-sdk/v2/*`（7 篇） |

连带更新 `AI.md`、`ARCHITECTURE.md:67-98`、`ROADMAP.md:228-260`。

**收益**：Hilt 冲突自然消失（构建恢复）、少 145 KB 资产、少一个前台服务与常驻通知、detekt 不被死代码拉低。

若想保守：整体移到仓库根 `agents-legacy/`（不参与 Gradle 构建），效果等价。

---

## 5. 分阶段

| 阶段 | 内容 | 工作量 | 验收 |
|---|---|---|---|
| **S0 清理** | §4 全部删除，跑通构建 | 0.5 天 | 产出 APK；`grep -rn "app\.agents" app/src` 无结果 |
| **S1 状态核心** | P0-1 层1+层2、P0-2、P0-3、P0-4、P0-5 | 3 天 | 单测 4 状态迁移；真机 `pkg install` 正确判定；哨兵能拿退出码 |
| **S2 交互增强** | P1-1、P1-2、P1-3 | 2 天 | 后台跑完命令能收到通知；会话列表显示状态与 cwd |
| **S3 AI 薄层** | §3 全部（无 UI） | 2 天 | 单测：mock 一次 tool call 走完闭环 |
| **S4 AI 面板** | `AiPanel` + 挂载 | 2 天 | 真机对话驱动终端 |
| **S5 可选** | P0-1 层3（前台进程组）、P2-1（搜索） | 按需 | — |

**顺序不可颠倒**：S1 没做完就做 S3，AI 只能读裸文本、无法判断命令是否跑完，体验会退化。

---

## 6. 验收标准

**S1（核心，重点）**
- 单测 `sample()`：输出持续→`Busy`；静默 500ms→`Idle`；`isRunning=false`→`Dead(exitStatus)`
- 单测 `awaitIdle()`：超时返回 `Busy` 而非抛异常
- 真机：`ls` 后 500ms 内 `Idle`；`pkg install sl` 期间 `Busy`，装完 `Idle`
- 真机：`exit` 后 `Dead` 且 `exitStatus` 正确
- 真机：哨兵命令 `echo $?` 能返回真实退出码（含非零）
- 真机：`vim` 中 `isAltBuffer=true`
- 真机：`sendKey(CTRL_C)` 能中断 `sleep 100`

**S2**
- 后台（切到桌面）执行 `sleep 3; echo done`，回来收到通知
- 会话列表显示每个会话的 `Busy/Idle/Dead` 与 `cwd`

**S3/S4**
- 单测：mock `tool_calls`（`send_command`），执行后回填 `role:tool`，第二轮纯文本结束
- 单测：`send_command` 走 `onConfirm`，拒绝时不执行
- 真机：面板输入「看看当前目录有什么」→ 工具步骤可见 → 终端执行 → Markdown 回复
- 点停止：协程取消 + 终端收到 Ctrl+C

---

## 7. 明确不做

| 不做 | 理由 |
|---|---|
| 修复旧的 `agents/` 框架 | 70% 死代码 + 三重断裂的 Python 桥，修比重写贵 |
| 多协议 LLM 适配（Claude/Gemini 原生） | 统一走 OpenAI 兼容端点即可覆盖 |
| Skills / MCP / Swarm / Handoff | 与本项目定位无关，是另一类产品 |
| AI 写文件、改配置、管容器 | 用户用终端里的 `vim`/`pkg` 更合适 |
| 为 AI 单独造终端读写层 | `TerminalSession` 已足够，只缺薄封装 |
| shell rc 集成做精确判定 | 给用户增加负担；层 2/3 已够 |

---

## 8. 落地记录

以下为实现时相对上文设计的实际取舍，以及与预期不同的地方。

### 8.1 已实现

| 模块 | 文件 | 说明 |
|---|---|---|
| 状态模型 | `termux-shared/.../terminal/TerminalState.kt` | 4 态 + `isQuiet`/`isTerminated` |
| 快照 | `.../TerminalSnapshot.kt` | 只含事实；`exitStatus` 仅在 Dead 时非空 |
| 控制接口 | `.../TerminalControl.kt` | 含 `CommandOutcome`、`SessionInfo` |
| 静默检测 | `.../TerminalStateTracker.kt` | 纯 Kotlin、无 Android 依赖，可 JVM 单测 |
| 哨兵 | `.../CommandSentinel.kt` | `{ cmd; }; printf '\nMARK\n' $?` |
| 前台进程组 | `.../ForegroundProcessProbe.kt` | 解析 `ps -o tpgid=` |
| 实现 | `app/.../terminal/TerminalControlImpl.kt` | 120 ms 采样；主线程读屏；Ctrl+C 走 PTY |
| 完成通知 | `app/.../terminal/CommandCompletionNotifier.kt` | 需 busy ≥ 5 s + 后台 + 3 s 去重 |
| AI 层 | `app/.../ai/`（12 文件） | 配置/客户端/循环/终端工具/文件工具/沙箱/面板/设置 |
| 会话状态条 | `app/.../terminal/TerminalStatusView.kt` | P1-2，仅 Busy/Dead 时显示 |
| 会话健康度 | `app/.../terminal/SessionLabelFormatter.kt` | P1-3，会话列表带状态符号/退出码/cwd |
| scrollback 搜索 | `termux-shared/.../terminal/TranscriptSearch.kt` | P2-1，More 菜单入口 |
| 命令历史采集 | `app/.../terminal/CommandHistoryRecorder.kt` | P2-2，JSONL 落盘，含退出码与时长 |
| 会话模板 | `app/.../terminal/SessionTemplates.kt` | P2-3，内置 3 个 + 用户自定义 |
| 分层检查 | `scripts/check-layering.sh` | CI 三步静态校验 |

**测试**：`termux-shared` 114 项 + `terminal-emulator` 290 项 + `app` 474 项，共 **878 项**（debug + release 两个变体各一份）全部通过。

### 8.2 与设计不同的地方

| 设计 | 实际 | 原因 |
|---|---|---|
| `snapshot()` 非 suspend | 改为 `suspend fun` | 屏幕缓冲只能在主线程读，同步签名无法安全实现 |
| 状态监听由 UI 轮询 | `TerminalControlImpl.stateListener` 回调 | 通知器需要在状态变化时立刻拿到快照 |
| 计划中 `assistant` 设置页用 Compose | 改用原生 `PreferenceFragmentCompat` | 复用现有 `root_preferences.xml` 入口，代码量更小 |
| 注释里出现 "assistant" 无妨 | 改为只检查 `import` 语句 | 首版规则误报了注释文本 |

### 8.3 实测发现的问题

**① 修正了一处此前的错误结论。**
之前判断「Hilt 重复绑定导致构建失败」是**错的**。`gradle :app:compileDebugKotlin --rerun-tasks` 通过。真实情况是 Dagger **惰性解析**：`LlmModule` 与 `AgentModule` 确实重复提供 `LlmConfig`/`LlmProvider`，但当前没有任何组件请求这两个类型，所以不报 `DuplicateBindings`。这是**潜伏缺陷**——一旦有人接入就会失败。旧代码已整体删除，问题随之消失。

**② `assembleDebug` 在本机失败于 NDK，与本文改动无关。**
`terminal-emulator` 的 `ndk-build` 崩溃（exit `-1073741819`，访问冲突）。Kotlin 编译、KSP、Hilt、单元测试均正常。

**③ 单元测试抓到一个真实安全 bug。**
`ReadFileTool.resolveInsideHome` 原本用 `File(home, path)` 拼接，而 `File(parent, child)` 在 **child 为绝对路径时并不会总是丢弃 parent**（Windows 上会直接拼接）。结果是 `C:\...\home\` + `C:\...\evil.txt` 被拼成 home 内的合法路径，沙箱被绕过。已改为显式判断 `File(expanded).isAbsolute`。

> **补充：这个修法后来被 ⑪ 推翻了一半。** 显式判断绝对性本身没错，但把它用在"先剥掉 `~` 再判断"的流程上是错的——剥掉 `~` 后的 `/docs` 在 POSIX 上就是绝对路径。正确的做法是先按前缀拼接（见 ⑪），绝对性判断只用于**从未经过 `~` 展开**的原始输入。

**④ 测试环境依赖需要联网首拉。**
`okhttp` 与 `security-crypto`（连带 `tink-android`）首次构建需联网下载，之后可 `--offline`。`org.json` 在纯 JVM 单测中是空桩，故相关测试改用 Robolectric。

**⑤ 文件工具的测试抓到一个真实沙箱逃逸。**
首版 `FileSandbox.resolve` 只依赖 `canonicalFile` 判断越界。实测发现 **Windows 上 `getCanonicalPath` 不解析符号链接**（同一路径在 Android/Linux 上会解析），于是 `<root>/link→/outside/secret` 被判定为在 root 内。已补 `assertNoEscapingSymlink`：沿祖先链检查 `Files.isSymbolicLink` + `toRealPath()`，任一环节指向 root 之外即拒绝。同时修了祖先检查在“目标就是 root 本身”时误拒的问题。

**⑪ `~/xxx` 在真机上完全不可用（CI 才抓到）。**
`resolve` 原本这样展开家目录：

```kotlin
val expanded = if (trimmed == "~") home.path else trimmed.removePrefix("~")
val candidate = if (File(expanded).isAbsolute) File(expanded) else File(home, expanded)
```

把 `~` 去掉后剩 `/docs`。**在 POSIX 上 `/docs` 就是绝对路径**，于是 `~/docs` 被解析到文件系统根、再因越界被拒。Windows 不把前导 `/` 当作绝对路径，所以它在开发机上一直"通过"；只有 Linux CI 和 Android 会失败。

后果不是测试问题而是**功能缺失**：`~/` 是文档里推荐的写法，助手实际只能用 `~` 本身和纯相对路径，任何 `~/子路径` 都被拒绝。

修法：按前缀拼接，不再先剥前缀再重新判断绝对性。

```kotlin
val candidate = when {
    trimmed == "~" -> home
    trimmed.startsWith("~/") -> File(home, trimmed.removePrefix("~/"))
    File(trimmed).isAbsolute -> File(trimmed)
    else -> File(home, trimmed)
}
```

同时新增 `TildeExpansionTest`：断言 `~/a/b.txt` 与 `a/b.txt` 解析到**同一个文件**、保持全部子路径段、且 `~/../../etc/passwd` 仍被拒。这类"在某平台恒真"的断言才是能跨平台抓住回归的写法——原来那条 `assertTrue(resolve("~/docs").path.startsWith(home.path))` 在 Windows 上恒真，等于没测。

**为什么潜伏这么久**：两处 CI 配置叠在一起，让 Linux 上的测试从来没被真正执行过。

1. `run_tests.yml` 的 trigger 写的是 `master` / `android-10`，而仓库分支是 `main` —— 这个 workflow **从未被触发过**，本次是 run #1。
2. `coverage.yml` 在测试步骤上带 `continue-on-error: true`，所以即使测试真的失败，那个 job 依然显示绿色。它的 28 次运行全部"成功"，掩盖了同一个失败。

两处都已修正：

- trigger 加入 `main`。
- 去掉测试步骤的 `continue-on-error`；覆盖率上传改用 `if: always()`，这样失败时仍保留覆盖率报告，但**失败本身不会再被吞掉**。这两件事本来就不冲突，`continue-on-error` 是被误用成了"顺便还能传报告"。
- 新增 `scripts/check-workflows.py` 并接入 CI：任何 workflow 里出现 `continue-on-error` 就令构建失败。已验证把该字段加回去会让脚本退出码变 1 —— 否则这个坑迟早被重新踩。该脚本还会把 workflow 里引用的 Gradle 任务名和 build 脚本里声明的任务名对照，找不到的任务给出警告。
- 新增失败自诊断：`run_tests.yml` 在测试失败时解析 JUnit XML，把**具体测试名与首行错误**打印到 Actions 摘要页，并上传 XML/HTML 报告。本次能定位到 `tilde with subpath expands` 靠的正是这个信息；在它之前，CI 只给出"There were failing tests"，只能靠猜。

**⑫ `continue-on-error` 一去掉，暴露出第二个被掩盖的问题：项目里根本没有 JaCoCo。**

`coverage.yml` 一直执行 `./gradlew testDebugUnitTest jacocoTestReport`，但**整个仓库没有任何 JaCoCo 配置**，`jacocoTestReport` 这个任务从未存在过。第一步报 `Task 'jacocoTestReport' not found` 并失败，`continue-on-error` 把它吞掉，后面两个上传步骤于是把**空目录**发了 28 次。也就是说，这个名为"Code Coverage"的 workflow 从来没有产出过一行覆盖率。

修法是让它真的能跑，而不是删掉任务：

- `app/build.gradle` 应用 `jacoco` 插件。
- `testOptions.unitTests.all { }` 里开启 `jacoco.includeNoLocationClasses`，让单测运行时写出 `build/jacoco/testDebugUnitTest.exec`。不开启则报告无数据可读。
- 手写注册 `jacocoTestReport`（`JacocoReport` 类型）：Android 插件不会为它自己的 variant 自动建报告任务，所以类目录直接指向 `tmp/kotlin-classes/debug` 与 `intermediates/javac/debug`。排除 `R`/`BuildConfig`/Hilt 与 Room 生成代码，以及 Robolectric 运行时合成的类——否则这些未测试的生成代码会把整体覆盖率压成噪声。
- workflow 里改为 `:app:jacocoTestReport`（Gradle 本身能在根项目解析到模块任务，限定只是更明确）。

实测结果（真实数据，非 0）：整体行覆盖 **11.5%**（1701/14806），`com.termux.app.ai` 包行覆盖 **65.7%**（1162/1769）——新增的助手代码被测试覆盖到了，而整个 app 因历史代码基数大而偏低，这是可以接受且此前不可见的基线。

顺带确认了一个隐含假设：`jacocoTestReport` 报告落在 `app/build/reports/jacoco/jacocoTestReport/`，能被 workflow 里 `**/build/reports/jacoco/**/*.xml` 的模式匹配到，Codecov 的上传路径是对的。**如果当时图省事把任务删掉，这个 workflow 会显示成功，而覆盖率将永远为空。**

**⑥ 确认粒度按操作类型分开。**
原设计用单个 `confirmCommands` 同时管命令与文件编辑。这会让用户陷入两难：要么每次编辑都点确认（烦），要么为省事把关掉后连命令确认也没了。已拆成 `confirmCommands` 与 `confirmFileEdits`。

**⑦ 状态条默认隐藏，只在 Busy/Dead 时出现。**
最初考虑常驻一条状态栏，但那等于每次启动都占一行屏幕换取大多数时候无用的信息。改为 Idle/NoSession 时 `GONE`，终端因此上移的条件是真实存在的状态变化。

**⑧ 搜索不做终端内高亮。**
在 `TerminalView` 渲染层做高亮需要改动绘制管线，风险与收益不成比例。改为：对话框里显示 `n/N + 该行内容`，点 Next/Prev 时用 `setTopRow` 滚动到对应行。查找长日志这件事本身已被覆盖，而绘制层保持原样。

**⑨ 命令历史是重新实现的，没有复用既有 `CommandHistoryRepository`。**
`app/data/repository/CommandHistoryRepository.kt` 看上去正是为此存在，但实际是**纯内存 `MutableStateFlow`、且 `addCommand` 零调用者**——重启即清空，从未采集过任何命令。在该空壳上加持久化与采集，成本高于写一个职责单一的 `CommandHistoryRecorder`（JSONL + 有界裁剪）。既有仓库保持原样不动，避免顺手改坏一个看似相关实则无关的组件。

**⑩ 会话模板存在应用私有目录。**
`filesDir/session-templates.json`，而不是 `~/.termux/`。模板是 App 的行为配置，不是终端内容；放进用户 home 会污染 `ls` 结果，也会在备份/同步时被当作数据带走。

### 8.4 面板交互与观感（真机反馈驱动）

真机试用后的四个问题，以及修法。共同点是：**这些缺陷在代码评审里看不出来，必须真的看屏幕**。因此本轮同时补了一套把面板渲染成 PNG 的测试（见下）。

**① 右边缘滑动完全不触发。**
原因有两层，缺一层都不生效：
- **父布局的 `setOnTouchListener` 根本不会被调用。** `OnTouchListener` 只在「没有任何子 View 消费该事件」时才被父布局询问；而 `TerminalView` 的 `onTouchEvent` 无条件 `return true`，即吞掉每一次触摸。所以监听器形同不存在。
- **手势导航会先截走边缘滑动。** Android 10 起，屏幕边缘约 20dp 内的滑动属于系统（返回手势），App 连事件都收不到。

改法：
- `TermuxActivityRootView` 重写 `dispatchTouchEvent`，提供 `TouchObserver` 钩子。它在任何子 View 之前看到事件，且只是**观察**不消费——若在 `ACTION_DOWN` 就返回 `true`，就必须当场知道方向（此时还不知道），并会把边缘条带的滚动/选字一并夺走。
- 用 `setSystemGestureExclusionRects` 把右边缘条带声明为排除区。**平台两条限制都是静默失败**：排除区不得覆盖整条边、总面积不得超过屏幕 5%，违反任一条就被丢弃、边缘滑动退回成返回手势。两条限制写进 `GestureStrip.exclusionRect` 并有单测盯住。
- 排除区**只在助手可用时占用**。占住边缘意味着该处的系统返回手势失效，这对没开助手的用户是纯粹的功能损失；`syncGestureExclusion` 每次 `onResume` 重新判断，设置里开关即时生效。

**② 工具步骤展开后文字几乎不可见。**
`colorScheme.outline` 是**分隔线颜色**（深色主题下 `0xFF262B31`），却被当成正文颜色用在步骤详情上——渲染出来是深灰压深灰。改用 `onSurfaceVariant` 的降透明度色。同时给步骤区做了分层：折叠态是一整行可点的浅色 chip，展开态每条步骤一张独立的浅底卡片 + 状态圆点（失败为 `error` 色），成功项右侧一个勾。这样「回复」和「诊断细节」在视觉上不再混在一起。

**③ 按钮从文字改成图标。**
Header 的 Stop / Clear / Close 与输入框的 Send 全部换成图标；Send 用 `FilledIconButton`，与终端工具栏已有的图标按钮一致。图标的 `contentDescription` 保留，无障碍不退化。

**④ Assistant 菜单项挪到 Settings 之前。**
助手是对「当前这个终端」做的动作，Settings 是离开终端；把常用的两项排在一起，而不是被一个导航项隔开。

**顺带修掉的两个颜色缺陷：**
- 面板原本只看系统深浅色（`isSystemInDarkTheme`），但 Termux 的主题是**独立设置**的。结果：App 锁深色 + 系统浅色 → 亮白面板盖在黑色终端上。新增 `AssistantTheme.isDark` 按 `NightMode` 判定，`TermuxTheme(darkTheme = ...)` 传入。
- Markwon 的默认配色是按白底页面设计的：行内代码渲染成「深字 + 浅灰块」，深色面板里像一道高亮涂痕。改为通过 `AbstractMarkwonPlugin.configureTheme` 按当前配色重建 `Markwon` 实例（仅主题切换时重建，不是每帧）。

**⑤ 新增：把面板渲染成 PNG 的测试。**
`AiPanelRenderTest` 用 Robolectric `GraphicsMode.NATIVE` 真实绘制面板，导出 `app/build/panel-render/*.png`，并断言画面里不同颜色数 > 8（面板「组成了但没画出来」是这次踩过的坑，纯逻辑测试抓不到）。CI 把这些图作为 artifact 上传，改完样式可以直接看，不必等真机。上面②的对比度问题，就是这个测试第一次跑出来时从图里看见的。

---

*本文 `文件:行号` 引用均经实读核对。*
