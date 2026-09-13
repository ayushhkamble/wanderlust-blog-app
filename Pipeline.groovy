pipeline {

    agent any

    environment {

        // SonarQube
        SONAR_HOME = tool "Sonar"

        // Docker Hub
        DOCKERHUB_USERNAME = "ayushkamble820"

        BACKEND_IMAGE = "${DOCKERHUB_USERNAME}/wanderlust-backend"
        FRONTEND_IMAGE = "${DOCKERHUB_USERNAME}/wanderlust-frontend"

        // AWS / EKS
        AWS_REGION = "eu-north-1"
        EKS_CLUSTER_NAME = "wanderlust-cluster"

        // Kubernetes
        K8S_NAMESPACE = "wanderlust"
    }

    stages {

        /*
         * 1. Clone source code
         */
        stage("Clone code from GitHub") {
            steps {
                git(
                    url: "https://github.com/ayushhkamble/wanderlust-blog-app.git",
                    branch: "devops"
                )
            }
        }


        /*
         * 2. SonarQube code analysis
         */
        stage("SonarQube Quality Analysis") {
            steps {

                withSonarQubeEnv("Sonar") {

                    sh """
                        $SONAR_HOME/bin/sonar-scanner \
                        -Dsonar.projectName=wanderlust \
                        -Dsonar.projectKey=wanderlust
                    """
                }
            }
        }


        /*
         * 3. OWASP dependency vulnerability scan
         */
        stage("OWASP Dependency Check") {
            steps {

                dependencyCheck(
                    additionalArguments: '--scan ./',
                    odcInstallation: 'Dependency-check'
                )

                dependencyCheckPublisher(
                    pattern: '**/dependency-check-report.xml'
                )
            }
        }


        /*
         * 4. SonarQube Quality Gate
         */
        stage("Sonar Quality Gate Scan") {
            steps {

                timeout(time: 2, unit: "MINUTES") {

                    waitForQualityGate(
                        abortPipeline: true
                    )
                }
            }
        }


        /*
         * 5. Trivy filesystem scan
         */
        stage("Trivy File System Scan") {
            steps {

                sh """
                    trivy fs \
                    --format table \
                    -o trivy-fs-report.html \
                    .
                """
            }
        }


        /*
         * 6. Build Docker images
         */
        stage("Build Docker Images") {
            steps {

                script {

                    echo "========================================="
                    echo "Building Backend Docker Image"
                    echo "========================================="

                    sh """
                        docker build \
                        -t ${BACKEND_IMAGE}:${BUILD_NUMBER} \
                        -t ${BACKEND_IMAGE}:latest \
                        ./backend
                    """


                    echo "========================================="
                    echo "Building Frontend Docker Image"
                    echo "========================================="

                    sh """
                        docker build \
                        -t ${FRONTEND_IMAGE}:${BUILD_NUMBER} \
                        -t ${FRONTEND_IMAGE}:latest \
                        ./frontend
                    """
                }
            }
        }


        /*
         * 7. Scan Docker images using Trivy
         */
        stage("Trivy Docker Image Scan") {
            steps {

                script {

                    echo "Scanning Backend Docker Image"

                    sh """
                        trivy image \
                        --format table \
                        ${BACKEND_IMAGE}:${BUILD_NUMBER}
                    """


                    echo "Scanning Frontend Docker Image"

                    sh """
                        trivy image \
                        --format table \
                        ${FRONTEND_IMAGE}:${BUILD_NUMBER}
                    """
                }
            }
        }


        /*
         * 8. Login and push images to Docker Hub
         */
        stage("Push Images to DockerHub") {
            steps {

                script {

                    withCredentials([
                        usernamePassword(
                            credentialsId: 'dockerhub-credentials',
                            usernameVariable: 'DOCKER_USERNAME',
                            passwordVariable: 'DOCKER_PASSWORD'
                        )
                    ]) {

                        echo "Logging in to Docker Hub"

                        sh '''
                            echo "$DOCKER_PASSWORD" | docker login \
                            --username "$DOCKER_USERNAME" \
                            --password-stdin
                        '''


                        echo "Pushing Backend Images"

                        sh """
                            docker push ${BACKEND_IMAGE}:${BUILD_NUMBER}
                            docker push ${BACKEND_IMAGE}:latest
                        """


                        echo "Pushing Frontend Images"

                        sh """
                            docker push ${FRONTEND_IMAGE}:${BUILD_NUMBER}
                            docker push ${FRONTEND_IMAGE}:latest
                        """


                        sh "docker logout"
                    }
                }
            }
        }


        /*
         * 9. Configure AWS/EKS access
         *
         * Jenkins uses AWS credentials stored
         * in Jenkins Credentials.
         */
        stage("Configure EKS Access") {
            steps {

                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     credentialsId: 'aws-credentials']
                ]) {

                    sh """
                        echo "Checking AWS identity..."

                        aws sts get-caller-identity

                        echo "Updating kubeconfig..."

                        aws eks update-kubeconfig \
                        --region ${AWS_REGION} \
                        --name ${EKS_CLUSTER_NAME}

                        echo "Checking EKS cluster..."

                        kubectl get nodes
                    """
                }
            }
        }


        /*
         * 10. Deploy Kubernetes manifests
         */
        stage("Deploy to Kubernetes") {
            steps {

                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     credentialsId: 'aws-credentials']
                ]) {

                    script {

                        echo "Applying Kubernetes manifests"

                        sh """
                            kubectl apply \
                            -f kubernetes/ \
                            -n ${K8S_NAMESPACE}
                        """


                        echo "Updating Backend Image"

                        sh """
                            kubectl set image \
                            deployment/backend-deployment \
                            backend=${BACKEND_IMAGE}:${BUILD_NUMBER} \
                            -n ${K8S_NAMESPACE}
                        """


                        echo "Updating Frontend Image"

                        sh """
                            kubectl set image \
                            deployment/frontend-deployment \
                            frontend=${FRONTEND_IMAGE}:${BUILD_NUMBER} \
                            -n ${K8S_NAMESPACE}
                        """
                    }
                }
            }
        }


        /*
         * 11. Verify deployment
         */
        stage("Verify Kubernetes Deployment") {
            steps {

                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     credentialsId: 'aws-credentials']
                ]) {

                    script {

                        echo "Checking Kubernetes Pods"

                        sh """
                            kubectl get pods \
                            -n ${K8S_NAMESPACE}
                        """


                        echo "Checking Kubernetes Deployments"

                        sh """
                            kubectl get deployments \
                            -n ${K8S_NAMESPACE}
                        """


                        echo "Checking Kubernetes Services"

                        sh """
                            kubectl get services \
                            -n ${K8S_NAMESPACE}
                        """


                        echo "Waiting for Backend rollout"

                        sh """
                            kubectl rollout status \
                            deployment/backend-deployment \
                            -n ${K8S_NAMESPACE} \
                            --timeout=5m
                        """


                        echo "Waiting for Frontend rollout"

                        sh """
                            kubectl rollout status \
                            deployment/frontend-deployment \
                            -n ${K8S_NAMESPACE} \
                            --timeout=5m
                        """


                        echo "Final Pod Status"

                        sh """
                            kubectl get pods \
                            -n ${K8S_NAMESPACE} \
                            -o wide
                        """
                    }
                }
            }
        }
    }


    /*
     * Post-build actions
     */
    post {

        always {

            archiveArtifacts(
                artifacts: '''
                    trivy-fs-report.html,
                    **/dependency-check-report.xml
                ''',
                allowEmptyArchive: true
            )
        }


        success {

            echo """
            =========================================
              WANDERLUST DEPLOYMENT SUCCESSFUL
            =========================================

            Jenkins Build:
            ${BUILD_NUMBER}

            Backend Image:
            ${BACKEND_IMAGE}:${BUILD_NUMBER}

            Frontend Image:
            ${FRONTEND_IMAGE}:${BUILD_NUMBER}

            Kubernetes Namespace:
            ${K8S_NAMESPACE}

            =========================================
            """
        }


        failure {

            echo """
            =========================================
              WANDERLUST PIPELINE FAILED
            =========================================

            Build:
            ${BUILD_NUMBER}

            Check the failed stage in Jenkins.

            =========================================
            """
        }
    }
}

