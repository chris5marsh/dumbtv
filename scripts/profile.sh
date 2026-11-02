#!/usr/bin/env bash
# Copies one TV's apps and home screen to another TV.
#   ./scripts/profile.sh save NAME                      read the TV into profiles/NAME/
#   ./scripts/profile.sh apply NAME [--dry-run] [-y]    make the TV match profiles/NAME/
#
# A profile holds:
#   home.conf    dumbtv home's settings: app order, hidden apps, names, look, VPN recents
#   apps.txt     the TV's own apps, one per line: package, then where it came from
#   dumbtv.conf  the answers configure.sh recorded in this checkout, if there are any
#   apks/        copies of dumbtv home and of apps that did not come from the Appstore.
#                Git-ignored: the apps are other people's software, the launcher is a build.
# Logins and each app's own settings stay behind: Android keeps them from adb.
#
# apply installs dumbtv home from the profile, then each missing app (opening its Appstore
# page for a person to press Get), offers to remove the apps the profile does not have, and
# pushes home.conf last so the new apps take their places. It only lists apps with an icon
# that someone installed; dumbtv's own apps and Amazon's are never offered for removal.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

verb=${1:-}
NAME=${2:-}
[[ $verb == save || $verb == apply ]] && [[ -n $NAME ]] ||
  die "usage: $0 save NAME | apply NAME [--dry-run] [-y]"
[[ $NAME =~ ^[A-Za-z0-9._-]+$ ]] || die "profile name '$NAME': use letters, digits, . _ -"
shift 2
parse_flags "$@"

DIR=$ROOT/profiles/$NAME
PKG=tv.dumbtv.home
# Installed and kept up by dumbtv's own scripts, so neither copied nor offered for removal.
OURS="$PKG dnsfilter.android org.smarttube.stable"
CONF_ON_TV=/sdcard/Android/data/$PKG/files/home.conf
CONF_DROP=/sdcard/Android/media/$PKG/home.conf
APPSTORE=com.amazon.venezia

# Apps someone installed that show an icon, one package per line. Disabled apps and
# overlays have no launcher activity, so they never appear here.
own_apps() {
  local third launch p
  third=$(dev pm list packages -3 | tr -d '\r' | sed -n 's/^package://p' | sort -u)
  launch=$(for c in LEANBACK_LAUNCHER LAUNCHER; do
      dev cmd package query-activities --brief -a android.intent.action.MAIN -c "android.intent.category.$c"
    done | tr -d '\r' | sed -nE 's#^ *([A-Za-z0-9_.]+)/.*#\1#p' | sort -u)
  for p in $third; do
    grep -qx "$p" <<<"$launch" || continue
    case " $OURS " in *" $p "*) continue ;; esac
    protected_by_policy "$p" && continue
    printf '%s\n' "$p"
  done
}

installer_of() {
  dev pm list packages -3 -i | tr -d '\r' | sed -n "s/^package:$1 *installer=//p" | head -1
}

pull() { "$ADB" -s "$SERIAL" pull "$1" "$2" >/dev/null 2>&1; }

# Copies an installed app's APK files (a split app has several) into $2/.
pull_apks() {
  local path n=0
  mkdir -p "$2"
  for path in $(dev pm path "$1" | tr -d '\r' | sed -n 's/^package://p'); do
    pull "$path" "$2/$(basename "$path")" && n=$((n+1))
  done
  [[ $n -gt 0 ]]
}

save() {
  local p src apps n=0 side=0
  mkdir -p "$DIR"
  step "apps on $SERIAL"
  apps=$(own_apps)
  {
    echo "# Apps on the TV this profile was saved from: package, then where it came from."
    echo "# appstore = Amazon's Appstore; sideload = installed from a file, kept in apks/."
    for p in $apps; do
      src=sideload
      [[ $(installer_of "$p") == "$APPSTORE" ]] && src=appstore
      printf '%-40s %s\n' "$p" "$src"
      n=$((n+1))
    done
  } >"$DIR/apps.txt"
  rm -rf "$DIR/apks"
  for p in $apps; do
    [[ $(installer_of "$p") == "$APPSTORE" ]] && continue
    if pull_apks "$p" "$DIR/apks/$p"; then note "$p  copied to apks/"; side=$((side+1))
    else note "$p  could not be copied; install it on the other TV by hand"; fi
  done
  note "$n apps listed, $side copied"

  step "dumbtv home"
  if present "$PKG" && pull_apks "$PKG" "$DIR/apks/$PKG"; then note "this TV's build copied to apks/"
  else note "dumbtv home is not installed here; apply will keep whatever the other TV has"; fi
  if pull "$CONF_ON_TV" "$DIR/home.conf"; then note "settings saved to home.conf"
  else
    rm -f "$DIR/home.conf"
    note "could not read $CONF_ON_TV (Fire OS 8 closes it to adb): the profile has no home screen settings"
  fi

  if [[ -f $CONF ]]; then cp "$CONF" "$DIR/dumbtv.conf"; note "dumbtv.conf copied"; fi
  step "saved profiles/$NAME"
  note "commit it to keep it; apks/ stays on this computer"
}

install_launcher() {
  local apk out
  apk=$(ls "$DIR/apks/$PKG"/*.apk 2>/dev/null | head -1)
  [[ -n $apk ]] || return 0
  if [[ $DRY_RUN == 1 ]]; then note "[dry-run] launcher/install.sh --apk $apk"; return 0; fi
  out=$(ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" --apk "$apk" 2>&1) && { note "dumbtv home installed"; return 0; }
  if [[ $out == *"different key"* ]]; then
    note "the TV has a dumbtv home signed with another key (probably the release)"
    confirm "Replace it with the profile's build? Its settings come from this profile next." ||
      { note "kept the TV's dumbtv home"; return 0; }
    "$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1
    out=$(ADB=$ADB ADB_SERIAL=$SERIAL "$ROOT/launcher/install.sh" --apk "$apk" 2>&1) && { note "dumbtv home installed"; return 0; }
  fi
  printf '%s\n' "$out" >&2
  die "dumbtv home did not install"
}

# Installs an app from apks/, or opens its Appstore page and waits for a person to press Get.
add_app() {
  local p=$1 src=$2 files i
  files=$(ls "$DIR/apks/$p"/*.apk 2>/dev/null)
  if [[ -n $files ]]; then
    if [[ $DRY_RUN == 1 ]]; then note "[dry-run] install $p from apks/"; return 0; fi
    # shellcheck disable=SC2086
    if "$ADB" -s "$SERIAL" install-multiple -r $files 2>&1 | grep -q Success; then
      printf '  %-46s installed\n' "$p"
    else printf '  %-46s did not install\n' "$p"; fi
    return 0
  fi
  if [[ $src != appstore ]]; then printf '  %-46s not in apks/: install it by hand\n' "$p"; return 0; fi
  acmd am start -a android.intent.action.VIEW -d "amzn://apps/android?p=$p" >/dev/null
  [[ $DRY_RUN == 1 ]] && return 0
  if [[ ! -t 0 ]]; then printf '  %-46s Appstore page opened: press Get on the TV\n' "$p"; return 0; fi
  note "$p: press Get on the TV (Enter here skips it)"
  for i in $(seq 1 60); do
    present "$p" && { printf '  %-46s installed\n' "$p"; return 0; }
    read -r -t 5 _ && break
  done
  printf '  %-46s skipped\n' "$p"
}

apply() {
  local wanted have p missing="" extra="" removed=0 out
  [[ -f $DIR/apps.txt ]] || die "no profile at profiles/$NAME (save one first)"
  need_supported_fireos
  wanted=$(list_of "$DIR/apps.txt")
  have=$(own_apps)
  for p in $(awk '{print $1}' <<<"$wanted"); do
    present "$p" || missing="$missing $p"
  done
  for p in $have; do
    awk '{print $1}' <<<"$wanted" | grep -qx "$p" || extra="$extra $p"
  done

  step "plan for $SERIAL ($(fireos_name))"
  if ls "$DIR/apks/$PKG"/*.apk >/dev/null 2>&1; then note "install dumbtv home from the profile"
  elif ! present "$PKG"; then
    die "dumbtv home is not on this TV and the profile has no copy; run CONF=profiles/$NAME/dumbtv.conf ./scripts/all.sh first"
  fi
  note "add:    ${missing:- nothing}"
  note "remove: ${extra:- nothing}"
  if [[ -f $DIR/home.conf ]]; then note "home screen: app order, hidden apps, look and VPN recents from home.conf"
  else note "home screen: unchanged (the profile has no home.conf)"; fi
  [[ $DRY_RUN == 1 ]] || confirm "Apply profile $NAME to $SERIAL?" || die "cancelled"

  step "dumbtv home"
  install_launcher

  step "apps"
  for p in $missing; do add_app "$p" "$(awk -v p="$p" '$1==p {print $2}' <<<"$wanted")"; done
  if [[ -n $extra ]]; then
    note "not in the profile:$extra"
    if [[ $DRY_RUN == 1 ]] || confirm "Remove these apps from $SERIAL?"; then
      for p in $extra; do
        out=$(acmd pm uninstall --user 0 "$p" | head -1 | tr -d '\r')
        case $out in
          Success*) printf '  %-46s uninstalled\n' "$p"; removed=$((removed+1)) ;;
          '') ;;
          *) printf '  %-46s not removed: %s\n' "$p" "$out" ;;
        esac
      done
    else note "kept them"; fi
  fi

  step "home screen"
  if [[ -f $DIR/home.conf ]]; then
    acmd mkdir -p "$(dirname "$CONF_DROP")" >/dev/null
    if [[ $DRY_RUN == 1 ]]; then note "[dry-run] push home.conf to $CONF_DROP"
    else
      out=$("$ADB" -s "$SERIAL" push "$DIR/home.conf" "$CONF_DROP" 2>&1) || die "could not push home.conf: $out"
      # The launcher reads a pushed file when the home screen comes back.
      acmd am start -a android.intent.action.MAIN -c android.intent.category.HOME -n "$PKG/.Home" >/dev/null
      note "settings pushed; the home screen picks them up now"
    fi
  fi
  step "done"
  note "apps removed $removed. Sign in to each app on the TV: logins are not copied."
}

need_device
load_conf
if [[ $verb == save ]]; then save; else apply; fi
