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

cat > "$bin/droidmap" <<EOF
#!/bin/sh
# droidmap: runs the jar with \$JAVA_HOME's java, or the one on the PATH (Java 17 or newer).
exec "\${JAVA_HOME:+\$JAVA_HOME/bin/}java" -jar "$home/droidmap.jar" "\$@"
EOF
chmod +x "$bin/droidmap"

echo "Installed $bin/droidmap"
if ! "$bin/droidmap" --version 2>/dev/null; then
  echo "droidmap needs Java 17 or newer: set JAVA_HOME (Android Studio has one in its jbr folder) or put java on your PATH."
fi
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Add $bin to your PATH to run droidmap from anywhere." ;;
esac
