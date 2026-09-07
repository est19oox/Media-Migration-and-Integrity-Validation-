# ============================================================
# Ansible Installation Script for Windows
# Installs WSL2 + Ubuntu and Ansible
# ============================================================

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "     Ansible Installation Script" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# Check administrator privileges
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($currentUser)

if (-not $principal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator
)) {
    Write-Host ""
    Write-Host "ERROR: Please run PowerShell as Administrator." -ForegroundColor Red
    Write-Host ""
    exit 1
}

Write-Host ""
Write-Host "[1/5] Checking WSL..." -ForegroundColor Yellow

# Check whether WSL is installed
$wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue

if (-not $wsl) {
    Write-Host "WSL is not installed. Installing WSL..." -ForegroundColor Yellow

    wsl --install

    Write-Host ""
    Write-Host "WSL installation has started." -ForegroundColor Green
    Write-Host "Windows may require a restart." -ForegroundColor Yellow
    Write-Host "After restarting, run this script again." -ForegroundColor Yellow

    exit 0
}

Write-Host "WSL is installed." -ForegroundColor Green


Write-Host ""
Write-Host "[2/5] Checking WSL version..." -ForegroundColor Yellow

wsl --set-default-version 2

Write-Host "WSL2 configured as the default version." -ForegroundColor Green


Write-Host ""
Write-Host "[3/5] Checking Ubuntu..." -ForegroundColor Yellow

$ubuntu = wsl --list --quiet 2>$null |
    Where-Object { $_ -match "Ubuntu" }

if (-not $ubuntu) {

    Write-Host "Ubuntu is not installed." -ForegroundColor Yellow
    Write-Host "Installing Ubuntu..." -ForegroundColor Yellow

    wsl --install -d Ubuntu

    Write-Host ""
    Write-Host "Ubuntu installation has started." -ForegroundColor Green
    Write-Host ""
    Write-Host "If Windows asks you to restart, restart the computer." -ForegroundColor Yellow
    Write-Host "Then run this script again." -ForegroundColor Yellow

    exit 0
}

Write-Host "Ubuntu is already installed." -ForegroundColor Green


Write-Host ""
Write-Host "[4/5] Installing Ansible..." -ForegroundColor Yellow

# Update Ubuntu packages
wsl -d Ubuntu -- bash -c "sudo apt-get update"

# Install prerequisites
wsl -d Ubuntu -- bash -c "sudo apt-get install -y python3 python3-pip python3-venv"

# Install Ansible
wsl -d Ubuntu -- bash -c "sudo apt-get install -y ansible"

Write-Host ""
Write-Host "Ansible installation completed." -ForegroundColor Green


Write-Host ""
Write-Host "[5/5] Checking Ansible installation..." -ForegroundColor Yellow

$ansibleVersion = wsl -d Ubuntu -- bash -c "ansible --version"

Write-Host ""
Write-Host "Ansible version:" -ForegroundColor Cyan
Write-Host $ansibleVersion -ForegroundColor Green


Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "       Installation Completed!" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Cyan

Write-Host ""
Write-Host "To enter Ubuntu/Ansible environment:" -ForegroundColor Yellow
Write-Host "    wsl -d Ubuntu" -ForegroundColor White

Write-Host ""
Write-Host "To check Ansible:" -ForegroundColor Yellow
Write-Host "    ansible --version" -ForegroundColor White

Write-Host ""
Write-Host "To test Ansible:" -ForegroundColor Yellow
Write-Host "    ansible localhost -m ping" -ForegroundColor White

Write-Host ""
```
