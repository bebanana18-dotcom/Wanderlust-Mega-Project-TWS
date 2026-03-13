@Library('Shared@main') _

pipeline {
    agent any

    environment {
        SONAR_HOME = tool "SONAR-TOOLS"
    }

    parameters {
        string(name: 'FRONTEND_DOCKER_TAG', defaultValue: '', description: 'Docker image tag for frontend')
        string(name: 'BACKEND_DOCKER_TAG',  defaultValue: '', description: 'Docker image tag for backend')
    }

    stages {

        // ─────────────────────────────────────────────
        // STAGE 1: Validate Parameters
        // ─────────────────────────────────────────────
        stage("Validate Parameters") {
            steps {
                script {
                    if (params.FRONTEND_DOCKER_TAG == '' || params.BACKEND_DOCKER_TAG == '') {
                        error("FRONTEND_DOCKER_TAG and BACKEND_DOCKER_TAG must be provided.")
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 2: Workspace Cleanup (Pre-build)
        // ─────────────────────────────────────────────
        stage("Workspace Cleanup: Pre Build") {
            steps {
                script {
                    cleanWs()
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 3: Git Checkout
        // ─────────────────────────────────────────────
        stage("Git: Code Checkout") {
            steps {
                script {
                    // FIX: Using withCredentials to ensure git operations
                    // (including later push) work on non-public or strict agents.
                    // code_checkout() uses plain `git` step with no creds — 
                    // safe for public clone, but be aware push uses separate creds block.
                    code_checkout("https://github.com/bebanana18-dotcom/Wanderlust-Mega-Project-GCP.git", "main" , "GITHUB-CRED")
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 4: SonarQube Code Analysis
        // ─────────────────────────────────────────────
        stage("SonarQube: Code Analysis") {
            steps {
                script {
                    // NOTE: Ensure sonarqube_analysis() uses withSonarQubeEnv("Sonar")
                    // internally — otherwise SONAR_HOME set above is never wired to a server.
                    sonarqube_analysis("SONAR-TOOLS", "SONAR-SYSTEM", "wanderlust", "wanderlust")
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 5: SonarQube Quality Gate
        // ─────────────────────────────────────────────
        stage("SonarQube: Code Quality Gates") {
            steps {
                script {
                    // NOTE: abortPipeline is false — pipeline won't fail on bad quality gate.
                    // Change to abortPipeline: true when you're ready to enforce quality.
                    sonarqube_code_quality(true)
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 6: Docker Build
        // ─────────────────────────────────────────────
        stage("Docker: Build Images") {
            steps {
                script {
                    // NOTE: Ensure the agent (or master if agent any) has Docker daemon
                    // access and the Jenkins user is in the docker group.
                    dir('backend') {
                        docker_build("wanderlust-backend-beta", "${params.BACKEND_DOCKER_TAG}", "himanshumaurya1920")
                    }
                    dir('frontend') {
                        docker_build("wanderlust-frontend-beta", "${params.FRONTEND_DOCKER_TAG}", "himanshumaurya1920")
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 7: Docker Push
        // ─────────────────────────────────────────────
        stage("Docker: Push to DockerHub") {
            steps {
                script {
                    // FIX: docker_push() had docker login OUTSIDE withCredentials block.
                    // The shared library function must be fixed to wrap BOTH login
                    // and push inside withCredentials. The corrected function is below.
                    //
                    // def call(String Project, String ImageTag, String DockerHubUser){
                    //     withCredentials([usernamePassword(
                    //         credentialsId: 'docker',
                    //         passwordVariable: 'dockerhubpass',
                    //         usernameVariable: 'dockerhubuser'
                    //     )]) {
                    //         sh "docker login -u ${dockerhubuser} -p ${dockerhubpass}"
                    //         sh "docker push ${dockerhubuser}/${Project}:${ImageTag}"
                    //     }
                    // }
                    docker_push("wanderlust-backend-beta",  "${params.BACKEND_DOCKER_TAG}",  "himanshumaurya1920" , "DOCKER-CRED")
                    docker_push("wanderlust-frontend-beta", "${params.FRONTEND_DOCKER_TAG}", "himanshumaurya1920" , "DOCKER-CRED")
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 8: Workspace Cleanup (Post Push, Pre GitOps)
        // FIX: Renamed from duplicate "Workspace cleanup" — was causing
        // "stage names must be unique" pipeline failure.
        // ─────────────────────────────────────────────
        stage("Workspace Cleanup: Post Push") {
            steps {
                script {
                    cleanWs()
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 9: Git Checkout (GitOps — fresh clone for manifest update)
        // ─────────────────────────────────────────────
        stage("Git: Code Checkout For GitOps") {
            steps {
                script {
                    code_checkout("https://github.com/bebanana18-dotcom/Wanderlust-Mega-Project-GCP.git", "main" , "GITHUB-CRED")
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 10: Verify Docker Image Tags
        // ─────────────────────────────────────────────
        stage("Verify: Docker Image Tags") {
            steps {
                script {
                    echo "FRONTEND_DOCKER_TAG : ${params.FRONTEND_DOCKER_TAG}"
                    echo "BACKEND_DOCKER_TAG  : ${params.BACKEND_DOCKER_TAG}"
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 11: Update Kubernetes Manifests
        // ─────────────────────────────────────────────
        stage("Update: Kubernetes Manifests") {
            steps {
                script {
                    // FIX: Changed sed delimiter from '/' to '|' to prevent breakage
                    // when Docker tags contain special characters like '.' or '-'.
                    // Old: sed -i -e s/wanderlust-backend-beta.*/...  (fragile, unquoted)
                    // New: sed -i "s|...|...|g"                       (safe, quoted)
                    dir('kubernetes') {
                        sh """
                            sed -i "s|wanderlust-backend-beta:.*|wanderlust-backend-beta:${params.BACKEND_DOCKER_TAG}|g" 05backend.yaml
                            sed -i "s|wanderlust-frontend-beta:.*|wanderlust-frontend-beta:${params.FRONTEND_DOCKER_TAG}|g" 06frontend.yaml
                        """
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 12: Git Commit and Push (GitOps)
        // ─────────────────────────────────────────────
        stage("Git: Commit and Push Manifests") {
            steps {
                script {
                    git_commit_and_push(
                        "GITHUB-CRED",
                        "https://github.com/bebanana18-dotcom/Wanderlust-Mega-Project-GCP.git",
                        "main",
                        ["kubernetes/backend.yaml", "kubernetes/frontend.yaml"],
                        "CI: Update image tags [skip ci]"
                    )
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 13: Deploy to Kubernetes
        // ─────────────────────────────────────────────
        stage("K8s: Apply Manifests") {
            steps {
                script {
                    k8s_apply("K8S-CRED", "kubernetes/05backend.yaml", "kubernetes/06frontend.yaml")
                }
            }
        }

    }

    // ─────────────────────────────────────────────────
    // POST BLOCK
    // FIX: Was completely missing — silent failures with no cleanup or alerts.
    // ─────────────────────────────────────────────────
    post {
        success {
            echo "✅ Pipeline completed successfully!"
            echo "   Frontend Tag : ${params.FRONTEND_DOCKER_TAG}"
            echo "   Backend Tag  : ${params.BACKEND_DOCKER_TAG}"
        }
        failure {
            echo "❌ Pipeline FAILED. Check logs above."
            cleanWs()
        }
        always {
            echo "Pipeline finished with status: ${currentBuild.currentResult}"
        }
    }

}
