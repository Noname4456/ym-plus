@echo off
chcp 65001 >nul
rem Запуск Яндекс Музыки мимо VPN (для VPN-клиентов, работающих как системный прокси)
set "YM=%LOCALAPPDATA%\Programs\YandexMusic\Яндекс Музыка.exe"
if not exist "%YM%" (
  echo Не нашёл Яндекс Музыку: %YM%
  pause & exit /b 1
)
taskkill /F /IM "Яндекс Музыка.exe" >nul 2>&1
timeout /t 1 /nobreak >nul
start "" "%YM%" --no-proxy-server
