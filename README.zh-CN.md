[English](README.md) | **简体中文** | [日本語](README.ja.md) | [한국어](README.ko.md)

# Tandem

一款为 Claude Code 打造的快速、原生的 JetBrains 插件。免费开源。

![Tandem：编辑以原生 diff 打开，两个会话并排，终端中启动的会话出现在侧边栏](docs/media/tandem.gif)

> 状态：早期阶段。自 0.4.1 起已上架 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35641-tandem)。
> 目前支持 macOS 和 Linux；Windows 支持正在开发中。
> 编辑经由 IDE 完成：Tandem 会禁用 CLI 自带的 Edit 和 Write 工具，改用自己提供的编辑工具。可编辑的 diff 已在回放真实录制内容的模拟 CLI 上测试过，也在 macOS 上手动测试过；难免还有粗糙之处。

## 为什么用 Tandem
- **原生界面。** 用 IDE 自己的 UI 工具包 Jewel（Compose）构建，不是内嵌浏览器。
- **不需要 Node，不捆绑 SDK。** Tandem 直接驱动你已经装好的 `claude` CLI，所以新模型、新功能在 CLI 发布的当天就能用上。
- **编辑经由 IDE。** Claude 提出的每一处编辑，在写入之前都会先在 IDE 自己的 diff 中打开，你可以直接在里面手动修改提议的内容。Accept 会通过编辑器应用这处编辑，并把文件现在的内容（包括你的改动）告诉 Claude；Reject 会把你的理由发给 Claude。选用 Accept edits 模式，或在 diff 中点 Accept all（对本轮余下的编辑都有效），就会跳过 diff。
- **如实显示状态。** 如果你选的模型不可用、CLI 回退到了另一个模型，Tandem 会明确告诉你。
- **登录信息留在 CLI 那里。** Tandem 从不读取凭据或 API 密钥。

## 你能得到什么
- **一个真正属于 IDE 的聊天工具窗口。** 会话列在侧边栏，每个运行中的会话各占一个标签页；可以重命名、分叉和删除；在终端里启动的会话也会出现在侧边栏，并用 CLI 自带的 `--resume` 恢复。
- **可编辑的 diff。** 按 Ctrl+S（⌘S）接受；写入走的是编辑器，从不直接落盘，所以撤销、本地历史和未保存的缓冲区都照常可用。
- **模型、推理强度和权限模式的选择器，显示的都是真实状态。** Tandem 会核对 CLI 报告的模型，与你选的不一致或发生回退时，会在聊天中说明；Shift+Tab 可循环切换权限模式。
- **权限卡片、提问、MCP 服务器、斜杠命令、`! command`。** 在聊天中批准工具调用，回答 Claude 的提问，在面板里启用、停用 MCP 服务器并登录，运行 shell 命令并把输出交给 Claude。
- **看得见的上下文。** 用 `@` 选择项目文件（包括未保存的更改），编辑器中的选区会以标签的形式附在消息上，点击上下文环可查看上下文窗口的占用明细，`/usage` 显示本会话的费用和你所用套餐的用量限制。
- **Claude 工作时也能直接发送，或者排队到本轮结束后再发**（Ctrl+Enter，⌘↵）；排队中的消息可以修改或取消。

## 工作原理
Tandem 以 `--output-format stream-json --input-format stream-json` 参数启动你安装的 `claude` 可执行文件，并用 Jewel 渲染它流式输出的内容。它的编辑工具是一个 SDK MCP 服务器，CLI 通过同一个控制通道访问它。除此之外什么都不运行：没有 Node，没有 sidecar，没有浏览器。[docs/protocol.md](docs/protocol.md) 如实描述了 CLI 实际使用的协议。

## 体积
0.4.1 版的 ZIP 为 4.0 MB：四个 jar，没有 Node，没有 webview，没有原生库。测量 Marketplace 上插件 ZIP 的脚本，以及其他几款适用于 Claude Code 的插件的数据，见 [docs/benchmarks.md](docs/benchmarks.md)。在 IDE 测试中，从侧边栏打开一个 1000 轮的对话耗时 1.4 秒，界面没有卡顿（数据同样在该文档中）。首个 token 的响应时间和内存占用尚未测量。

## 系统要求
- JetBrains IDE，2026.1 或更高版本。
- 已安装并登录 [Claude Code](https://code.claude.com)（在终端里能运行 `claude --version`）。

### 使用中转或第三方接口
Tandem 启动你安装的 `claude` 时，用的就是 CLI 在终端里的那套环境，所以 CLI 现有的配置原样生效：登录信息如此，在 `~/.claude/settings.json` 的 `env` 里设置或在 shell 里导出的 `ANTHROPIC_BASE_URL` 和 `ANTHROPIC_AUTH_TOKEN` 也是如此。Tandem 自己没有任何服务商设置，也从不读取这些值；通过改写 `settings.json` 来切换 CLI 服务商的工具，要到下一次启动会话时才生效。

如果原生安装程序下载不下来，可以改用 npm 安装 CLI：
```bash
npm install -g @anthropic-ai/claude-code
# npm 官方源较慢时：
npm install -g @anthropic-ai/claude-code --registry=https://registry.npmmirror.com
```

Tandem 的选择器和底栏里显示的模型，就是 CLI 报告的模型；如果中转服务做了模型名映射，显示的也是 CLI 报告的那个名字。

## 安装
在 Settings | Plugins | Marketplace 中搜索 **Tandem**，或打开 [plugins.jetbrains.com/plugin/35641-tandem](https://plugins.jetbrains.com/plugin/35641-tandem)。然后打开 Tandem 工具窗口，输入提示词即可。

## 从源码构建
```bash
./gradlew runIde        # 带插件的沙盒 IDE
./gradlew test          # 单元测试和协议 fixture 测试
./gradlew buildPlugin   # ZIP 生成在 build/distributions/
```
通过 Settings | Plugins | ⚙ | Install Plugin from Disk 安装该 ZIP。

## 用 Claude Code 开发 Tandem
先运行一次 `./scripts/setup-agent.sh`，它会安装本仓库所需的插件、语言服务器和文档服务器。然后阅读 `CLAUDE.md`、`docs/design.md` 和 `docs/protocol.md`。

## 许可证
Apache 2.0。Tandem 是独立项目，与 Anthropic 和 JetBrains 均无关联，也未获得其认可。
Claude 和 Claude Code 是 Anthropic, PBC 的商标。
