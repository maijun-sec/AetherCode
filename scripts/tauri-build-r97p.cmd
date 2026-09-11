@echo off
setlocal
cd /d D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri
call cargo build --release 2>&1
