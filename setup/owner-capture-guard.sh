#!/system/bin/sh
# Fixed-purpose detached safety guard. Positional arguments: action, random token.
# No journal file is ever sourced; no identity is written to disk.
umask 077
d=/data/local/tmp/volt-owner-capture.lock
token=$2
case "$token" in *[!0-9a-f]*|'') exit 1 ;; esac
[ "${#token}" = 64 ] || exit 1
ours() {
  [ ! -L "$d" ] && [ -d "$d" ] && [ "$(cat "$d/token")" = "$token" ] && [ "$(cat "$d/owner")" = shizuku ]
}
child=
gate_held=0
guard_mode=0
guard_outcome=stopped
release_gate() {
  [ "$gate_held" = 1 ] || return 0
  rmdir "$d/cleaning" 2>/dev/null
  gate_held=0
}
# Keep the gate until an in-flight bounded command is finished. A repeated signal
# cannot interrupt this wait and expose a late write after another helper restores.
interrupted() {
  trap '' HUP INT TERM
  if [ -n "$child" ]; then wait "$child" 2>/dev/null; fi
  exit 1
}
finish() {
  release_gate
  # Exactly one terminal acknowledgment, as the final side effect of this guard.
  if [ "$guard_mode" = 1 ] && ours; then printf %s "$guard_outcome" > "$d/guard_done"; fi
}
trap finish EXIT
trap interrupted HUP INT TERM
run() {
  timeout "$@" &
  child=$!
  wait "$child"
  result=$?
  child=
  return "$result"
}
cleanup() {
  ours || return 1
  mkdir "$d/cleaning" 2>/dev/null || return 2
  gate_held=1
  ok=1
  [ -f "$d/original" ] && [ ! -L "$d/original" ] || ok=0
  [ "$(wc -c < "$d/original")" -le 91 ] || ok=0
  if [ "$ok" = 1 ]; then
    old=$(cat "$d/original") || ok=0
    run 6 am force-stop --user 0 com.navee.ucaret >/dev/null 2>&1 || ok=0
    run 3 setprop log.tag.com.navee.ucaret "$old" >/dev/null 2>&1 || ok=0
    current=$(timeout 3 getprop log.tag.com.navee.ucaret) || ok=0
    [ "$current" = "$old" ] || ok=0
  fi
  if [ "$ok" = 1 ]; then printf done > "$d/status"; fi
  release_gate
  [ "$ok" = 1 ]
}
case "$1" in
  watch)
    ours || exit 1
    guard_mode=1
    printf ready > "$d/ready"
    i=0
    while [ "$i" -lt 150 ]; do
      ours || exit 1
      [ "$(cat "$d/status" 2>/dev/null)" = done ] && break
      sleep 1
      i=$((i+1))
    done
    if [ "$(cat "$d/status" 2>/dev/null)" != done ]; then
      printf stop > "$d/stopping"
      i=0
      while [ "$i" -lt 120 ]; do
        [ "$(cat "$d/status" 2>/dev/null)" = done ] && break
        cleanup && break
        [ "$?" = 2 ] || exit 1
        sleep 0.25
        i=$((i+1))
      done
      [ "$(cat "$d/status" 2>/dev/null)" = done ] || exit 1
    fi
    guard_outcome=done
    ;;
  start)
    ours || exit 1
    mkdir "$d/cleaning" 2>/dev/null || exit 1
    gate_held=1
    ours && [ "$(cat "$d/ready")" = ready ] && [ ! -e "$d/status" ] && [ ! -e "$d/stopping" ] || exit 1
    run 3 setprop log.tag.com.navee.ucaret VERBOSE || exit 1
    [ "$(timeout 3 getprop log.tag.com.navee.ucaret)" = VERBOSE ] || exit 1
    run 6 am force-stop --user 0 com.navee.ucaret >/dev/null 2>&1 || exit 1
    case "$3" in com.navee.ucaret/*) ;; *) exit 1 ;; esac
    run 6 am start --user 0 -n "$3" >/dev/null 2>&1 || exit 1
    ;;
  clean)
    ours || exit 1
    printf stop > "$d/stopping"
    i=0
    while [ "$i" -lt 120 ]; do
      cleanup && exit 0
      [ "$?" = 2 ] || exit 1
      sleep 0.25
      i=$((i+1))
    done
    exit 1
    ;;
  *) exit 1 ;;
esac
