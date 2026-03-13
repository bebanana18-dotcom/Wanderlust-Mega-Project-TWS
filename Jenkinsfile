@Library('Shared@main') _

pipeline {
    agent any

    environment {
        SONAR_HOME = tool "SONAR-TOOLS"

        GCP_PROJECT  = "piyush-gcp"
        GAR_LOCATION = "us-central1"
        GAR_REPO     = "wanderlust"
        GAR_REGISTRY = "${GAR_LOCATION}-docker.pkg.dev"
    }

    // ─── NO PARAMETERS BLOCK ───────────────────────────────────────────────
    // Tags are gone. SHAs are fetched from Docker after build.
    // No human input = no human error. Revolutionary concept.


    stages {

        // ─────────────────────────────────────────────
        // STAGE 1: Validate Parameters
        // ─────────────────────────────────────────────
        // no-parameter NO STAGE-1
        

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
                    dir('backend') {
                        env.BACKEND_SHA = docker_build(
                            "wanderlust-backend-beta", "latest", 
                            "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}"
                        )
                        if (!env.BACKEND_SHA) {
                            error("Failed to extract SHA for backend image. Build may have failed silently.")
                        }
                        echo "Backend SHA: ${env.BACKEND_SHA}"
                    }
        
                    dir('frontend') {
                        env.FRONTEND_SHA = docker_build(
                            "wanderlust-frontend-beta", "latest",
                            "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}"
                        )
                        if (!env.FRONTEND_SHA) {
                            error("Failed to extract SHA for frontend image. Build may have failed silently.")
                        }
                        echo "Frontend SHA: ${env.FRONTEND_SHA}"
                        //RESULT : us-central1-docker.pkg.dev/piyush-gcp/wanderlust/wanderlust-backend-beta@sha256:abc123...
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
                    docker_push("wanderlust-backend-beta",  "latest",  "himanshumaurya1920" , "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
                    docker_push("wanderlust-frontend-beta", "latest",  "himanshumaurya1920" , "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
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
        // Stage 10 "Verify Docker Image Tags" (we'll verify SHAs instead)

        
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
                            sed -i "s|image:.*wanderlust-backend-beta.*|image: ${env.BACKEND_SHA}|g"  05backend.yaml
                            sed -i "s|image:.*wanderlust-frontend-beta.*|image: ${env.FRONTEND_SHA}|g" 06frontend.yaml
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
                        ["kubernetes/05backend.yaml", "kubernetes/06frontend.yaml"],
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
            echo "   Frontend SHA : ${env.FRONTEND_SHA}"
            echo "   Backend  SHA : ${env.BACKEND_SHA}"
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
