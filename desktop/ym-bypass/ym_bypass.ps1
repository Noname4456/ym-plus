# Яндекс Музыка мимо VPN: трафик к сетям Яндекса идёт через обычный интернет, остальное — через VPN.
# Маршруты временные: после перезагрузки или переподключения Wi-Fi запустите ym_bypass.bat ещё раз.
param([switch]$Remove)
$ErrorActionPreference = 'SilentlyContinue'
$tag = 'ym-bypass'
$stateFile = Join-Path $env:LOCALAPPDATA 'ym-bypass-routes.txt'

function Remove-Old {
    if (Test-Path $stateFile) {
        foreach ($line in Get-Content $stateFile) {
            $p, $idx = $line -split ';'
            Remove-NetRoute -DestinationPrefix $p -InterfaceIndex $idx -Confirm:$false
        }
        Remove-Item $stateFile
    }
}

if ($Remove) {
    Remove-Old
    Write-Host 'Готово: Яндекс Музыка снова идёт через VPN.' -ForegroundColor Green
    return
}

# 1. Настоящий (не VPN) сетевой адаптер и его шлюз
$virtual = 'WireGuard|Wintun|TAP|TUN|VPN|Amnezia|sing|Hiddify|Clash|OpenVPN|Outline|Virtual|Hyper-V|VirtualBox|VMware|Loopback|Tailscale|ZeroTier|Radmin|Hamachi'
$cfg = Get-NetIPConfiguration | Where-Object {
    $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' -and
    ($_.NetAdapter.InterfaceDescription -notmatch $virtual) -and ($_.InterfaceAlias -notmatch $virtual)
} | Select-Object -First 1
if (-not $cfg) {
    Write-Host 'Не нашёл обычное подключение к интернету (Wi-Fi или кабель).' -ForegroundColor Red
    Get-NetIPConfiguration | Format-Table InterfaceAlias, InterfaceDescription, IPv4DefaultGateway -AutoSize
    return
}
$gw = $cfg.IPv4DefaultGateway.NextHop
$idx = $cfg.InterfaceIndex
Write-Host "Обычное подключение: $($cfg.InterfaceAlias), шлюз $gw"

# 2. Сети Яндекса — свежий список из RIPE (AS13238 Яндекс, AS200350 Yandex.Cloud), иначе встроенный
$prefixes = @()
foreach ($as in 'AS13238', 'AS200350') {
    try {
        $r = Invoke-RestMethod "https://stat.ripe.net/data/announced-prefixes/data.json?resource=$as" -TimeoutSec 20
        $prefixes += $r.data.prefixes.prefix | Where-Object { $_ -notmatch ':' }
    } catch {}
}
if ($prefixes.Count -lt 10) {
    Write-Host 'Список из интернета не получил — беру встроенный.' -ForegroundColor Yellow
    $prefixes = @('5.45.192.0/18','5.255.192.0/18','37.9.64.0/18','37.140.128.0/18','77.88.0.0/18','84.201.128.0/18',
                  '87.250.224.0/19','93.158.128.0/18','95.108.128.0/17','141.8.128.0/18','178.154.128.0/18',
                  '185.32.184.0/22','213.180.192.0/19','51.250.0.0/17','84.252.128.0/20','158.160.0.0/16')
}
$prefixes = $prefixes | Sort-Object -Unique

# 3. Маршруты через обычный шлюз
Remove-Old
$ok = 0
foreach ($p in $prefixes) {
    $r = New-NetRoute -DestinationPrefix $p -InterfaceIndex $idx -NextHop $gw -RouteMetric 1 -PolicyStore ActiveStore
    if ($r) { $ok++; Add-Content $stateFile "$p;$idx" }
}
Write-Host "Готово: $ok сетей Яндекса идут мимо VPN." -ForegroundColor Green
Write-Host 'Перезапустите Яндекс Музыку.'
