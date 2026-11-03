@echo off
chcp 65001 >nul
title Yandex Music - lyrics button
where py >nul 2>nul
if %errorlevel%==0 (set "PY=py -3") else (set "PY=python")
%PY% "%~dp0ym_lyrics.py"
