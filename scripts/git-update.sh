#!/usr/bin/env bash
set -euo pipefail

message="${1:-update}"
branch="${2:-main}"
remote="${3:-origin}"

git add .
git commit -m "$message"
git push "$remote" "$branch"
