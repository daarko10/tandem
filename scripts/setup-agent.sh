#!/usr/bin/env bash
# One-time setup of the Claude Code agent tooling for working on Tandem.
# Run from the repo root on your own machine:  ./scripts/setup-agent.sh
# Safe to re-run. Nothing here touches your Claude credentials.
set -uo pipefail

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

have claude || { echo "Install Claude Code first: https://code.claude.com"; exit 1; }

say "1/5 Official plugins (Anthropic marketplace)"
for p in superpowers code-review security-guidance commit-commands pr-review-toolkit kotlin-lsp claude-code-setup; do
  claude plugin install "$p@claude-plugins-official" --scope project || echo "  ! could not install $p (check the name with /plugin)"
done

say "2/5 Kotlin language server for the kotlin-lsp plugin"
if have kotlin-lsp; then
  echo "  kotlin-lsp already installed"
elif have brew; then
  brew install JetBrains/utils/kotlin-lsp
else
  echo "  ! Install kotlin-lsp manually: https://github.com/Kotlin/kotlin-lsp (needs Java 17+)"
fi

say "3/5 ktlint for the format hook (optional)"
if have ktlint; then echo "  ktlint already installed"
elif have brew; then brew install ktlint
else echo "  ! Optional: install ktlint so the edit hook formats Kotlin: https://pinterest.github.io/ktlint/"
fi

say "4/5 Context7 (current library docs)"
if claude mcp list 2>/dev/null | grep -q context7; then
  echo "  context7 already configured"
else
  # Interactive: OAuth in the browser, then it adds the MCP server and CLAUDE.md rules.
  npx -y ctx7 setup --claude || claude mcp add --scope user --transport http context7 https://mcp.context7.com/mcp
fi

say "5/5 IntelliJ MCP server (manual, one click)"
cat <<'EOF'
  In IntelliJ IDEA 2025.2+:
    Settings | Tools | MCP Server -> Enable MCP Server
    -> "Project Clients Auto-Configuration" -> Auto-Configure next to Claude Code
  Then restart Claude Code. Claude can now run inspections, builds, run configs and refactorings.
EOF

say "Done. Start Claude Code in this folder and run /plugin to confirm everything is enabled."
