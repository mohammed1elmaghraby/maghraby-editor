#!/usr/bin/env bash
set -e

if [ "$EUID" -ne 0 ]; then
    echo "[-] Please run this script with sudo: sudo ./uninstall.sh"
    exit 1
fi

echo "[*] Uninstalling Maghraby Editor..."

rm -f /usr/local/bin/maghraby
rm -f /usr/local/bin/medit
rm -rf /usr/local/share/maghraby-editor

echo "[✔] Maghraby Editor successfully removed!"