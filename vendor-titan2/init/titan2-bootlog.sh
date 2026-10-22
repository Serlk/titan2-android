#!/system/bin/sh
# Boot log (Titan2Parts -> "Boot log", persist.sys.titan2.bootlog): the kernel log and
# logcat of every boot are written to /metadata: it is mounted first and survives a crash and
# `-w` in lk. After a successful boot a copy goes to /data/local/tmp/titan2log (adb pull).
# persist properties are not loaded yet at `on init`, so enabling is a flag file in /metadata.
#   titan2-bootlog.sh            : run at boot (exits if not enabled)
#   titan2-bootlog.sh on|off     : toggle from the settings
D=/metadata/titan2log
case "$1" in
on)  mkdir -p $D; touch $D/enabled; exit 0 ;;
off) rm -rf $D /data/local/tmp/titan2log; exit 0 ;;
esac
[ -e $D/enabled ] || exit 0
n=$(cat $D/count 2>/dev/null); n=$(( ${n:-0} + 1 )); echo $n > $D/count
rm -f $D/boot$(( n - 4 )).*
cat /dev/kmsg > $D/boot$n.kmsg &
( until [ -e /dev/socket/logdr ]; do sleep 0.2; done; logcat -b all -v time > $D/boot$n.logcat ) &
( while true; do sync; sleep 1; done ) &
# /data/local/tmp belongs to shell: adb could plant a symlink there beforehand, and the script
# runs as root. So the directory is created anew and all work happens inside it (after cd a
# path swap no longer helps), without -R and without 777: only shell can read it (adb pull),
# apps cannot access the log.
( until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
  T=/data/local/tmp/titan2log
  # shell owns /data/local/tmp and could swap $T for a symlink to another root-owned directory
  # between mkdir and cd: only continue in the empty 0700 root directory we just made
  rm -rf $T; mkdir -m 0700 $T && cd $T && [ "$(stat -c %u:%a .)" = 0:700 ] && [ -z "$(ls -A)" ] || exit 0
  for f in $D/*; do [ -f "$f" ] && cp "$f" . && chown shell:shell "${f##*/}" && chmod 0640 "${f##*/}"; done
  chown shell:shell .; chmod 0750 . ) &
wait
