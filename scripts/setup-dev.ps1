# ============================================================
# Media Migration and Integrity Validation
# Developer Environment Setup Script
# ============================================================

$ErrorActionPreference = "Continue"

Write-Host ""
Write-Host "=========================================="
Write-Host " Developer Environment Setup"
Write-Host "=========================================="
Write-Host ""

# ------------------------------------------------------------
# Check Winget
# ------------------------------------------------------------

Write-Host "[1/4] Checking Winget..."

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: Winget is not available."
    Write-Host "Install/update App Installer from Microsoft Store and try again."
    exit 1
}

Write-Host "Winget is available."
Write-Host ""

# ------------------------------------------------------------
# Check Git
# ------------------------------------------------------------

Write-Host "[2/4] Checking Git..."

if (Get-Command git -ErrorAction SilentlyContinue) {
    Write-Host "Git is already installed:"
    git --version
}
else {
    Write-Host "Git not found. Installing Git..."

    winget install --id Git.Git -e --accept-package-agreements --accept-source-agreements

    if ($LASTEXITCODE -ne 0) {
        Write-Host "WARNING: Git installation may not have completed successfully."
    }
}

Write-Host ""

# ------------------------------------------------------------
# Check Terraform
# ------------------------------------------------------------

Write-Host "[3/4] Checking Terraform..."

if (Get-Command terraform -ErrorAction SilentlyContinue) {
    Write-Host "Terraform is already installed:"
    terraform version
}
else {
    Write-Host "Terraform not found. Installing Terraform..."

    winget install --id Hashicorp.Terraform -e --accept-package-agreements --accept-source-agreements

    # Winget commonly exposes command aliases through this directory.
    $wingetLinks = "$env:LOCALAPPDATA\Microsoft\WinGet\Links"

    if (Test-Path $wingetLinks) {
        if ($env:Path -notlike "*$wingetLinks*") {
            $env:Path += ";$wingetLinks"
        }
    }

    if (Get-Command terraform -ErrorAction SilentlyContinue) {
        Write-Host "Terraform installed successfully:"
        terraform version
    }
    else {
        Write-Host "Terraform was installed but is not available in this terminal yet."
        Write-Host "Restart VS Code after this script finishes."
    }
}

Write-Host ""

# ------------------------------------------------------------
# Check AWS CLI
# ------------------------------------------------------------

Write-Host "[4/4] Checking AWS CLI..."

if (Get-Command aws -ErrorAction SilentlyContinue) {
    Write-Host "AWS CLI is already installed:"
    aws --version
}
else {
    Write-Host "AWS CLI not found. Installing AWS CLI..."

    winget install --id Amazon.AWSCLI -e --accept-package-agreements --accept-source-agreements

    # Standard AWS CLI v2 installation location on Windows.
    $awsCliPath = "C:\Program Files\Amazon\AWSCLIV2"

    if (Test-Path $awsCliPath) {
        if ($env:Path -notlike "*$awsCliPath*") {
            $env:Path += ";$awsCliPath"
        }
    }

    if (Get-Command aws -ErrorAction SilentlyContinue) {
        Write-Host "AWS CLI installed successfully:"
        aws --version
    }
    else {
        Write-Host "AWS CLI was installed but is not available in this terminal yet."
        Write-Host "Restart VS Code after this script finishes."
    }
}

# ------------------------------------------------------------
# Final verification
# ------------------------------------------------------------

Write-Host ""
Write-Host "=========================================="
Write-Host " Installation Verification"
Write-Host "=========================================="

Write-Host ""

if (Get-Command git -ErrorAction SilentlyContinue) {
    Write-Host "[OK] Git"
    git --version
}
else {
    Write-Host "[MISSING] Git"
}

Write-Host ""

if (Get-Command terraform -ErrorAction SilentlyContinue) {
    Write-Host "[OK] Terraform"
    terraform version
}
else {
    Write-Host "[RESTART REQUIRED] Terraform"
}

Write-Host ""

if (Get-Command aws -ErrorAction SilentlyContinue) {
    Write-Host "[OK] AWS CLI"
    aws --version
}
else {
    Write-Host "[RESTART REQUIRED] AWS CLI"
}

Write-Host ""
Write-Host "=========================================="
Write-Host " Setup Complete"
Write-Host "=========================================="
Write-Host ""
Write-Host "If Terraform or AWS CLI was newly installed,"
Write-Host "close ALL VS Code windows and reopen VS Code."
Write-Host ""
Write-Host "After restarting, verify with:"
Write-Host "  git --version"
Write-Host "  terraform version"
Write-Host "  aws --version"
Write-Host ""
Write-Host "AWS credentials are intentionally NOT configured"
Write-Host "by this script."
Write-Host ""
