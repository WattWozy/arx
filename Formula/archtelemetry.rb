class Arx < Formula
  desc "Architectural governance layer for AI-assisted development"
  homepage "https://github.com/WattWozy/archiTele"
  version "PLACEHOLDER"
  license "MIT"

  on_macos do
    on_arm do
      url "https://github.com/WattWozy/archiTele/releases/download/v#{version}/arx-darwin-arm64"
      sha256 "PLACEHOLDER_SHA256_DARWIN_ARM64"
    end
    on_intel do
      url "https://github.com/WattWozy/archiTele/releases/download/v#{version}/arx-darwin-amd64"
      sha256 "PLACEHOLDER_SHA256_DARWIN_AMD64"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/WattWozy/archiTele/releases/download/v#{version}/arx-linux-amd64"
      sha256 "PLACEHOLDER_SHA256_LINUX_AMD64"
    end
    on_arm do
      url "https://github.com/WattWozy/archiTele/releases/download/v#{version}/arx-linux-arm64"
      sha256 "PLACEHOLDER_SHA256_LINUX_ARM64"
    end
  end

  def install
    binary = Dir["arx-*"].first
    bin.install binary => "arx"
  end

  test do
    system "#{bin}/arx", "--version"
  end
end
