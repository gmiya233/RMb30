@echo off
chcp 65001 >nul
cd /d "E:\三消策划案\Malody"
set "TEMP=E:\三消策划案\Malody\build\temp"
set "TMP=E:\三消策划案\Malody\build\temp"
set "NUMBA_CACHE_DIR=E:\三消策划案\Malody\build\tempo-numba-cache"
"C:\Users\bigcool\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -B tools/analyze_neural_tempo.py
if errorlevel 1 pause
