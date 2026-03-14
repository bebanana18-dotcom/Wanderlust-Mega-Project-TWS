def call(String ProjectName, String ImageTag, String Registry) {
    if (!ProjectName || !ImageTag || !Registry) {
        error("docker_push: ProjectName, ImageTag, and Registry must not be empty.")
    }

    def fullImageName = "${Registry}/${ProjectName}:${ImageTag}"
    echo "Pushing Docker image: ${fullImageName}"

    def pushStatus = sh(script: "docker push ${fullImageName}", returnStatus: true)
    if (pushStatus != 0) {
        error("docker_push: Push FAILED for ${fullImageName}. Check GAR permissions.")
    }

    // ✅ RepoDigests = GAR manifest digest (confirmed during push handshake)
    // ✅ awk strips everything before sha256: leaving only the clean hash
    def digest = sh(
        script: """
            docker inspect --format='{{index .RepoDigests 0}}' ${fullImageName} \
            | awk -F'@sha256:' '{print \$2}'
        """,
        returnStdout: true
    ).trim()

    if (!digest) {
        error("docker_push: Could not retrieve GAR manifest digest for ${fullImageName}.")
    }

    echo "✅ GAR digest for ${fullImageName}: sha256:${digest}"
    return digest
}
