#!/system/bin/sh
# CPU profile and I/O scheduler (Titan2Parts -> persist.sys.titan2.cpu_profile / io_sched).
# Run by init (titan2-parts.rc) in the phhsu_daemon domain: the app has no access to cpufreq/block.
# The MTK PowerHAL may raise the minimum temporarily (touch boost): that is fine, the ceiling holds.
#   titan2-perf.sh cpu balance|powersave|performance
#   titan2-perf.sh io  <scheduler>
case "$1" in
cpu)
    for p in /sys/devices/system/cpu/cpufreq/policy*; do
        hwmin=$(cat $p/cpuinfo_min_freq) hwmax=$(cat $p/cpuinfo_max_freq)
        case "$2" in
        powersave)   min=$hwmin; max=$(( hwmax * 6 / 10 )) ;;
        performance) min=$(( hwmax / 2 )); max=$hwmax ;;
        *)           min=$hwmin; max=$hwmax ;;
        esac
        # order matters: the kernel does not allow min > max
        echo $hwmin > $p/scaling_min_freq
        echo $max > $p/scaling_max_freq
        echo $min > $p/scaling_min_freq
    done
    log -t titan2-perf "cpu profile $2"
    ;;
io)
    for q in /sys/block/sd*/queue/scheduler; do
        grep -qw "$2" $q && echo "$2" > $q
    done
    log -t titan2-perf "io scheduler $2"
    ;;
esac
