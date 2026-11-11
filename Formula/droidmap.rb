# Homebrew formula, kept up to date by the release workflow.
#   brew tap hussainshirazi/droidmap https://github.com/hussainshirazi/droidmap
#   brew install droidmap
class Droidmap < Formula
  desc "Android-aware project graph, checks and MCP server"
  homepage "https://github.com/hussainshirazi/droidmap"
  url "https://github.com/hussainshirazi/droidmap/releases/download/v0.1.0/droidmap.jar"
  version "0.1.0"
  sha256 "0a4b8f9cff64095a05cfe64d0558b688adfc07e9c1a9ed36dadd836416e5524e"
  license "Apache-2.0"

  depends_on "openjdk"

  def install
    libexec.install "droidmap.jar"
    bin.write_jar_script libexec/"droidmap.jar", "droidmap"
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/droidmap --version")
  end
end
