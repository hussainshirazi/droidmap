# Homebrew formula, kept up to date by the release workflow.
#   brew tap hussainshirazi/droidmap https://github.com/hussainshirazi/droidmap
#   brew install droidmap
class Droidmap < Formula
  desc "Android-aware project graph, checks and MCP server"
  homepage "https://github.com/hussainshirazi/droidmap"
  url "https://github.com/hussainshirazi/droidmap/releases/download/v0.4.1/droidmap.jar"
  version "0.4.1"
  sha256 "77e5d842245beb87fa22b4f6270018c230297d3f50d1aa94cebfcd3c7d73f914"
  license "Apache-2.0"

  depends_on "openjdk"

  def install
    libexec.install "droidmap.jar"
    # A short run starts faster with only the quick JIT compiler and the serial GC.
    bin.write_jar_script libexec/"droidmap.jar", "droidmap", "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/droidmap --version")
  end
end
