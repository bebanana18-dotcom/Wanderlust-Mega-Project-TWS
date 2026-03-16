@Library('Shared@main') _

pipeline {
    agent any

    // Jenkins pipeline safety and observability settings.
    //
    // timeout(30 MINUTES)
    //   Prevents builds from hanging indefinitely if a stage gets stuck.
    //
    // buildDiscarder(logRotator(numToKeepStr: '2'))
    //   Keeps only the last 2 builds to control disk usage on the Jenkins server. (ideally 6-10)
    //
    // disableConcurrentBuilds()
    //   Ensures only one pipeline execution runs at a time.
    //   This prevents concurrent builds from pushing to the same branch
    //   simultaneously, reducing the risk of Git race conditions.
    //
    // timestamps()
    //   Adds timestamps to every log line to make debugging and pipeline
    //   performance analysis easier.
    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()       // ✅ prevents two builds running simultaneously
                                        //    ties directly into Problem 3 race condition!
        timestamps()                    // ✅ adds timestamp to every log line
    }

    environment {
   

        // ── SonarQube ────────────────────────────────────────────
        SONAR_HOME        = tool "SONAR-TOOLS"
        SONAR_TOOL        = "SONAR-TOOLS"
        SONAR_SERVER      = "SONAR-SYSTEM"
        SONAR_PROJECT_KEY = "wanderlust"

        GCP_PROJECT  = "piyush-gcp"
        GAR_LOCATION = "us-central1"
        GAR_REPO     = "wanderlust-repo"
        GAR_REGISTRY = "${GAR_LOCATION}-docker.pkg.dev"

        // ── Image Names ──────────────────────────────────────────
        BACKEND_IMAGE  = "wanderlust-backend-beta"
        FRONTEND_IMAGE = "wanderlust-frontend-beta"
    
        // ── Git ──────────────────────────────────────────────────
        GIT_REPO_URL   = "https://github.com/bebanana18-dotcom/Wanderlust-Mega-Project-GCP.git"
        GIT_BRANCH     = "main"
        GITHUB_CRED    = "GITHUB-CRED"
    
        // ── Kubernetes / Helm ────────────────────────────────────
        K8S_CRED       = "K8S-CRED"
        HELM_CHART     = "./helm/wanderlust"
        K8S_NAMESPACE  = "wanderlust"
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
                    code_checkout(env.GIT_REPO_URL , env.GIT_BRANCH , env.GITHUB_CRED )
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT[0..6]}"
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 3: SonarQube Code Analysis
        // ─────────────────────────────────────────────
        stage("SonarQube: Code Analysis") {
            steps {
                script {
                    // sonarqube_analysis("SONAR-TOOLS", "SONAR-SYSTEM", "wanderlust", "wanderlust")
                    sonarqube_analysis(
                        env.SONAR_TOOL,
                        env.SONAR_SERVER,
                        env.SONAR_PROJECT_KEY,
                        env.SONAR_PROJECT_KEY   // project name = project key, same value
                    )
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
                        docker_build(env.BACKEND_IMAGE, env.IMAGE_TAG,
                            "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
                    }
                    dir('frontend') {
                        docker_build(env.FRONTEND_IMAGE, env.IMAGE_TAG,
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
                    env.BACKEND_SHA = docker_push(env.BACKEND_IMAGE, env.IMAGE_TAG,
                        "${env.GAR_REGISTRY}/${env.GCP_PROJECT}/${env.GAR_REPO}")
        
                    env.FRONTEND_SHA = docker_push(env.FRONTEND_IMAGE, env.IMAGE_TAG,
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
        // Update image digests in values.yaml using yq.
        //
        // We pass BACKEND_SHA and FRONTEND_SHA as environment variables using
        // `withEnv`, then use `strenv()` so yq reads them directly from the shell
        // environment.
        //
        // This avoids Groovy string interpolation inside shell commands, which can
        // cause quoting issues or unexpected substitutions in Jenkins pipelines.
        stage("Update values.yaml for helm") {
            steps {
                script {
                    withEnv([
                        "BACKEND_SHA=${env.BACKEND_SHA}",
                        "FRONTEND_SHA=${env.FRONTEND_SHA}"
                    ]) {
                        sh """
                            # ✅ Fail loudly if values.yaml is missing — never let yq create a blank one
                            test -f values.yaml || { echo "❌ values.yaml not found at repo root!"; exit 1; }
                    
                            yq e '.backend.image.digest  = strenv(BACKEND_SHA)'  -i values.yaml
                            yq e '.frontend.image.digest = strenv(FRONTEND_SHA)' -i values.yaml
                    
                            # ✅ Confirm the values were actually written
                            echo "--- values.yaml digest section after update ---"
                            yq e '.backend.image.digest, .frontend.image.digest' values.yaml
                        """
                    }
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
                        env.GITHUB_CRED,
                        env.GIT_REPO_URL,
                        env.GIT_BRANCH,
                        ["values.yaml"],
                        "CI: Update image tags [skip ci]"
                    )
                }
            }
        }

        // ─────────────────────────────────────────────
        // STAGE 9: Helm Deploy to GKE (INTERNALLY : "K8s: Apply Manifests")
        //
        // --atomic   : auto-rollback if deploy fails
        // --timeout  : wait max 5m for pods to be ready
        // No --values flag needed — symlink in helm/wanderlust/values.yaml
        // points to repo root values.yaml automatically
        // ─────────────────────────────────────────────
        stage("Helm: Deploy to GKE") {
            steps {
                script {
                    withCredentials([file(credentialsId: 'K8S-CRED', variable: 'KUBECONFIG')]) {
                        sh """
                            helm upgrade --install wanderlust ${env.HELM_CHART} \
                                --namespace ${env.K8S_NAMESPACE} \
                                --create-namespace \
                                --atomic \
                                --timeout 5m \
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
            cleanWs()        // ✅ runs on EVERY build — success, failure, or abort
        }
    }

}
