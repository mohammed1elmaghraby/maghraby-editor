#!/usr/bin/env bash
set -e

# Ensure the script is run with root/sudo privileges
if [ "$EUID" -ne 0 ]; then
  echo "[-] Please run this script with sudo: sudo ./install.sh"
  exit 1
fi

INSTALL_BIN_DIR="/usr/local/bin"
INSTALL_SHARE_DIR="/usr/local/share/maghraby-editor"

# 1. Check for a compiled GraalVM native binary
if [ -f "target/maghraby-linux-x86_64" ]; then
    echo "[+] GraalVM native binary found!"
    cp target/maghraby-linux-x86_64 "$INSTALL_BIN_DIR/maghraby"
    chmod +x "$INSTALL_BIN_DIR/maghraby"
elif ls target/maghraby-editor-*.jar 1> /dev/null 2>&1; then
    # 2. Fallback to standard JAR installation
    echo "[*] Installing executable JAR package..."
    mkdir -p "$INSTALL_SHARE_DIR"
    cp target/maghraby-editor-*.jar "$INSTALL_SHARE_DIR/maghraby-editor.jar"

    cat << 'EOF' > "$INSTALL_BIN_DIR/maghraby"
#!/usr/bin/env bash
exec java -jar /usr/local/share/maghraby-editor/maghraby-editor.jar "$@"
EOF
    chmod +x "$INSTALL_BIN_DIR/maghraby"
else
    echo "[-] Error: No build artifacts found in target/. Please run build first."
    exit 1
fi

# 3. Create quick launcher symlink (medit)
ln -sf "$INSTALL_BIN_DIR/maghraby" "$INSTALL_BIN_DIR/medit"

echo "================================================="
echo "[✔] Installation completed successfully!"
echo "You can now launch the editor from anywhere using:"
echo "    maghraby <filename>"
echo "Or using the quick alias:"
echo "    medit <filename>"
echo "================================================="
