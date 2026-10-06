# Points this PowerShell session at a JDK 21 in %USERPROFILE%\.jdks (e.g. a portable Temurin build).
# Usage (dot-source it so the variables stay set):   . .\scripts\env.ps1
$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) {
    Write-Error "No jdk-21* folder found in $env:USERPROFILE\.jdks. Install JDK 21 (https://adoptium.net) and set JAVA_HOME instead."
    return
}
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
