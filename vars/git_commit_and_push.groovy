def call(String credentialsId, String repoUrl, String branch = "main", List<String> files = [], String commitMessage = "CI: Update image tags [skip ci]") {
    
    if (!credentialsId || !repoUrl) {
        error("git_commit_and_push: credentialsId and repoUrl must not be empty.")
    }

    if (!files) {
        error("git_commit_and_push: files list must not be empty.")
    }

    echo "📦 Preparing to commit and push to ${repoUrl} on branch: ${branch}"

    withCredentials([gitUsernamePassword(credentialsId: credentialsId, gitToolName: 'Default')]) {
        sh """
            echo "Checking repository status:"
            git status

            echo "Fixing detached HEAD:"
            git checkout -B ${branch}
            
            echo "Configuring git identity:"
            git config user.email "jenkins@ci.local"
            git config user.name  "Jenkins CI"

            echo "Adding changes:"
            git add ${files.join(' ')}

            echo "Committing changes:"
            git diff --cached --quiet || git commit -m "${commitMessage}"

            echo "Pushing to GitHub:"
            git push ${repoUrl} ${branch}
        """
    }

    echo "✅ Successfully pushed to ${repoUrl} on branch: ${branch}"
}
