// Captures the REAL GAR manifest digest after push.
//
// WHY NOT --iidfile ?
// --iidfile writes the local Image Config digest (hash of image config JSON).
// GAR stores images by Manifest digest (hash of manifest JSON).
// These are fundamentally different and will NEVER match.
//
// PROOF:
// --iidfile : sha256:1eef1a8f422ecad922bc627f0c2bda2a6b87b8b7bafaf383ac3f5ef7bebb5595  (local, useless)
// GAR digest: sha256:c437e1cd6233afa5391998b39cdeaea935b283e1adc847d66635cbcaf2c5b954  (remote, real)
//
// HOW:
// docker inspect --format='{{index .RepoDigests 0}}' reads the manifest digest
// that GAR confirmed back during the push handshake.
// awk -F'@sha256:' strips the registry path, leaving only the clean hash.
//
// RESULT:
// env.BACKEND_SHA / env.FRONTEND_SHA will contain the exact digest
// GAR serves — safe to use in values.yaml and Helm --set flags.

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
    return "sha256:${digest.replaceAll('^sha256:', '')}"  // normalize — always exactly one prefix
}
