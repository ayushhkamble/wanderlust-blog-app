# Wanderlust Blog Application --- Deployment Troubleshooting

This document records the errors encountered while deploying the
**Wanderlust Blog Application** using **Jenkins, Docker Compose,
Node.js, MongoDB, Redis, and AWS EC2**.

It includes the error observed, checks performed, root cause, solution,
and final verification.

------------------------------------------------------------------------

## 1. Jenkins Dependency-Check Parameter Error

### Error

Jenkins pipeline compilation failed with:

``` text
Invalid parameter "additionalArguements", did you mean "additionalArguments"?
```

### Cause

There was a spelling mistake in the OWASP Dependency-Check Jenkins step.

Incorrect:

``` groovy
dependencyCheck additionalArguements: '--scan ./'
```

Correct:

``` groovy
dependencyCheck(
    additionalArguments: '--scan ./',
    odcInstallation: 'dc'
)
```

### Checks Performed

-   Reviewed the Jenkins compilation error.
-   Checked the parameter name in the pipeline.
-   Jenkins explicitly suggested `additionalArguments`.

### Solution

Changed:

``` text
additionalArguements
```

to:

``` text
additionalArguments
```

### Status

**Resolved**

------------------------------------------------------------------------

## 2. OWASP Dependency-Check Taking Too Long

### Problem

The OWASP Dependency-Check stage was taking a long time during the
Jenkins pipeline.

### Cause

Dependency-Check scans project dependencies against vulnerability data
from the NVD. Initial scans can take considerable time because
vulnerability data and project dependencies are analyzed.

### Checks Performed

Reviewed the Jenkins stage:

``` groovy
withCredentials([
    string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')
]) {
    dependencyCheck(
        additionalArguments: "--scan ./ --nvdApiKey ${NVD_API_KEY}",
        odcInstallation: 'dc'
    )
}
```

### Solution

An NVD API key was configured through Jenkins Credentials and passed to
Dependency-Check.

The key was not hard-coded into the Jenkinsfile.

### Resource Consideration

Dependency-Check can consume CPU and RAM while scanning. This is
important when Jenkins, SonarQube, Docker, and other services are
running on the same EC2 instance.

### Status

**Configured / Resolved**

------------------------------------------------------------------------

## 3. MongoDB Container Failed to Start

### Error

MongoDB initially used:

``` yaml
image: mongo:latest
```

The container failed with an error indicating that the MongoDB version
was incompatible with the Linux kernel running on the EC2 instance.

### Cause

The `mongo:latest` image pulled a MongoDB release affected by a Linux
kernel compatibility issue.

Using `latest` also means that the exact MongoDB version can change in
future deployments.

### Checks Performed

Ran:

``` bash
docker ps
```

MongoDB was not running.

Then:

``` bash
docker compose logs mongodb --tail 100
```

The logs showed the kernel compatibility error.

### Solution

Changed the MongoDB image from:

``` yaml
image: mongo:latest
```

to:

``` yaml
image: mongo:7.0
```

Final configuration:

``` yaml
mongodb:
  container_name: mongo
  image: mongo:7.0
  volumes:
    - ./backend/data:/data
  ports:
    - "27017:27017"
```

### Verification

MongoDB started successfully and showed:

``` text
Waiting for connections
port: 27017
```

and:

``` text
mongod startup complete
```

### Status

**Resolved**

### Lesson

Avoid relying on `latest` for important infrastructure images. Use a
tested and compatible version.

------------------------------------------------------------------------

## 4. MongoDB Hostname Resolution Error

### Error

The backend initially reported:

``` text
MongooseServerSelectionError: getaddrinfo ENOTFOUND mongodb
```

The backend was trying to connect to:

``` text
mongodb:27017
```

but could not resolve the hostname.

### Cause

The backend and MongoDB containers were not communicating through the
expected Docker Compose network at that point.

There was also confusion between Docker Compose service names and
container names.

The Compose service was:

``` yaml
mongodb:
```

while its container name was:

``` yaml
container_name: mongo
```

For Compose service-to-service communication, the service name `mongodb`
is the appropriate hostname.

### Incorrect Configuration

An earlier configuration used:

``` env
MONGODB_URI="mongodb://mongo-service/wanderlust"
```

There was no Compose service named `mongo-service`.

### Solution

Changed the MongoDB connection string to:

``` env
MONGODB_URI="mongodb://mongodb:27017/wanderlust"
```

### Checks Performed

Ran:

``` bash
docker exec backend getent hosts mongodb
```

Result:

``` text
172.18.0.3 mongodb
```

This confirmed that Docker DNS could resolve the MongoDB service.

The backend later confirmed:

``` text
Database connected: mongodb://mongodb:27017/wanderlust
```

### Status

**Resolved**

------------------------------------------------------------------------

## 5. Redis Hostname Resolution Error

### Error

The backend initially reported:

``` text
Error connecting to Redis: getaddrinfo ENOTFOUND redis-service
```

### Cause

The backend was configured to use:

``` env
REDIS_URL="redis://redis-service:6379"
```

but the Docker Compose service was named:

``` yaml
redis:
```

There was no service called `redis-service`.

### Solution

Changed the Redis URL to:

``` env
REDIS_URL="redis://redis:6379"
```

### Verification

Backend logs showed:

``` text
Redis Connected: redis://redis:6379
```

### Status

**Resolved**

------------------------------------------------------------------------

## 6. Backend Application Crashed

### Error

The backend initially started Node.js but then crashed:

``` text
Server is running on port 8080
MongooseServerSelectionError
```

followed by:

``` text
[nodemon] app crashed - waiting for file changes before starting...
```

### Cause

The backend process was unable to establish its MongoDB connection.

The MongoDB container was either unavailable or its hostname could not
be resolved.

### Checks Performed

Ran:

``` bash
docker logs backend --tail 100
```

The logs showed:

``` text
Server is running on port 8080
```

followed by:

``` text
getaddrinfo ENOTFOUND mongodb
```

### Solution

Fixed:

1.  MongoDB image compatibility.
2.  Docker Compose networking.
3.  MongoDB service hostname.
4.  Backend MongoDB connection string.

### Final Backend Logs

``` text
Server is running on port 8080
Database connected: mongodb://mongodb:27017/wanderlust
Redis Connected: redis://redis:6379
```

### Status

**Resolved**

------------------------------------------------------------------------

## 7. Backend Port Mapping

### Configuration

The Node.js backend listens on port:

``` text
8080
```

inside the container.

The EC2 host exposes it through port:

``` text
5000
```

Docker Compose mapping:

``` yaml
ports:
  - "5000:8080"
```

### Meaning

``` text
EC2 Host :5000
      |
      v
Container :8080
      |
      v
Node.js Backend
```

### Verification

`docker ps` showed:

``` text
0.0.0.0:5000->8080/tcp
```

### Status

**Correct / Working**

------------------------------------------------------------------------

## 8. Backend `curl` Connection Reset

### Error

Before MongoDB was fixed:

``` bash
curl -v http://localhost:5000
```

returned:

``` text
Recv failure: Connection reset by peer
curl: (56) Recv failure: Connection reset by peer
```

### Cause

The backend application was crashing because MongoDB was unavailable.

The Docker port was reachable, but the Node.js process was not staying
healthy.

### Checks Performed

The connection reached port `5000`, but the connection was reset.

Then backend logs showed:

``` text
MongooseServerSelectionError
getaddrinfo ENOTFOUND mongodb
```

### Solution

Fixed the MongoDB startup and backend database connection.

### Final Verification

After the fix:

``` bash
curl -v http://localhost:5000
```

returned:

``` text
HTTP/1.1 200 OK
```

and:

``` text
Yay!! Backend of wanderlust app is now accessible
```

### Status

**Resolved**

------------------------------------------------------------------------

## 9. Frontend API URL Was Incorrect

### Error

The frontend initially used:

``` env
VITE_API_PATH="http://ayushk.online:5173"
```

### Cause

Port `5173` is the frontend port, not the backend port.

The backend was exposed through port `5000`.

### Solution

Changed the frontend configuration to:

``` env
VITE_API_PATH="http://ayushk.online:5000"
```

### Checks Performed

The browser Network tab was used to identify the API request URL.

The correct API requests were eventually shown as:

``` text
http://ayushk.online:5000/api/posts/
```

### Status

**Resolved**

------------------------------------------------------------------------

## 10. Frontend Needed to Be Rebuilt After Environment Change

### Problem

The `VITE_API_PATH` variable is used by the Vite frontend during the
build process.

Changing `.env.docker` does not necessarily update an already-built
frontend image.

### Original Deployment Command

``` bash
docker compose up -d
```

### Solution

Rebuild the frontend image:

``` bash
docker compose down
docker compose up -d --build
```

For Jenkins, the deployment stage was changed to:

``` groovy
stage("Deploying using docker compose") {
    steps {
        sh "docker compose down"
        sh "docker compose up -d --build"
    }
}
```

### Status

**Resolved**

------------------------------------------------------------------------

## 11. Frontend Displayed `Network Error`

### Error

When clicking **Post blog**, the frontend displayed:

``` text
Error: Network Error
```

### Initial Checks

We checked:

-   Frontend container.
-   Backend container.
-   MongoDB container.
-   Redis container.
-   Frontend API URL.
-   Backend port mapping.
-   Backend local connectivity.
-   Browser Network requests.

The browser Network tab showed the request going to:

``` text
http://ayushk.online:5000/api/posts/
```

### Important Finding

The backend was working locally on the EC2 instance.

``` bash
curl -v http://localhost:5000
```

returned:

``` text
HTTP/1.1 200 OK
```

Therefore, the remaining problem was external access to port `5000`.

### Final Root Cause

The AWS EC2 Security Group was blocking inbound traffic on TCP port
`5000`.

### Solution

Added an inbound Security Group rule:

``` text
Type: Custom TCP
Port: 5000
Source: 0.0.0.0/0
```

### Verification

After allowing port `5000`, the browser could communicate with the
backend and the **Post blog** operation worked successfully.

### Status

**Resolved**

------------------------------------------------------------------------

## 12. Docker Compose `version` Warning

### Warning

Docker Compose displayed:

``` text
the attribute `version` is obsolete, it will be ignored
```

### Cause

The Compose file contained:

``` yaml
version: "3.8"
```

Modern Docker Compose does not require this field.

### Solution

The `version` field can be removed.

Instead of:

``` yaml
version: "3.8"

services:
```

use:

``` yaml
services:
```

### Important

This was only a warning and was **not responsible for the application
failure**.

### Status

**Non-critical warning**

------------------------------------------------------------------------

## 13. `nc` Command Not Found

### Error

We attempted to test the MongoDB port from inside the backend container:

``` bash
docker exec backend sh -c 'nc -zv mongodb 27017'
```

The container returned:

``` text
sh: 1: nc: not found
```

### Cause

The backend Docker image did not contain the `netcat` (`nc`) utility.

### Solution

No application change was required.

Docker DNS was tested using:

``` bash
docker exec backend getent hosts mongodb
```

which returned:

``` text
172.18.0.3 mongodb
```

The successful database connection in the backend logs provided the
final confirmation.

### Status

**Diagnostic issue only --- no application problem**

------------------------------------------------------------------------

# Final Environment Configuration

## Frontend

``` env
VITE_API_PATH="http://ayushk.online:5000"
```

## Backend

``` env
MONGODB_URI="mongodb://mongodb:27017/wanderlust"
REDIS_URL="redis://redis:6379"
PORT=8080
FRONTEND_URL="http://ayushk.online:5173"
```

## Docker Compose Port Mapping

``` yaml
mongodb:
  ports:
    - "27017:27017"

backend:
  ports:
    - "5000:8080"

frontend:
  ports:
    - "5173:5173"
```

------------------------------------------------------------------------

# Final Verification Checklist

After deployment, the following checks were performed.

### 1. Check containers

``` bash
docker ps
```

Expected:

``` text
mongo       Up
backend     Up
redis       Up
frontend    Up
```

### 2. Check MongoDB

``` bash
docker logs mongo --tail 50
```

Expected:

``` text
Waiting for connections
port: 27017
```

### 3. Check backend

``` bash
docker logs backend --tail 50
```

Expected:

``` text
Server is running on port 8080
Database connected: mongodb://mongodb:27017/wanderlust
Redis Connected: redis://redis:6379
```

### 4. Check Docker DNS

``` bash
docker exec backend getent hosts mongodb
```

Expected:

``` text
172.x.x.x mongodb
```

### 5. Check backend locally

``` bash
curl -v http://localhost:5000
```

Expected:

``` text
HTTP/1.1 200 OK
```

### 6. Check backend publicly

From a local machine/browser:

``` text
http://ayushk.online:5000/
```

Expected:

``` text
Yay!! Backend of wanderlust app is now accessible
```

### 7. Check frontend

``` text
http://ayushk.online:5173
```

### 8. Test application

Open:

``` text
http://ayushk.online:5173/add-blog
```

Create a blog and click:

``` text
Post blog
```

Expected result:

**Blog is successfully posted.**

------------------------------------------------------------------------

# Final Architecture

``` text
                         Internet
                            |
                            v
                  ayushk.online:5173
                            |
                            v
                 +-------------------+
                 |     Frontend      |
                 |    Vite / React   |
                 |      :5173        |
                 +---------+---------+
                           |
                           | API Request
                           v
                 AWS Security Group
                    TCP 5000 Allowed
                           |
                           v
                 +-------------------+
                 |      Backend      |
                 |   Node.js/Express |
                 | Container :8080   |
                 | Host :5000        |
                 +---------+---------+
                           |
                    Docker Compose
                     Internal Network
                           |
                 +---------+---------+
                 |                   |
                 v                   v
        +----------------+   +----------------+
        |    MongoDB     |   |     Redis      |
        |     :27017     |   |     :6379      |
        +----------------+   +----------------+

                         CI/CD
                           |
                           v
                       Jenkins
                           |
                           v
                  Docker Compose
                           |
                           v
                 Build & Deployment
```

------------------------------------------------------------------------

# Error Summary

  --------------------------------------------------------------------------------------------------------------------------------------
  \#             Error / Problem                              Root Cause                 Solution                         Status
  -------------- -------------------------------------------- -------------------------- -------------------------------- --------------
  1              `Invalid parameter "additionalArguements"`   Typo in Jenkins parameter  Changed to `additionalArguments` Resolved

  2              Dependency-Check taking too long             Vulnerability/dependency   NVD API key + resource           Resolved
                                                              scanning workload          monitoring                       

  3              MongoDB failed to start                      MongoDB/kernel             Changed `mongo:latest` to        Resolved
                                                              compatibility issue        `mongo:7.0`                      

  4              `ENOTFOUND mongodb`                          Docker hostname/network    Recreated Compose environment    Resolved
                                                              problem                    and used service name `mongodb`  

  5              `ENOTFOUND redis-service`                    Incorrect Redis hostname   Changed to `redis:6379`          Resolved

  6              Backend crashed                              MongoDB connection failure Fixed MongoDB and networking     Resolved

  7              Backend port configuration                   Host/container port        `5000:8080`                      Resolved
                                                              mapping                                                     

  8              `Connection reset by peer`                   Backend was crashing       Fixed MongoDB/backend connection Resolved

  9              Frontend API URL incorrect                   Pointed to frontend port   Changed to backend port `5000`   Resolved
                                                              `5173`                                                      

  10             Frontend retained old API configuration      Vite environment variable  `docker compose up -d --build`   Resolved
                                                              required rebuild                                            

  11             Browser `Network Error`                      AWS Security Group blocked Allowed inbound TCP `5000`       Resolved
                                                              TCP `5000`                                                  

  12             Compose `version` warning                    Obsolete Compose `version` Remove `version: "3.8"`          Non-critical
                                                              field                                                       

  13             `nc: not found`                              Netcat not installed in    Used `getent` instead            Diagnostic
                                                              backend image                                               only
  --------------------------------------------------------------------------------------------------------------------------------------

------------------------------------------------------------------------

# Troubleshooting Flow

``` text
Browser Network Error
        |
        v
Check Frontend API URL
        |
        v
API URL corrected to :5000
        |
        v
Check Backend
        |
        v
Backend crashing
        |
        v
Check MongoDB
        |
        v
MongoDB failed because of kernel compatibility
        |
        v
Change mongo:latest -> mongo:7.0
        |
        v
MongoDB starts
        |
        v
Backend cannot resolve mongodb
        |
        v
Fix Docker Compose networking/service names
        |
        v
MongoDB + Redis connected
        |
        v
curl localhost:5000 -> 200 OK
        |
        v
Browser still gets Network Error
        |
        v
Check AWS Security Group
        |
        v
TCP 5000 was blocked
        |
        v
Allow TCP 5000
        |
        v
Wanderlust Blog Application Working
```

------------------------------------------------------------------------

## Final Result

The Wanderlust Blog Application was successfully deployed using:

-   **Jenkins** --- CI/CD
-   **SonarQube** --- Code quality analysis
-   **OWASP Dependency-Check** --- Dependency vulnerability scanning
-   **Trivy** --- Filesystem security scanning
-   **Docker** --- Containerization
-   **Docker Compose** --- Multi-container deployment
-   **Node.js / Express** --- Backend
-   **MongoDB** --- Database
-   **Redis** --- Caching / supporting service
-   **Vite / React** --- Frontend
-   **AWS EC2** --- Deployment server
-   **AWS Security Group** --- Network access control

The final deployment was verified through Docker container checks,
application logs, Docker DNS resolution, local `curl` testing, browser
Network inspection, and successful blog creation.
