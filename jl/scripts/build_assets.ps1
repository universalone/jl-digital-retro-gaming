# Fills pkg\android\jl\assets for the all-in-one app:
#   assets\assets\{ozone,xmb,pkg,sounds}   JL-branded menu assets (Ozone's icons live in xmb\monochrome) (RetroArch unpacks APK "assets/*" into <dataDir>)
#   assets\{autoconfig,info,filters,overlays}   controller profiles, core info, touch overlays (from the official APK)
#   assets\index.html, icon.png, PressStart2P.ttf   the game library page, served by MainActivity
# Bump versionCode in build.gradle after changing these, or devices keep their old unpacked copy.
$ErrorActionPreference = 'Stop'
$web = 'D:\JL Digital Retro Gaming\web'
$officialApk = 'D:\JL Digital Retro Gaming\.tools\downloads\RetroArch_aarch64.apk'
$out = 'D:\jlra\RetroArch\pkg\android\jl\assets'
$menu = "$out\assets"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $menu | Out-Null


# Menu assets (matching this engine version), controller profiles, core info, filters and touch overlays,
# as shipped in the official 1.22.2 APK.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($officialApk)
try {
    foreach ($e in $zip.Entries) {
        if ($e.FullName -notmatch '^assets/(assets/(ozone|xmb|pkg|sounds)|autoconfig|info|filters|overlays)/' -or $e.Name -eq '') { continue }
        $dest = Join-Path $out ($e.FullName.Substring(7) -replace '/', '\')
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
    }
} finally { $zip.Dispose() }

# Headers and titles use the 8-bit "Press Start 2P" font (SIL Open Font License).
Copy-Item 'D:\jlra\fonts\PressStart2P-Regular.ttf' "$menu\ozone\bold.ttf" -Force
Copy-Item 'D:\jlra\fonts\OFL.txt' "$menu\ozone\bold-font-license.txt" -Force

# JL Digital logo in the menu header (drawn with a white tint, so it keeps its colors).
Add-Type -AssemblyName System.Drawing
$icon = [System.Drawing.Image]::FromFile("$web\icon.png")
$bmp = New-Object System.Drawing.Bitmap 96, 96
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = 'HighQualityBicubic'
$g.DrawImage($icon, 0, 0, 96, 96)
$bmp.Save("$menu\ozone\png\retroarch.png", [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose(); $icon.Dispose()

# White "JL" mark (tinted by the menu) in place of RetroArch's invader icon.
$fonts = New-Object System.Drawing.Text.PrivateFontCollection
$fonts.AddFontFile('D:\jlra\fonts\PressStart2P-Regular.ttf')
foreach ($icon in @(@("$menu\ozone\png\sidebar\retroarch.png", 64), @("$menu\xmb\monochrome\png\retroarch.png", 256))) {
    $size = $icon[1]
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), ($size * 0.07)
    $inset = $size * 0.08
    $g.DrawRectangle($pen, $inset, $inset, $size - 2 * $inset, $size - 2 * $inset)
    $font = New-Object System.Drawing.Font $fonts.Families[0], ($size * 0.30), ([System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = 'Center'; $fmt.LineAlignment = 'Center'
    $g.DrawString('JL', $font, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, ($size * 0.04), $size, $size), $fmt)
    $bmp.Save($icon[0], [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose(); $pen.Dispose(); $font.Dispose()
}

# The game library page.
Get-ChildItem $web -File | Copy-Item -Destination $out
"{0:N1} MB" -f ((Get-ChildItem -Recurse $out -File | Measure-Object Length -Sum).Sum / 1MB)
