cd /opt/blog/pai_blog

cat > /opt/blog/pai_blog/scripts/auto_pull.sh <<'EOF'
#!/usr/bin/env bash
set -u

REPO_DIR="/opt/blog/pai_blog"
REMOTE="origin"
BRANCH="main"
LOG_FILE="$REPO_DIR/.git/auto_pull.log"
LOCK_FILE="/tmp/auto_git_pull_pai_blog.lock"

while true; do
  (
    flock -n 9 || exit 0

    echo "==== $(date '+%F %T') start ====" >> "$LOG_FILE"

    cd "$REPO_DIR" || {
      echo "[ERROR] $(date '+%F %T') cd failed: $REPO_DIR" >> "$LOG_FILE"
      exit 1
    }

    git pull --ff-only "$REMOTE" "$BRANCH" >> "$LOG_FILE" 2>&1 || \
      echo "[ERROR] $(date '+%F %T') git pull failed" >> "$LOG_FILE"

    echo "==== $(date '+%F %T') end ====" >> "$LOG_FILE"
  ) 9>"$LOCK_FILE"

  sleep 10
done
EOF

sed -i 's/\r$//' /opt/blog/pai_blog/scripts/auto_pull.sh
chmod +x /opt/blog/pai_blog/scripts/auto_pull.sh
git add scripts/auto_pull.sh
git commit -m "add auto pull script"
nohup /opt/blog/pai_blog/scripts/auto_pull.sh >/dev/null 2>&1 &
ps -ef | grep auto_pull.sh | grep -v grep
tail -f /opt/blog/pai_blog/.git/auto_pull.log