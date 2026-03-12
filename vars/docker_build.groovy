// vars/docker_build.groovy
// FIX: Added input validation — fails fast if any argument is empty.
// FIX: Added --no-cache flag as optional param to avoid stale layer bugs.
// FIX: Added explicit error handling with clear message if docker build fails.
// FIX: Echoes the full image name being built for easier log tracing.

def call(String ProjectName, String ImageTag, String DockerHubUser, Boolean noCache = false) {
    if (!ProjectName || !ImageTag || !DockerHubUser) {
        error("docker_build: ProjectName, ImageTag, and DockerHubUser must not be empty.")
    }

    def fullImageName = "${DockerHubUser}/${ProjectName}:${ImageTag}"
    def noCacheFlag   = noCache ? "--no-cache" : ""

    echo "🔨 Building Docker image: ${fullImageName}"

    def buildStatus = sh(
        script: "docker build ${noCacheFlag} -t ${fullImageName} .",
        returnStatus: true
    )

    if (buildStatus != 0) {
        error("docker_build: Build FAILED for image ${fullImageName}. Check Dockerfile and build context.")
    }

    echo "✅ Docker image built successfully: ${fullImageName}"
}
