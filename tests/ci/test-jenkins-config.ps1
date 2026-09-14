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
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'FROM jenkins/jenkins:lts-jdk21' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'FROM eclipse-temurin:25-jdk AS application-jdk' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'COPY --from=application-jdk /opt/java/openjdk /opt/jdk-25' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = '\bmaven\b' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'jenkins-plugin-cli' },
    @{ File = 'jenkins/Dockerfile'; Content = $dockerfile; Pattern = 'docker-ce-cli' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'configuration-as-code' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'workflow-aggregator' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'workflow-multibranch' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'job-dsl' },
    @{ File = 'jenkins/plugins.txt'; Content = $plugins; Pattern = 'timestamper' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = '127\.0\.0\.1:8080:8080' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = '/var/run/docker\.sock:/var/run/docker\.sock' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'healthcheck:' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'jenkins_home:' },
    @{ File = 'jenkins/docker-compose.yml'; Content = $compose; Pattern = 'JENKINS_ADMIN_PASSWORD' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "multibranchPipelineJob\('mmiv-ci'\)" },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'https://github\.com/est19oox/Media-Migration-and-Integrity-Validation-\.git' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = 'periodicFolderTrigger' },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "interval\('2m'\)" },
    @{ File = 'jenkins/casc.yaml'; Content = $casc; Pattern = "scriptPath\('Jenkinsfile'\)" },
    @{ File = 'jenkins/.env.example'; Content = $exampleEnvironment; Pattern = 'JENKINS_ADMIN_PASSWORD=' },
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
if ($dockerfile -match '(?m)^ENV\s+JAVA_HOME=/opt/jdk-25') {
    $failures.Add('The Jenkins controller must not use the application JDK 25 as its global JAVA_HOME.')
}
if ($casc -match "pipelineJob\('mmiv-pipeline'\)") {
    $failures.Add('JCasC still defines the obsolete local snapshot pipeline job.')
}
if ($casc -match '/project/Jenkinsfile') {
    $failures.Add('JCasC must load Jenkinsfile through SCM, not from /project.')
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
