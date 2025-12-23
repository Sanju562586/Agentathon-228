$env:MONGO_URI = "mongodb+srv://sanjaykumardupati6_db_user:SanjayKumar@lt.vjjea71.mongodb.net/?appName=GuardianGatekeeper"
Write-Host "🌍 Starting Backend Locally with Cloud DB..." -ForegroundColor Cyan

# Run the app
& "C:\Program Files\Go\bin\go.exe" run main.go
