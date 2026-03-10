#!/bin/bash

set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_NAME="rss-ai"
INSTALL_DIR="${HOME}/.local/bin"
CARGO_FLAGS="--release"

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Build and install rss-ai binary.

OPTIONS:
    -d, --dir PATH       Installation directory (default: ~/.local/bin)
    -p, --prefix PATH   Installation prefix (/usr/local if run as root)
    -f, --force         Force rebuild
    -h, --help          Show this help

EXAMPLES:
    $0                      # Build and install to ~/.local/bin
    $0 -d /usr/local/bin    # Install system-wide
    $0 -f                   # Force rebuild
EOF
    exit 1
}

FORCE_BUILD=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -d|--dir)
            INSTALL_DIR="$2"
            shift 2
            ;;
        -p|--prefix)
            if [[ $(id -u) -eq 0 ]]; then
                INSTALL_DIR="$2/bin"
            else
                echo "Warning: --prefix only works as root. Using ~/.local/bin"
                INSTALL_DIR="${HOME}/.local/bin"
            fi
            shift 2
            ;;
        -f|--force)
            FORCE_BUILD=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

echo "=== rss-ai Build & Install Script ==="
echo ""

cd "$REPO_DIR"

echo "[1/3] Checking dependencies..."
if ! command -v cargo &> /dev/null; then
    echo "Error: Rust/Cargo not found. Install from https://rustup.rs/"
    exit 1
fi

echo "[2/3] Building $BIN_NAME..."
if [[ "$FORCE_BUILD" == true ]]; then
    cargo build $CARGO_FLAGS
else
    cargo build $CARGO_FLAGS 2>/dev/null || cargo build $CARGO_FLAGS
fi

echo "[3/3] Installing to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp "target/release/$BIN_NAME" "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/$BIN_NAME"

echo ""
echo "=== Success! ==="
echo "Installed: $INSTALL_DIR/$BIN_NAME"
echo ""
echo "Add to PATH if needed:"
echo "  export PATH=\"\$PATH:$INSTALL_DIR\""
echo ""
echo "Then run:"
echo "  $BIN_NAME --help"
