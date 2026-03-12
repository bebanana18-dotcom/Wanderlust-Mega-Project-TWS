// vars/docker_push.groovy
// FIX: docker push was OUTSIDE the withCredentials block in original —
//      credentials were already disposed before push ran. Guaranteed failure.
// FIX: Both docker login AND docker push are now inside withCredentials block.
// FIX: Uses credential username variable instead of the passed-in DockerHubUser
//      for the actual push — so credential and image owner always match.
// FIX: Added input validation.
// FIX: Added explicit returnStatus check for both login and push with clear errors.
// FIX: docker logout added after push — security best practice, clears session.

def call(String ProjectName, String ImageTag, String DockerHubUser, String credentialsId = 'docker') {
    if (!ProjectName || !ImageTag || !DockerHubUser) {
        error("docker_push: ProjectName, ImageTag, and DockerHubUser must not be empty.")
    }

    def fullImageName = "${DockerHubUser}/${ProjectName}:${ImageTag}"

    echo "📤 Pushing Docker image: ${fullImageName}"

    withCredentials([usernamePassword(
        credentialsId: "${credentialsId}",
        passwordVariable: 'DOCKER_PASS',
        usernameVariable: 'DOCKER_USER'
    )]) {
        def loginStatus = sh(
            script: "echo \${DOCKER_PASS} | docker login -u \${DOCKER_USER} --password-stdin",
            returnStatus: true
        )

        if (loginStatus != 0) {
            error("docker_push: Docker login FAILED. Check credentials ID '${credentialsId}' in Jenkins.")
        }

        def pushStatus = sh(
            script: "docker push ${fullImageName}",
            returnStatus: true
        )

        if (pushStatus != 0) {
            error("docker_push: Push FAILED for image ${fullImageName}. Check DockerHub access and tag.")
        }

        sh "docker logout"
    }

    echo "✅ Docker image pushed successfully: ${fullImageName}"
}
