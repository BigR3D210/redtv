# One-paste push: sends this RedTV folder to your GitHub repo, which triggers a build.
# Uses the git bundled with GitHub Desktop (no separate install needed).

$repo = "https://github.com/BigR3D210/redtv.git"
$src  = "C:\Users\david\Dropbox\Claude Creations\RedTV"
$work = "$env:TEMP\redtv-push"

# Locate git
$git = (Get-Command git -ErrorAction SilentlyContinue).Source
if (-not $git) {
    $gd = Get-ChildItem "$env:LOCALAPPDATA\GitHubDesktop" -Filter "app-*" -Directory -ErrorAction SilentlyContinue |
          Sort-Object Name -Descending | Select-Object -First 1
    if ($gd) { $git = Join-Path $gd.FullName "resources\app\git\cmd\git.exe" }
}
if (-not $git -or -not (Test-Path $git)) { Write-Host "git not found. Open GitHub Desktop once, then retry."; exit 1 }

# Fresh clone, replace contents with the latest RedTV folder, commit, push
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
& $git clone $repo $work
if (-not (Test-Path $work)) { Write-Host "Clone failed - check you're signed into GitHub Desktop."; exit 1 }

Get-ChildItem $work -Force | Where-Object { $_.Name -ne ".git" } | Remove-Item -Recurse -Force
Copy-Item "$src\*" $work -Recurse -Force

Push-Location $work
& $git add -A
& $git -c user.email="redtv@local" -c user.name="Red TV" commit -m "Update from Cowork"
& $git push origin HEAD:main
Pop-Location

Write-Host ""
Write-Host "Pushed. Watch the Actions tab - a new build starts automatically."
