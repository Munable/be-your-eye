#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
readonly DEFAULT_ROOT="${BEYOUREYES_VISION_EVAL_ROOT:-/Volumes/DevDisk/DeveloperData/be-your-eyes/external-eval-cache/external-replay-set-v1}"
readonly ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
readonly EMULATOR_BIN="${ANDROID_EMULATOR_BIN:-$ANDROID_HOME/emulator/emulator}"
readonly AVD_NAME="${BEYOUREYES_FUNCTIONAL_AVD:-be-your-eyes-api36-8gb}"
readonly PORT="${BEYOUREYES_FUNCTIONAL_PORT:-5554}"
readonly SERIAL="emulator-$PORT"

usage() {
    cat >&2 <<'EOF'
usage:
  run-camera-replay.sh emulator --reel REEL.mp4 [--window] [--keep-emulator] [--dry-run] [-- COMMAND...]
  run-camera-replay.sh pja110  --reel REEL.mp4 [--repeat COUNT] [--display INDEX] [--dry-run]

The command after -- runs with ANDROID_SERIAL set to the replay emulator.
Media and logs default below the External Replay Set v1 folder on DevDisk.
EOF
}

MODE="${1:-}"
if [[ "$MODE" != "emulator" && "$MODE" != "pja110" ]]; then
    usage
    exit 2
fi
shift

REEL=""
WINDOW=false
KEEP_EMULATOR=false
DRY_RUN=false
REPEAT=1
DISPLAY_INDEX="${BEYOUREYES_REPLAY_DISPLAY_INDEX:-0}"
COMMAND=()
while (($#)); do
    case "$1" in
        --reel)
            REEL="${2:-}"
            shift 2
            ;;
        --window)
            WINDOW=true
            shift
            ;;
        --keep-emulator)
            KEEP_EMULATOR=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --repeat)
            REPEAT="${2:-}"
            shift 2
            ;;
        --display)
            DISPLAY_INDEX="${2:-}"
            shift 2
            ;;
        --)
            shift
            COMMAND=("$@")
            break
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

if [[ -z "$REEL" ]]; then
    usage
    exit 2
fi
if [[ ! "$REPEAT" =~ ^[1-9][0-9]*$ ]] || [[ ! "$DISPLAY_INDEX" =~ ^[0-9]+$ ]]; then
    echo "repeat must be positive and display must be non-negative" >&2
    exit 2
fi
REEL="$(cd -- "$(dirname -- "$REEL")" && pwd)/$(basename -- "$REEL")"
python3 "$SCRIPT_DIR/reel.py" verify --reel "$REEL" >/dev/null

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$MODE-$(basename "${REEL%.mp4}")"
RUN_DIR="$DEFAULT_ROOT/runs/$RUN_ID"

print_command() {
    printf '%q ' "$@"
    printf '\n'
}

if [[ "$MODE" == "pja110" ]]; then
    command -v ffplay >/dev/null
    FFPLAY=(ffplay -hide_banner -loglevel warning -autoexit -an -fs -window_title "Be Your Eye External Replay" "$REEL")
    if [[ "$DRY_RUN" == true ]]; then
        printf 'SDL_VIDEO_FULLSCREEN_DISPLAY=%q ' "$DISPLAY_INDEX"
        print_command "${FFPLAY[@]}"
        exit 0
    fi
    mkdir -p "$RUN_DIR"
    for _ in $(seq 1 "$REPEAT"); do
        SDL_VIDEO_FULLSCREEN_DISPLAY="$DISPLAY_INDEX" \
            SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS=0 \
            "${FFPLAY[@]}" 2>>"$RUN_DIR/ffplay.log"
    done
    echo "PASS: PJA110 screen replay completed; log=$RUN_DIR/ffplay.log"
    exit 0
fi

test -x "$EMULATOR_BIN"
command -v adb >/dev/null
if adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "$SERIAL is already running; stop it before this isolated replay" >&2
    exit 3
fi

EMULATOR_COMMAND=(
    "$EMULATOR_BIN" -avd "$AVD_NAME" -no-audio -no-snapshot -no-boot-anim
    -gpu off -port "$PORT" -camera-back "videofile:$REEL"
)
if [[ "$WINDOW" != true ]]; then
    EMULATOR_COMMAND+=(-no-window)
fi
if [[ "$DRY_RUN" == true ]]; then
    print_command "${EMULATOR_COMMAND[@]}"
    if ((${#COMMAND[@]})); then
        printf 'ANDROID_SERIAL=%q ' "$SERIAL"
        print_command "${COMMAND[@]}"
    fi
    exit 0
fi

mkdir -p "$RUN_DIR"
"${EMULATOR_COMMAND[@]}" >"$RUN_DIR/emulator.log" 2>&1 &
EMULATOR_PID=$!

cleanup() {
    set +e
    if [[ "$KEEP_EMULATOR" != true ]]; then
        adb -s "$SERIAL" emu kill >/dev/null 2>&1
        wait "$EMULATOR_PID" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 180); do
    if adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -qx 1; then
        break
    fi
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        echo "emulator stopped during boot; see $RUN_DIR/emulator.log" >&2
        exit 4
    fi
    sleep 1
done
if [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    echo "emulator did not finish booting; see $RUN_DIR/emulator.log" >&2
    exit 4
fi

if ((${#COMMAND[@]})); then
    ANDROID_SERIAL="$SERIAL" "${COMMAND[@]}" 2>&1 | tee "$RUN_DIR/command.log"
else
    echo "READY: ANDROID_SERIAL=$SERIAL camera-back=videofile:$REEL"
    echo "Press Ctrl-C to stop; emulator log=$RUN_DIR/emulator.log"
    wait "$EMULATOR_PID"
fi
