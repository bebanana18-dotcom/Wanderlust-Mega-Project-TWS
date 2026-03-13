def call(String kubeConfigCredId, String... manifestFiles) {

    if (!kubeConfigCredId) {
        error("k8s_apply: kubeConfigCredId must not be empty.")
    }

    if (!manifestFiles) {
        error("k8s_apply: At least one manifest file must be provided.")
    }

    echo "🚀 Applying Kubernetes manifests: ${manifestFiles.join(', ')}"

    withCredentials([file(credentialsId: kubeConfigCredId, variable: 'KUBECONFIG')]) {
        manifestFiles.each { file ->
            echo "📄 Applying: ${file}"
            def applyStatus = sh(
                script: "kubectl apply -f ${file} --kubeconfig=${KUBECONFIG}",
                returnStatus: true
            )
            if (applyStatus != 0) {
                error("k8s_apply: Failed to apply ${file}. Exit code: ${applyStatus}")
            }
        }
    }

    echo "✅ All manifests applied successfully."
}
```

---

## Jenkins Credential Setup

You need to add your kubeconfig file in Jenkins:
```
Jenkins > Manage Jenkins > Credentials
  → Add Credential
  → Kind: Secret file
  → File: your ~/.kube/config
  → ID: KUBECONFIG-CRED
```

