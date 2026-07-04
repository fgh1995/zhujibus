$ErrorActionPreference = "Stop"

# 获取当前目录作为项目目录
$ProjectRoot = $PWD.Path
$OutputDir = "$ProjectRoot\build"

# 从 main.go 中读取版本号
$VersionSuffix = ""
$MainGoPath = "$ProjectRoot\main.go"
if (Test-Path $MainGoPath) {
    $MainGoContent = Get-Content $MainGoPath -Raw
    $VersionMatch = [regex]::Match($MainGoContent, 'const\s+frontendVersion\s*=\s*"([^"]+)"')
    if ($VersionMatch.Success) {
        $VersionSuffix = "-$($VersionMatch.Groups[1].Value)"
        Write-Host "检测到版本号: $($VersionMatch.Groups[1].Value)" -ForegroundColor Cyan
    }
}

$Platforms = @(
    @{GOOS="linux"; GOARCH="arm64"; Folder="linux-arm64"; Ext=""},
    @{GOOS="linux"; GOARCH="amd64"; Folder="linux-amd64"; Ext=""},
    @{GOOS="windows"; GOARCH="amd64"; Folder="windows-amd64"; Ext=".exe"},
    @{GOOS="windows"; GOARCH="arm64"; Folder="windows-arm64"; Ext=".exe"}
)

if (Test-Path $OutputDir) {
    Remove-Item $OutputDir -Recurse -Force
}
New-Item $OutputDir -ItemType Directory | Out-Null

$Resources = @("config", "static", "install.sh")

Write-Host "开始构建多平台版本..." -ForegroundColor Cyan

foreach ($Platform in $Platforms) {
    $GOOS = $Platform.GOOS
    $GOARCH = $Platform.GOARCH
    $Folder = $Platform.Folder
    $Ext = $Platform.Ext
    $BinaryName = "zhuji-bus-wsserver-$Folder$Ext"

    Write-Host "`n========== 构建 $Folder ==========" -ForegroundColor Yellow

    $Env:GOOS = $GOOS
    $Env:GOARCH = $GOARCH

    Write-Host "正在编译 $BinaryName..."
    go build -o "$OutputDir\$Folder\$BinaryName" .

    if ($LASTEXITCODE -ne 0) {
        Write-Host "构建 $Folder 失败!" -ForegroundColor Red
        exit 1
    }

    Write-Host "正在复制资源文件..."
    foreach ($Resource in $Resources) {
        if ($Resource -eq "install.sh") {
            $SourceFile = "$ProjectRoot\$Resource"
            $DestFile = "$OutputDir\$Folder\$Resource"
            if (Test-Path $SourceFile) {
                Copy-Item $SourceFile $DestFile -Force
                Write-Host "  复制 $Resource -> build\$Folder/"
            }
        } else {
            $SourcePath = "$ProjectRoot\$Resource"
            $DestPath = "$OutputDir\$Folder\$Resource"
            if (Test-Path $SourcePath) {
                if (Test-Path $DestPath) {
                    Remove-Item $DestPath -Recurse -Force
                }
                Copy-Item $SourcePath $DestPath -Recurse -Force
                Write-Host "  复制 $Resource -> build\$Folder/"
            }
        }
    }

    Write-Host "$Folder 构建完成!" -ForegroundColor Green
}

$Env:GOOS = $null
$Env:GOARCH = $null

# 打包为 zip
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "开始打包为 zip..." -ForegroundColor Cyan

foreach ($Platform in $Platforms) {
    $Folder = $Platform.Folder
    $ZipName = "zhuji-bus-wsserver-$Folder$VersionSuffix.zip"
    $ZipPath = "$OutputDir\$ZipName"
    $SourcePath = "$OutputDir\$Folder"

    Write-Host "  打包 $Folder -> $ZipName..."
    Compress-Archive -Path "$SourcePath\*" -DestinationPath $ZipPath -Force
    Write-Host "  完成!" -ForegroundColor Green
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "多平台构建全部完成!" -ForegroundColor Cyan
Write-Host ""

foreach ($Platform in $Platforms) {
    $Folder = $Platform.Folder
    $ZipName = "zhuji-bus-wsserver-$Folder$VersionSuffix.zip"
    $ZipPath = "$OutputDir\$ZipName"
    if (Test-Path $ZipPath) {
        $Size = (Get-Item $ZipPath).Length / 1MB
        Write-Host "  $ZipName ($([math]::Round($Size, 2)) MB)"
    }
}