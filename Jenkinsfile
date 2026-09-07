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
                sh '''
                    set -eu
                    tar --exclude=.git --exclude=docs/superpowers -C /project -cf - . | tar -xf -
                    chmod +x scripts/ci/*.sh
                '''
            }
        }

        stage('Validate') {
            steps {
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
