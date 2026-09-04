@echo off
start "LAN Send" powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0PocketDrop-Receiver.ps1"
