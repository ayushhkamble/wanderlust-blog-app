pipeline {

    agent any

    environment {
        SONAR_HOME = tool "Sonar"

        AWS_REGION  = "eu-north-1"
        EKS_CLUSTER = "wanderlust-cluster"

        DOCKERHUB_CREDENTIALS = credentials("dockerhub-credentials")

        BACKEND_IMAGE  = "ayushkamble820/wanderlust-backend"
        FRONTEND_IMAGE = "ayushkamble820/wanderlust-frontend"
    }

    stages {

        stage("Clone Code from GitHub") {
            steps {
                git branch: "devops",
                    url: "https://github.com/ayushhkamble/wanderlust-blog-app.git"
            }
        }

        stage("SonarQube Analysis") {
            steps {
                withSonarQubeEnv("Sonar") {
                    sh """
                        ${SONAR_HOME}/bin/sonar-scanner \
                        -Dsonar.projectKey=wanderlust \
                        -Dsonar.projectName=wanderlust \
                        -Dsonar.sources=backend,frontend
                    """
                }
            }
        }

        stage("Quality Gate") {
            steps {
                timeout(time: 5, unit: "MINUTES") {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage("OWASP Dependency Check") {
            steps {

                dependencyCheck(
                    additionalArguments: '--scan ./ --format XML --format HTML --nvdApiKey $NVD_API_KEY',
                    odcInstallation: 'Dependency-check'
                )

                dependencyCheckPublisher(
                    pattern: '**/dependency-check-report.xml'
                )
            }
        }

        stage("Trivy Filesystem Scan") {
            steps {
                sh '''
                    trivy fs \
                    --scanners vuln \
                    --exit-code 0 \
                    --format table \
                    .
                '''
            }
        }

        stage("Build Backend Docker Image") {
            steps {
                sh """
                    docker build \
                    -t ${BACKEND_IMAGE}:${BUILD_NUMBER} \
                    -t ${BACKEND_IMAGE}:latest \
                    ./backend
                """
            }
        }

        stage("Build Frontend Docker Image") {
            steps {
                sh """
                    docker build \
                    -t ${FRONTEND_IMAGE}:${BUILD_NUMBER} \
                    -t ${FRONTEND_IMAGE}:latest \
                    ./frontend
                """
            }
        }

        stage("Trivy Backend Image Scan") {
            steps {
                sh """
                    trivy image \
                    --scanners vuln \
                    --exit-code 0 \
                    --format table \
                    ${BACKEND_IMAGE}:${BUILD_NUMBER}
                """
            }
        }

        stage("Trivy Frontend Image Scan") {
            steps {
                sh """
                    trivy image \
                    --scanners vuln \
                    --exit-code 0 \
                    --format table \
                    ${FRONTEND_IMAGE}:${BUILD_NUMBER}
                """
            }
        }

        stage("Push Images to Docker Hub") {
            steps {
                sh '''
                    echo "$DOCKERHUB_CREDENTIALS_PSW" | docker login \
                    -u "$DOCKERHUB_CREDENTIALS_USR" \
                    --password-stdin

                    docker push ${BACKEND_IMAGE}:${BUILD_NUMBER}
                    docker push ${BACKEND_IMAGE}:latest

                    docker push ${FRONTEND_IMAGE}:${BUILD_NUMBER}
                    docker push ${FRONTEND_IMAGE}:latest

                    docker logout
                '''
            }
        }

        stage("Deploy to Kubernetes") {
            steps {

                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     credentialsId: 'aws-credentials']
                ]) {

                    sh '''
                        echo "===== CONNECTING TO EKS ====="

                        aws sts get-caller-identity

                        aws eks update-kubeconfig \
                        --region ${AWS_REGION} \
                        --name ${EKS_CLUSTER}

                        echo "===== EKS NODES ====="

                        kubectl get nodes

                        echo "===== APPLYING KUBERNETES MANIFESTS ====="

                        kubectl apply -f kubernetes/

                        echo "===== UPDATING BACKEND IMAGE ====="

                        kubectl set image deployment/backend-deployment \
                        backend=${BACKEND_IMAGE}:${BUILD_NUMBER} \
                        -n wanderlust

                        echo "===== UPDATING FRONTEND IMAGE ====="

                        kubectl set image deployment/frontend-deployment \
                        frontend=${FRONTEND_IMAGE}:${BUILD_NUMBER} \
                        -n wanderlust

                        echo "===== KUBERNETES RESOURCES ====="

                        kubectl get all -n wanderlust
                    '''
                }
            }
        }

        stage("Verify Kubernetes Deployment") {
            steps {

                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     credentialsId: 'aws-credentials']
                ]) {

                    sh '''
                        echo "===== BACKEND ROLLOUT ====="

                        kubectl rollout status deployment/backend-deployment \
                        -n wanderlust \
                        --timeout=300s

                        echo "===== FRONTEND ROLLOUT ====="

                        kubectl rollout status deployment/frontend-deployment \
                        -n wanderlust \
                        --timeout=300s

                        echo "===== MONGODB ROLLOUT ====="

                        kubectl rollout status deployment/mongo-deployment \
                        -n wanderlust \
                        --timeout=300s

                        echo "===== REDIS ROLLOUT ====="

                        kubectl rollout status deployment/redis-deployment \
                        -n wanderlust \
                        --timeout=300s
                    '''
                }
            }
        }

        stage("Verify ALB Ingress") {
            steps {

                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     credentialsId: 'aws-credentials']
                ]) {

                    sh '''
                        echo "===== PODS ====="

                        kubectl get pods -n wanderlust -o wide

                        echo "===== SERVICES ====="

                        kubectl get svc -n wanderlust

                        echo "===== INGRESS ====="

                        kubectl get ingress -n wanderlust

                        echo "===== INGRESS DETAILS ====="

                        kubectl describe ingress -n wanderlust
                    '''
                }
            }
        }
    }

    post {

        success {
            echo "============================================"
            echo "Wanderlust deployment completed successfully"
            echo "Docker images pushed successfully"
            echo "Kubernetes deployment completed"
            echo "ALB Ingress configuration applied"
            echo "============================================"
        }

        failure {
            echo "============================================"
            echo "Wanderlust deployment failed"
            echo "Check the Jenkins console log"
            echo "============================================"
        }

        always {
            sh '''
                docker image prune -f || true
            '''
        }
    }
}
