# Deployment Prerequisites

This document lists what you need to provide so the Beckn team can deploy and run the Beckn Discovery Service in your Google Cloud project. The service runs on a Google Kubernetes Engine (GKE) cluster.

Please choose one of the two options below and arrange the items listed for it, plus the items in the **Common requirements** section that apply to both.

## Choose a deployment option

### Option 1 — You create and own the cluster (we deploy onto it)

You create the GKE cluster (to the sizing in the table further down) and give our team access. We deploy and run all the Discovery services on it.

You provide:

* A GKE cluster sized as in [Cluster sizing](#cluster-sizing) below.
* Cluster access for our deployment team — `cluster-admin`, or equivalent RBAC sufficient to install and manage the services on the cluster.
* Standard GKE storage classes for SSD and balanced disks (available by default on GKE).
* A Cloud Storage (GCS) bucket for backups, plus a service account the backup jobs use to write to it (or grant us access to set this up).

### Option 2 — We create the cluster for you (using our automation)

We create the GKE cluster in your Google Cloud project using our automation, then deploy the services. The backup storage is created automatically as part of this.

You provide the following access on your Google Cloud project, granted to the identity we use for deployment:

| Role | Role ID | What it is used for |
|------|---------|---------------------|
| Kubernetes Engine Admin | `roles/container.admin` | Create the cluster and its node pool |
| Compute Network Admin | `roles/compute.networkAdmin` | Create the network, subnets, and firewall rules |
| Service Account Admin | `roles/iam.serviceAccountAdmin` | Create the service accounts the cluster uses |
| Service Account User | `roles/iam.serviceAccountUser` | Attach those service accounts to the cluster |
| Project IAM Admin | `roles/resourcemanager.projectIamAdmin` | Grant the cluster's service accounts their permissions |
| Storage Admin | `roles/storage.admin` | Create the backup storage bucket |
| Service Usage Admin | `roles/serviceusage.serviceUsageAdmin` | Enable the required Google Cloud APIs |

These are needed only to set up the environment. They can be granted for the duration of the setup and removed afterwards if you prefer.

## Common requirements (both options)

### Endpoint and DNS

The discovery service is exposed through an ingress (Kong) load balancer that runs inside the cluster and is assigned a public IP (your cluster must allow creating an external LoadBalancer service — the default on GKE). After we deploy, you create a DNS A record mapping your hostname to that load-balancer IP; an HTTPS certificate is then issued automatically for the hostname.

Please have a hostname ready (for example, `discovery.yourdomain.com`) and be able to create the DNS record once we share the ingress load-balancer IP.

### Container images

Our services run from container images held in Beckn's NFH image registry on Google Artifact Registry (project `beckn-one-infra-services`, region `asia-south1`).

When a GKE node pulls an image, it authenticates using the node pool's service account. So the only access required is read access for your cluster's node service account on our registry — and that grant is made on our side. No image pull secrets and no extra setup are needed on your cluster.

What we need from you:

* **Option 1:** share your cluster's node service account email. You can find it with:

  ```bash
  gcloud container node-pools describe POOL_NAME \
    --cluster=CLUSTER_NAME --region=REGION --project=YOUR_PROJECT \
    --format="value(config.serviceAccount)"
  ```

  We then grant it the Artifact Registry Reader role (scoped to the specific repository) on our registry project.
* **Option 2:** nothing — our automation creates the node service account, and we grant it read access on our registry.

### Alerts (Slack)

We send infrastructure and service health alerts to Slack. Please provide:

* A Slack channel for alerts (for example, `#beckn-discovery-alerts`).
* An Incoming Webhook for that channel, and share the webhook URL with us.

(You may provide one channel, or two if you want infrastructure and service alerts separated.)

## Cluster sizing

The full Discovery stack (services, database, search, event bus, monitoring, and backups) needs approximately the resources below to start.

**Minimum resources [Non-Production]**

| Resource | Starting size |
|----------|---------------|
| CPU | ~12 vCPU |
| Memory | ~20 GiB |

Region: your choice (e.g. `asia-south1` / Mumbai).

**Recommended node pool**

| Environment | Node pool | Total capacity |
|-------------|-----------|----------------|
| Non-production | 3 × `c2-standard-4` | 12 vCPU / 48 GiB |
| Production | 2 × `c2-standard-8` (recommended) | 16 vCPU / 64 GiB |

Start with the non-production sizing and scale up — add nodes or enable autoscaling — as catalogue size and query load grow. Disk storage also grows with the catalogue over time.
