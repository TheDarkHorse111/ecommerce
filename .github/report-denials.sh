#!/usr/bin/env bash
set -uo pipefail

f=/home/runner/work/_temp/claude-execution-output.json
[ -f "$f" ] || { echo "no execution output"; exit 0; }

echo "tools called:"
jq -r '[.. | objects | select(.type? == "tool_use") | .name] | group_by(.)
       | map("\(length)\t\(.[0])") | .[]' "$f"

echo
echo "denials:"
jq -r '[.. | objects | select(has("permission_denials")) | .permission_denials[]?]
       | unique | .[]
       | "\(.tool_name // .name // "unknown")\t\((.tool_input // .input // {})
         | to_entries | map("\(.key)=\(.value | tostring | .[0:200])") | join(" "))"' "$f"
