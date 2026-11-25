# YGComic 书源引擎 - 电脑原生测试脚本
# 双击运行（右键 -> 使用 PowerShell 运行），或在这个窗口里执行。
# 走本机代理 127.0.0.1:17891，按提示选书源集、输入书名即可逐源实测。

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$exe = "C:\Users\Administrator\.dsh\profiles\desktop\YGComic\rust\source-engine\target\debug\examples\probe.exe"
$report = "C:\Users\Administrator\.dsh\im\probe_out.txt"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " YGComic 书源引擎 · 电脑测试" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

if (-not (Test-Path $exe)) {
    Write-Host "探针未编译，正在编译..." -ForegroundColor Yellow
    $env:CARGO_HTTP_PROXY = "http://127.0.0.1:17891"
    & "C:\Users\Administrator\.cargo\bin\cargo.exe" build --example probe `
        --manifest-path "C:\Users\Administrator\.dsh\profiles\desktop\YGComic\rust\source-engine\Cargo.toml"
}

Write-Host ""
Write-Host "选择书源集：" -ForegroundColor Green
Write-Host "  1) 基础 65 源      (bookSource.json)"
Write-Host "  2) 墨辰 463 源     (mochen_sources.json)"
Write-Host "  3) yiove 2158 源   (yiove_sources.json)"
$pick = Read-Host "输入 1/2/3（默认 1）"
switch ($pick) {
    "2" { $json = "C:\Users\Administrator\.dsh\im\mochen_sources.json" }
    "3" { $json = "C:\Users\Administrator\.dsh\im\yiove_sources.json" }
    default { $json = "C:\Users\Administrator\.dsh\profiles\desktop\bookSource.json" }
}

$key = Read-Host "输入要搜索的书名（默认 斗破苍穹）"
if ([string]::IsNullOrWhiteSpace($key)) { $key = "斗破苍穹" }

$filter = Read-Host "只测某个源？输入源名关键字，留空=测全部"

Write-Host ""
Write-Host "开始测试：$key  |  $json" -ForegroundColor Cyan
Write-Host "（逐源真实请求，源多时可能要几分钟）" -ForegroundColor DarkGray
Write-Host ""

$env:YG_PROXY = "127.0.0.1:17891"
& $exe $filter $key $json

Write-Host ""
Write-Host "================ 结果摘要 ================" -ForegroundColor Cyan
if (Test-Path $report) {
    Get-Content $report -Encoding UTF8 | Select-Object -Last 40
}
Write-Host ""
Write-Host "完整报告：$report" -ForegroundColor Green
Write-Host "[OK n=x exact=y] 能出书（exact=精确命中数）" -ForegroundColor DarkGray
Write-Host "[EMPTY] 请求成功但解析 0 本 / [ERR] 请求失败 / [NO_SEARCH_URL] 无搜索规则" -ForegroundColor DarkGray
Write-Host ""
Read-Host "测试完成，按回车关闭"
