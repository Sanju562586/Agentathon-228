# Guardian Mesh - GCP Deployment Helper
# Usage: .\deploy_gcp.ps1 -MongoURI "mongodb+srv://..."

param (
    [Parameter(Mandatory=$false)]
    [string]$MongoURI = "mongodb+srv://sanjaykumardupati6_db_user:<password>@lt.vjjea71.mongodb.net/?appName=GuardianGatekeeper"
)

Write-Host "🌩️ Deploying Guardian Mesh to Google Cloud Run..." -ForegroundColor Cyan

# Check for gcloud
if (-not (Get-Command "gcloud" -ErrorAction SilentlyContinue)) {
    Write-Error "❌ 'gcloud' CLI is not installed. Please install it first: https://cloud.google.com/sdk/docs/install"
    exit 1
}

# Configuration
$ServiceName = "guardian-gatekeeper"
$Region = "us-central1"
$ProjectID = "guardian-mesh-app-01"
$BackendPath = Resolve-Path "$PSScriptRoot\..\backend"

Write-Host "🚀 Deploying Service: $ServiceName"
Write-Host "🔹 Project: $ProjectID"
Write-Host "📂 Source: $BackendPath"

# --- NEW: Local Build Step ---
Write-Host "🚧 Building Cloud Binary (Locally)..." -ForegroundColor Yellow
Push-Location $BackendPath
try {
    $env:GOOS = "linux"
    $env:GOARCH = "amd64"
    $env:CGO_ENABLED = "0"
    
    # Clean previous
    if (Test-Path "app_linux") { Remove-Item "app_linux" }

    # Build (Use absolute path & target main.go)
    & "C:\Program Files\Go\bin\go.exe" build -mod=vendor -o app_linux main.go
    
    if (-not (Test-Path "app_linux")) {
        Write-Error "❌ Local Build Failed! 'app_linux' binary not generated."
        exit 1
    }
    Write-Host "✅ Local Build Success!" -ForegroundColor Green
} finally {
    # Reset env vars
    $env:GOOS = ""
    $env:GOARCH = ""
    $env:CGO_ENABLED = ""
    Pop-Location
}

gcloud run deploy $ServiceName `
    --project $ProjectID `
    --source $BackendPath `
    --platform managed `
    --region $Region `
    --allow-unauthenticated `
    --quiet `
    --set-env-vars MONGO_URI="$MongoURI"

if ($?) {
    Write-Host "✅ Deployment Complete!" -ForegroundColor Green
    Write-Host "👉 Copy the Service URL printed above and update AppConfig.kt" -ForegroundColor Yellow
} else {
    Write-Error "❌ Deployment Failed."
}
