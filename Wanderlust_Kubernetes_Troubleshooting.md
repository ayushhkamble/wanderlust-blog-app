# Wanderlust Blog Application – Deployment Troubleshooting Guide

This document records the real deployment and troubleshooting issues faced while deploying the **Wanderlust MERN Blog Application** on AWS EKS using Docker, Jenkins, Kubernetes, AWS Load Balancer Controller, Route 53, SonarQube, OWASP Dependency Check, and Trivy.

The purpose of this guide is to explain each issue in simple language, show how it was investigated, identify the root cause, and document the solution that was used.

---

## Table of Contents

1. Project Deployment Architecture
2. Jenkins Kubernetes Deployment Failed – NoCredentials
3. OWASP Dependency Check Taking Too Long
4. Dependency Check Jenkins Parameter Error
5. Node Audit API Error
6. AWS Load Balancer Controller CrashLoopBackOff
7. Old ALB DNS / NXDOMAIN Problem
8. Route 53 Domain Not Reaching the Application
9. Frontend Network Error
10. Backend Pod Running but Application Not Working
11. MongoDB Connection Error
12. Redis Connection Error
13. Kubernetes Backend Environment Variables Missing
14. Frontend API URL Configuration
15. Featured Posts Not Loading
16. Kubernetes Deployment Verification
17. Troubleshooting Approach
18. Useful Commands
19. Final Deployment Status
20. Key Lessons Learned

---

# 1. Project Deployment Architecture

Wanderlust is a MERN-based travel blog application.

### Components

- React frontend
- Node.js / Express backend
- MongoDB
- Redis
- Docker
- Kubernetes
- Amazon EKS
- AWS Load Balancer Controller
- Application Load Balancer
- Route 53
- Jenkins
- GitHub
- Docker Hub
- SonarQube
- OWASP Dependency Check
- Trivy

### AWS Region

```text
eu-north-1
```

### EKS Cluster

```text
wanderlust-cluster
```

### Kubernetes Namespace

```text
wanderlust
```

### Deployments

```text
backend-deployment
frontend-deployment
mongo-deployment
redis-deployment
```

### Services

```text
backend-service
frontend-service
mongo-service
redis-service
```

### Application Domain

```text
http://wanderlust.ayushk.online
```

### High-Level Flow

```text
GitHub
   |
   v
Jenkins
   |
   +---- SonarQube
   +---- OWASP Dependency Check
   +---- Trivy
   |
   v
Docker Build
   |
   v
Docker Hub
   |
   v
Amazon EKS
   |
   +---- Frontend
   +---- Backend
   +---- MongoDB
   +---- Redis
   |
   v
AWS Load Balancer Controller
   |
   v
Application Load Balancer
   |
   v
Route 53
   |
   v
wanderlust.ayushk.online
```

---

# 2. Jenkins Kubernetes Deployment Failed – NoCredentials

## Error

Jenkins Build #7 failed during Kubernetes deployment:

```text
aws: [ERROR]: An error occurred (NoCredentials):
Unable to locate credentials.
```

## What happened?

The Jenkins pipeline could run:

```bash
aws eks update-kubeconfig
```

but Kubernetes commands were executed outside the AWS credentials block.

When `kubectl` communicates with EKS, AWS authentication is required.

## Investigation

We checked:

```bash
aws sts get-caller-identity
```

and:

```bash
aws eks update-kubeconfig --region eu-north-1 --name wanderlust-cluster
```

These worked when AWS credentials were available.

The failure occurred when Jenkins executed:

```bash
kubectl apply -f kubernetes/
```

without AWS credentials available to that process.

## Root Cause

AWS credentials were not available to Jenkins when `kubectl` was authenticating against EKS.

## Solution

We wrapped the Kubernetes commands inside:

```groovy
withCredentials([
    [$class: 'AmazonWebServicesCredentialsBinding',
     credentialsId: 'aws-credentials']
]) {
```

Example:

```groovy
withCredentials([
    [$class: 'AmazonWebServicesCredentialsBinding',
     credentialsId: 'aws-credentials']
]) {
    sh '''
        aws sts get-caller-identity

        aws eks update-kubeconfig         --region ${AWS_REGION}         --name ${EKS_CLUSTER}

        kubectl get nodes
        kubectl apply -f kubernetes/
    '''
}
```

The same approach was applied to the Kubernetes verification stages.

## Result

The next Jenkins deployment successfully applied the Kubernetes manifests.

---

# 3. OWASP Dependency Check Taking Too Long

## Problem

The OWASP Dependency Check stage was taking a long time during Jenkins builds.

This raised concerns about build time, EC2 memory usage, vulnerability database updates, and NVD API access.

## Investigation

Dependency Check needs vulnerability information to compare project dependencies against known vulnerabilities.

The initial database download/update can take considerable time.

## Solution

An NVD API key was configured and passed to Dependency Check:

```groovy
dependencyCheck(
    additionalArguments: '--scan ./ --format XML --format HTML --nvdApiKey $NVD_API_KEY',
    odcInstallation: 'Dependency-check'
)
```

The report was published using:

```groovy
dependencyCheckPublisher(
    pattern: '**/dependency-check-report.xml'
)
```

## Result

Dependency Check completed and Jenkins processed the generated report.

---

# 4. Dependency Check Jenkins Parameter Error

## Error

Jenkins initially reported:

```text
Invalid parameter "additionalArguements",
did you mean "additionalArguments"?
```

## Root Cause

The parameter name was misspelled.

### Incorrect

```groovy
additionalArguements
```

### Correct

```groovy
additionalArguments
```

## Solution

The Jenkinsfile was changed to:

```groovy
dependencyCheck(
    additionalArguments: '--scan ./ --format XML --format HTML --nvdApiKey $NVD_API_KEY',
    odcInstallation: 'Dependency-check'
)
```

## Result

The Jenkinsfile compiled successfully and the Dependency Check stage executed.

---

# 5. Node Audit API Error

## Problem

A Node Audit API-related error appeared during the security scanning process.

However, Jenkins was still able to find and process the Dependency Check report.

## Investigation

We checked whether the Dependency Check report was generated.

The report:

```text
dependency-check-report.xml
```

was available and the publisher processed it.

## Solution

The generated report was explicitly published using:

```groovy
dependencyCheckPublisher(
    pattern: '**/dependency-check-report.xml'
)
```

## Result

The security scanning process continued and Jenkins processed the available report.

---

# 6. AWS Load Balancer Controller CrashLoopBackOff

## Error

The AWS Load Balancer Controller initially showed:

```text
CrashLoopBackOff
```

## Investigation

We checked:

```bash
kubectl get pods -n kube-system
```

Then:

```bash
kubectl logs -n kube-system <aws-load-balancer-controller-pod>
```

The logs showed an IMDS/VPC lookup timeout.

## Root Cause

The AWS Load Balancer Controller could not successfully obtain the required VPC information through its metadata lookup.

## Approach

We checked:

1. Controller pod status
2. Controller logs
3. EKS configuration
4. AWS Load Balancer Controller installation
5. AWS permissions/configuration

After correcting the controller configuration/installation, the controller became healthy.

## Verification

```bash
kubectl get pods -n kube-system
```

The controller pods eventually showed:

```text
Running
```

## Result

The AWS Load Balancer Controller successfully processed the Kubernetes Ingress and created an Application Load Balancer.

---

# 7. Old ALB DNS / NXDOMAIN Problem

## Problem

An earlier Kubernetes Ingress created an ALB with an older DNS name.

The old ALB hostname was no longer resolving correctly and DNS testing resulted in an NXDOMAIN-type problem.

## Investigation

We checked:

```bash
kubectl get ingress -n wanderlust
```

and:

```bash
kubectl describe ingress wanderlust-ingress -n wanderlust
```

We compared the ALB hostname returned by Kubernetes with the hostname being used in DNS.

## Root Cause

The active ALB had changed, but the old ALB hostname was still being used.

## Solution

The old Ingress was deleted and recreated so that the AWS Load Balancer Controller could provision a fresh ALB.

The new ALB hostname was:

```text
k8s-wanderlu-wanderlu-b2f9faf0b3-915385509.eu-north-1.elb.amazonaws.com
```

## Result

The new ALB became reachable and was used for the Route 53 record.

---

# 8. Route 53 Domain Not Reaching the Application

## Problem

The application worked through the ALB, but the custom domain was not initially reaching the correct load balancer.

## Investigation

We separated the problem into:

```text
Application
    |
    v
ALB
    |
    v
Route 53
    |
    v
Domain
```

First, we tested the ALB directly:

```bash
ALB=$(kubectl get ingress wanderlust-ingress -n wanderlust -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
```

Then:

```bash
curl -I -H "Host: wanderlust.ayushk.online" "http://$ALB/"
```

The ALB returned:

```text
HTTP/1.1 200 OK
```

This proved that the frontend, Ingress, and ALB were working.

## Root Cause

The remaining problem was DNS configuration.

## Solution

A Route 53 CNAME record was configured for:

```text
wanderlust.ayushk.online
```

and pointed to the active ALB DNS name.

## Result

The application became accessible using:

```text
http://wanderlust.ayushk.online
```

---

# 9. Frontend Network Error

## Error

The application opened successfully, but creating a blog produced:

```text
Error: Network Error
```

## Investigation

We searched the frontend code:

```bash
grep -R "VITE_API_PATH" frontend/src
```

The frontend uses:

```text
import.meta.env.VITE_API_PATH
```

and appends routes such as:

```text
/api/posts
/api/posts/latest
/api/posts/featured
```

## Root Cause

The frontend Docker environment originally contained:

```env
VITE_API_PATH="http://ayushk.online:5000"
```

This was incorrect for the Kubernetes deployment.

The backend was not publicly exposed through port 5000. It was routed through the ALB using `/api`.

## Correct Configuration

```env
VITE_API_PATH="http://wanderlust.ayushk.online"
```

The frontend then generates requests such as:

```text
http://wanderlust.ayushk.online/api/posts
```

## Result

Frontend API requests started working and blog creation became functional.

---

# 10. Backend Pod Running but Application Not Working

## Problem

Kubernetes showed the backend pod as running, but backend requests were failing.

## Important Lesson

A pod being:

```text
Running
```

does not automatically mean the application inside it is healthy.

## Investigation

We checked:

```bash
kubectl get pods -n wanderlust
```

Then:

```bash
kubectl logs deployment/backend-deployment -n wanderlust
```

The logs showed:

```text
Server is running on port 8080
```

but also showed MongoDB and Redis connection errors.

## Root Cause

The Node.js application started, but it could not connect to its dependencies.

We therefore investigated MongoDB and Redis separately.

---

# 11. MongoDB Connection Error

## Error

Backend logs showed:

```text
MongooseServerSelectionError:
getaddrinfo ENOTFOUND mongodb
```

The application was trying to connect using:

```text
mongodb://mongodb:27017/wanderlust
```

## Investigation

We checked:

```bash
kubectl get svc -n wanderlust
```

The MongoDB Service was:

```text
mongo-service
```

not:

```text
mongodb
```

## Root Cause

The hostname in the backend configuration did not match the Kubernetes Service name.

## Incorrect

```text
mongodb://mongodb:27017/wanderlust
```

## Correct

```text
mongodb://mongo-service:27017/wanderlust
```

## Solution

The backend Deployment was updated:

```yaml
env:
  - name: MONGODB_URI
    value: "mongodb://mongo-service:27017/wanderlust"
```

## Result

The backend successfully connected to MongoDB.

---

# 12. Redis Connection Error

## Error

Backend logs showed:

```text
Error connecting to Redis:
getaddrinfo ENOTFOUND redis
```

## Investigation

We checked:

```bash
kubectl get svc -n wanderlust
```

The Redis Service was:

```text
redis-service
```

not:

```text
redis
```

## Root Cause

The Redis hostname configured for the backend did not match the Kubernetes Service name.

## Incorrect

```text
redis://redis:6379
```

## Correct

```text
redis://redis-service:6379
```

## Solution

The backend Deployment was updated:

```yaml
env:
  - name: REDIS_URL
    value: "redis://redis-service:6379"
```

## Result

The backend logs showed:

```text
Redis Connected: redis://redis-service:6379
```

---

# 13. Kubernetes Backend Environment Variables Missing

## Problem

This was the main reason behind the MongoDB and Redis connection failures.

The Docker environment file contained application configuration, but the Kubernetes Deployment did not define the required environment variables.

The original Deployment contained:

```yaml
containers:
  - name: backend
    image: ayushkamble820/wanderlust-backend:9
    ports:
      - containerPort: 8080
```

There was no `env:` section.

## Why This Caused the Problem

The backend needed values such as:

```text
MONGODB_URI
REDIS_URL
PORT
FRONTEND_URL
JWT_SECRET
NODE_ENV
```

Kubernetes was not explicitly supplying the required production values.

## Solution

The Kubernetes Deployment was updated with the required environment variables.

Important values included:

```yaml
env:
  - name: MONGODB_URI
    value: "mongodb://mongo-service:27017/wanderlust"

  - name: REDIS_URL
    value: "redis://redis-service:6379"

  - name: PORT
    value: "8080"

  - name: FRONTEND_URL
    value: "http://wanderlust.ayushk.online"

  - name: NODE_ENV
    value: "production"
```

The remaining application variables were also configured in the Deployment.

The updated `backend.yaml` was committed to GitHub and Jenkins applied the change during deployment.

## Result

Backend logs showed:

```text
Server is running on port 8080
Redis Connected: redis://redis-service:6379
Database connected: mongodb://mongo-service:27017/wanderlust
```

This confirmed successful MongoDB and Redis connectivity.

---

# 14. Frontend API URL Configuration

## Problem

The frontend originally used:

```env
VITE_API_PATH="http://ayushk.online:5000"
```

This did not match the Kubernetes/ALB architecture.

## How We Found the Problem

We searched:

```bash
grep -R "VITE_API_PATH" frontend/src
```

We found API calls such as:

```text
/api/posts
/api/posts/latest
/api/posts/featured
```

For example:

```typescript
axios.get(
    import.meta.env.VITE_API_PATH + '/api/posts'
)
```

## Correct Configuration

```env
VITE_API_PATH="http://wanderlust.ayushk.online"
```

The resulting request becomes:

```text
http://wanderlust.ayushk.online/api/posts
```

## Request Flow

```text
Frontend
   |
   v
http://wanderlust.ayushk.online/api/posts
   |
   v
ALB
   |
   v
/backend-service:8080
```

## Result

The frontend successfully communicated with the backend.

---

# 15. Featured Posts Not Loading

## Problem

The application was working and blogs could be posted, but the Featured Posts section was empty.

## Investigation

We first checked all posts:

```bash
curl -s http://wanderlust.ayushk.online/api/posts | python3 -m json.tool
```

The returned posts contained:

```json
"isFeaturedPost": false
```

We then tested:

```bash
curl -s http://wanderlust.ayushk.online/api/posts/featured
```

The result was:

```json
[]
```

## Source Code Investigation

We searched:

```bash
grep -Rni "isFeaturedPost\|featured" backend frontend/src
```

The relevant files included:

```text
backend/routes/posts.js
backend/controllers/posts-controller.js
backend/models/post.js
frontend/src/components/blog-feed.tsx
frontend/src/pages/add-blog.tsx
```

The backend retrieves featured posts using:

```javascript
Post.find({ isFeaturedPost: true });
```

Therefore, only posts with:

```text
isFeaturedPost = true
```

are returned.

## Root Cause

The API was working correctly.

There were simply no featured posts in the database.

## Solution

The frontend already had:

```text
Is this a featured blog?
```

We enabled this option while creating a blog and posted it.

The new post was stored with:

```json
"isFeaturedPost": true
```

## Verification

```bash
curl -s http://wanderlust.ayushk.online/api/posts/featured | python3 -m json.tool
```

The featured post was then returned.

## Result

The Featured Posts section started displaying posts correctly.

---

# 16. Kubernetes Deployment Verification

After fixing the application, explicit verification was added to the Jenkins pipeline.

### Check Pods

```bash
kubectl get pods -n wanderlust
```

### Check Services

```bash
kubectl get svc -n wanderlust
```

### Check Ingress

```bash
kubectl get ingress -n wanderlust
```

### Check Ingress Details

```bash
kubectl describe ingress -n wanderlust
```

### Check Backend Rollout

```bash
kubectl rollout status deployment/backend-deployment -n wanderlust --timeout=300s
```

### Check Frontend Rollout

```bash
kubectl rollout status deployment/frontend-deployment -n wanderlust --timeout=300s
```

### Check MongoDB Rollout

```bash
kubectl rollout status deployment/mongo-deployment -n wanderlust --timeout=300s
```

### Check Redis Rollout

```bash
kubectl rollout status deployment/redis-deployment -n wanderlust --timeout=300s
```

This verification made sure the application components were actually deployed and rolled out successfully.

---

# 17. Troubleshooting Approach

The most useful approach during this deployment was to troubleshoot one layer at a time.

```text
Identify the failing layer
        |
        v
Check logs / command output
        |
        v
Find the root cause
        |
        v
Change only the required configuration
        |
        v
Deploy again
        |
        v
Verify the result
```

## Layer 1 – Jenkins

Check:

```text
Jenkins
   |
   v
Pipeline Stage
   |
   v
Console Log
```

Useful command:

```bash
aws sts get-caller-identity
```

## Layer 2 – Docker

Check:

```text
Docker Build
   |
   v
Docker Image
   |
   v
Docker Hub
```

Useful command:

```bash
docker images
```

## Layer 3 – Kubernetes

Check:

```bash
kubectl get pods -n wanderlust
kubectl get svc -n wanderlust
kubectl get ingress -n wanderlust
```

## Layer 4 – Application Logs

If a pod is running but the application is failing:

```bash
kubectl logs <pod-name> -n wanderlust
```

## Layer 5 – Kubernetes Networking

Check the actual Service names:

```text
mongo-service
redis-service
backend-service
frontend-service
```

## Layer 6 – ALB

Test the ALB directly:

```bash
ALB=$(kubectl get ingress wanderlust-ingress -n wanderlust -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

curl -I -H "Host: wanderlust.ayushk.online" "http://$ALB/"
```

If this returns:

```text
HTTP/1.1 200 OK
```

the ALB and application routing are working.

## Layer 7 – DNS

After confirming that the ALB works directly:

```bash
curl -I http://wanderlust.ayushk.online
```

## Layer 8 – Frontend API

If the frontend loads but API operations fail, check:

```text
VITE_API_PATH
```

Then test:

```bash
curl -i http://wanderlust.ayushk.online/api/posts
```

---

# 18. Useful Commands

## Kubernetes

```bash
kubectl get all -n wanderlust
kubectl get pods -n wanderlust -o wide
kubectl get svc -n wanderlust
kubectl get ingress -n wanderlust
kubectl describe pod <pod-name> -n wanderlust
kubectl logs <pod-name> -n wanderlust
kubectl logs -f <pod-name> -n wanderlust
```

### Restart deployment

```bash
kubectl rollout restart deployment/backend-deployment -n wanderlust
```

### Check rollout

```bash
kubectl rollout status deployment/backend-deployment -n wanderlust
```

## AWS EKS

### Check AWS identity

```bash
aws sts get-caller-identity
```

### Configure EKS kubeconfig

```bash
aws eks update-kubeconfig --region eu-north-1 --name wanderlust-cluster
```

### Check nodes

```bash
kubectl get nodes
```

## ALB

### Get ALB hostname

```bash
kubectl get ingress wanderlust-ingress -n wanderlust -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

### Test ALB

```bash
ALB=$(kubectl get ingress wanderlust-ingress -n wanderlust -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

curl -I -H "Host: wanderlust.ayushk.online" "http://$ALB/"
```

## Application API

### All posts

```bash
curl -s http://wanderlust.ayushk.online/api/posts | python3 -m json.tool
```

### Latest posts

```bash
curl -s http://wanderlust.ayushk.online/api/posts/latest | python3 -m json.tool
```

### Featured posts

```bash
curl -s http://wanderlust.ayushk.online/api/posts/featured | python3 -m json.tool
```

## Search Source Code

```bash
grep -Rni "VITE_API_PATH" frontend/src
```

```bash
grep -Rni "isFeaturedPost\|featured" backend frontend/src
```

---

# 19. Final Deployment Status

After troubleshooting and fixing the issues, the Wanderlust application was successfully deployed.

### Working Components

- Jenkins CI/CD pipeline
- GitHub source control
- Docker image build
- Docker Hub image push
- SonarQube analysis
- OWASP Dependency Check
- Trivy vulnerability scanning
- Amazon EKS
- Kubernetes Deployments
- Kubernetes Services
- MongoDB
- Redis
- AWS Load Balancer Controller
- Application Load Balancer
- Route 53
- Frontend API communication
- Blog creation
- Featured Posts

### Final Application URL

```text
http://wanderlust.ayushk.online
```

---

# 20. Key Lessons Learned

## 1. Running Pod Does Not Always Mean Healthy Application

A pod can show:

```text
Running
```

while the application inside the container is failing.

Always check:

```bash
kubectl logs <pod-name> -n wanderlust
```

## 2. Kubernetes Service Names Matter

The backend must use the actual Kubernetes Service names:

```text
MongoDB  -> mongo-service
Redis    -> redis-service
Backend  -> backend-service
Frontend -> frontend-service
```

## 3. kubectl Access to EKS Requires AWS Authentication

When Jenkins executes:

```bash
kubectl
```

against EKS, AWS authentication must be available.

This was the reason for the Jenkins `NoCredentials` error.

## 4. Test Each Layer Separately

When an application is not working, test:

```text
Pod
  |
  v
Service
  |
  v
Ingress
  |
  v
ALB
  |
  v
DNS
  |
  v
Browser
```

## 5. Test the API Before Changing the Frontend

If the UI shows:

```text
Network Error
```

test:

```bash
curl -i http://wanderlust.ayushk.online/api/posts
```

If the API works, investigate the frontend configuration.

## 6. Empty API Response Does Not Always Mean API Failure

The Featured Posts endpoint returned:

```json
[]
```

but the API itself was healthy.

The database simply had no post where:

```text
isFeaturedPost = true
```

The correct solution was to create/mark a post as featured.

---

# Conclusion

The Wanderlust deployment involved troubleshooting multiple layers including Jenkins, Docker, Kubernetes, AWS EKS, AWS Load Balancer Controller, Application Load Balancer, Route 53, frontend configuration, backend configuration, MongoDB, and Redis.

The main troubleshooting process was:

```text
Identify the failing layer
        |
        v
Check logs / output
        |
        v
Find the root cause
        |
        v
Change only the required configuration
        |
        v
Deploy again
        |
        v
Verify the result
```

This approach helped resolve the deployment issues while keeping the working parts of the application unchanged.
