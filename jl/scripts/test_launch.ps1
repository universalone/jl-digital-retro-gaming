# Starts a game in RetroArch on the PC, waits, screenshots the screen, then closes RetroArch.
param([string]$Core, [string]$Rom, [int]$Wait = 30, [string]$Shot = 'D:\jlra\pc_test.png')
$p = Start-Process D:\RetroArch-Win64\retroarch.exe -ArgumentList '-L', "`"$Core`"", "`"$Rom`"" -WorkingDirectory D:\RetroArch-Win64 -PassThru
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h); }
'@
Start-Sleep 5
$p.Refresh(); [W]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep ($Wait - 5)
$p.Refresh(); [W]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep 2
$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
[System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
$bmp.Save($Shot); $bmp.Dispose()
"running: $(-not $p.HasExited)"
if (-not $p.HasExited) { $p.CloseMainWindow() | Out-Null; Start-Sleep 3; if (-not $p.HasExited) { $p.Kill() } }
