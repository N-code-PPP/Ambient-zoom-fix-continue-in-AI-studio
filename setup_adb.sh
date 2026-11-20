#!/usr/bin/env bash
# One-Time ADB Setup Script for Ambient Brain (Android 14+ / 15 / 16 • AOSP & HyperOS/MIUI)
# Prerequisites:
#   1. Settings -> About phone -> Tap Build number / OS version 7 times to enable Developer options.
#   2. Developer options -> Turn ON "USB debugging".
#   3. Xiaomi / Poco / HyperOS devices: ALSO turn ON "USB debugging (Security settings)" in Developer options!

set -euo pipefail

PKG="com.ambientbrain.recorder"
A11Y_FULL="$PKG/$PKG.service.AmbientCaptureAccessibilityService"
A11Y_SHORT="$PKG/.service.AmbientCaptureAccessibilityService"

echo "==> [1/5] Granting runtime permissions & Zero-Touch Self-Heal (WRITE_SECURE_SETTINGS)..."
adb shell pm grant --user 0 "$PKG" android.permission.RECORD_AUDIO || adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
adb shell pm grant --user 0 "$PKG" android.permission.READ_PHONE_STATE || adb shell pm grant "$PKG" android.permission.READ_PHONE_STATE || true
adb shell pm grant --user 0 "$PKG" android.permission.WRITE_SECURE_SETTINGS || adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

echo "==> [2/5] Configuring AppOps (Mic + Restricted Settings + Overlay + PROJECT_MEDIA + UsageStats)..."
adb shell cmd appops set --user 0 "$PKG" RECORD_AUDIO allow
adb shell cmd appops set --user 0 "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
adb shell cmd appops set --user 0 "$PKG" SYSTEM_ALERT_WINDOW allow || true
adb shell cmd appops set --user 0 "$PKG" PROJECT_MEDIA allow
adb shell cmd appops set --user 0 "$PKG" RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set --user 0 "$PKG" RUN_IN_BACKGROUND allow
adb shell cmd appops set --user 0 "$PKG" WAKE_LOCK allow || true
adb shell cmd appops set --user 0 "$PKG" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore || true
adb shell cmd appops set --user 0 "$PKG" GET_USAGE_STATS allow || true

echo "==> [3/5] Bypassing Android 14-16 Cached Freezer & Phantom Process Killer..."
adb shell "cmd deviceidle whitelist +$PKG || dumpsys deviceidle whitelist +$PKG"
adb shell am set-standby-bucket --user 0 "$PKG" active || adb shell am set-standby-bucket "$PKG" active || true
adb shell settings put global settings_enable_monitor_phantom_procs false || true
adb shell device_config put activity_manager max_phantom_processes 2147483647 || true

echo "==> [4/5] Appending & rebinding AmbientCaptureAccessibilityService without wiping other services..."
CURRENT_A11Y=$((adb shell settings get secure enabled_accessibility_services || true) | tr -d '\r\n')
CLEAN_A11Y=$(printf '%s\n' "$CURRENT_A11Y" | awk -v a="$A11Y_FULL" -v b="$A11Y_SHORT" -F: '{
    out = ""
    for (i = 1; i <= NF; i++) {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", $i)
        if ($i != "" && $i != "null" && $i != a && $i != b) {
            out = (out == "" ? $i : out ":" $i)
        }
    }
    print out
}')

if [[ -n "$CLEAN_A11Y" ]]; then
    adb shell settings put secure enabled_accessibility_services "$CLEAN_A11Y"
    sleep 1
    adb shell settings put secure enabled_accessibility_services "${CLEAN_A11Y}:${A11Y_FULL}"
else
    adb shell settings put secure enabled_accessibility_services ""
    sleep 1
    adb shell settings put secure enabled_accessibility_services "$A11Y_FULL"
fi
adb shell settings put secure accessibility_enabled 1

echo "==> [5/5] Verifying setup..."
echo "Enabled Accessibility Services: $(adb shell settings get secure enabled_accessibility_services || true)"
adb shell cmd appops get "$PKG" | grep -E "RECORD_AUDIO|ACCESS_RESTRICTED_SETTINGS|SYSTEM_ALERT_WINDOW|PROJECT_MEDIA|RUN_ANY_IN_BACKGROUND|AUTO_REVOKE|GET_USAGE_STATS" || adb shell cmd appops get "$PKG"
PHANTOM_MON=$((adb shell settings get global settings_enable_monitor_phantom_procs 2>/dev/null || true) | tr -d '\r\n')
PHANTOM_MAX=$((adb shell device_config get activity_manager max_phantom_processes 2>/dev/null || true) | tr -d '\r\n')
echo "Phantom Monitor Disabled Setting: ${PHANTOM_MON:-unset} | Max Phantom Processes: ${PHANTOM_MAX:-unset}"

cat << 'EOF'

================================================================================
Manual Device Checklist (Do once on device):
  1. App Info -> Battery: "Unrestricted" / "No restrictions"
  2. Recent Apps -> Long-press Ambient Brain card -> Tap Lock (🔒) icon to prevent eviction.
  3. Xiaomi / Poco / HyperOS:
       - Developer options -> "USB debugging (Security settings)" -> ON
       - App Info -> Autostart / Background Autostart -> ON
       - App Info -> Other permissions -> "Display pop-up windows while running in background" -> Always Allow
       - App Info -> Other permissions -> "Show on Lock screen" -> Always Allow
  4. Reboot / App Update Resilience:
       - WRITE_SECURE_SETTINGS enables zero-touch Accessibility self-healing after reboot or APK update!
================================================================================
EOF
