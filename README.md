# Task Tracker - Backend

Spring Boot REST + WebSocket API for the Task Tracker app: JWT auth, role-based access control (User/Admin), task assignment workflow, real-time updates.

**Companion repo:** the React frontend lives in a separate repository (`task-tracker-frontend`), deployed to Vercel. This repo is backend-only, deployed as a Docker container to AWS EC2, pulling images from ECR, backed by RDS MySQL.

Stack: Spring Boot 3.2 · Spring Security 6 (JWT) · Spring Data JPA · MySQL / AWS RDS · WebSocket (STOMP) · Spring Boot Actuator · JaCoCo · SonarQube

## DevOps & CI/CD Highlights

This project demonstrates **production-ready DevOps practices**:

- **GitHub Actions CI/CD Pipeline**: Automated build, test, and deployment on every push to `main`
- **AWS Integration**: Docker images pushed to Amazon ECR, deployed to EC2 with automatic container updates
- **SonarQube/SonarCloud**: Continuous code quality analysis with coverage metrics via JaCoCo
- **Checkstyle**: Automated code style enforcement using Google Java Style Guide
- **Zero-Downtime Deployment**: Graceful container restart with health checks
- **Environment-Based Configuration**: Single codebase works across local, Docker, and production AWS environments

---

## Setup Instructions

### Backend Setup

**Option A — Docker Compose (fastest, local MySQL included):**
```bash
docker compose up --build
```
Starts MySQL + the backend together at `http://localhost:8080`, using the `prod` profile with environment variables (quieter console logs, no SQL echoed).

**Option B — Run directly with Maven:**
1. Have a local MySQL reachable (or use Option A instead).
2. Copy `.env.example` to `.env` for reference — Spring Boot doesn't auto-load `.env` files; either `export` the variables yourself or rely on the built-in defaults, which work against `localhost:3306/task_tracker` with `root`/`root`.
3. Run:
```bash
   mvn spring-boot:run
```
   API starts at `http://localhost:8080`.

**Running tests:**
```bash
mvn test -Dspring.profiles.active=test
```
Uses an in-memory H2 database — no MySQL needed. Generates a JaCoCo coverage report at `target/site/jacoco/`.

### Frontend Setup
The frontend is a separate repository (`task-tracker-frontend`). Clone it, `npm install`, `npm run dev` — full instructions are in that repo's own README. The only thing this backend needs to know about it: whatever origin the frontend runs on (`http://localhost:5173` by default) must be listed in `CORS_ALLOWED_ORIGINS` here (already the default for local dev).

### Environment Configuration

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | localhost / 3306 / task_tracker / root / root | In production, point at your RDS endpoint |
| `DB_USE_SSL` / `DB_SSL_MODE` | false / DISABLED | Set to `true` / `REQUIRED` for RDS |
| `APP_JWT_SECRET` | dev-only default | **Must** override in any real deployment (32+ chars) |
| `APP_JWT_EXPIRATION_MS` | 3600000 | JWT expiry (1 hour) |
| `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` | admin / Admin@123 | Auto-seeded admin credentials — change before/after first run |
| `CORS_ALLOWED_ORIGINS` | localhost:5173,localhost:3000 | Comma-separated. Set to your Vercel URL(s) in production |
| `SERVER_PORT` | 8080 | |
| `SPRING_PROFILES_ACTIVE` | dev | `dev` (console only) or `prod` (quieter console + rolling files under `LOG_PATH`) |
| `LOG_PATH` | ./logs | Only used when `SPRING_PROFILES_ACTIVE=prod` |

See `.env.example` for the full annotated list.

### Database Setup
- **Local:** `docker compose up` provisions a MySQL 8.0 container automatically — nothing to set up by hand. Tables are created automatically on first startup (`spring.jpa.hibernate.ddl-auto=update`); no manual schema scripts or migrations are needed to run this project.
- **Production (RDS):** create a MySQL 8.0 RDS instance, note its endpoint/port/db name/credentials, and set `DB_USE_SSL=true` + `DB_SSL_MODE=REQUIRED` (RDS enforces SSL by default). See "Deploying to AWS" below for the full walkthrough.
- **Default admin:** on first startup against an empty database, `AdminSeeder` automatically creates one admin account (`admin` / `Admin@123` by default, overridable via `APP_ADMIN_USERNAME`/`APP_ADMIN_PASSWORD`) — no manual SQL required. Safe no-op on every later restart once any admin exists. **Change the password after first login.**

---

## Design Decisions

### Architecture Overview
Standard layered architecture, `Controller → Service → Repository`, with a few deliberate additions:

src/main/java/com/tasktracker/
├── config/        SecurityConfig (JWT filter chain, CORS), WebSocketConfig, AdminSeeder
├── controller/     AuthController, TaskController, UserController - thin, no business logic
├── dto/            request/response shapes, decoupled from JPA entities
├── entity/         User, Task, Role, TaskStatus, TaskPriority
├── exception/      GlobalExceptionHandler (@RestControllerAdvice) + custom exceptions
├── repository/     Spring Data JPA + Specification-based dynamic filtering
├── security/       JwtUtil, JwtAuthFilter, CustomUserDetailsService
├── service/        AuthService, TaskService, UserService - all business logic and RBAC lives here
└── websocket/      TaskEventPublisher - sanitized real-time event broadcast

- **Auth flow:** `/api/v1/auth/login` → Spring's `AuthenticationManager` validates credentials (also checks the account isn't deactivated) → `JwtUtil` issues a 1-hour token → `JwtAuthFilter` validates that token (and re-checks the account is still active) on every subsequent request.
- **Real-time flow:** any task create/update/delete publishes `{action, taskId}` (never the full entity) to `/topic/tasks` over STOMP/SockJS. Clients refetch the authenticated REST endpoint on receiving it, so RBAC is enforced exactly once, in one place, regardless of how many clients are subscribed.
- **Task ownership model:** `Task.owner` is nullable. An Admin creating a task without assigning anyone produces an `UNASSIGNED`-status, owner-less row, invisible to every regular User's queries (which always scope to `owner = themselves`) until an Admin assigns it.

### Key Implementation Decisions
- **RBAC enforced in the service layer**, not just `@PreAuthorize` — per-row ownership checks (e.g. "can this user edit *this* task?") happen after the entity loads, in `TaskService`, which is directly unit-testable without spinning up Spring Security.
- **Status is never client-chosen on create** — the server decides `IN_PROGRESS` (self-created), `PENDING` (assigned to someone else), or `UNASSIGNED` (no owner picked), and a task's status can't be changed at all until it has an owner.
- **Tasks can only be assigned to active, non-admin users** — enforced server-side regardless of what the frontend sends.
- **CORS/JWT secret/admin credentials/log routing are all environment-driven**, not hardcoded — the same jar runs correctly in local dev, Docker Compose, and the EC2/RDS production target just by changing env vars.
- **Logging is per-service, not one firehose file** — see "Logging" below.

---

## Assumptions
- Tasks can only ever be assigned to **active, non-admin** users — assigning to another Admin, or to a deactivated account, is rejected with a 400.
- A task's due date cannot be in the past **on create**; editing an already-overdue task without touching its due date is still allowed (only a genuinely new past date is rejected).
- Reassigning an already-assigned task (not just first-time assignment from Unassigned) keeps its current status untouched — only the owner/assignedBy fields change.
- `ON DELETE RESTRICT` on `Task.owner` is achieved simply by not configuring any cascade — MySQL's default FK behavior without an explicit `ON DELETE` clause is exactly RESTRICT.
- No dedicated staging environment — the CI/CD pipeline treats `main` as the only deploy target; `develop` gets test/SonarQube feedback but no deployment.

---

## Future Improvements
- Refresh tokens (currently just a 1-hour access token + forced re-login).
- Rate limiting on auth endpoints.
- Soft-delete for tasks instead of hard delete.
- Flyway/Liquibase migrations instead of `ddl-auto=update` for schema changes in production.
- Push a real-time notification when a task is assigned, rather than relying on the next refetch.
- A self-service `/auth/change-password` endpoint (there currently isn't one — changing the seeded admin's password requires a direct DB update).
- A staging environment/branch in the CI/CD pipeline, separate from production.
- Enforce the SonarQube quality gate as a CI failure condition (currently the scan runs and reports, but doesn't block the pipeline on failure).

---

## API Documentation

### Postman Collection
Import `TaskTracker.postman_collection.json` into Postman. It covers every implemented endpoint: register/login (regular user and admin, both auto-save their JWT via test scripts), full task CRUD, pagination/filtering (search/status/priority/owner/unassigned/overdue), quick inline status change, admin task assignment, and user activation/deactivation. Requests are grouped and ordered so they can be run top-to-bottom with minimal manual setup.

### Postman Environment
Import `TaskTracker.postman_environment.json` alongside the collection and select it (top-right environment dropdown in Postman) for a ready-to-go `baseUrl` pointing at `http://localhost:8080/api/v1` — duplicate/edit it if you need to point at a deployed environment instead.

---

## Version Control
This repo ships with a real, staged commit history — not one bulk commit. Run `git log --oneline` after cloning to see it; commits are scoped by concern (core scaffold → security → business logic → WebSocket → admin seeding → logging/profiles → tests → Docker → CI/CD → docs), plus a further sequence of bug-fix commits on top (due-date validation, unassigned-task status handling, the priority-filter fix, etc.). Keep committing in similarly-scoped chunks going forward rather than in one large commit per feature.

---

## Additional Operational Notes

### Logging
Every service logs its own operations (creates/updates/deletes, logins, activation changes) and errors via SLF4J — no per-request debug noise.
- **`dev`/`test` profile:** console only.
- **`prod` profile:** console **plus** one rotating log file per service under `LOG_PATH` (`auth-service.log`, `task-service.log`, `user-service.log`, `security.log`), plus a dedicated `error.log` catching ERROR-level events app-wide. Rotation: daily or 20MB, 30 days retained, 200MB cap per file family. Config lives entirely in `logback-spring.xml`.

### Health Check
`GET /actuator/health` is public (no auth) and exposes only `health`/`info` — used by Docker's own `HEALTHCHECK`, and should be wired into your EC2/ALB target group health check the same way.

### Deploying to AWS (ECR + EC2 + RDS)

**1. RDS (MySQL):** create a MySQL 8.0 RDS instance, note the endpoint/port/db/username/password. RDS enforces SSL by default — set `DB_USE_SSL=true` and `DB_SSL_MODE=REQUIRED` wherever the backend runs.

**2. ECR:** create a private ECR repository (e.g. `task-tracker-backend`). The CI/CD pipeline builds and pushes to it on every merge to `main`.

**3. EC2:** launch an instance (Amazon Linux 2023 or Ubuntu) with Docker installed and AWS CLI configured (an IAM instance role with `AmazonEC2ContainerRegistryReadOnly` is the cleanest approach — avoids storing AWS credentials on the box). Open port `8080` (or put it behind a load balancer/Nginx and only expose 80/443).

Create `/home/<ec2-user>/task-tracker.env` on the instance with the **real** production values (this file never leaves the box, and is never passed through GitHub Actions logs):

DB_HOST=<your-rds-endpoint>
DB_PORT=3306
DB_NAME=task_tracker
DB_USERNAME=<rds-username>
DB_PASSWORD=<rds-password>
DB_USE_SSL=true
DB_SSL_MODE=REQUIRED
APP_JWT_SECRET=<a-real-long-random-secret>
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=<a-real-password-you-will-change-after-first-login>
CORS_ALLOWED_ORIGINS=https://your-app.vercel.app
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod
LOG_PATH=/app/logs


**4. GitHub Secrets** (Settings → Secrets and variables → Actions):

| Secret | Purpose |
|---|---|
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | IAM user with ECR push permissions (used by the CI runner, not the EC2 box) |
| `AWS_REGION` | e.g. `us-east-1` |
| `AWS_ACCOUNT_ID` | Your 12-digit AWS account ID (used to build the ECR registry URL) |
| `ECR_REPOSITORY` | e.g. `task-tracker-backend` |
| `EC2_HOST` | Public IP or DNS of the EC2 instance |
| `EC2_USERNAME` | e.g. `ec2-user` or `ubuntu` |
| `EC2_SSH_KEY` | Private key (PEM contents) matching a public key installed on the instance |
| `SONAR_TOKEN` | SonarQube/SonarCloud auth token |
| `SONAR_HOST_URL` | Your SonarQube server URL, or `https://sonarcloud.io` |
| `SONAR_PROJECT_KEY` | Project key as registered in SonarQube/SonarCloud |

**5. Pipeline flow** (`.github/workflows/ci-cd.yml`):
1. **Build & Test** — Checkstyle (non-blocking) + `mvn test` (H2) + JaCoCo coverage report
2. **SonarQube Analysis** — Full test run + `mvn sonar:sonar` for code quality metrics, security hotspots, and coverage tracking
3. **Docker Build & Push** — *only on push to `main`* — builds the Docker image, tags with commit SHA + `latest`, pushes both to Amazon ECR
4. **Deploy to EC2** — *only on push to `main`* — SSHes into EC2, pulls the new image from ECR, stops/removes old container, starts new container with production environment variables, performs image cleanup

### SonarQube Setup
Either self-hosted SonarQube or SonarCloud works — both are driven by the same `mvn sonar:sonar` goal (see the `sonar-maven-plugin` in `pom.xml`). Create the project first, grab its project key and a token, set the three `SONAR_*` secrets above. Coverage comes from the JaCoCo report generated during `mvn test`.

### CI/CD Architecture

This project implements a **complete DevOps pipeline** suitable for production environments:

**Quality Gates:**
- **Code Style**: Google Java Style Guide enforced via Checkstyle Maven plugin
- **Test Coverage**: JaCoCo generates coverage reports during test execution
- **Code Quality**: SonarQube analyzes code for bugs, code smells, security hotspots, and technical debt
- **Build Verification**: All tests must pass before deployment proceeds

**AWS Deployment Strategy:**
- **Container Registry**: Amazon ECR stores Docker images with version tags (commit SHA + latest)
- **Compute**: EC2 instance runs Docker containers with health checks
- **Database**: Amazon RDS MySQL with SSL encryption
- **Secrets Management**: Production credentials stored in GitHub Secrets, never in code
- **Zero-Downtime**: Graceful container replacement with automatic rollback capability

**Pipeline Orchestration:**
- Jobs run sequentially with dependencies (test → SonarQube → build → deploy)
- Only `main` branch triggers deployment to production
- Debug steps validate secret presence before critical operations
- Automatic image cleanup prevents disk space issues