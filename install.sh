#!/bin/sh
# Installs droidmap on macOS or Linux: the jar from a GitHub release, and a `droidmap` command.
#
#   curl -fsSL https://raw.githubusercontent.com/hussainshirazi/droidmap/main/install.sh | sh
#
# DROIDMAP_VERSION=0.2.0 picks a release (default: the latest). DROIDMAP_HOME (default ~/.droidmap)
# is where the jar goes, DROIDMAP_BIN (default ~/.local/bin) where the command goes.
set -eu

version="${DROIDMAP_VERSION:-}"
home="${DROIDMAP_HOME:-$HOME/.droidmap}"
bin="${DROIDMAP_BIN:-$HOME/.local/bin}"
releases="https://github.com/hussainshirazi/droidmap/releases"
if [ -n "$version" ]; then url="$releases/download/v$version"; else url="$releases/latest/download"; fi

mkdir -p "$home" "$bin"
echo "Downloading $url/droidmap.jar"
curl -fsSL --retry 3 -o "$home/droidmap.jar.part" "$url/droidmap.jar"
# Releases since 0.2.0 publish a checksum; check it when there is one.
if curl -fsSL --retry 3 -o "$home/droidmap.jar.sha256" "$url/droidmap.jar.sha256" 2>/dev/null; then
  expected=$(cut -d ' ' -f 1 < "$home/droidmap.jar.sha256")
  actual=$( (sha256sum "$home/droidmap.jar.part" 2>/dev/null || shasum -a 256 "$home/droidmap.jar.part") | cut -d ' ' -f 1)
  if [ "$expected" != "$actual" ]; then
    rm -f "$home/droidmap.jar.part"
    echo "droidmap: the download does not match its checksum; nothing was installed" >&2
    exit 1
  fi
fi
mv "$home/droidmap.jar.part" "$home/droidmap.jar"

cat > "$bin/droidmap" <<'EOF'
#!/bin/sh
# droidmap: runs the jar with Java 17 or newer: $JAVA_HOME's, the one on the PATH, or the one
# Android Studio brings (so a machine with Android Studio needs no Java setup).
java=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java="$JAVA_HOME/bin/java"
elif command -v java > /dev/null 2>&1; then
  java=java
else
  for d in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/android-studio/jbr" /opt/android-studio/jbr /usr/local/android-studio/jbr /snap/android-studio/current/android-studio/jbr; do
    if [ -x "$d/bin/java" ]; then java="$d/bin/java"; break; fi
  done
fi
if [ -z "$java" ]; then
  echo "droidmap needs Java 17 or newer: install Android Studio or a JDK, or set JAVA_HOME." >&2
  exit 2
fi
# A short run starts faster with only the quick JIT compiler and the serial GC.
exec "$java" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -jar "__HOME__/droidmap.jar" "$@"
EOF
sed "s|__HOME__|$home|" "$bin/droidmap" > "$bin/droidmap.new" && mv "$bin/droidmap.new" "$bin/droidmap"
chmod +x "$bin/droidmap"

echo "Installed $bin/droidmap"
"$bin/droidmap" --version || true
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Add $bin to your PATH to run droidmap from anywhere." ;;
esac
