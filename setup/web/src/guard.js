import { PACKAGE, PROPERTY, quote, tokenValid, validLauncher } from './protocol.js';

// This path and basic journal fields are shared with the optional Shizuku helper.
// Journal data is never sourced or evaluated as shell code. No owner identity is
// written here. A session may clean up only the lock carrying its random token.
export const LOCK = '/data/local/tmp/volt-owner-capture.lock';
const d = quote(LOCK);
function checkToken(token) {
  if (!tokenValid(token)) throw new Error('Invalid session token');
  return `[ ! -L ${d} ] && [ -d ${d} ] && [ "$(cat ${d}/token)" = ${quote(token)} ] && [ "$(cat ${d}/owner)" = webadb ]`;
}
export function guardScript(token) {
  return `#!/system/bin/sh
umask 077
d=${d}
ours() { ${checkToken(token)}; }
cleanup() {
  ours || return 1
  mkdir "$d/cleaning" 2>/dev/null || return 2
  ok=1
  # The original is a bounded property value, never shell source.
  [ -f "$d/original" ] && [ ! -L "$d/original" ] || ok=0
  [ "$(wc -c < "$d/original")" -le 91 ] || ok=0
  if [ "$ok" = 1 ]; then
    old=$(cat "$d/original") || ok=0
    timeout 6 am force-stop --user 0 ${PACKAGE} >/dev/null 2>&1 || ok=0
    timeout 3 setprop ${PROPERTY} "$old" >/dev/null 2>&1 || ok=0
    current=$(timeout 3 getprop ${PROPERTY}) || ok=0
    [ "$current" = "$old" ] || ok=0
  fi
  if [ "$ok" = 1 ]; then printf done > "$d/status"; fi
  rmdir "$d/cleaning" 2>/dev/null
  [ "$ok" = 1 ]
}
case "$1" in
  watch)
    ours || exit 1
    # Record termination even when restoration fails, so an explicit later
    # recovery can restore successfully and release an already-ended timer.
    guard_outcome=stopped
    # This is the timer's sole terminal write. An earlier acknowledgment plus
    # another EXIT write could race a browser releasing/reusing the lock.
    trap 'if ours; then printf %s "$guard_outcome" > "$d/guard_done"; fi' EXIT
    printf ready > "$d/ready"
    i=0
    while [ "$i" -lt 120 ]; do
      ours || exit 1
      [ "$(cat "$d/status" 2>/dev/null)" = done ] && break
      sleep 1
      i=$((i+1))
    done
    if [ "$(cat "$d/status" 2>/dev/null)" != done ]; then
      printf stop > "$d/stopping"
      # start and cleanup share the same gate. A start already in flight must
      # finish (or hit its command bounds) before final restoration happens.
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
`;
}
export function prepareCommand(token) {
  return `umask 077
[ "$(id -u)" = 2000 ] || exit 20
[ "$(am get-current-user)" = 0 ] || exit 26
command -v nohup >/dev/null || exit 21
command -v timeout >/dev/null || exit 21
pm path --user 0 ${PACKAGE} | head -c 4096 | grep -q '^package:' || exit 22
mkdir ${d} 2>/dev/null || exit 23
printf %s ${quote(token)} > ${d}/token || exit 24
printf webadb > ${d}/owner || exit 24
# getprop adds exactly one newline. Reject embedded control characters instead
# of normalizing them and accidentally changing an unusual existing value.
getprop ${PROPERTY} > ${d}/original.raw || exit 24
[ "$(wc -l < ${d}/original.raw)" -eq 1 ] || exit 24
IFS= read -r old < ${d}/original.raw
printf %s "$old" > ${d}/original || exit 24
rm ${d}/original.raw || exit 24
[ -z "$old" ] || ! printf %s "$old" | LC_ALL=C grep -q '[^ -~]' || exit 24
[ "$(wc -c < ${d}/original)" -le 91 ] || exit 24
date +%s.%N > ${d}/started || exit 24
cat ${d}/started
base64 ${d}/original | tr -d '\\r\\n'
printf '\\n'
`;
}
export function armCommand(token) {
  return `${checkToken(token)} || exit 30
cat > ${d}/guard.sh || exit 31
chmod 700 ${d}/guard.sh || exit 31
nohup sh ${d}/guard.sh watch </dev/null >/dev/null 2>&1 &
i=0
while [ "$i" -lt 30 ]; do
  [ "$(cat ${d}/ready 2>/dev/null)" = ready ] && { printf READY; exit 0; }
  sleep 0.1
  i=$((i+1))
done
exit 32`;
}
export function startCommand(token, launcher) {
  if (!validLauncher(launcher)) throw new Error('Invalid NAVEE launcher');
  return `${checkToken(token)} || exit 40
mkdir ${d}/cleaning 2>/dev/null || exit 40
child=
trap 'rmdir ${d}/cleaning 2>/dev/null' EXIT
trap 'if [ -n "$child" ]; then wait "$child" 2>/dev/null; fi; exit 1' HUP INT TERM
run() {
  timeout "$@" &
  child=$!
  wait "$child"
  result=$?
  child=
  return "$result"
}
${checkToken(token)} && [ "$(cat ${d}/ready)" = ready ] && [ ! -e ${d}/status ] && [ ! -e ${d}/stopping ] || exit 40
run 3 setprop ${PROPERTY} VERBOSE || exit 41
[ "$(timeout 3 getprop ${PROPERTY})" = VERBOSE ] || exit 41
run 6 am force-stop --user 0 ${PACKAGE} >/dev/null 2>&1 || exit 42
run 6 am start --user 0 -n ${quote(launcher)} >/dev/null 2>&1 || exit 43
printf STARTED`;
}
export function cleanupCommand(token) {
  // Inline the trusted cleanup implementation: recovery never executes a script
  // read from the phone. The detached watchdog executes only the script we wrote.
  return `${guardScript(token)}\n`;
}
// A preparing journal proves that this page has not requested logging or armed
// a watchdog. A different, readable token therefore cannot need our recovery.
// Missing/partial tokens remain ambiguous and must never be guessed or removed.
export function preparationStateCommand(token) {
  if (!tokenValid(token)) throw new Error('Invalid session token');
  return `if [ ! -e ${d} ] && [ ! -L ${d} ]; then printf ABSENT
elif [ ! -L ${d} ] && [ -d ${d} ] && [ ! -L ${d}/token ] && [ -f ${d}/token ] && [ "$(wc -c < ${d}/token)" -eq 64 ]; then
  value=$(cat ${d}/token) || exit 50
  [ "\${#value}" -eq 64 ] || { printf AMBIGUOUS; exit 0; }
  case "$value" in *[!0-9a-f]*) printf AMBIGUOUS ;;
    ${token}) printf OURS ;;
    *) printf FOREIGN ;;
  esac
else printf AMBIGUOUS
fi`;
}
export function inspectCommand(token) {
  return `${checkToken(token)} || exit 50
[ ! -L ${d}/original ] && [ "$(wc -c < ${d}/original)" -le 91 ] || exit 51
base64 ${d}/original | tr -d '\\r\\n'
printf '\\n'
[ "$(cat ${d}/status 2>/dev/null)" = done ] && printf DONE || printf PENDING`;
}
export function releaseCommand(token) {
  return `${checkToken(token)} && [ "$(cat ${d}/status)" = done ] || exit 60
# If arming never completed there may be no watchdog; otherwise wait for its
# acknowledgement before removing files, preventing a late timeout restoring a
# later capture's property. An uncertain guard start stays recoverable.
if [ -e ${d}/guard.sh ]; then
  i=0
  while [ ! -f ${d}/guard_done ] && [ "$i" -lt 40 ]; do sleep 0.1; i=$((i+1)); done
  case "$(cat ${d}/guard_done 2>/dev/null)" in done|stopped) ;; *) exit 61 ;; esac
fi
${checkToken(token)} || exit 62
# Refuse unexpected entries instead of deleting an unknown file or directory.
for file in ${d}/* ${d}/.[!.]* ${d}/..?*; do
  [ -e "$file" ] || [ -L "$file" ] || continue
  case "$file" in */guard.sh|*/guard_done|*/ready|*/status|*/stopping|*/original|*/original.raw|*/started|*/owner|*/token) ;; *) exit 63 ;; esac
  [ -f "$file" ] && [ ! -L "$file" ] || exit 63
done
# Retire the completed reservation atomically BEFORE deleting its recovery keys.
# An interrupted delete can leave only a harmless retired directory, never a
# global lock without its token/original. A lost reply is recovered by reacquire.
retired=$(mktemp -d /data/local/tmp/volt-owner-retired-XXXXXXXXXX) || exit 64
mv ${d} "$retired/session" || { rmdir "$retired"; exit 64; }
for file in guard.sh guard_done ready status stopping original original.raw started owner token; do
  rm -f "$retired/session/$file" || exit 65
done
rmdir "$retired/session" && rmdir "$retired" || exit 65
printf RELEASED`;
}

export function reacquireRecoveryCommand(token, original) {
  if (!tokenValid(token) || !/^[\x20-\x7e]{0,91}$/.test(original)) throw new Error('Invalid recovery data');
  // A release acknowledgement may be lost after its lock was removed. Absence
  // is never treated as successful cleanup: reacquire exclusively, then repeat
  // force-stop + exact restoration from the browser's pre-mutation journal.
  return `umask 077
mkdir ${d} 2>/dev/null || exit 80
printf %s ${quote(token)} > ${d}/token || exit 81
printf webadb > ${d}/owner || exit 81
printf %s ${quote(original)} > ${d}/original || exit 81
printf RECOVERABLE`;
}
