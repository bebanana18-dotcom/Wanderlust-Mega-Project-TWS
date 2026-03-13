// vars/sonarqube_analysis.groovy
// FIX: Wrapped execution in withSonarQubeEnv() — without this, SONAR_HOME is set
//      but never wired to a SonarQube server, so analysis runs blind/fails silently.
// FIX: Added input validation.
// FIX: Used SONAR_HOME from environment to explicitly call sonar-scanner binary —
//      avoids PATH issues on agents where sonar-scanner isn't globally installed.
// FIX: Added -Dsonar.sourceEncoding=UTF-8 — prevents encoding warnings/failures
//      on non-ASCII codebases.

def call(String SonarTool, String SonarServer, String ProjectKey, String ProjectName) {
    if (!SonarTool  || !SonarServer || !ProjectKey || !ProjectName) {
        error("sonarqube_analysis: SonarTool, ProjectKey, and ProjectName must not be empty.")
    }

    def scannerHome = tool "${SonarTool}"

    echo "🔍 Running SonarQube analysis for project: ${ProjectName} (key: ${ProjectKey})"

    withSonarQubeEnv("${SonarServer}") {
        sh """
            ${scannerHome}/bin/sonar-scanner \
                -Dsonar.projectKey=${ProjectKey} \
                -Dsonar.projectName=${ProjectName} \
                -Dsonar.sources=. \
                -Dsonar.sourceEncoding=UTF-8
        """
    }

    echo "✅ SonarQube analysis submitted for: ${ProjectName}"
}
