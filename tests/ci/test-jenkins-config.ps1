$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Read-RequiredFile {
    param([Parameter(Mandatory)] [string] $RelativePath)
    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $failures.Add("Missing file: $RelativePath")
        return ''
    }
    return Get-Content -Raw -LiteralPath $path
}

$dockerfile = Read-RequiredFile 'jenkins/Dockerfile'
$compose = Read-RequiredFile 'jenkins/docker-compose.yml'
$plugins = Read-RequiredFile 'jenkins/plugins.txt'
$casc = Read-RequiredFile 'jenkins/casc.yaml'
$exampleEnvironment = Read-RequiredFile 'jenkins/.env.example'
$gitignore = Read-RequiredFile '.gitignore'
$gitattributes = Read-RequiredFile '.gitattributes'

$expectations = @(
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'FROM jenkins/jenkins:2\.568\.3-lts-jdk21@sha256:c1e4c349365f6d16d88595b2c5f7e8ff39b8ae1d061f62420bac193b4b9616d0' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'FROM eclipse-temurin:25\.0\.4_7-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2 AS application-jdk' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'COPY --from=application-jdk /opt/java/openjdk /opt/jdk-25' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = '\bmaven\b' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'ARG TRIVY_VERSION=0\.74\.0' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'ARG TRIVY_AMD64_SHA256=cf1e32ec8d4d8823e023096a28cadb14f5b5123ce03f201fb633c5b76aa712dd' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'ARG TRIVY_ARM64_SHA256=14fae2604eab589fd544224ef56b8b8a44266872c43b259bd36cfde81428acc8' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'dpkg --print-architecture' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'amd64\).*TRIVY_RELEASE_ARCH=64bit' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'arm64\).*TRIVY_RELEASE_ARCH=ARM64' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'github\.com/aquasecurity/trivy/releases/download/v\$\{TRIVY_VERSION\}/\$\{TRIVY_PACKAGE\}' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'sha256sum --check --strict' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'jenkins-plugin-cli --latest=false' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'docker-ce-cli' },
    @{ File = 'jenkins/docker-compose.yml'; Content = (Read-RequiredFile 'jenkins/docker-compose.yml'); Pattern = 'path:\s+\$\{MMIV_JENKINS_ENV_FILE:-\.env\}' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = '127\.0\.0\.1:\$\{JENKINS_PORT:-18080\}:8080' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'JENKINS_EXTERNAL_URL:\s+http://localhost:\$\{JENKINS_PORT:-18080\}/' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'url:\s+"?\$\{JENKINS_EXTERNAL_URL\}"?' },
    @{ File = 'jenkins/.env.example'; Content = $exampleEnvironment; Pattern = '(?m)^JENKINS_PORT=18080\r?$' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'configuration-as-code' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'workflow-aggregator' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'workflow-multibranch' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'github-branch-source' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'job-dsl' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'timestamper' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = '/var/run/docker\.sock:/var/run/docker\.sock' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'healthcheck:' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'jenkins_home:' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'JENKINS_ADMIN_PASSWORD' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'GITHUB_USERNAME' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'GITHUB_TOKEN' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'GITHUB_REPOSITORY_URL' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'id:\s*[''"]?github-pat' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'username:\s*"\$\{GITHUB_USERNAME\}"' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'password:\s*"\$\{GITHUB_TOKEN\}"' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "credentialsId\('github-pat'\)" },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "multibranchPipelineJob\('mmiv-ci'\)" },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'github\s*\{' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "repositoryUrl\('\$\{GITHUB_REPOSITORY_URL\}'\)" },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'gitHubBranchDiscovery\s*\{' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'periodicFolderTrigger' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "interval\('2m'\)" },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "scriptPath\('Jenkinsfile'\)" },
    @{ File = 'jenkins/.env.example'; Content = $exampleEnvironment; Pattern = 'JENKINS_ADMIN_PASSWORD=' },
    @{ File = 'jenkins/.env.example'; Content = $exampleEnvironment; Pattern = 'GITHUB_USERNAME=' },
    @{ File = 'jenkins/.env.example'; Content = $exampleEnvironment; Pattern = 'GITHUB_TOKEN=' },
    @{ File = 'jenkins/.env.example'; Content = $exampleEnvironment; Pattern = 'GITHUB_REPOSITORY_URL=https://github\.com/est19oox/Media-Migration-and-Integrity-Validation-\.git' },
    @{ File = '.gitignore'; Content = $gitignore; Pattern = 'jenkins/\.env' },
    @{ File = '.gitignore'; Content = $gitignore; Pattern = '\.ci/' },
    @{ File = '.gitattributes'; Content = $gitattributes; Pattern = '\*\.sh\s+text\s+eol=lf' },
    @{ File = '.gitattributes'; Content = $gitattributes; Pattern = 'Jenkinsfile\s+text\s+eol=lf' }
)

foreach ($expectation in $expectations) {
    if ($expectation.Content -notmatch $expectation.Pattern) {
        $failures.Add("$($expectation.File) does not contain required pattern: $($expectation.Pattern)")
    }
}

$combined = "$dockerfile`n$compose`n$casc"
if ($combined -match 'AKIA[0-9A-Z]{16}') {
    $failures.Add('Jenkins configuration contains an AWS access key-like value.')
}
if ($combined -match '(?im)^\s*(password|secret)\s*[:=]\s*["'']?(?!\$\{)[^"''$<{\s][^\r\n]+$') {
    $failures.Add('Jenkins configuration appears to contain a plaintext credential.')
}
if ($casc -match 'excludeClientIPFromCrumb') {
    $failures.Add('JCasC uses the unsupported DefaultCrumbIssuer attribute excludeClientIPFromCrumb.')
}

$pluginLines = $plugins -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
foreach ($pluginLine in $pluginLines) {
    if ($pluginLine -notmatch '^[a-z0-9][a-z0-9-]*:[^\s:]+$') {
        $failures.Add("jenkins/plugins.txt contains an unpinned plugin entry: $pluginLine")
    }
}
$securityDependencyPins = @(
    'git-client:6.6.1',
    'github:1.47.0',
    'jakarta-mail-api:2.1.5-1',
    'pipeline-build-step:601.v6d4c6d1a_9dc7',
    'pipeline-groovy-lib:806.v408277b_33d1d',
    'script-security:1429.v0810f1b_530f5'
)
foreach ($securityDependencyPin in $securityDependencyPins) {
    if ($pluginLines -notcontains $securityDependencyPin) {
        $failures.Add("jenkins/plugins.txt is missing the security-reviewed dependency pin: $securityDependencyPin")
    }
}
if ($dockerfile -match '(?m)^ENV\s+JAVA_HOME=/opt/jdk-25') {
    $failures.Add('The Jenkins controller must not use the application JDK 25 as its global JAVA_HOME.')
}
if ($dockerfile -match 'apt-get install[^\r\n]*trivy=\$\{TRIVY_VERSION\}') {
    $failures.Add('Trivy must not be pinned through the rolling APT index; install the verified immutable release package.')
}
if ($compose -match '127\.0\.0\.1:8080:8080') {
    $failures.Add('Docker Compose must not expose Jenkins on the commonly occupied host port 8080.')
}
if ($dockerfile -match '(?m)^FROM\s+(jenkins/jenkins:lts-jdk21|eclipse-temurin:25-jdk)(\s|$)') {
    $failures.Add('Jenkins Dockerfile must not use floating base-image tags.')
}
if ($casc -match "pipelineJob\('mmiv-pipeline'\)") {
    $failures.Add('JCasC still defines the obsolete local snapshot pipeline job.')
}
if ($casc -match '/project/Jenkinsfile') {
    $failures.Add('JCasC must load Jenkinsfile through SCM, not from /project.')
}
if ($casc -match "new File\('/project/Jenkinsfile'\)") {
    $failures.Add('JCasC must not define a local /project Jenkinsfile job.')
}
if ($compose -match '\.\./:/project:ro') {
    $failures.Add('Compose must not mount the local repository as the CI source.')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: Jenkins container configuration contract is present.'
exit 0
