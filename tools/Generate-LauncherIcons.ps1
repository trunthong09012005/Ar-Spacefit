param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$resourceRoot = Join-Path $workspaceRoot 'app\src\main\res'
$sourceImage = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $SourcePath))

$densitySizes = [ordered]@{
    'mipmap-mdpi' = 48
    'mipmap-hdpi' = 72
    'mipmap-xhdpi' = 96
    'mipmap-xxhdpi' = 144
    'mipmap-xxxhdpi' = 192
}

function New-SpaceFitIcon {
    param(
        [int]$Size,
        [bool]$Round,
        [string]$OutputPath
    )

    $bitmap = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

        $backgroundBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 9, 18, 36))
        try {
            if ($Round) {
                $graphics.FillEllipse($backgroundBrush, 0, 0, $Size - 1, $Size - 1)
            } else {
                $radius = [single]($Size * 0.22)
                $diameter = [single]($radius * 2)
                $path = New-Object System.Drawing.Drawing2D.GraphicsPath
                try {
                    $path.AddArc(0, 0, $diameter, $diameter, 180, 90)
                    $path.AddArc($Size - $diameter - 1, 0, $diameter, $diameter, 270, 90)
                    $path.AddArc($Size - $diameter - 1, $Size - $diameter - 1, $diameter, $diameter, 0, 90)
                    $path.AddArc(0, $Size - $diameter - 1, $diameter, $diameter, 90, 90)
                    $path.CloseFigure()
                    $graphics.FillPath($backgroundBrush, $path)
                } finally {
                    $path.Dispose()
                }
            }
        } finally {
            $backgroundBrush.Dispose()
        }

        $inset = [single]($Size * 0.04)
        $graphics.DrawImage($sourceImage, $inset, $inset, $Size - (2 * $inset), $Size - (2 * $inset))
        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

try {
    foreach ($entry in $densitySizes.GetEnumerator()) {
        $directory = Join-Path $resourceRoot $entry.Key
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        New-SpaceFitIcon -Size $entry.Value -Round $false -OutputPath (Join-Path $directory 'ic_spacefit.png')
        New-SpaceFitIcon -Size $entry.Value -Round $true -OutputPath (Join-Path $directory 'ic_spacefit_round.png')
    }
} finally {
    $sourceImage.Dispose()
}
