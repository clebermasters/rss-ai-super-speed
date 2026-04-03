#!/bin/bash

set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_NAME="rss-ai"
INSTALL_DIR="${HOME}/.local/bin"
CARGO_FLAGS="--release --features tui"

usage() {
    cat <<EOF
Usage: $0 [OPTIONS]

Build and install rss-ai binary.

OPTIONS:
    -d, --dir PATH       Installation directory (default: ~/.local/bin)
    -p, --prefix PATH    Installation prefix (/usr/local if run as root)
    -f, --force          Force rebuild
    -h, --help           Show this help

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

echo "[1/4] Checking dependencies..."
if ! command -v cargo &> /dev/null; then
    echo "Error: Rust/Cargo not found. Install from https://rustup.rs/"
    exit 1
fi

echo "[2/4] Building $BIN_NAME (with TUI feature)..."
if [[ "$FORCE_BUILD" == true ]]; then
    cargo build $CARGO_FLAGS
else
    cargo build $CARGO_FLAGS
fi

echo "[3/4] Installing to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp "target/release/$BIN_NAME" "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/$BIN_NAME"

echo "[4/4] Checking PATH..."

# Detect which shell config file to use
SHELL_CONFIG=""
if [[ -n "$ZSH_VERSION" ]] || [[ "$(basename "$SHELL")" == "zsh" ]]; then
    SHELL_CONFIG="${HOME}/.zshrc"
elif [[ -n "$BASH_VERSION" ]] || [[ "$(basename "$SHELL")" == "bash" ]]; then
    if [[ -f "${HOME}/.bash_profile" ]]; then
        SHELL_CONFIG="${HOME}/.bash_profile"
    else
        SHELL_CONFIG="${HOME}/.bashrc"
    fi
fi

PATH_EXPORT="export PATH=\"\$PATH:$INSTALL_DIR\""

# Check if INSTALL_DIR is already in PATH
if echo "$PATH" | tr ':' '\n' | grep -qx "$INSTALL_DIR"; then
    echo "  $INSTALL_DIR is already in PATH."
    # Refresh shell's hash table so it finds the new binary immediately
    hash -r 2>/dev/null || true
else
    echo "  $INSTALL_DIR is NOT in PATH."
    if [[ -n "$SHELL_CONFIG" ]]; then
        # Avoid duplicate entries
        if ! grep -qF "$INSTALL_DIR" "$SHELL_CONFIG" 2>/dev/null; then
            echo "" >> "$SHELL_CONFIG"
            echo "# Added by rss-ai install.sh" >> "$SHELL_CONFIG"
            echo "$PATH_EXPORT" >> "$SHELL_CONFIG"
            echo "  Added to $SHELL_CONFIG automatically."
        else
            echo "  Entry already exists in $SHELL_CONFIG."
        fi
    fi
    echo ""
    echo "  Run this to use rss-ai in the current shell:"
    echo "    $PATH_EXPORT"
    echo "  Or open a new terminal."
fi

# If an older copy in a higher-priority PATH dir shadows the new install,
# try to replace it automatically.
OLD_BIN="$(command -v "$BIN_NAME" 2>/dev/null || true)"
if [[ -n "$OLD_BIN" && "$OLD_BIN" != "$INSTALL_DIR/$BIN_NAME" ]]; then
    echo ""
    echo "  Detected shadowing binary at: $OLD_BIN"
    echo "  Replacing it with the new build..."
    if cp "$INSTALL_DIR/$BIN_NAME" "$OLD_BIN" 2>/dev/null; then
        echo "  Replaced successfully."
    else
        echo "  Could not replace (file may be in use — close any running rss-ai first)."
        echo "  Then run:  cp $INSTALL_DIR/$BIN_NAME $OLD_BIN"
        echo "  Or add ~/.local/bin before ~/.cargo/bin in your PATH."
    fi
fi

echo ""
echo "=== Success! ==="
echo "Installed: $INSTALL_DIR/$BIN_NAME"
echo "Version  : $("$INSTALL_DIR/$BIN_NAME" --version 2>/dev/null || echo 'n/a')"
echo ""
echo "Run:"
echo "  $BIN_NAME --help"
echo "  $BIN_NAME tui"
