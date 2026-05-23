#!/bin/sh
# Arx installer — detects OS/arch, downloads binary, places in /usr/local/bin
# Usage: curl -sSL https://github.com/WattWozy/archiTele/releases/latest/download/install.sh | sh

set -e

REPO="WattWozy/archiTele"
INSTALL_DIR="${ARX_INSTALL_DIR:-/usr/local/bin}"
BINARY_NAME="arx"

die() { echo "Error: $1" >&2; exit 1; }

# Detect OS
OS="$(uname -s)"
case "$OS" in
  Linux)  OS=linux ;;
  Darwin) OS=darwin ;;
  *)      die "Unsupported OS: $OS. Download manually from https://github.com/$REPO/releases" ;;
esac

# Detect arch
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) die "Unsupported architecture: $ARCH. Download manually from https://github.com/$REPO/releases" ;;
esac

# Resolve latest version if not pinned
if [ -z "$ARX_VERSION" ]; then
  echo "Fetching latest release..."
  ARX_VERSION=$(curl -sSf \
    "https://api.github.com/repos/$REPO/releases/latest" \
    | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\(.*\)".*/\1/')
  [ -n "$ARX_VERSION" ] || die "Could not determine latest release. Set ARX_VERSION manually."
fi

ASSET="${BINARY_NAME}-${OS}-${ARCH}"
URL="https://github.com/${REPO}/releases/download/${ARX_VERSION}/${ASSET}"

echo "Installing arx ${ARX_VERSION} for ${OS}/${ARCH}..."
echo "  Source : $URL"
echo "  Dest   : ${INSTALL_DIR}/${BINARY_NAME}"

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

curl -sSfL "$URL" -o "$TMP" || die "Download failed. Check that $URL exists."

chmod +x "$TMP"

# Verify the binary works
"$TMP" --version >/dev/null 2>&1 || die "Downloaded binary failed self-test."

# Install
if [ -w "$INSTALL_DIR" ]; then
  mv "$TMP" "${INSTALL_DIR}/${BINARY_NAME}"
else
  echo "  (sudo required to write to ${INSTALL_DIR})"
  sudo mv "$TMP" "${INSTALL_DIR}/${BINARY_NAME}"
fi

echo ""
echo "arx installed successfully."
echo "Run: arx --version"
