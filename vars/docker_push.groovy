// vars/docker_push.groovy
// STEP 2: Migrated from DockerHub to Google Artifact Registry
// REMOVED: withCredentials block — no username/password needed
// REMOVED: docker login / docker logout — gcloud credential helper handles auth transparently
// REMOVED: DockerHubUser parameter — GAR uses full registry path instead
// ADDED:   Registry parameter — full GAR registry path passed in
// NOTE:    Auth prereq — gcloud auth configure-docker us-central1-docker.pkg.dev
//          must be run once on the Jenkins VM as the jenkins user

def call(String ProjectName, String ImageTag, String Registry) {
    if (!ProjectName || !ImageTag || !Registry) {
        error("docker_push: ProjectName, ImageTag, and Registry must not be empty.")
    }

    def fullImageName = "${Registry}/${ProjectName}:${ImageTag}"
    echo "Pushing Docker image: ${fullImageName}"

    try {
        def pushStatus = sh(
            script: "docker push ${fullImageName}",
            returnStatus: true
        )
        if (pushStatus != 0) {
            error("docker_push: Push FAILED for image ${fullImageName}. Check GAR permissions and registry path.")
        }
    } catch (Exception e) {
        error("docker_push: Unexpected error pushing ${fullImageName}: ${e.message}")
    }

    echo "Docker image pushed successfully: ${fullImageName}"
}
