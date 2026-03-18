# wanderlust 2

# GKE Private Cluster with VPC — Full Setup Guide

A step-by-step guide to create a **production-ready private GKE cluster** on Google Cloud Platform.

Access to the cluster is only through a **Jenkins VM that acts as a Bastion Host** — nothing is exposed directly to the internet.

---

## What We Are Building

```
Your Laptop
    │
    │  SSH via IAP: IDENTITY-AWARE-PROXY (gcloud compute ssh)
    │  Browser → Jenkins UI (port 8080)
    ▼
Jenkins Master VM  ←── GitHub Webhooks (port 8080)
    │  (Public IP: 146.148.57.84)
    │  (Inside vpc-gke — has internal IP 10.0.x.x)
    │
    │  kubectl / gcloud (private VPC network)
    ▼
GKE Private Cluster API Server
    │
    ▼
Private Nodes (no public IPs)
    │
    ├── Pods (10.1.0.0/16)
    └── Services (10.2.0.0/20)
          │
          ▼
    Cloud NAT (outbound internet for nodes)
```

**Key points:**

- Nodes have **no public IPs** — completely private
- You **cannot SSH directly** into Jenkins — only via Google IAP
- Jenkins is the **only entry point** to reach the cluster from outside
- Cloud NAT lets private nodes pull container images from the internet

---

## Prerequisites

Before running any command, make sure you have:

- `gcloud` CLI installed and logged in
    
    ```bash
    gcloud auth login
    gcloud config set project piyush-gcp
    ```
    
- `gcloud` beta components installed
    
    ```bash
    gcloud components install beta
    ```
    
- `kubectl` installed
- These GCP APIs enabled
    
    ```bash
    gcloud services enable \\
      container.googleapis.com \\
      compute.googleapis.com \\
      monitoring.googleapis.com \\
      logging.googleapis.com \\
      iap.googleapis.com \\
      --project piyush-gcp
    ```
    
- A reserved static IP named `jenkins-master-ip` already created in `us-central1`

---

## Execution Order

> **Important:** Run these steps in order. Each step depends on the previous one.
> 

```
Step 1 → Create VPC
Step 2 → Create Subnet (with pod + service IP ranges)
Step 3 → Create Cloud Router + Cloud NAT
Step 4 → Create GKE Node Service Account + IAM permissions
Step 5 → Create Jenkins Bastion VM
Step 6 → Create Firewall Rules for Jenkins
Step 7 → Create GKE Private Cluster
Step 8 → Connect kubectl via Jenkins
```

---

## Step 1 — Create the VPC

We create a **custom VPC** called `vpc-gke`.

We use `custom` subnet mode so we have full control over IP ranges — GCP won't auto-create anything.

```bash
# =============================================================
# Step 1 — Create the VPC
# =============================================================
gcloud compute networks create vpc-gke \\
  --project=piyush-gcp \\
  --subnet-mode=custom \\
  --bgp-routing-mode=regional \\
  --bgp-best-path-selection-mode=legacy
```

---

## Step 2 — Create the Subnet

We create `subnet-1` inside `vpc-gke` with **three IP ranges**:

| Range | Purpose | CIDR |
| --- | --- | --- |
| Primary | Node IPs | `10.0.0.0/20` (4094 nodes) |
| `gke-pods` | Pod IPs | `10.1.0.0/16` (65536 pods) |
| `gke-services` | Service IPs | `10.2.0.0/20` (4094 services) |

We also enable **VPC Flow Logs** to capture network traffic for debugging and security.

```bash
# =============================================================
# Step 2 — Create the subnet with secondary ranges
# =============================================================
gcloud compute networks subnets create subnet-1 \\
  --project=piyush-gcp \\
  --network=vpc-gke \\
  --region=us-central1 \\
  --range=10.0.0.0/20 \\
  --stack-type=IPV4_ONLY \\
  --secondary-range=gke-pods=10.1.0.0/16 \\
  --secondary-range=gke-services=10.2.0.0/20 \\
  --enable-flow-logs \\
  --logging-aggregation-interval=interval-5-sec \\
  --logging-flow-sampling=0.5 \\
  --logging-metadata=include-all
```

---

## Step 3 — Create Cloud Router + Cloud NAT

This is the **most commonly forgotten step**.

Private nodes have no public IPs — without Cloud NAT they cannot reach the internet at all.

Without this, pods will be stuck in `ImagePullBackOff` because nodes cannot pull container images.

```
Private Node → needs to pull nginx:latest
             → no public IP on node
             → looks for NAT gateway
             → Cloud NAT found  → request goes out ✅
             → no Cloud NAT     → request dropped  ❌ → ImagePullBackOff
```

```bash
# =============================================================
# Step 3 — Cloud Router + Cloud NAT (required for private nodes)
# =============================================================

# Cloud Router must exist before Cloud NAT can be created
gcloud compute routers create nat-router \\
  --project=piyush-gcp \\
  --network=vpc-gke \\
  --region=us-central1

# Cloud NAT — gives private nodes outbound internet access
gcloud compute routers nats create nat-config \\
  --project=piyush-gcp \\
  --router=nat-router \\
  --region=us-central1 \\
  --auto-allocate-nat-external-ips \\
  --nat-all-subnet-ip-ranges \\
  --enable-logging
```

---

## Step 4 — Create GKE Node Service Account

We do **not** use the default Compute Engine service account — it has too many permissions.

We create a dedicated SA with only what GKE nodes actually need.

```bash
# =============================================================
# Step 4 — Create dedicated GKE node service account
# =============================================================
gcloud iam service-accounts create gke-node-sa \\
  --display-name "GKE Node SA" \\
  --project piyush-gcp

# Grant only what nodes actually need
gcloud projects add-iam-policy-binding piyush-gcp \\
  --member "<serviceAccount:gke-node-sa@piyush-gcp.iam.gserviceaccount.com>" \\
  --role roles/logging.logWriter

gcloud projects add-iam-policy-binding piyush-gcp \\
  --member "<serviceAccount:gke-node-sa@piyush-gcp.iam.gserviceaccount.com>" \\
  --role roles/monitoring.metricWriter

gcloud projects add-iam-policy-binding piyush-gcp \\
  --member "<serviceAccount:gke-node-sa@piyush-gcp.iam.gserviceaccount.com>" \\
  --role roles/artifactregistry.reader
```

| Role | Why |
| --- | --- |
| `logging.logWriter` | Nodes can send logs to Cloud Logging |
| `monitoring.metricWriter` | Nodes can send metrics to Cloud Monitoring |
| `artifactregistry.reader` | Nodes can pull images from Artifact Registry |

---

## Step 5 — Create Jenkins Bastion VM

### Step 1 — Create Service Account

Create a dedicated service account for Jenkins master VM.

```bash
gcloud iam service-accounts create jenkins-master-sa \\
  --project piyush-gcp \\
  --display-name "Jenkins Master Service Account"
```

Service account email:

```
jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com
```

---

### Step 2 — Grant permissions for GKE access

Since this VM is also used as a bastion host inside the VPC,
we grant full GKE permissions.

Full access (recommended for bastion / admin / Jenkins)

```bash
gcloud projects add-iam-policy-binding piyush-gcp \\
  --member=serviceAccount:jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com \\
  --role=roles/container.admin
  
  
  # Push to GAR #IMPORATANT
gcloud projects add-iam-policy-binding piyush-gcp \
  --member="serviceAccount:jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"
```

This allows:

- get-credentials
- kubectl access
- deploy workloads
- create namespaces
- create pods
- create services
- helm install / upgrade
- CI/CD deployments
- cluster operations

Suitable for:

- Jenkins master
- Bastion host
- Admin VM
- CI/CD runner

---

### Step 3 — Recommended  roles (for Jenkins / CI/CD)

Some Jenkins plugins, kubectl operations, or Workload Identity
require additional permissions.

Allow Jenkins to use service accounts

```bash
gcloud projects add-iam-policy-binding piyush-gcp \\
  --member=serviceAccount:jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com \\
  --role=roles/iam.serviceAccountUser
```

Allow read access to Compute resources

```bash
gcloud projects add-iam-policy-binding piyush-gcp \\
  --member=serviceAccount:jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com \\
  --role=roles/compute.viewer
```

Needed for:

- some Jenkins plugins
- kubectl auth
- workload identity
- cluster operations
- reading instance / network metadata

---

## Notes

- This service account will be attached to the Jenkins VM
- VM must be inside the same VPC as the private GKE cluster
- Use --scopes=cloud-platform when creating the VM
- No need to run gcloud auth login inside Jenkins VM

Jenkins serves two purposes here:

1. **CI/CD server** — runs pipelines, deploys to GKE
2. **Bastion Host** — the only way to access the private cluster

The VM uses a **reserved static IP** so the address never changes across reboots — important for GitHub webhooks and DNS.

```bash
# =============================================================
# Step 5 — Jenkins Bastion VM
# =============================================================
gcloud compute instances create jenkins-master-vm \\
  --project=piyush-gcp \\
  --zone=us-central1-a \\
  --machine-type=e2-standard-2 \\
  --image-family=debian-12 \\
  --image-project=debian-cloud \\
  --network=vpc-gke \\
  --subnet=subnet-1 \\
  --tags=jenkins-master \\
  --address=jenkins-master-ip \\
  --service-account=jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com \\
  --scopes=cloud-platform
```

```bash
#this is important
  --service-account=jenkins-master-sa@piyush-gcp.iam.gserviceaccount.com \\
```

---

## Step 6 — Firewall Rules for Jenkins

We create **three rules** — no more, no less.

```bash
# =============================================================
# Rule 1 — Jenkins UI + SonarQube
# Production: replace 0.0.0.0/0 with YOUR_OFFICE_IP/32
# Demo only: 0.0.0.0/0 — tighten this before going live
# Port 8080 = Jenkins, Port 9000 = SonarQube
# =============================================================
gcloud compute firewall-rules create allow-jenkins-ui \\
  --project=piyush-gcp \\
  --network=vpc-gke \\
  --direction=INGRESS \\
  --priority=1000 \\
  --source-ranges="0.0.0.0/0" \\
  --action=ALLOW \\
  --rules=tcp:8080,tcp:9000 \\
  --target-tags=jenkins-master

# =============================================================
# Rule 2 — SSH only via IAP (gcloud compute ssh)
# 35.235.240.0/20 is Google's IAP IP range
# No direct SSH from internet or your laptop IP
# =============================================================
gcloud compute firewall-rules create allow-jenkins-ssh-iap \\
  --project=piyush-gcp \\
  --network=vpc-gke \\
  --direction=INGRESS \\
  --priority=1000 \\
  --source-ranges="35.235.240.0/20" \\
  --action=ALLOW \\
  --rules=tcp:22 \\
  --target-tags=jenkins-master

# =============================================================
# Rule 3 — GitHub webhook IPs to trigger builds
# =============================================================
gcloud compute firewall-rules create allow-github-webhooks \\
  --project=piyush-gcp \\
  --network=vpc-gke \\
  --direction=INGRESS \\
  --priority=1000 \\
  --source-ranges="192.30.252.0/22,185.199.108.0/22,140.82.112.0/20" \\
  --action=ALLOW \\
  --rules=tcp:8080 \\
  --target-tags=jenkins-master
```

| Rule | Who can connect | Port |
| --- | --- | --- |
| `allow-jenkins-ui` | Everyone (demo) / Your IP (prod) | 8080, 9000 |
| `allow-jenkins-ssh-iap` | Google IAP only | 22 |
| `allow-github-webhooks` | GitHub servers only | 8080 |

---

## Step 7 — Create GKE Private Cluster

This creates the actual Kubernetes cluster.

The API server is only reachable from within `10.0.0.0/20` — which includes the Jenkins VM.

Note: this is with comment and space for readability

Note : use Gloud command after this one

```bash
# Identity & project
gcloud beta container \\
  --project "piyush-gcp" \\
  clusters create "standard-cluster-private-1" \\

# Location & version
  --region "us-central1" \\
  --cluster-version "1.34.4-gke.1047000" \\
  --release-channel "regular" \\

# Node machine config
  --machine-type "e2-custom-2-5120" \\
  --image-type "COS_CONTAINERD" \\
  --disk-type "pd-standard" \\
  --disk-size "20" \\
  --spot \\
  --num-nodes "0" \\
  --max-pods-per-node "110" \\
  --default-max-pods-per-node "110" \\

# Auth & service account
  --no-enable-basic-auth \\
  --service-account "gke-node-sa@piyush-gcp.iam.gserviceaccount.com" \\
  --metadata disable-legacy-endpoints=true \\

# Networking
  --enable-private-nodes \\
  --enable-ip-alias \\ # for gke-pods and gke-services in secondary ranges in subnet of vpc
  --enable-dataplane-v2 \\
  --network "projects/piyush-gcp/global/networks/vpc-gke" \\
  --subnetwork "projects/piyush-gcp/regions/us-central1/subnetworks/subnet-1" \\
  --cluster-secondary-range-name "gke-pods" \\
  --services-secondary-range-name "gke-services" \\

#With --no-enable-intra-node-visibility
#Pod-to-pod on same node = kernel handles it.
#VPC doesn't see it(No-VPC Flow Log visibility). Faster. Cheaper.
  --no-enable-intra-node-visibility \\

# Keep API accessible from within the VPC (Jenkins internal IP)
  --enable-master-authorized-networks \\
  --master-authorized-networks "10.0.0.0/20" \\

# Node locations (HA across zones)
  --node-locations "us-central1-a","us-central1-b","us-central1-c" \\

# Observability
  --logging=SYSTEM,WORKLOAD \\
  --monitoring=SYSTEM,STORAGE,POD,DEPLOYMENT,STATEFULSET,DAEMONSET,HPA,JOBSET,CADVISOR,KUBELET \\
  --enable-managed-prometheus \\

# Security
  --enable-shielded-nodes \\
  --shielded-integrity-monitoring \\
  --shielded-secure-boot \\
  --security-posture=standard \\
  --workload-vulnerability-scanning=disabled \\
  --workload-pool "piyush-gcp.svc.id.goog" \\
  --binauthz-evaluation-mode=DISABLED \\

# Addons
  --addons HorizontalPodAutoscaling,HttpLoadBalancing,NodeLocalDNS,GcePersistentDiskCsiDriver,GcpFilestoreCsiDriver \\

# Upgrade policy
  --enable-autoupgrade \\
  --enable-autorepair \\
  --max-surge-upgrade 1 \\
  --max-unavailable-upgrade 0
```

## USE THIS

```bash
#!/bin/bash

set -x

gcloud beta container clusters create "standard-cluster-private-1" \\
  --project "piyush-gcp" \\
  --region "us-central1" \\
  --cluster-version "1.34.4-gke.1047000" \\
  --release-channel "regular" \\
  --machine-type "e2-custom-2-5120" \\
  --image-type "COS_CONTAINERD" \\
  --disk-type "pd-standard" \\
  --disk-size "20" \\
  --spot \\
  --num-nodes "1" \\
  --max-pods-per-node "110" \\
  --default-max-pods-per-node "110" \\
  --no-enable-basic-auth \\
  --service-account "gke-node-sa@piyush-gcp.iam.gserviceaccount.com" \\
  --metadata disable-legacy-endpoints=true \\
  --enable-private-nodes \\
  --enable-ip-alias \\
  --enable-dataplane-v2 \\
  --network "projects/piyush-gcp/global/networks/vpc-gke" \\
  --subnetwork "projects/piyush-gcp/regions/us-central1/subnetworks/subnet-1" \\
  --cluster-secondary-range-name "gke-pods" \\
  --services-secondary-range-name "gke-services" \\
  --no-enable-intra-node-visibility \\
  --enable-master-authorized-networks \\
  --master-authorized-networks "10.0.0.0/20" \\
  --node-locations us-central1-a,us-central1-b,us-central1-c \\
  --logging=SYSTEM,WORKLOAD \\
  --monitoring=SYSTEM \\
  --enable-managed-prometheus \\
  --enable-shielded-nodes \\
  --shielded-integrity-monitoring \\
  --shielded-secure-boot \\
  --security-posture=standard \\
  --workload-vulnerability-scanning=disabled \\
  --workload-pool "piyush-gcp.svc.id.goog" \\
  --binauthz-evaluation-mode=DISABLED \\
  --addons HorizontalPodAutoscaling,HttpLoadBalancing,NodeLocalDNS,GcePersistentDiskCsiDriver,GcpFilestoreCsiDriver \\
  --enable-autoupgrade \\
  --enable-autorepair \\
  --max-surge-upgrade 1 \\
  --max-unavailable-upgrade 0
```

> **Estimated time:** 8–15 minutes
> 

---

## Step 8 — Connect kubectl via Jenkins (Bastion)

Because the cluster is private, `kubectl` must be run **from Jenkins VM** — not your laptop.

**SSH into Jenkins via IAP:**

```bash
gcloud compute ssh jenkins-master-vm \
  --project=piyush-gcp \
  --zone=us-central1-a \
  --tunnel-through-iap

#To increase the performance of the tunnel, consider installing NumPy. For instructions,
#please see <https://cloud.google.com/iap/docs/using-tcp-forwarding#increasing_the_tcp_upload_bandwidth>
#step1
$(gcloud info --format="value(basic.python_location)") -m pip install numpy
#step2
export CLOUDSDK_PYTHON_SITEPACKAGES=1
#step3
echo 'export CLOUDSDK_PYTHON_SITEPACKAGES=1' >> ~/.bashrc
source ~/.bashrc
```

**On Jenkins VM — install tools and connect:**

```bash
# Install kubectl and GKE auth plugin
sudo apt-get update
sudo apt-get install -y kubectl google-cloud-sdk-gke-gcloud-auth-plugin

# Get cluster credentials
#our gke-cluster inside private vpc so use this :   --internal-ip
gcloud container clusters get-credentials standard-cluster-private-1 \\
  --region us-central1 \
  --project piyush-gcp \
  --internal-ip

# Verify everything is working
kubectl get nodes
kubectl get pods --all-namespaces
```

---

## How Bastion Access Works

```
Your Laptop
    │
    │  gcloud compute ssh --tunnel-through-iap
    │  (Google verifies your identity first)
    ▼
Google IAP (35.235.240.0/20)
    │
    │  Authenticated tunnel only
    ▼
Jenkins VM (internal IP: 10.0.x.x)
    │
    │  kubectl over private VPC network
    ▼
GKE API Server (only reachable from 10.0.0.0/20)
```

**Why this is secure:**

- Port 22 is NOT open to the internet — only Google IAP can reach it
- IAP requires a valid Google account + IAM permission before the tunnel opens
- Every SSH session is logged in Cloud Audit Logs automatically
- Revoking access = removing one IAM binding — no SSH key hunting

**To give a teammate SSH access:**

```bash
gcloud projects add-iam-policy-binding piyush-gcp \\
  --member="user:teammate@yourdomain.com" \\
  --role="roles/iap.tunnelResourceAccessor"

gcloud projects add-iam-policy-binding piyush-gcp \\
  --member="user:teammate@yourdomain.com" \\
  --role="roles/compute.osLogin"
```

---

## GitHub Webhook Setup

Once Jenkins is running, add a webhook in your GitHub repo:

- Go to **Settings → Webhooks → Add webhook**
- Payload URL: `http://146.148.57.84:8080/github-webhook/`
- Content type: `application/json`
- Events: `Push`, `Pull Request`

This URL is stable forever because we used a reserved static IP.

---

## Before Going to Production

| Item | Current (Demo) | Change to |
| --- | --- | --- |
| Jenkins UI access | `0.0.0.0/0` | Your office IP only |
| Vulnerability scanning | Disabled | `standard` |
| Binary Authorization | Disabled | Enable with image signing |
| Disk size | 20 GB | 50 GB minimum |
| Node count | 0 (cold start) | Min 1 node |
| Node service account | `gke-node-sa` ✅ | Already correct |

---

## Quick Reference

| Resource | Name | Value |
| --- | --- | --- |
| Project | `piyush-gcp` | — |
| VPC | `vpc-gke` | — |
| Subnet | `subnet-1` | `10.0.0.0/20` |
| Pod range | `gke-pods` | `10.1.0.0/16` |
| Service range | `gke-services` | `10.2.0.0/20` |
| Cloud Router | `nat-router` | `us-central1` |
| Cloud NAT | `nat-config` | All subnet ranges |
| Jenkins VM | `jenkins-master-vm` | `us-central1-a` |
| GKE Cluster | `standard-cluster-private-1` | `us-central1` |
| Node SA | `gke-node-sa` | Least privilege |

---

## References

- [GKE Private Clusters](https://cloud.google.com/kubernetes-engine/docs/concepts/private-cluster-concept)
- [Cloud IAP for SSH](https://cloud.google.com/iap/docs/using-tcp-forwarding)
- [Cloud NAT with GKE](https://cloud.google.com/nat/docs/gke-example)
- [Workload Identity](https://cloud.google.com/kubernetes-engine/docs/how-to/workload-identity)
- [gcloud container clusters create](https://cloud.google.com/sdk/gcloud/reference/container/clusters/create)

### install docker

```json
sudo apt install docker.io
sudo usermod 
```

### run soanrqube in docker container

```json
docker run -itd --name SonarQube-Server -p 9000:9000 sonarqube:lts-community
```

### INSTALL yq (YAML-EDITOR) : UPDATING VALUES.YAML

```bash
sudo wget https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 \
  -O /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
  

# Verify
yq --version
```

### INSTALL HELM

```bash
curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
```

```bash
chmod 700 get_helm.sh
```

```bash
./get_helm.sh
```

### INSTALL Jenkins

```bash
#step1
sudo apt update
sudo apt install fontconfig openjdk-17-jre
java -version

#step-2
sudo wget -O /etc/apt/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key
echo "deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc]" \
  https://pkg.jenkins.io/debian-stable binary/ | sudo tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update
sudo apt install jenkins

```

### IT will create a new User : Jenkins

login as jenkins 

```bash
#first add jenkins-user in docker-group
sudo usermod -aG docker jenkins

#Log-out as current user
exit

#login as jenkins
gcloud compute ssh jenkins@jenkins-master-vm \
  --project=piyush-gcp \
  --zone=us-central1-a \
  --tunnel-through-iap
  
#add gke-cluster context in kubectl for jenkins user
gcloud container clusters get-credentials standard-cluster-private-1 \
  --region us-central1 \
  --project piyush-gcp \
  --internal-ip
  
 #test
 kubectl get nodes -o wide
 

```

 

### Configure docker to push to GAR

```bash
 gcloud auth configure-docker us-central1-docker.pkg.dev
```

### Note : jenkins-vm need a static ip , else if ip change after vm-restart it will totaaly slow

### INSTALL SONARQUBE-SCANNER AND Pipeline-Stage-View  PLUGIN IN JENKINS

- JENKINS-SONAR-QUBE INTEGRATION-PART-1
    - THIS WILL CONNECT IN SAME WAY : WITH-TOCKEN
    - THE CONTAINER WAS RUNNING ON 9000 PORT OF MASTER-JENKINS-VM
    - GO TO ADMINISTRATION
    - TEN SECURITY → THEN USERS
    - THERE WILL BE DEFAULT USER ADMIN
    - WE WILL CREATE TOCKEN (PERSONAL ACCESS TOCKEN FOR THAT USER)
    - CLICK ON TOCKEN
    - NAME : JENKINS-TOCKEN , EXPIRES : MONTH OR 2
    - HIT GENERATE
    - NOW - WE WILL ADD THIS TOCKEN IN JENKINS-CREDENTIAL
    - MANAGE-JENKINS → CREDENTIAL
    - NEW-CREDENTIAL
    - BUT THIS TIME KIND WILL NOT : USER-NAME PASSWORD
    - IT WILL BE : SECRET-TEXT
    - LET THE SCOPE DEFAULT (GLOBAL)
    - ENTER TOCKEN IN SECRET
    - ID : SONAR-CRED
    - HIT CREATE
- JENKINS-SONAR-QUBE INTEGRATION-PART-2
    - MANAGE-JENKINS → TOOLS
    - SEARCH : “SonarQube Scanner installations”
    - NAME : SONAR-TOOLS
    - INSTALL-AUTOMATICALLY → FROM MAVEN-CENTRAL
    - HIT SAVE
- JENKINS-SONAR-QUBE INTEGRATION-PART-3
    - MANAGE-JENKINS → SYSTEM
    - SEARCH : “SONAR-SYSTEM”
    - SERVER-URL : MASTER-JENKINS-VM-IP:9000 (SONAR-QUBE USE 9000 PORT)
        - OR “LOCALHOST:9000”
            - CAUSE SONAR-QUBE IS ON SAME MACHINE AND EXPOSE 9000
    - AND FOR AUTHENTICATION TOCKEN : SONAR-CRED (FROM CREDENTIALS)
    - HIT-SAVE
- JENKINS-SONAR-QUBE INTEGRATION-PART-4
    - WHEN SONAR-QUBE SCAN IS DONE IT WILL NOTIFY THE JENKINS (JUST A HTTP-POST REQUEST WITH JSON-PAYLOAD)
    - WE NEED TO CREATE WEB-HOOK
    - GOTO SONAR-QUBE PAGE (MASTER-VM-IP:9000)
    - ADMINISTRATION →CONFIGRATION → WEBHOOK → CREATE
    - NAME: JENKINS-WEBHOOK
    - URL : http://JENKINS-VM:8080/sonarqube-webhook/
        - same last path we used in github webhook (for letting jenkins know : change happen in git repo)
    - secret : NOTHING , LET IT GO BLANK
    - HIT CREATE
    
- JENKINS-GITHUB INTEGRATION
    - NOT JUST PULL CODE
    - JENKINS ALSO UPDATE GIT-HUB FILE LIKE
    - DOCKER-TAG , DEPLOYMENT-YAML , ETC..
    - AND ALL ROLL-OUT UPDATE AND ROLL-BACK ALLSO GET RECORDED/VERSIONED IN GIT-HUB USING ARGO-CD
    - IN GIT-HUB
        - GO-TO PROFILE SETTINGS (NOT REPO-SETTING)
        - GO-TO DEVELOPER-SETTINGS
        - PERSONAL-ACCESS-TOCKER → TOCKEN(CLASSIC)
            - WE CAN ALSO GO FOR FINE-GRAINED TOCKENS (FOR GIVING SPECIFIC-CONTROL)
        - GENRATE NEW-TOCKEN (CLASSIC)
        - NOTE : WONDER-LUST-JENKINS
        - EXPIRATION : MONTH OR 2
        - SCOPE : GIVE REPO-ACCESS (FULL-CONTROL OF PRIVATE-REPO)
    - ADDING GIT-HUB TOCKEN(PERSONAL-ACCESS) IN JENKINS CREDENTIAL
        - MANAGE-JENKINS  → CREDENTIAL
        - ADD GLOBAL-CREDENTIAL
        - HERE IT WILL NOT BE A SECRET IT WILL BE GO LIKE DOCKER (username-password)
        - cause access-tocken can be used behalf of password
        - USERNAME : GITHUB USERNAME ([**bebanana18-dotcom**](https://github.com/bebanana18-dotcom))
        - PASSWORD : PERSONAL-ACCESS-TOCKEN
        - ID : GITHUB-CRED
    
    JENKINS-K8S INTEGRATION (SECRET-FILE)
    
    - when we added k8s-cluster-context for Jenkins-user
        - it add ONE FILE(YAML-FILE) IN LOCATION : “~/.kube/config”
        - PRINT AND COPY THIS FILE AND CREATE A LOCAL CONFIG FILE IN LOCAL
        
        ```bash
        cat ~/.kube/config
        ```
        
    - MANAGE-JENKINS → CREDENTIAL
        - SECRET-FILE
            - CHOOSE-FILE : BROWSE-AND-SELECT
            - ID : K8S-CRED
            - HIT CREATE
        
    
    ## CREATE PIPELINE
    
    - NEW-ITEM
        - NAME : WANDERLUST-2
        - ITEM : PIPELINE
        - HIT OKAY
    - Discard old buildS
        - Days to keep builds : 10
        - Max # of builds to keep : 2
    - GitHub project
        - PROJECT URL : YOUR-URL(HTTP)
    - Triggers
        - SELECT : GitHub hook trigger for GITScm polling (FOR WEB-HOOK)
    - Pipeline
        - Definition : PIPELINE-SCRIPT FROM SCM
        - SCM : GIT
        - REPOSITORY-URL: REPO-URL
        - CREDENTIAL :  YOUR-GIYHUB-USER-NAME OR GITHUB-CRED
        - BRANCH : main
        - Script Path : Jenkinsfile
    - HIT SAVE

### LAST BUT NOT LEAST

### ADD SHARED-LIBRARY

- MANAGE-JENKINS → SYSTEM
- GO TO: Global Trusted Pipeline Libraries
    - ADD
        - NAME : Shared
        - Default version : main
        - Retrieval method : MODERN-SCM
        - SOURCE-CODE MANAGMENT : GIT
        - Project Repository : YOUR-REPO-URL
            - NOTE IF YOUR SHARED LIBRARY IN DIFF REPO ADD URL OF THAT
        - credential : github-username
- HIT SAVE
