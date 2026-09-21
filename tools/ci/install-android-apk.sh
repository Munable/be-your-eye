#!/usr/bin/env bash

# Install one APK and acknowledge ColorOS's own package-installer confirmation
# when it appears. Emulators and devices without that confirmation simply wait
# for adb to finish.
install_android_apk() {
    local serial="$1"
    local apk="$2"
    local log_file="$3"
    local width
    local height
    local size
    local pid
    local status
    local focus
    local hierarchy
    local tick

    size="$(adb -s "$serial" shell wm size 2>/dev/null | awk -F: '/Physical size|Override size/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
    width="${size%x*}"
    height="${size#*x}"
    [[ "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ ]] || {
        echo "could not determine display size for $serial" >&2
        return 7
    }

    adb -s "$serial" install --no-streaming -t "$apk" >"$log_file" 2>&1 &
    pid=$!
    for tick in $(seq 1 180); do
        if ! kill -0 "$pid" >/dev/null 2>&1; then
            if wait "$pid"; then
                status=0
            else
                status=$?
            fi
            if ((status != 0)); then
                cat "$log_file" >&2
                return "$status"
            fi
            return 0
        fi

        focus="$(adb -s "$serial" shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
        if [[ "$focus" == *com.android.packageinstaller* ]]; then
            hierarchy="$(adb -s "$serial" shell uiautomator dump /sdcard/package-installer-ui.xml >/dev/null 2>&1; adb -s "$serial" shell cat /sdcard/package-installer-ui.xml 2>/dev/null || true)"
            if [[ "$hierarchy" == *'安装成功'* || "$hierarchy" == *'安装完成'* ]]; then
                if ((tick % 3 == 0)); then
                    adb -s "$serial" shell input tap "$((width * 73 / 100))" "$((height * 93 / 100))" >/dev/null 2>&1 || true
                fi
            elif ((tick >= 3 && tick % 3 == 0)); then
                adb -s "$serial" shell input tap "$((width / 2))" "$((height * 93 / 100))" >/dev/null 2>&1 || true
            fi
        fi
        sleep 1
    done

    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
    echo "timed out installing $apk on $serial; see $log_file" >&2
    return 124
}
