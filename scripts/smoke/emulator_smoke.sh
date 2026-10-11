#!/usr/bin/env bash
# Guest-mode emulator smoke screenshots for ir.vinor.app.
# Not a unit or instrumented test.
#
# The job exits non-zero only when the APK cannot be installed or the app
# crashes / ANRs. A missing optional row is recorded and does not fail the job.
#
# Safety: this script never types into a field and never taps OTP / SMS controls
# (login submit, resend, or any label containing ارسال).
set -euo pipefail

PKG=ir.vinor.app
OUT=${SMOKE_OUT:-$PWD/smoke-out}
DUMP=${SMOKE_DUMP:-/tmp/smoke-ui.xml}
APK=${SMOKE_APK:-}
W=1080
H=1920
LAST_SHOT=

usage() {
  printf '%s\n' "usage: emulator_smoke.sh {self-check|validate-inputs|select-apk|run}" >&2
  exit 2
}

write_output() {
  local key="$1"
  local value="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$key" "$value" >> "$GITHUB_OUTPUT"
  else
    printf '%s=%s\n' "$key" "$value"
  fi
}

one_line() {
  local s="$1"
  s=${s//$'\r'/ }
  s=${s//$'\n'/ }
  s=${s//$'\t'/ }
  s=${s//'|'/ }
  printf '%s' "${s:0:240}"
}

write_summary() {
  local result="pass"
  local reason=""
  local step res detail shot
  local -a shots=()
  if [[ -f /tmp/smoke-fatal ]]; then
    result="fail"
    reason=$(cat /tmp/smoke-fatal)
  fi
  mkdir -p "$OUT"
  {
    printf '%s\n' "# Emulator smoke"
    printf '\n'
    printf -- "- package: \`%s\`\n" "$PKG"
    printf -- "- apk run id: \`%s\`\n" "${SMOKE_RUN_ID:-}"
    printf -- "- api level: \`%s\`\n" "${SMOKE_API_LEVEL:-}"
    printf -- "- job result: \`%s\`\n" "$result"
    printf '%s\n' "- exit policy: fail the job only for install failure or an app crash/ANR. A missing optional row stays in this table and does not fail the job."
    if [[ -n "$reason" ]]; then
      printf -- "- fatal: \`%s\`\n" "$(one_line "$reason")"
    fi
    printf '\n'
    printf '%s\n' "| Step | Result | Detail |"
    printf '%s\n' "| --- | --- | --- |"
    if [[ -f "$OUT/steps.tsv" ]]; then
      while IFS=$'\t' read -r step res detail; do
        [[ -z "$step" ]] && continue
        printf '| %s | %s | %s |\n' "$step" "$res" "$detail"
      done < "$OUT/steps.tsv"
    fi
    printf '\n'
    printf '%s\n' "## Screenshots"
    printf '\n'
    mapfile -t shots < <(find "$OUT" -maxdepth 1 -type f -name '*.png' -printf '%f\n' | sort)
    if [[ ${#shots[@]} -eq 0 ]]; then
      printf '%s\n' "none yet"
    else
      for shot in "${shots[@]}"; do
        printf -- "- \`%s\`\n" "$shot"
      done
    fi
    printf '\n'
    printf '%s\n' "Each screenshot has a matching .xml uiautomator dump and a .activity.txt excerpt from dumpsys activity top. logcat.txt is filtered to ir.vinor.app plus fatal/ANR context."
    printf '\n'
    printf '%s\n' "## Crashes / ANR"
    printf '\n'
    if [[ -n "$reason" ]]; then
      printf '%s\n' "$reason"
    else
      printf '%s\n' "none"
    fi
  } > "$OUT/summary.md"
}

record() {
  local step="$1"
  local result="$2"
  local detail="$3"
  mkdir -p "$OUT"
  printf '%s\t%s\t%s\n' "$step" "$result" "$(one_line "$detail")" >> "$OUT/steps.tsv"
  write_summary || true
}

mark_fatal() {
  printf '%s\n' "$1" > /tmp/smoke-fatal
  write_summary || true
}

run_ui() {
  python3 - "$@" <<'PY'
import hashlib
import re
import sys
import xml.etree.ElementTree as ET

PKG = "ir.vinor.app"
BLOCK_IDS = {
    "loginStep1Submit",
    "loginStep1Phone",
    "loginStep2Submit",
    "loginStep2Resend",
    "loginStep2Code",
    "onboardNext",
    "tabAds",
    "tabSearch",
    "tabPost",
    "tabChat",
    "tabMe",
    "tabAdsIcon",
    "tabAdsLabel",
    "tabSearchIcon",
    "tabSearchLabel",
    "tabPostIcon",
    "tabPostLabel",
    "tabChatIcon",
    "tabChatLabel",
    "tabMeIcon",
    "tabMeLabel",
}
BLOCK_TEXTS = {
    "بعدی",
    "ارسال کد",
    "ارسال مجدد کد",
    "ورود به پنل",
    "شروع وینور",
}
GUEST_IDS = {"profileGuestLogin", "profileGuestApply"}
KNOWN_SLUGS = {
    "آموزش": "amoozesh",
    "ورود": "vorood",
    "بازاریاب وینور": "bazaryab",
    "ملک من": "melk-man",
    "وینور پلاس": "plus",
    "دریافت وینور پلاس": "plus",
    "وکیل ملکی": "vakil",
    "پیگیری": "peygiri",
    "مشتریان": "customers",
    "کیف پول": "wallet",
    "نشانها": "neshanha",
    "دعوت همکار": "invite",
    "دعوت دوستان": "invite-friends",
    "تنظیمات": "settings",
    "بروزرسانی اپلیکیشن": "update",
    "وضعیت بازاریاب وینور": "bazaryab-status",
    "خروج": "logout",
}
LETTERS = {
    "آ": "a", "ا": "a", "أ": "a", "إ": "a", "ء": "",
    "ب": "b", "پ": "p", "ت": "t", "ث": "s",
    "ج": "j", "چ": "ch", "ح": "h", "خ": "kh",
    "د": "d", "ذ": "z", "ر": "r", "ز": "z", "ژ": "zh",
    "س": "s", "ش": "sh", "ص": "s", "ض": "z",
    "ط": "t", "ظ": "z", "ع": "a", "غ": "gh",
    "ف": "f", "ق": "gh", "ک": "k", "ك": "k", "گ": "g",
    "ل": "l", "م": "m", "ن": "n", "و": "v", "ؤ": "v",
    "ه": "h", "ة": "h", "ی": "y", "ي": "y", "ئ": "y",
    "۰": "0", "۱": "1", "۲": "2", "۳": "3", "۴": "4",
    "۵": "5", "۶": "6", "۷": "7", "۸": "8", "۹": "9",
}
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def local_id(value):
    if not value:
        return ""
    return value.split("/")[-1]


def clean(value):
    return " ".join((value or "").replace("\t", " ").split())


def norm_label(value):
    return clean(value).replace("\u200c", "").replace("\u200d", "")


def bounds_of(node):
    match = BOUNDS_RE.match(node.attrib.get("bounds", ""))
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def center_of(box):
    x1, y1, x2, y2 = box
    return (x1 + x2) // 2, (y1 + y2) // 2


def parse_tree(path):
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"ui xml parse error: {exc}", file=sys.stderr)
        return None


def iter_nodes(root):
    return list(root.iter("node"))


def slugify(label):
    key = norm_label(label)
    if key in KNOWN_SLUGS:
        return KNOWN_SLUGS[key]
    pieces = []
    for ch in key:
        if ch in LETTERS:
            pieces.append(LETTERS[ch])
        elif ch.isascii() and ch.isalnum():
            pieces.append(ch.lower())
        else:
            pieces.append("-")
    slug = re.sub(r"-+", "-", "".join(pieces)).strip("-")
    if not slug:
        slug = "row-" + hashlib.sha1(key.encode("utf-8")).hexdigest()[:6]
    return slug[:40]


def title_nodes(node):
    found = []
    for child in node.iter("node"):
        if local_id(child.attrib.get("resource-id", "")) == "itemProfileLinkTitle":
            found.append(child)
    return found


def row_label(node):
    rid = local_id(node.attrib.get("resource-id", ""))
    if rid in GUEST_IDS:
        return clean(node.attrib.get("text") or node.attrib.get("content-desc") or rid)
    titles = title_nodes(node)
    if titles:
        return clean(titles[0].attrib.get("text") or "")
    return clean(node.attrib.get("text") or node.attrib.get("content-desc") or "")


def forbidden(rid, label):
    if rid in BLOCK_IDS:
        return True
    if label in BLOCK_TEXTS or norm_label(label) in BLOCK_TEXTS:
        return True
    if "ارسال" in label:
        return True
    return False


def fully_visible(box, screen_h, tab_top):
    x1, y1, x2, y2 = box
    if x2 - x1 < 20 or y2 - y1 < 20:
        return False
    if y2 - y1 > int(screen_h * 0.85):
        return False
    if y1 < 40 or y2 > tab_top - 2:
        return False
    return True


def is_profile_row(node, screen_h, tab_top):
    if node.attrib.get("clickable") != "true":
        return False
    if node.attrib.get("enabled", "true") == "false":
        return False
    package = node.attrib.get("package", "")
    if package and package != PKG:
        return False
    box = bounds_of(node)
    if box is None or not fully_visible(box, screen_h, tab_top):
        return False
    rid = local_id(node.attrib.get("resource-id", ""))
    label = row_label(node)
    if forbidden(rid, label):
        return False
    if node.attrib.get("class") == "android.widget.EditText":
        return False
    if rid in GUEST_IDS:
        return True
    titles = title_nodes(node)
    if len(titles) != 1:
        return False
    for child in node.iter("node"):
        if child is node:
            continue
        if child.attrib.get("clickable") == "true" and len(title_nodes(child)) == 1:
            return False
    return True


def profile_rows(root, screen_h, tab_top):
    rows = []
    for node in iter_nodes(root):
        if not is_profile_row(node, screen_h, tab_top):
            continue
        box = bounds_of(node)
        x, y = center_of(box)
        rid = local_id(node.attrib.get("resource-id", ""))
        label = row_label(node) or rid or "row"
        if rid in GUEST_IDS:
            key = f"id:{rid}"
        else:
            key = f"title:{label}"
        rows.append((y, x, key, label, slugify(label), rid))
    rows.sort()
    return rows


def matches(node, spec):
    kind, _, value = spec.partition(":")
    rid = local_id(node.attrib.get("resource-id", ""))
    text = node.attrib.get("text") or ""
    desc = node.attrib.get("content-desc") or ""
    if kind == "id":
        return rid == value
    if kind == "text":
        return text == value or desc == value
    return False


def best_match(root, spec):
    found = [node for node in iter_nodes(root) if matches(node, spec) and bounds_of(node)]
    if not found:
        return None

    def rank(node):
        box = bounds_of(node)
        area = (box[2] - box[0]) * (box[3] - box[1])
        clickable = 0 if node.attrib.get("clickable") == "true" else 1
        return (clickable, area)

    found.sort(key=rank)
    return found[0]


def node_hash(root):
    parts = []
    for node in iter_nodes(root):
        rid = local_id(node.attrib.get("resource-id", ""))
        text = node.attrib.get("text") or ""
        if rid or text:
            parts.append(rid + "=" + text)
    return hashlib.sha1("\n".join(parts).encode("utf-8")).hexdigest()


def guest_visible(root):
    for node in iter_nodes(root):
        if local_id(node.attrib.get("resource-id", "")) == "profileGuest":
            return True
        text = node.attrib.get("text") or ""
        if text == "کاربر وارد نشده است":
            return True
        if text.startswith("برای مشاهده و مدیریت پروفایل"):
            return True
    return False


def phone_empty(root):
    for node in iter_nodes(root):
        if local_id(node.attrib.get("resource-id", "")) == "loginStep1Phone":
            return clean(node.attrib.get("text") or "") == ""
    return False


def contains_text(root, needle):
    for node in iter_nodes(root):
        blob = (node.attrib.get("text") or "") + " " + (node.attrib.get("content-desc") or "")
        if needle in blob:
            return True
    return False


def tab_top(root, screen_h):
    for node in iter_nodes(root):
        if local_id(node.attrib.get("resource-id", "")) == "tabMe":
            box = bounds_of(node)
            if box:
                return box[1]
    return None


def run_self_check():
    xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node package="ir.vinor.app" class="android.widget.FrameLayout" clickable="false" bounds="[0,0][1080,2400]" resource-id="" text="">
    <node package="ir.vinor.app" class="android.widget.LinearLayout" clickable="true" resource-id="ir.vinor.app:id/tabMe" content-desc="وینور من" text="" bounds="[800,2200][1080,2340]">
      <node package="ir.vinor.app" class="android.widget.TextView" clickable="false" resource-id="ir.vinor.app:id/tabMeLabel" text="وینور من" bounds="[820,2280][1000,2320]"/>
    </node>
    <node package="ir.vinor.app" class="android.widget.TextView" clickable="true" resource-id="ir.vinor.app:id/profileGuestLogin" text="ورود" bounds="[100,200][400,300]"/>
    <node package="ir.vinor.app" class="android.widget.TextView" clickable="true" resource-id="ir.vinor.app:id/profileGuestApply" text="بازاریاب وینور" bounds="[420,200][800,300]"/>
    <node package="ir.vinor.app" class="android.widget.LinearLayout" clickable="true" resource-id="" text="" bounds="[0,400][1080,900]">
      <node package="ir.vinor.app" class="android.widget.LinearLayout" clickable="true" resource-id="" text="" bounds="[0,400][1080,600]">
        <node package="ir.vinor.app" class="android.widget.TextView" clickable="false" resource-id="ir.vinor.app:id/itemProfileLinkTitle" text="آموزش" bounds="[40,440][400,520]"/>
      </node>
      <node package="ir.vinor.app" class="android.widget.LinearLayout" clickable="true" resource-id="" text="" bounds="[0,600][1080,800]">
        <node package="ir.vinor.app" class="android.widget.TextView" clickable="false" resource-id="ir.vinor.app:id/itemProfileLinkTitle" text="ملک من" bounds="[40,640][400,720]"/>
        <node package="ir.vinor.app" class="android.widget.TextView" clickable="false" resource-id="ir.vinor.app:id/itemProfileLinkSubtitle" text="ثبت ملک و انتشار آگهی" bounds="[40,720][700,780]"/>
      </node>
    </node>
    <node package="ir.vinor.app" class="android.widget.LinearLayout" clickable="true" resource-id="" text="" bounds="[0,2260][1080,2360]">
      <node package="ir.vinor.app" class="android.widget.TextView" clickable="false" resource-id="ir.vinor.app:id/itemProfileLinkTitle" text="تنظیمات" bounds="[40,2280][400,2340]"/>
    </node>
    <node package="ir.vinor.app" class="android.widget.TextView" clickable="true" resource-id="ir.vinor.app:id/loginStep1Submit" text="بعدی" bounds="[40,1500][1000,1600]"/>
    <node package="ir.vinor.app" class="android.widget.EditText" clickable="true" resource-id="ir.vinor.app:id/loginStep1Phone" text="" bounds="[40,1200][1000,1300]"/>
  </node>
</hierarchy>
"""
    root = ET.fromstring(xml)
    rows = profile_rows(root, 2400, 2200)
    labels = [row[3] for row in rows]
    keys = [row[2] for row in rows]
    slugs = {row[3]: row[4] for row in rows}
    assert labels == ["ورود", "بازاریاب وینور", "آموزش", "ملک من"], labels
    assert "بعدی" not in labels
    assert "تنظیمات" not in labels
    assert "وینور من" not in labels
    assert slugs["آموزش"] == "amoozesh"
    assert "id:profileGuestLogin" in keys
    assert "id:profileGuestApply" in keys
    assert "title:بازاریاب وینور" not in keys
    assert "title:آموزش" in keys
    assert "title:ملک من" in keys
    assert not any(key.startswith("id:login") for key in keys)
    amoozesh = next(row for row in rows if row[3] == "آموزش")
    assert amoozesh[0] == 500 and amoozesh[1] == 540
    tab = best_match(root, "id:tabMe")
    assert tab is not None
    x, y = center_of(bounds_of(tab))
    assert 800 <= x <= 1080 and 2200 <= y <= 2340
    assert guest_visible(ET.fromstring(
        '<hierarchy><node resource-id="ir.vinor.app:id/profileGuest" text="" bounds="[0,0][10,10]"/></hierarchy>'
    ))
    phone_root = ET.fromstring(
        '<hierarchy><node resource-id="ir.vinor.app:id/loginStep1Phone" text="" bounds="[0,0][10,10]"/></hierarchy>'
    )
    assert phone_empty(phone_root)
    filled = ET.fromstring(
        '<hierarchy><node resource-id="ir.vinor.app:id/loginStep1Phone" text="0912" bounds="[0,0][10,10]"/></hierarchy>'
    )
    assert not phone_empty(filled)
    assert slugify("آموزش") == "amoozesh"
    assert slugify("Hello") == "hello"
    print("self-check ok")


def main(argv):
    if len(argv) < 2:
        print("missing ui command", file=sys.stderr)
        return 2
    cmd = argv[1]
    if cmd == "self-check":
        run_self_check()
        return 0
    if len(argv) < 3:
        print("missing xml path", file=sys.stderr)
        return 2
    root = parse_tree(argv[2])
    if root is None:
        return 1
    if cmd == "rows":
        screen_h = int(argv[3])
        top = int(argv[4])
        # Tuple is (center_y, center_x, key, label, slug, rid) for top-to-bottom order.
        for y, x, key, label, slug, rid in profile_rows(root, screen_h, top):
            print(f"{key}\t{x}\t{y}\t{label}\t{slug}\t{rid}")
        return 0
    if cmd == "center":
        node = best_match(root, argv[3])
        if node is None:
            return 1
        x, y = center_of(bounds_of(node))
        print(f"{x} {y}")
        return 0
    if cmd == "has":
        return 0 if best_match(root, argv[3]) is not None else 1
    if cmd == "contains":
        return 0 if contains_text(root, argv[3]) else 1
    if cmd == "guest":
        return 0 if guest_visible(root) else 1
    if cmd == "phone-empty":
        return 0 if phone_empty(root) else 1
    if cmd == "hash":
        print(node_hash(root))
        return 0
    if cmd == "tab-top":
        screen_h = int(argv[3])
        top = tab_top(root, screen_h)
        if top is None:
            return 1
        print(top)
        return 0
    print(f"unknown ui command: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
PY
}

self_check() {
  local tmp saved_out saved_fatal=""
  local fatal_existed=0
  run_ui self-check
  tmp=$(mktemp -d)
  saved_out=$OUT
  if [[ -f /tmp/smoke-fatal ]]; then
    fatal_existed=1
    saved_fatal=$(cat /tmp/smoke-fatal)
  fi
  rm -f /tmp/smoke-fatal
  OUT=$tmp
  printf '%s\n' $'home\tpass\tok' > "$OUT/steps.tsv"
  printf 'x' > "$OUT/01-home.png"
  write_summary
  grep -q '| home | pass | ok |' "$OUT/summary.md"
  grep -q '01-home.png' "$OUT/summary.md"
  OUT=$saved_out
  rm -rf "$tmp"
  if [[ "$fatal_existed" -eq 1 ]]; then
    printf '%s\n' "$saved_fatal" > /tmp/smoke-fatal
  fi
}

validate_inputs() {
  local name run_id api lower
  name=${ARTIFACT_NAME:-vinor-direct-apk}
  run_id=${RUN_ID:-}
  api=${API_LEVEL:-34}
  if [[ -z "$api" ]]; then
    api=34
  fi
  if [[ -z "$name" ]]; then
    name=vinor-direct-apk
  fi
  lower=$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')
  if [[ "$lower" == *.aab ]]; then
    printf 'Refusing AAB artifact name: %s\n' "$name" >&2
    exit 1
  fi
  if [[ "$name" == "vinor-play-bundle-not-for-phone" ]]; then
    printf 'Refusing Play bundle artifact: %s\n' "$name" >&2
    exit 1
  fi
  if [[ ! "$name" =~ ^[A-Za-z0-9._-]{1,100}$ ]]; then
    printf 'artifact_name has unsupported characters\n' >&2
    exit 1
  fi
  if [[ ! "$run_id" =~ ^[0-9]{1,20}$ ]]; then
    printf 'run_id must be a numeric Actions run id\n' >&2
    exit 1
  fi
  if [[ ! "$api" =~ ^[0-9]{2}$ ]] || ((api < 21 || api > 36)); then
    printf 'api_level must be an integer from 21 to 36\n' >&2
    exit 1
  fi
  write_output artifact_name "$name"
  write_output run_id "$run_id"
  write_output api_level "$api"
  printf 'Validated artifact=%s run_id=%s api_level=%s\n' "$name" "$run_id" "$api"
}

select_apk() {
  local dir aab_count listing apk
  local -a apks
  dir=${SMOKE_APK_DIR:-smoke-apk}
  if [[ ! -d "$dir" ]]; then
    printf 'APK directory not found: %s\n' "$dir" >&2
    exit 1
  fi
  aab_count=$(find "$dir" -type f -iname '*.aab' -print | wc -l | tr -d '[:space:]')
  if [[ "$aab_count" != "0" ]]; then
    printf 'Refusing downloaded .aab file\n' >&2
    exit 1
  fi
  mapfile -t apks < <(find "$dir" -type f -iname '*.apk' -print | sort)
  if [[ ${#apks[@]} -ne 1 ]]; then
    printf 'Expected exactly one .apk, found %s\n' "${#apks[@]}" >&2
    printf '%s\n' "${apks[@]}" >&2
    exit 1
  fi
  apk=$(realpath "${apks[0]}")
  case "$apk" in
    *.aab|*.AAB)
      printf 'Refusing AAB path: %s\n' "$apk" >&2
      exit 1
      ;;
  esac
  listing=$(unzip -l "$apk") || {
    printf 'APK is not a readable zip: %s\n' "$apk" >&2
    exit 1
  }
  if [[ "$listing" == *"BundleConfig.pb"* ]]; then
    printf 'Refusing Android App Bundle (BundleConfig.pb): %s\n' "$apk" >&2
    exit 1
  fi
  write_output path "$apk"
  printf 'Selected APK: %s\n' "$apk"
}

is_forbidden() {
  local rid="$1"
  local label="$2"
  case "$rid" in
    loginStep1Submit|loginStep1Phone|loginStep2Submit|loginStep2Resend|loginStep2Code|onboardNext)
      return 0
      ;;
  esac
  case "$label" in
    "بعدی"|"ارسال کد"|"ارسال مجدد کد"|"ورود به پنل"|"شروع وینور")
      return 0
      ;;
  esac
  if [[ "$label" == *ارسال* ]]; then
    return 0
  fi
  return 1
}

adb_pid() {
  local pid
  pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
  [[ -n "${pid// /}" ]]
}

read_screen_size() {
  local line
  line=$(adb shell wm size 2>/dev/null | tr -d '\r' || true)
  W=1080
  H=1920
  if [[ "$line" =~ Override\ size:\ ([0-9]+)x([0-9]+) ]]; then
    W=${BASH_REMATCH[1]}
    H=${BASH_REMATCH[2]}
  elif [[ "$line" =~ Physical\ size:\ ([0-9]+)x([0-9]+) ]]; then
    W=${BASH_REMATCH[1]}
    H=${BASH_REMATCH[2]}
  fi
}

dump_ui() {
  local dest="$1"
  for _ in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/uidump.xml >/tmp/smoke-dump.out 2>&1 || true
    if adb pull /sdcard/uidump.xml "$dest" >/dev/null 2>&1 && [[ -s "$dest" ]] && grep -q "<hierarchy" "$dest"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

has_id() {
  run_ui has "$DUMP" "id:$1"
}

has_text() {
  run_ui has "$DUMP" "text:$1"
}

ui_contains() {
  run_ui contains "$DUMP" "$1"
}

tap_spec() {
  local spec="$1"
  local line x y
  line=$(run_ui center "$DUMP" "$spec") || return 1
  read -r x y <<< "$line"
  [[ "$x" =~ ^[0-9]+$ && "$y" =~ ^[0-9]+$ ]] || return 1
  adb shell input tap "$x" "$y"
}

tap_id() {
  tap_spec "id:$1"
}

dismiss_once() {
  if has_id onboardSkip; then
    tap_id onboardSkip || true
    return 0
  fi
  if has_id permission_deny_and_dont_ask_again_button; then
    tap_id permission_deny_and_dont_ask_again_button || true
    return 0
  fi
  if has_id permission_deny_button; then
    tap_id permission_deny_button || true
    return 0
  fi
  if has_text "Don't allow"; then
    tap_spec "text:Don't allow" || true
    return 0
  fi
  if has_text "عدم اجازه"; then
    tap_spec "text:عدم اجازه" || true
    return 0
  fi
  return 1
}

wait_until() {
  local timeout="$1"
  shift
  local deadline
  deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    if dump_ui "$DUMP"; then
      if dismiss_once; then
        sleep 1
        continue
      fi
      if "$@"; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

hash_changed() {
  local before="$1"
  local now
  now=$(run_ui hash "$DUMP" || true)
  [[ -n "$now" && "$now" != "$before" ]]
}

on_otp_screen() {
  has_id loginStep2Code || has_id loginStep2Submit || has_id loginStep2Resend
}

on_profile() {
  if has_id loginStep1Phone || on_otp_screen; then
    return 1
  fi
  has_id itemProfileLinkTitle || has_id profileGuest || has_id profileGuestLogin || has_id profileTitle
}

profile_settled() {
  if has_id profileProgress; then
    return 1
  fi
  has_id profileGuest || has_id itemProfileLinkTitle || has_id profileTitle
}

is_png() {
  python3 -c 'import pathlib,sys; b=pathlib.Path(sys.argv[1]).read_bytes()[:8]; raise SystemExit(0 if b.startswith(b"\x89PNG") else 1)' "$1"
}

capture() {
  local slug="$1"
  local seq_n prefix base n
  slug=${slug//[^a-z0-9-]/}
  if [[ -z "$slug" ]]; then
    slug=screen
  fi
  seq_n=$(cat /tmp/smoke-seq)
  seq_n=$((seq_n + 1))
  printf '%s\n' "$seq_n" > /tmp/smoke-seq
  prefix=$(printf '%02d' "$seq_n")
  base="${prefix}-${slug}"
  n=2
  while [[ -e "$OUT/${base}.png" ]]; do
    base="${prefix}-${slug}-${n}"
    n=$((n + 1))
  done
  dump_ui "$DUMP" || true
  if [[ -s "$DUMP" ]]; then
    cp "$DUMP" "$OUT/${base}.xml" || printf '%s\n' "dump copy failed" > "$OUT/${base}.xml"
  else
    printf '%s\n' "dump unavailable" > "$OUT/${base}.xml"
  fi
  adb shell dumpsys activity top 2>/dev/null | tr -d '\r' | head -n 160 > "$OUT/${base}.activity.txt" || true
  for _ in 1 2 3; do
    if adb exec-out screencap -p > "$OUT/${base}.png" && is_png "$OUT/${base}.png"; then
      LAST_SHOT=$base
      return 0
    fi
    sleep 1
  done
  LAST_SHOT=$base
  return 1
}

log_crash() {
  [[ -f /tmp/smoke-logcat.raw ]] || return 1
  if grep -F "ANR in ${PKG}" /tmp/smoke-logcat.raw >/dev/null; then
    return 0
  fi
  awk -v pkg="$PKG" '
    /FATAL EXCEPTION/ { c = 40 }
    c > 0 {
      if (index($0, pkg)) found = 1
      c--
    }
    END { exit found ? 0 : 1 }
  ' /tmp/smoke-logcat.raw
}

check_crash() {
  if [[ -f /tmp/smoke-fatal ]]; then
    exit 1
  fi
  dump_ui "$DUMP" || true
  if ui_contains "keeps stopping" || ui_contains "isn't responding" || ui_contains "has stopped"; then
    capture crash-dialog || true
    mark_fatal "system crash dialog"
    record crash fail "system crash dialog ${LAST_SHOT}"
    exit 1
  fi
  if log_crash; then
    mark_fatal "logcat crash or ANR"
    record crash fail "logcat crash or ANR"
    exit 1
  fi
}

cold_start() {
  adb shell am force-stop "$PKG" || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! adb_pid; then
      break
    fi
    sleep 1
  done
  adb shell am start -W \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -n "$PKG/.MainActivity" >/tmp/smoke-start.out 2>&1 || true
  wait_until 45 has_id tabAds
}

open_tab() {
  local id="$1"
  local wait_for_id="$2"
  local timeout="$3"
  for _ in 1 2 3; do
    if ! dump_ui "$DUMP"; then
      sleep 1
      continue
    fi
    dismiss_once || true
    if has_id "$wait_for_id"; then
      return 0
    fi
    if has_id "$id"; then
      tap_id "$id" || true
    fi
    sleep 1
  done
  wait_until "$timeout" has_id "$wait_for_id"
}

return_to_profile() {
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    dump_ui "$DUMP" || true
    if on_otp_screen; then
      adb shell input keyevent KEYCODE_BACK || true
      mark_fatal "OTP screen appeared; left without submitting"
      record otp fail "stopped without typing or submitting a code"
      exit 1
    fi
    if on_profile; then
      return 0
    fi
    if ! adb_pid; then
      cold_start || true
      open_tab tabMe profileTitle 20 || true
      return 0
    fi
    adb shell input keyevent KEYCODE_BACK || true
    sleep 1
  done
  open_tab tabMe profileTitle 20 || true
}

scroll_down() {
  local before after
  dump_ui "$DUMP" || true
  before=$(run_ui hash "$DUMP" || true)
  adb shell input swipe $((W / 2)) $((H * 72 / 100)) $((W / 2)) $((H * 34 / 100)) 400 || true
  for _ in 1 2 3 4 5 6; do
    sleep 1
    dump_ui "$DUMP" || true
    after=$(run_ui hash "$DUMP" || true)
    if [[ -n "$after" && "$after" != "$before" ]]; then
      return 0
    fi
  done
  return 1
}

scroll_top() {
  local before after
  for _ in 1 2 3 4 5; do
    dump_ui "$DUMP" || true
    before=$(run_ui hash "$DUMP" || true)
    adb shell input swipe $((W / 2)) $((H * 30 / 100)) $((W / 2)) $((H * 75 / 100)) 300 || true
    sleep 1
    dump_ui "$DUMP" || true
    after=$(run_ui hash "$DUMP" || true)
    if [[ "$after" == "$before" ]]; then
      return 0
    fi
  done
}

tab_top_y() {
  local top
  if top=$(run_ui tab-top "$DUMP" "$H"); then
    printf '%s\n' "$top"
    return 0
  fi
  printf '%s\n' $((H - 160))
}

next_row() {
  local top key x y label slug resid
  dump_ui "$DUMP" || return 1
  top=$(tab_top_y)
  while IFS=$'\t' read -r key x y label slug resid; do
    [[ -z "$key" ]] && continue
    if grep -Fxq "$key" /tmp/smoke-seen-rows; then
      continue
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$key" "$x" "$y" "$label" "$slug" "$resid"
    return 0
  done < <(run_ui rows "$DUMP" "$H" "$top" || true)
  return 1
}

tap_profile_rows() {
  local scrolls=0
  local tapped=0
  local line key x y label slug resid before
  : > /tmp/smoke-seen-rows
  while ((scrolls < 10 && tapped < 30)); do
    check_crash
    if ! line=$(next_row); then
      if ! scroll_down; then
        break
      fi
      scrolls=$((scrolls + 1))
      continue
    fi
    IFS=$'\t' read -r key x y label slug resid <<< "$line"
    printf '%s\n' "$key" >> /tmp/smoke-seen-rows
    tapped=$((tapped + 1))
    if is_forbidden "$resid" "$label"; then
      record "row-${slug}" skip "refused unsafe control ${label}"
      continue
    fi
    if [[ ! "$x" =~ ^[0-9]+$ || ! "$y" =~ ^[0-9]+$ ]]; then
      record "row-${slug}" fail "bad tap coordinates for ${label}"
      continue
    fi
    before=$(run_ui hash "$DUMP" || true)
    adb shell input tap "$x" "$y" || true
    if wait_until 12 hash_changed "$before"; then
      if on_otp_screen; then
        adb shell input keyevent KEYCODE_BACK || true
        mark_fatal "OTP screen appeared; left without submitting"
        record otp fail "stopped without typing or submitting a code"
        exit 1
      fi
      if capture "vinorman-row-${slug}"; then
        record "row-${slug}" pass "${label} ${LAST_SHOT}"
      else
        record "row-${slug}" fail "${label} screenshot failed"
      fi
    else
      capture "vinorman-row-${slug}" || true
      record "row-${slug}" fail "${label} did not open a new screen"
    fi
    return_to_profile
  done
  if ((tapped == 0)); then
    record profile-rows fail "no visible profile rows"
  fi
}

capture_login() {
  open_tab tabMe profileTitle 20 || true
  scroll_top || true
  dump_ui "$DUMP" || true
  if ! has_id profileGuestLogin; then
    record login-phone fail "login button not visible"
    return 0
  fi
  tap_id profileGuestLogin || true
  if ! wait_until 20 has_id loginStep1Phone; then
    capture login-phone || true
    record login-phone fail "phone entry screen not shown"
    return_to_profile || true
    return 0
  fi
  if on_otp_screen; then
    adb shell input keyevent KEYCODE_BACK || true
    mark_fatal "OTP screen appeared; left without submitting"
    record otp fail "stopped without typing or submitting a code"
    exit 1
  fi
  if capture login-phone; then
    if run_ui phone-empty "$DUMP"; then
      record login-phone pass "phone field empty, not submitted ${LAST_SHOT}"
    else
      record login-phone pass "login screen captured, not submitted ${LAST_SHOT}"
    fi
  else
    record login-phone fail "screenshot failed"
  fi
  adb shell input keyevent KEYCODE_BACK || true
}

disable_network() {
  local ok=0
  if adb shell svc wifi disable; then
    ok=1
  fi
  if adb shell svc data disable; then
    ok=1
  fi
  if adb shell cmd connectivity airplane-mode enable; then
    ok=1
  fi
  if ((ok == 0)); then
    record network fail "could not disable wifi, data, or airplane mode"
    return 1
  fi
  record network pass "network disabled"
  return 0
}

enable_network() {
  adb shell cmd connectivity airplane-mode disable || true
  adb shell svc wifi enable || true
  adb shell svc data enable || true
  adb shell settings put global airplane_mode_on 0 || true
}

offline_pass() {
  local ping_note=""
  if ! disable_network; then
    record offline-vinorman fail "network stayed on"
    record offline-guest-view fail "skipped because network stayed on"
    return 0
  fi
  if adb shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; then
    ping_note=" ping still succeeded"
  fi
  if cold_start || cold_start; then
    open_tab tabMe profileTitle 25 || true
    wait_until 20 profile_settled || true
    if capture vinorman-offline; then
      record offline-vinorman pass "screenshot ${LAST_SHOT}${ping_note}"
    else
      record offline-vinorman fail "screenshot failed${ping_note}"
    fi
    if run_ui guest "$DUMP"; then
      record offline-guest-view fail "guest view visible while offline"
    else
      record offline-guest-view pass "guest view not shown"
    fi
  else
    record offline-vinorman fail "cold start failed${ping_note}"
    record offline-guest-view fail "profile not shown"
  fi
  enable_network || true
}

install_apk() {
  local log
  if [[ ! -f "$APK" ]]; then
    record install fail "APK missing"
    return 1
  fi
  case "$APK" in
    *.aab|*.AAB)
      record install fail "refusing AAB"
      return 1
      ;;
  esac
  if log=$(adb install -r -g "$APK" 2>&1); then
    record install pass "adb install -r -g"
    adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    return 0
  fi
  if log=$(adb install -r "$APK" 2>&1); then
    record install pass "adb install -r"
    adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    return 0
  fi
  record install fail "$log"
  return 1
}

start_logcat() {
  : > /tmp/smoke-logcat.raw
  adb logcat -c >/dev/null 2>&1 || true
  adb logcat -v threadtime >> /tmp/smoke-logcat.raw 2>&1 &
  printf '%s\n' "$!" > /tmp/smoke-logcat.pid
}

stop_logcat() {
  local pid
  if [[ -f /tmp/smoke-logcat.pid ]]; then
    pid=$(cat /tmp/smoke-logcat.pid)
    if [[ -n "$pid" ]]; then
      kill "$pid" >/dev/null 2>&1 || true
      wait "$pid" >/dev/null 2>&1 || true
    fi
  fi
}

filter_logcat() {
  if [[ ! -s /tmp/smoke-logcat.raw ]]; then
    printf '%s\n' "no log lines captured" > "$OUT/logcat.txt"
    return 0
  fi
  awk '
    /ir\.vinor\.app/ { print; next }
    /FATAL EXCEPTION/ { c = 60; print; next }
    /ANR in/ { c = 60; print; next }
    c > 0 { print; c--; next }
  ' /tmp/smoke-logcat.raw > "$OUT/logcat.txt" || true
  if [[ ! -s "$OUT/logcat.txt" ]]; then
    printf '%s\n' "no lines matched ir.vinor.app" > "$OUT/logcat.txt"
  fi
}

cleanup() {
  enable_network || true
  stop_logcat || true
  filter_logcat || true
  write_summary || true
}

run_smoke() {
  self_check
  mkdir -p "$OUT"
  : > "$OUT/steps.tsv"
  printf '%s\n' 0 > /tmp/smoke-seq
  : > /tmp/smoke-seen-rows
  rm -f /tmp/smoke-fatal
  write_summary
  trap cleanup EXIT
  read_screen_size
  start_logcat
  if ! install_apk; then
    mark_fatal "install failed"
    exit 1
  fi
  check_crash
  if cold_start || cold_start; then
    wait_until 12 has_id dashboardScroll || true
    if capture home; then
      record home pass "$LAST_SHOT"
    else
      record home fail "screenshot failed"
    fi
  else
    record home fail "home tab not shown"
  fi
  check_crash

  if open_tab tabSearch searchInput 20 || open_tab tabSearch searchHeader 8; then
    if capture search; then
      record search pass "$LAST_SHOT"
    else
      record search fail "screenshot failed"
    fi
  else
    capture search || true
    record search fail "search field not found"
  fi
  check_crash

  if open_tab tabPost loginStep1Phone 20; then
    if on_otp_screen; then
      adb shell input keyevent KEYCODE_BACK || true
      mark_fatal "OTP screen appeared; left without submitting"
      record otp fail "stopped without typing or submitting a code"
      exit 1
    fi
    if capture post-login; then
      record post-login pass "login redirect ${LAST_SHOT}"
    else
      record post-login fail "screenshot failed"
    fi
    adb shell input keyevent KEYCODE_BACK || true
  else
    capture post-login || true
    record post-login fail "login redirect not shown"
  fi
  check_crash

  if open_tab tabMe profileTitle 25; then
    wait_until 20 profile_settled || true
    if capture vinorman; then
      record vinorman pass "$LAST_SHOT"
    else
      record vinorman fail "screenshot failed"
    fi
    tap_profile_rows
  else
    capture vinorman || true
    record vinorman fail "profile tab not shown"
    record profile-rows fail "profile tab not shown"
  fi
  check_crash

  capture_login
  check_crash
  offline_pass
  check_crash
  if [[ -f /tmp/smoke-fatal ]]; then
    exit 1
  fi
}

cmd=${1:-}
case "$cmd" in
  self-check) self_check ;;
  validate-inputs) validate_inputs ;;
  select-apk) select_apk ;;
  run) run_smoke ;;
  *) usage ;;
esac
