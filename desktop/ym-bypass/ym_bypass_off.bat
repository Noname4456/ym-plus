@echo off
rem Яндекс Музыка снова через VPN (нужны права администратора — Windows спросит)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -NoExit -File \"%~dp0ym_bypass.ps1\" -Remove'"
