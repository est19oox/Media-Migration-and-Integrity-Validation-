pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                script {
                    env.TESTED_COMMIT = sh(
                        script: 'git rev-parse HEAD',
                        returnStdout: true
                    ).trim()
                    currentBuild.description = "${env.BRANCH_NAME} ${env.TESTED_COMMIT.take(12)}"
                }
                sh 'chmod +x scripts/ci/*.sh scripts/cd/*.sh tests/ci/*.sh'
            }
        }

        stage('Validate') {
            steps {
                script {
                    if (!env.TESTED_COMMIT) {
                        error('TESTED_COMMIT was not captured after SCM checkout.')
                    }
                }
                sh './scripts/ci/validate-repository.sh .'
                sh 'tests/ci/test-jenkins-posix-runtime.sh'
            }
        }

        stage('Build and Unit Test') {
            steps {
                sh './scripts/ci/run-tests.sh .'
            }
        }

        stage('SAST') {
            steps {
                sh './scripts/ci/run-sast.sh .'
            }
        }

        stage('SCA') {
            steps {
                sh './scripts/ci/run-sca.sh .'
            }
        }

        stage('Infrastructure and Container Checks') {
            steps {
                sh './scripts/ci/run-terraform-checks.sh .'
                sh './scripts/ci/run-iac-scan.sh .'
                sh './scripts/ci/build-image.sh .'
                sh './scripts/ci/scan-image.sh .'
            }
        }

        // Feature branches and every pull request must pass the same deployment
        // gate as main, including PRs originating from differently named branches.
        stage('Deploy to Test Environment') {
            when {
                anyOf {
                    branch 'main'
                    branch pattern: 'feature/*', comparator: 'GLOB'
                    changeRequest()
                }
            }
            steps {
                sh(label: 'Trigger deployment', script: '''
                    set -eu
                    mkdir -p .ci/reports
                    rm -f .ci/deployment.env
                    if [ ! -f scripts/cd/trigger-deployment.sh ]; then
                        echo 'FAIL: Missing required deployment script: scripts/cd/trigger-deployment.sh' > .ci/reports/deployment.log
                        cat .ci/reports/deployment.log >&2
                        exit 1
                    fi
                    if scripts/cd/trigger-deployment.sh "$TESTED_COMMIT" > .ci/reports/deployment.log 2>&1; then
                        deployment_status=0
                    else
                        deployment_status=$?
                    fi
                    cat .ci/reports/deployment.log
                    if [ "$deployment_status" -ne 0 ]; then
                        exit "$deployment_status"
                    fi
                    test -s .ci/deployment.env || {
                        echo 'FAIL: Deployment did not create .ci/deployment.env' | tee -a .ci/reports/deployment.log >&2
                        exit 1
                    }
                ''')
            }
        }

        stage('Verify Deployment') {
            when {
                anyOf {
                    branch 'main'
                    branch pattern: 'feature/*', comparator: 'GLOB'
                    changeRequest()
                }
            }
            steps {
                script {
                    env.DAST_TARGET_URL = sh(
                        script: './scripts/ci/verify-deployment.sh .ci/deployment.env "$TESTED_COMMIT"',
                        returnStdout: true
                    ).trim()
                }
            }
        }

        stage('DAST') {
            when {
                anyOf {
                    branch 'main'
                    branch pattern: 'feature/*', comparator: 'GLOB'
                    changeRequest()
                }
            }
            steps {
                sh './scripts/ci/run-dast.sh "$DAST_TARGET_URL"'
            }
        }

        stage('Collect Evidence') {
            steps {
                script {
                    withEnv(["MMIV_BUILD_RESULT=${currentBuild.currentResult}"]) {
                        sh 'sh scripts/ci/collect-evidence.sh'
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                // Each publisher is attempted even if an earlier publisher fails.
                try {
                    junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
                } catch (Exception publicationError) {
                    currentBuild.result = 'FAILURE'
                    throw publicationError
                } finally {
                    try {
                        withEnv(["MMIV_BUILD_RESULT=${currentBuild.currentResult}"]) {
                            sh 'sh scripts/ci/collect-evidence.sh'
                        }
                    } finally {
                        archiveArtifacts artifacts: '.ci/reports/**/*,**/target/surefire-reports/**/*', allowEmptyArchive: true, fingerprint: true
                    }
                }
            }
        }
        cleanup {
            sh 'if [ -f .ci/test-container ]; then docker rm -f "$(cat .ci/test-container)" || true; fi; rm -f .ci/image-ref .ci/test-container .ci/deployment.env' 
        }
    }
}
