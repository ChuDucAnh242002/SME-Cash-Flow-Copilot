pipeline {
    agent { label 'cashflow-ci' }
    options {
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
        timestamps()
        skipDefaultCheckout()
    }
    environment {
        APP_URL = 'http://127.0.0.1:8087'
        APP_USER = 'developer'
        APP_PASSWORD = 'local-demo-password'
    }
    stages {
        stage('Prepare') {
            steps {
                checkout scm
                dir('backend/target/surefire-reports') { deleteDir() }
                dir('frontend/test-results') { deleteDir() }
                script {
                    def jobKey = sh(script: 'printf "%s" "$JOB_NAME" | sha256sum | cut -c1-12', returnStdout: true).trim()
                    env.CI_IMAGE_TAG = "build-${env.BUILD_NUMBER}-${jobKey}"
                    env.CI_PROJECT = "cashflow-ci-${jobKey}-${env.BUILD_NUMBER}"
                }
                sh 'java -version && node --version && docker compose version && kubectl version --client'
            }
        }
        stage('Verify') {
            steps {
                dir('backend') { sh 'mvn -B verify' }
                dir('frontend') { sh 'npm ci && npm test && npm run build' }
                sh '''
                    bash -n scripts/k8s-db.sh scripts/ci-compose.sh
                    node --test scripts/*.test.mjs
                    kubectl kustomize deploy/k8s > /dev/null
                    kubectl kustomize deploy/k8s-demo > /dev/null
                '''
            }
        }
        stage('Synthetic deployment') {
            steps {
                sh 'scripts/ci-compose.sh up --build -d --wait'
                sh 'node scripts/smoke.mjs'
                dir('frontend') {
                    sh 'npx playwright install chromium && npm run test:e2e'
                }
            }
        }
    }
    post {
        always {
            script {
                try {
                    junit testResults: 'backend/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
                    archiveArtifacts artifacts: 'frontend/test-results/**', allowEmptyArchive: true
                } finally {
                    if (env.CI_PROJECT) {
                        sh 'scripts/ci-compose.sh down --volumes'
                    }
                }
            }
        }
    }
}
