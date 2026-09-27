$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jenkinsfilePath = Join-Path $repositoryRoot 'Jenkinsfile'

if (-not (Test-Path -LiteralPath $jenkinsfilePath -PathType Leaf)) {
    Write-Error 'Missing file: Jenkinsfile'
    exit 1
}

$jenkinsfile = Get-Content -Raw -LiteralPath $jenkinsfilePath
$failures = [System.Collections.Generic.List[string]]::new()

$requiredPatterns = @(
    'pipeline\s*\{',
    'disableConcurrentBuilds',
    'buildDiscarder',
    'timeout\(',
    'timestamps\(\)',
    "stage\('Checkout'\)",
    "stage\('Validate'\)",
    "stage\('Build and Unit Test'\)",
    "stage\('SAST'\)",
    "stage\('SCA'\)",
    "stage\('Infrastructure and Container Checks'\)",
    "stage\('Deploy to Test Environment'\)",
    "stage\('Verify Deployment'\)",
    "stage\('DAST'\)",
    "stage\('Collect Evidence'\)",
    'archiveArtifacts',
    'checkout\s+scm',
    'env\.TESTED_COMMIT\s*=\s*sh\s*\(',
    "script:\s*'git rev-parse HEAD'",
    'returnStdout:\s*true',
    'currentBuild\.description\s*=\s*"\$\{env\.BRANCH_NAME\}\s+\$\{env\.TESTED_COMMIT\.take\(12\)\}"',
    'BRANCH_NAME',
    'TESTED_COMMIT',
    'scripts/ci/validate-repository\.sh',
    'tests/ci/test-jenkins-posix-runtime\.sh',
    'scripts/ci/run-tests\.sh',
    'scripts/ci/run-sast\.sh',
    'scripts/ci/run-sca\.sh',
    'scripts/ci/run-terraform-checks\.sh',
    'scripts/ci/run-iac-scan\.sh',
    'scripts/ci/build-image\.sh',
    'scripts/ci/scan-image\.sh'
)

foreach ($pattern in $requiredPatterns) {
    if ($jenkinsfile -notmatch $pattern) {
        $failures.Add("Jenkinsfile does not contain required pattern: $pattern")
    }
}

if ($jenkinsfile -match 'terraform\s+apply') {
    $failures.Add('Jenkinsfile must not run terraform apply in the initial pipeline.')
}
if ($jenkinsfile -match '(?i)aws_access_key_id|aws_secret_access_key') {
    $failures.Add('Jenkinsfile must not contain AWS credential fields.')
}

$expectedStages = @('Checkout', 'Validate', 'Build and Unit Test', 'SAST', 'SCA',
    'Infrastructure and Container Checks', 'Deploy to Test Environment', 'Verify Deployment', 'DAST', 'Collect Evidence')
$stageMatches = [regex]::Matches($jenkinsfile, "stage\('([^']+)'\)")
$actualStages = @($stageMatches | ForEach-Object { $_.Groups[1].Value })
if (($actualStages -join '|') -cne ($expectedStages -join '|')) {
    $failures.Add('Security gate stages must occur exactly once in the required order.')
}
$stageBodies = @{}
for ($index = 0; $index -lt $stageMatches.Count; $index++) {
    $end = if ($index + 1 -lt $stageMatches.Count) { $stageMatches[$index + 1].Index } else { $jenkinsfile.IndexOf('post {') }
    $stageBodies[$actualStages[$index]] = $jenkinsfile.Substring($stageMatches[$index].Index, $end - $stageMatches[$index].Index)
}
foreach ($stage in @('Deploy to Test Environment', 'Verify Deployment', 'DAST')) {
    $body = [string]$stageBodies[$stage]
    foreach ($pattern in @('when\s*\{\s*anyOf\s*\{', "branch 'main'", "branch pattern: 'feature/\*', comparator: 'GLOB'", 'changeRequest\(\)')) {
        if ($body -notmatch $pattern) { $failures.Add("${stage}: missing explicit main/feature/PR deployment gate: $pattern") }
    }
    if ($body -match 'fileExists|returnStatus|catchError|\|\|\s*(true|:)') {
        $failures.Add("${stage}: must not skip or swallow a required deployment/DAST failure.")
    }
}
$stageContracts = @{
    'Validate' = @('sh ''\./scripts/ci/validate-repository\.sh \.' , 'sh ''tests/ci/test-jenkins-posix-runtime\.sh''' )
    'Build and Unit Test' = @('sh ''\./scripts/ci/run-tests\.sh \.' )
    'SAST' = @('sh ''\./scripts/ci/run-sast\.sh \.' )
    'SCA' = @('sh ''\./scripts/ci/run-sca\.sh \.' )
    'Infrastructure and Container Checks' = @('(?s)sh ''\./scripts/ci/run-terraform-checks\.sh \..*sh ''\./scripts/ci/run-iac-scan\.sh \..*sh ''\./scripts/ci/build-image\.sh \..*sh ''\./scripts/ci/scan-image\.sh \.' )
    'Deploy to Test Environment' = @('if scripts/cd/trigger-deployment\.sh "\$TESTED_COMMIT"', '\.ci/reports/deployment\.log', 'test -s \.ci/deployment\.env')
    'Verify Deployment' = @('env\.DAST_TARGET_URL\s*=\s*sh\(', 'script: ''\./scripts/ci/verify-deployment\.sh \.ci/deployment\.env "\$TESTED_COMMIT"''', 'returnStdout: true', '\)\.trim\(\)')
    'DAST' = @('sh ''\./scripts/ci/run-dast\.sh "\$DAST_TARGET_URL"''')
    'Collect Evidence' = @('MMIV_BUILD_RESULT=\$\{currentBuild.currentResult\}', 'scripts/ci/collect-evidence\.sh')
}
foreach ($stage in $stageContracts.Keys) {
    foreach ($pattern in $stageContracts[$stage]) {
        if ([string]$stageBodies[$stage] -notmatch $pattern) { $failures.Add("${stage}: missing call contract: $pattern") }
    }
}
$post = ($jenkinsfile -split 'post\s*\{', 2)[-1]
foreach ($pattern in @('always\s*\{', 'scripts/ci/collect-evidence\.sh', 'MMIV_BUILD_RESULT=\$\{currentBuild.currentResult\}',
        "junit testResults: '\*\*/target/surefire-reports/\*\.xml', allowEmptyResults: true",
        "archiveArtifacts artifacts: '\.ci/reports/\*\*/\*,\*\*/target/surefire-reports/\*\*/\*'")) {
    if ($post -notmatch $pattern) { $failures.Add("Post/always must publish evidence even after failure: $pattern") }
}
if ($post -notmatch '(?s)finally\s*\{\s*try\s*\{.*?scripts/ci/collect-evidence\.sh.*?\}\s*finally\s*\{\s*archiveArtifacts') {
    $failures.Add('Post/always must archive evidence from a nested finally even when metadata collection fails.')
}
if ($jenkinsfile -match 'returnStatus|catchError|\|\|\s*(true|:)') {
    $failures.Add('Jenkins security gates must propagate nonzero statuses.')
}
$ownedFiles = @((Get-Item $jenkinsfilePath)) + @(Get-ChildItem (Join-Path $repositoryRoot 'scripts/ci') -Filter '*.sh')
foreach ($file in $ownedFiles) {
    $source = Get-Content -Raw -LiteralPath $file.FullName
    if ($source -match '(?im)\bgit\s+(push|merge)\b|\bgh\s+pr\s+(merge|review\s+--approve)\b|api\.github\.com/[^\s]*pulls/[^\s]*/merge|\bterraform\s+apply\b|\b(ansible-playbook|kubectl|helm)\b|\baws\s+(ecs|ec2|deploy)\b|\bdocker\s+(compose|stack)\b') {
        $failures.Add("$($file.Name): forbidden repository mutation or application deployment logic.")
    }
}
if ($jenkinsfile -match '/project') {
    $failures.Add('Jenkinsfile must not copy source from the local /project mount.')
}
if ($jenkinsfile -match 'tar\s+--exclude') {
    $failures.Add('Jenkinsfile must use SCM checkout instead of creating a local tar snapshot.')
}
if ($jenkinsfile -match 'env\.GIT_COMMIT\.take\(') {
    $failures.Add('Jenkinsfile must build its description from TESTED_COMMIT because checkout scm does not guarantee env.GIT_COMMIT.')
}
if ($jenkinsfile -notmatch 'if\s*\(\s*!env\.TESTED_COMMIT\s*\)') {
    $failures.Add('Jenkinsfile must fail validation when TESTED_COMMIT is empty.')
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Output 'PASS: Jenkinsfile contract is present.'
exit 0
