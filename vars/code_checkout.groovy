// vars/code_checkout.groovy
// FIX: Added credentialsId support for private repos and authenticated push operations.
// FIX: Added validation so pipeline fails fast with a clear message instead of
//      a cryptic git error if URL or branch is accidentally empty.

def call(String GitUrl, String GitBranch, String credentialsId = 'Github-cred') {
    if (!GitUrl || !GitBranch) {
        error("code_checkout: GitUrl and GitBranch must not be empty.")
    }

    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${GitBranch}"]],
        userRemoteConfigs: [[
            url: "${GitUrl}",
            credentialsId: "${credentialsId}"
        ]],
        extensions: [
            [$class: 'CloneOption', depth: 1, shallow: true, noTags: false],
            [$class: 'CleanBeforeCheckout']
        ]
    ])

    echo "✅ Code checked out successfully from ${GitUrl} on branch: ${GitBranch}"
}
