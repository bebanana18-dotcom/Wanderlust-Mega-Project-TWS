@Library('Shared@main') _

pipeline {
    agent any

    environment {
        SONAR_HOME = tool "SONAR-TOOLS"

        GCP_PROJECT  = "piyush-gcp"
        GAR_LOCATION = "us-central1"
        GAR_REPO     = "wanderlust-repo"
        GAR_REGISTRY = "${GAR_LOCATION}-docker.pkg.dev"
    }


    stages {


        

        // ─────────────────────────────────────────────
        // STAGE 1: Workspace Cleanup (Pre-build)
        // ─────────────────────────────────────────────
        stage("Workspace Cleanup: Pre Build") {
            steps {
                script {
                    cleanWs()
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 2: Git Checkout
        // ─────────────────────────────────────────────
        stage("Git: Code Checkout") {
            steps {
                script {
                    code_checkout("https://github.com/bebanana18-dotcom/Wanderlust-Mega-Project-GCP.git", "main" , "GITHUB-CRED")
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 3: SonarQube Code Analysis
        // ─────────────────────────────────────────────
        stage("SonarQube: Code Analysis") {
            steps {
                script {
                    sonarqube_analysis("SONAR-TOOLS", "SONAR-SYSTEM", "wanderlust", "wanderlust")
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 4: SonarQube Quality Gate
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
        // STAGE 5: Docker Build
        // ─────────────────────────────────────────────
        stage("Docker: Build Images") {
            steps {
                script {
                    dir('backend') {
                        docker_build("wanderlust-backend-beta", "latest",
                            "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
                    }
                    dir('frontend') {
                        docker_build("wanderlust-frontend-beta", "latest",
                            "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 6: Docker Push to GOOGLE ARTIFACT REGISTRY
        // ─────────────────────────────────────────────
        stage("Docker: Push to GOOGLE ARTIFACT REGISTRY") {
            steps {
                script {
                    // ✅ NOW capture digest AFTER push — this is the real GAR manifest digest
                    env.BACKEND_SHA = docker_push("wanderlust-backend-beta", "latest",
                        "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
        
                    env.FRONTEND_SHA = docker_push("wanderlust-frontend-beta", "latest",
                        "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
        
                    if (!env.BACKEND_SHA || !env.FRONTEND_SHA) {
                        error("Failed to retrieve GAR digest after push. Refusing to deploy phantom images.")
                    }
                }
            }
        }



        
        // ─────────────────────────────────────────────
        // STAGE 7: Update values.yaml + Git Push
        // ─────────────────────────────────────────────
        stage("Update values.yaml for helm") {
            steps {
                script {
                    sh """
                        sed -i '/^backend:/,/^frontend:/ s|digest:.*|digest: "sha256:${env.BACKEND_SHA}"|' values.yaml
                        sed -i '/^frontend:/,/^[a-z]/ s|digest:.*|digest: "sha256:${env.FRONTEND_SHA}"|' values.yaml
                    """
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 8: Git Commit and Push (GitOps)
        // Records the deployed digests in git for audit trail.
        // Helm will read values.yaml from local workspace — no re-pull needed.
        // ─────────────────────────────────────────────
        stage("Git: Commit and Push Manifests") {
            steps {
                script {
                    git_commit_and_push(
                        "GITHUB-CRED",
                        "https://github.com/bebanana18-dotcom/Wanderlust-Mega-Project-GCP.git",
                        "main",
                        ["values.yaml"],
                        "CI: Update image tags [skip ci]"
                    )
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 8: Helm Deploy to GKE (INTERNALLY : "K8s: Apply Manifests")
        //
        // --atomic   : auto-rollback if deploy fails
        // --timeout  : wait max 3m for pods to be ready
        // No --values flag needed — symlink in helm/wanderlust/values.yaml
        // points to repo root values.yaml automatically
        // ─────────────────────────────────────────────
        stage("Helm: Deploy to GKE") {
            steps {
                script {
                    withCredentials([file(credentialsId: 'K8S-CRED', variable: 'KUBECONFIG')]) {
                        sh """
                            helm upgrade --install wanderlust ./helm/wanderlust \
                                --set backend.image.digest=sha256:${env.BACKEND_SHA} \
                                --set frontend.image.digest=sha256:${env.FRONTEND_SHA} \
                                --namespace wanderlust \
                                --create-namespace \
                                --atomic \
                                --timeout 3m \
                                --kubeconfig \$KUBECONFIG
                        """
                    }
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
