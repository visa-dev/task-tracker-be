# Task Tracker - Backend

Spring Boot REST + WebSocket API for the Task Tracker app: JWT auth, role-based access control (User/Admin), task assignment workflow, real-time updates.

**Companion repo:** the React frontend lives in a separate repository, deployed to Vercel. This repo is backend-only, deployed as a Docker container to AWS EC2, pulling images from ECR, backed by RDS MySQL.

## Stack
Spring Boot 3.2 · Spring Security 6 (JWT) · Spring Data JPA · MySQL / AWS RDS · WebSocket (STOMP) · Spring Boot Actuator · JaCoCo · SonarQube

## Project Structure
```
.
├── .github/workflows/ci-cd.yml   # test -> SonarQube -> build/push ECR -> deploy EC2
├── docker-compose.yml            # LOCAL DEV ONLY (backend + local MySQL)
├── Dockerfile                    # multi-stage build, non-root user, actuator healthcheck
├── TaskTracker.postman_collection.json
└── src/
    ├── main/java/com/tasktracker/
    │   ├── config/        SecurityConfig, WebSocketConfig, AdminSeeder
    │   ├── controller/     AuthController, TaskController, UserController
    │   ├── dto/            request/response DTOs
    │   ├── entity/         User, Task, Role, TaskStatus, TaskPriority
    │   ├── exception/      GlobalExceptionHandler + custom exceptions
    │   ├── repository/     UserRepository, TaskRepository
    │   ├── security/       JwtUtil, JwtAuthFilter, CustomUserDetailsService
    │   ├── service/        AuthService, TaskService, UserService
    │   └── websocket/      TaskEventPublisher
    └── test/               unit tests (Mockito) for every service
```

## Local Development

### Option A: Docker Compose (fastest)
```bash
docker compose up --build
```
Starts MySQL + the backend together at `http://localhost:8080`. Uses the `dev` profile (console logging, SQL echoed).

### Option B: Run directly with Maven
1. Have a local MySQL running (or point `DB_HOST` etc. at any reachable instance).
2. Copy `.env.example` to `.env` for reference (Spring Boot doesn't auto-load it — either `export` the vars or rely on the built-in defaults, which work against `localhost:3306` with `root`/`root`).
3. Run:
   ```bash
   mvn spring-boot:run
   ```
   API starts at `http://localhost:8080`, tables auto-created (`ddl-auto=update`).

### Running tests
```bash
mvn test -Dspring.profiles.active=test
```
Uses an in-memory H2 database — no MySQL needed. Generates a JaCoCo coverage report at `target/site/jacoco/`.

## Default Admin Account
On first startup against an empty database, `AdminSeeder` automatically creates one admin account (`admin` / `Admin@123` by default — override via `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD`). This is a safe no-op on every later restart once any admin exists. **Change the password after first login**, or set both env vars before that first run.

## Environment Variables

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | localhost / 3306 / task_tracker / root / root | In production, point at your RDS endpoint |
| `DB_USE_SSL` / `DB_SSL_MODE` | false / DISABLED | Set to `true` / `REQUIRED` for RDS |
| `APP_JWT_SECRET` | dev-only default | **Must** override in any real deployment (32+ chars) |
| `APP_JWT_EXPIRATION_MS` | 3600000 | JWT expiry (1 hour) |
| `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` | admin / Admin@123 | Seeded admin credentials — change before/after first run |
| `CORS_ALLOWED_ORIGINS` | localhost:5173,localhost:3000 | Comma-separated. **Set to your Vercel URL(s) in production** |
| `SERVER_PORT` | 8080 | |
| `SPRING_PROFILES_ACTIVE` | dev | `dev` (console only) or `prod` (quieter console + rolling files under `LOG_PATH`) |
| `LOG_PATH` | ./logs | Only used when `SPRING_PROFILES_ACTIVE=prod` |

See `.env.example` for the full annotated list.

## Logging
Every service logs its own operations (creates/updates/deletes, logins, activation changes) and errors via SLF4J — no per-request debug noise.
- **`dev`/`test` profile:** console only.
- **`prod` profile:** console **plus** one rotating log file per service under `LOG_PATH` (`auth-service.log`, `task-service.log`, `user-service.log`, `security.log`), plus a dedicated `error.log` catching ERROR-level events app-wide. Rotation: daily or 20MB, 30 days retained, 200MB cap per file family. Config lives entirely in `logback-spring.xml` — no logging properties duplicated elsewhere.

## Health Check
`GET /actuator/health` is public (no auth) and exposes only `health`/`info` — used by Docker's own `HEALTHCHECK`, and should be wired into your EC2/ALB target group health check the same way.

---

## Deploying to AWS (ECR + EC2 + RDS)

### 1. RDS (MySQL)
Create a MySQL 8.0 RDS instance. Note the endpoint, port, database name, username, password. RDS enforces SSL by default — set `DB_USE_SSL=true` and `DB_SSL_MODE=REQUIRED` wherever the backend runs.

### 2. ECR
Create a private ECR repository (e.g. `task-tracker-backend`). The CI/CD pipeline builds and pushes to it on every merge to `main`.

### 3. EC2
Launch an EC2 instance (Amazon Linux 2023 or Ubuntu) with Docker installed, and the AWS CLI configured (an IAM instance role with `AmazonEC2ContainerRegistryReadOnly` is the cleanest way — avoids storing AWS credentials on the box). Open port `8080` (or put it behind a load balancer/Nginx and only expose 80/443).

Create `/home/<ec2-user>/task-tracker.env` on the instance with the **real** production values (this file never leaves the box, and is never passed through GitHub Actions logs):
```
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
```

### 4. GitHub Secrets
Set these in the repo's **Settings → Secrets and variables → Actions**:

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

### 5. Pipeline flow (`.github/workflows/ci-cd.yml`)
1. **test** — Checkstyle (non-blocking) + `mvn test` (H2) + coverage report, on every push/PR.
2. **sonarqube** — full test run + `mvn sonar:sonar`, on every push/PR (PR feedback + main-branch tracking).
3. **build-and-push** — *only on push to `main`* — builds the Docker image, tags it with the commit SHA and `latest`, pushes both to ECR.
4. **deploy** — *only on push to `main`* — SSHes into the EC2 instance, pulls the new `latest` image, stops/removes the old container, starts the new one with `--env-file /home/<user>/task-tracker.env`.

Push to `develop` or open a PR to get test + SonarQube feedback without deploying anything. Merge to `main` to ship.

## SonarQube Setup
Either self-hosted SonarQube or SonarCloud works — both are driven by the same `mvn sonar:sonar` goal (see the `sonar-maven-plugin` in `pom.xml`). Create the project in your SonarQube/SonarCloud instance first, grab its project key and a token, and set the three `SONAR_*` secrets above. Coverage comes from the JaCoCo report generated during `mvn test` (`target/site/jacoco/jacoco.xml`), wired in via `-Dsonar.coverage.jacoco.xmlReportPaths`.

## API Documentation
Import `TaskTracker.postman_collection.json` into Postman — covers auth, full task CRUD, pagination/filtering (search/status/priority/owner/unassigned/overdue), quick status change, admin assignment, and user activation.

## Design Decisions
- **RBAC enforced in the service layer**, not just `@PreAuthorize` — per-row ownership checks (e.g. "can this user edit *this* task?") happen after the entity loads, in `TaskService`, which is directly unit-testable without spinning up Spring Security.
- **Unassigned tasks are nullable-owner rows with their own `UNASSIGNED` status** — an Admin can create a task with no owner; it's invisible to every regular User (their queries always scope to `owner = themselves`, which a null owner never matches) and shows up in a dedicated Admin-only bucket until assigned.
- **Status is never client-chosen on create** — the server decides `IN_PROGRESS` (self-created), `PENDING` (assigned to someone else), or `UNASSIGNED` (no owner picked), and a task's status can't be changed at all until it has an owner.
- **WebSocket broadcasts `{action, taskId}` only, never the full entity** — clients refetch the authenticated REST endpoint on any event, so RBAC is enforced exactly once, in one place.
- **CORS/JWT secret/admin credentials/log routing are all environment-driven**, not hardcoded — the same jar runs correctly in local dev, Docker Compose, and the EC2/RDS production target just by changing env vars.

## Assumptions
- Tasks can only ever be assigned to **active, non-admin** users — assigning to another Admin, or to a deactivated account, is rejected with a 400.
- A task's due date cannot be in the past **on create**; editing an already-overdue task without touching its due date is still allowed (only a genuinely new past date is rejected).
- Reassigning an already-assigned task (not just first-time assignment from Unassigned) keeps its current status untouched — only the owner/assignedBy fields change.
- `ON DELETE RESTRICT` on `Task.owner` is achieved simply by not configuring any cascade — MySQL's default FK behavior without an explicit `ON DELETE` clause is exactly RESTRICT.

## Future Improvements
- Refresh tokens (currently a 1-hour access token + forced re-login).
- Rate limiting on auth endpoints.
- Soft-delete for tasks instead of hard delete.
- Flyway/Liquibase migrations instead of `ddl-auto=update` for schema changes in production.
- Push a real-time notification when a task is assigned, rather than relying on the next refetch.
- A `/auth/change-password` endpoint (currently changing the seeded admin's password requires re-registering logic or a direct DB update — there's no self-service password change yet).
