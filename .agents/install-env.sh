#!/usr/bin/env bash
set -euo pipefail

# Idempotent, non-interactive cloud/dev environment setup for Ambient Brain (Android / Kotlin / Gradle).
# Installs Android SDK 35 + build-tools into $ANDROID_HOME (default: $HOME/android-sdk),
# configures shell environment variables & JVM proxy/truststore support in ~/.bashrc,
# and warms the Gradle wrapper and compile dependencies cache.

ENV_SCRIPT="$HOME/.config/android-env.sh"
mkdir -p "$(dirname "$ENV_SCRIPT")" "$HOME/.local/share/java"

cat << 'EOF' > "$ENV_SCRIPT"
# Android SDK & JDK 17 environment (managed by .agents/install-env.sh)
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  elif command -v javac >/dev/null 2>&1; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  fi
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

case ":$PATH:" in
  *":$ANDROID_HOME/cmdline-tools/latest/bin:"*) ;;
  *) export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH" ;;
esac
case ":$PATH:" in
  *":$ANDROID_HOME/platform-tools:"*) ;;
  *) export PATH="$ANDROID_HOME/platform-tools:$PATH" ;;
esac

# When the container JVM's default cacerts symlink is missing, build a user-space PKCS12 truststore
# from the system CA bundle (e.g. local proxy CA) and keep it refreshed across VM restarts.
_java_net_opts=""
_truststore="$HOME/.local/share/java/cacerts.p12"
if [ -n "${JAVA_HOME:-}" ] && [ ! -e "$JAVA_HOME/lib/security/cacerts" ] && [ -f /etc/ssl/certs/ca-certificates.crt ]; then
  if [ ! -f "$_truststore" ] || [ /etc/ssl/certs/ca-certificates.crt -nt "$_truststore" ]; then
    mkdir -p "$(dirname "$_truststore")"
    rm -f "$_truststore"
    keytool -importcert -noprompt -trustcacerts -alias system-ca \
      -file /etc/ssl/certs/ca-certificates.crt \
      -keystore "$_truststore" -storepass changeit -storetype PKCS12 >/dev/null 2>&1 || true
  fi
  if [ -f "$_truststore" ]; then
    _java_net_opts="$_java_net_opts -Djavax.net.ssl.trustStore=$_truststore -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
  fi
fi

# Dynamically propagate HTTP(S)_PROXY to JVM tools (sdkmanager, Gradle wrapper/daemon).
_proxy_url="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -n "$_proxy_url" ]; then
  _p="${_proxy_url#*://}"
  _p="${_p#*@}"
  _p="${_p%%/*}"
  _host="${_p%%:*}"
  _port="${_p##*:}"
  if [ -n "$_host" ] && [ -n "$_port" ] && [ "$_host" != "$_port" ]; then
    _java_net_opts="$_java_net_opts -Dhttps.proxyHost=$_host -Dhttps.proxyPort=$_port -Dhttp.proxyHost=$_host -Dhttp.proxyPort=$_port -Dhttp.nonProxyHosts=localhost|127.0.0.1"
  fi
fi

if [ -n "$_java_net_opts" ]; then
  case " ${JAVA_TOOL_OPTIONS:-} " in
    *"-Dhttps.proxyHost="*|*"-Djavax.net.ssl.trustStore="*) ;;
    *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }${_java_net_opts# }" ;;
  esac
fi
unset _java_net_opts _truststore _proxy_url _p _host _port
EOF

# Ensure non-interactive bash shells (sourced via BASH_ENV) load ~/.bashrc without returning early.
sed -i '/case \$- in/,/esac/d' "$HOME/.bashrc" 2>/dev/null || true
SOURCE_LINE='[ -f "$HOME/.config/android-env.sh" ] && . "$HOME/.config/android-env.sh"'
grep -qxF "$SOURCE_LINE" "$HOME/.bashrc" 2>/dev/null || echo "$SOURCE_LINE" >> "$HOME/.bashrc"

# Load environment for this setup run
# shellcheck source=/dev/null
. "$ENV_SCRIPT"

# 1. Install Android command-line tools if missing
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  TMP_ZIP="$(mktemp /tmp/cmdline-tools-XXXXXX.zip)"
  TMP_DIR="$(mktemp -d /tmp/cmdline-tools-XXXXXX)"
  trap 'rm -rf "$TMP_ZIP" "$TMP_DIR"' EXIT
  curl -fsSL -o "$TMP_ZIP" "$CMDLINE_TOOLS_URL"
  unzip -q "$TMP_ZIP" -d "$TMP_DIR"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$TMP_DIR/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$TMP_ZIP" "$TMP_DIR"
  trap - EXIT
fi

# 2. Accept Android SDK licenses and install required SDK packages if missing
if [ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ] \
  || [ ! -d "$ANDROID_HOME/platform-tools" ] \
  || [ ! -d "$ANDROID_HOME/platforms/android-35" ] \
  || [ ! -d "$ANDROID_HOME/build-tools/35.0.0" ] \
  || [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  set +o pipefail
  yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  set -o pipefail
  sdkmanager --sdk_root="$ANDROID_HOME" --install \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "build-tools;34.0.0"
fi

# 3. Warm Gradle wrapper and compile dependencies without mutating tracked file permissions
sh ./gradlew :app:dependencies --configuration debugCompileClasspath --no-daemon >/dev/null
