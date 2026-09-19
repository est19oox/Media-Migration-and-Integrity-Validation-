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
                    currentBuild.description = "${env.BRANCH_NAME} ${env.GIT_COMMIT.take(12)}"
                }
                sh 'chmod +x scripts/ci/*.sh'
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
            }
        }

        stage('Unit Test') {
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

        stage('Terraform Validation') {
            steps {
                sh './scripts/ci/run-terraform-checks.sh .'
            }
        }

        stage('IaC Scan') {
            steps {
                sh './scripts/ci/run-iac-scan.sh .'
            }
        }

        stage('Container Build') {
            steps {
                sh './scripts/ci/build-image.sh .'
            }
        }

        stage('Container Scan') {
            steps {
                sh './scripts/ci/scan-image.sh .'
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '.ci/reports/**/*', allowEmptyArchive: true, fingerprint: true
        }
        cleanup {
            sh 'rm -f .ci/image-ref'
        }
    }
}
