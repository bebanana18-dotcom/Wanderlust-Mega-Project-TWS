def call(String ProjectName, String ImageTag, String DockerHubUser, Boolean noCache = false) {
    if (!ProjectName || !ImageTag || !DockerHubUser) {
        error("docker_build: ProjectName, ImageTag, and DockerHubUser must not be empty.")
    }

    def fullImageName = "${DockerHubUser}/${ProjectName}:${ImageTag}"
    def noCacheFlag   = noCache ? "--no-cache" : ""

    echo "🔨 Building Docker image: ${fullImageName}"

    sh "docker build ${noCacheFlag} --iidfile imageid.txt -t ${fullImageName} ."
    def sha = sh(
        script: "cat imageid.txt | sed 's/sha256://'",
        returnStdout: true
    ).trim()
    
    if (!sha) { error("Failed to get SHA for ${fullImageName}") }
    
   
    echo "Built ${fullImageName} -> ${sha}"
    return sha

}
