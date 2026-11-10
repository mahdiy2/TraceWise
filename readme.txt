# Project: StackTrace Studio
## Autonomous Development Instructions

You are a senior Kotlin Multiplatform engineer, software architect, and product developer.

Your task is to design and implement a lightweight, production-oriented, cross-platform Android Stack Trace Deobfuscation tool called **StackTrace Studio**.

The goal is to build a tool that allows developers to upload Android APK files and R8/ProGuard mapping files, submit obfuscated crash logs, and receive readable, deobfuscated stack traces.

This project must support:
- Linux Desktop
- Windows Desktop
- Web Application
- An independent REST API backend for integration with existing systems.

IMPORTANT: This is a small utility project. Do not overengineer it. Prioritize working functionality, simplicity, maintainability, and fast development.

You have permission to make reasonable technical decisions and implement the project autonomously. Do not stop after producing an architecture document or a development plan. Actually build the application.

---

# 1. Core Product Requirements

The application must provide the following capabilities:

### 1.1 Project Management

Users must be able to create and manage multiple Android application projects.

Each project should contain:

- Project name
- Application ID / Package name
- Description (optional)
- Creation date

Example:

- Diyan Driver
- com.anibaran.kshdriver

### 1.2 Build and Mapping Management

Each project can have multiple application builds.

A build should contain:

- Version name
- Version code
- Build variant (debug/release)
- Mapping file (mapping.txt)
- APK file (optional)
- APK SHA-256 (optional)
- Mapping SHA-256
- Creation date

Users must be able to:

1. Upload a mapping.txt file.
2. Optionally upload its corresponding APK.
3. Associate these files with a specific project and build.
4. View all uploaded builds.
5. Replace or delete mapping files.
6. Identify builds by version code and version name.

IMPORTANT:

An APK is NOT required for standard R8 Retrace operations. The mapping file is the essential input.

Do not make APK upload mandatory.

APK files should be stored as optional build artifacts. Do not execute or install uploaded APK files.

### 1.3 Stack Trace Deobfuscation

This is the most important feature.

Users must be able to:

- Paste a single stack trace as plain text.
- Submit multiple stack traces.
- Upload a .txt log file.
- Select an application project.
- Select a specific build/version.
- Deobfuscate the stack trace using its corresponding mapping file.
- View original and translated stack traces side by side.
- Copy translated results.
- Download translated results as a .txt file.

Support Android crash stack traces, including:

- Java exceptions
- Kotlin exceptions
- NullPointerException
- IllegalStateException
- RuntimeException
- Caused by sections
- Suppressed exceptions
- Android framework stack frames
- Nested exceptions
- Coroutine-related stack frames

Use Google's official R8 Retrace implementation.

Do NOT implement your own mapping parser or deobfuscation algorithm.

Use the official R8 Retrace API or its supported CLI interface. Investigate the current official API, dependency availability, and supported integration approach before implementing it.

The implementation must support:
- Obfuscated class names
- Obfuscated method names
- Line number restoration
- Inlined methods where supported by R8 Retrace
- Original source file and method information

Preserve the original stack trace and never overwrite it.

If a frame cannot be resolved, preserve it unchanged.

If a mapping file is invalid or missing, return a meaningful error.

---

# 2. Technology Stack

Use the following technologies unless a genuine technical incompatibility requires a justified alternative.

### Shared Application

- Kotlin
- Kotlin Multiplatform
- Compose Multiplatform
- MVVM
- Clean Architecture (lightweight implementation)
- Koin for dependency injection
- Kotlin Coroutines
- Kotlin Flow
- Kotlinx Serialization
- Ktor Client

### Desktop

- Compose Multiplatform Desktop
- JVM
- Windows distribution
- Linux distribution

### Web

- Compose Multiplatform Web where practical.
- Use the currently supported Kotlin/Wasm or JS target.
- If Web support creates significant compatibility or maintenance problems, use a lightweight separate web client while sharing common domain models and API contracts.

Do not compromise desktop functionality just to force everything into a single UI target.

### Backend

- Kotlin/JVM
- Ktor Server
- REST API
- Kotlinx Serialization
- Koin

### Storage

Keep persistence simple.

For the initial implementation:
- SQLite is acceptable.
- Store uploaded mapping and APK files in a configurable filesystem storage directory.
- Store file metadata and project/build relationships in the database.

Design storage access behind simple interfaces so it can be replaced later if needed.

Do not introduce microservices, Kubernetes, Kafka, Redis, or unnecessary infrastructure.

---

# 3. Architecture

Use a modular architecture without unnecessary abstraction.

Suggested structure:

```text
StackTraceStudio/
│
├── composeApp/
│   └── src/
│       ├── commonMain/
│       ├── desktopMain/
│       ├── wasmJsMain/
│       └── jvmMain/
│
├── shared/
│   └── src/
│       ├── commonMain/
│       │   ├── domain/
│       │   ├── data/
│       │   ├── models/
│       │   └── utils/
│       │
│       └── jvmMain/
│           └── retrace/
│
├── server/
│   └── src/
│       └── main/
│
├── gradle/
│
└── README.md
```

You may adjust the structure if necessary.

Architectural rules:

1. Keep business logic out of Compose UI.
2. Use ViewModels for presentation logic.
3. Keep repositories responsible for data access.
4. Use UseCases only when they provide meaningful value.
5. Keep common code platform-independent.
6. Keep Google R8 Retrace integration in JVM-specific code.
7. The Retrace Engine must be reusable independently of the UI.
8. Desktop should be capable of running Retrace locally without requiring a backend.
9. Web should communicate with the backend through HTTP.
10. The server must reuse the same Retrace implementation.

Avoid unnecessary interfaces, factories, wrappers, and abstraction layers.

Do not create a giant ViewModel or a giant service class.

---

# 4. Retrace Engine

Implement a dedicated Retrace Engine.

Suggested responsibilities:

```kotlin
interface RetraceEngine {

    suspend fun retrace(
        mappingFile: File,
        stackTrace: String
    ): RetraceResult

    suspend fun retraceBatch(
        mappingFile: File,
        stackTraces: List<String>
    ): List<RetraceResult>
}
```

Adapt the API to the actual supported R8 Retrace API and your chosen JVM architecture.

Requirements:

- Use Google's official R8 Retrace implementation.
- Avoid loading the mapping file repeatedly for every frame.
- Reuse parsed mapping data where safely supported.
- Do not share mutable state unsafely between concurrent requests.
- Support large stack traces within reasonable memory limits.
- Handle malformed input gracefully.
- Support batch processing.
- Include meaningful error information.

Use the official Google R8 project:

https://r8.googlesource.com/r8

Investigate the official Retrace documentation and implementation before writing integration code.

Do not assume a particular dependency version or API signature without verifying it.

---

# 5. Backend REST API

Build an independent Ktor server.

Suggested API endpoints:

### Projects

```http
GET    /api/v1/projects
POST   /api/v1/projects
GET    /api/v1/projects/{projectId}
PUT    /api/v1/projects/{projectId}
DELETE /api/v1/projects/{projectId}
```

### Builds

```http
GET    /api/v1/projects/{projectId}/builds
POST   /api/v1/projects/{projectId}/builds
GET    /api/v1/builds/{buildId}
DELETE /api/v1/builds/{buildId}
```

### File Upload

```http
POST /api/v1/builds/{buildId}/mapping
POST /api/v1/builds/{buildId}/apk
```

Use multipart/form-data for file uploads.

### Retrace

```http
POST /api/v1/retrace
POST /api/v1/retrace/batch
```

Example request:

```json
{
  "projectId": "project-id",
  "versionCode": 72,
  "stackTrace": "java.lang.NullPointerException\n at a.b.c(Unknown Source:12)"
}
```

Example response:

```json
{
  "success": true,
  "original": "java.lang.NullPointerException...",
  "translated": "java.lang.NullPointerException...",
  "status": "SUCCESS",
  "build": {
    "versionCode": 72,
    "versionName": "2.1.4"
  }
}
```

For batch requests:

```json
{
  "projectId": "project-id",
  "versionCode": 72,
  "stackTraces": [
    "stack trace one",
    "stack trace two",
    "stack trace three"
  ]
}
```

Return individual results for every submitted stack trace.

Use meaningful HTTP status codes and structured error responses.

Implement:
- Request validation
- File size limits
- File type validation
- Exception handling
- Logging
- Configurable server port
- Configurable storage directory
- Configurable API authentication

Do not expose uploaded mapping files or internal storage paths through public endpoints.

---

# 6. Integration With Existing PHP Crash Reporting Systems

This requirement is extremely important.

The application must be designed so that an existing PHP backend can integrate with StackTrace Studio without changing its existing crash-reporting architecture.

Typical workflow:

1. Android application crashes.
2. Existing PHP API receives the crash report.
3. PHP stores the original crash report.
4. PHP sends the stack trace to StackTrace Studio.
5. StackTrace Studio identifies the correct mapping using the application ID and version code.
6. Retrace Engine translates the stack trace.
7. The backend returns the translated result.
8. PHP stores the translated stack trace alongside the original.
9. The existing PHP dashboard displays both versions.

The PHP backend must not need to understand R8 mapping files.

It should only communicate with the REST API.

Provide a complete PHP integration example.

Example:

```php
$response = Http::withToken($token)
    ->post('https://retrace.example.com/api/v1/retrace', [
        'applicationId' => 'com.example.app',
        'versionCode' => 72,
        'stackTrace' => $crash->stack_trace,
    ]);

$result = $response->json();

if ($response->successful() && $result['success']) {
    $crash->translated_stack_trace = $result['translated'];
    $crash->save();
}
```

The API should support identifying builds using:

- Application ID
- Version code

Do not rely on project IDs known only to the desktop application.

Provide a straightforward way to register applications and upload their mappings.

IMPORTANT:

PHP integration should be optional. The desktop application must work independently.

---

# 7. Desktop Application UI/UX

Build a modern developer-oriented interface.

Design inspiration:

- Android Studio
- Linear
- Raycast
- Modern developer tools

Use a clean, professional UI.

Avoid unnecessary animations, gradients, and decorative elements.

Support:
- Light theme
- Dark theme
- Responsive layouts
- Keyboard shortcuts where useful

Suggested screens:

### Dashboard

Display:

- Total projects
- Total builds
- Recently added builds
- Recent retrace operations

### Projects Screen

List all Android applications.

Allow creating, editing, and deleting projects.

### Build Management Screen

Display builds belonging to a selected project.

Allow mapping and APK uploads.

Show mapping file status and build information.

### Retrace Screen

This is the primary screen.

Suggested layout:

```text
--------------------------------------------------
 StackTrace Studio
--------------------------------------------------

 Project: [ Diyan Driver       v ]
 Build:   [ 2.1.4 (72)          v ]

--------------------------------------------------
 Original Stack Trace
--------------------------------------------------

 [ Paste stack trace here                    ]

 [ Load TXT ]                [ Retrace ]

--------------------------------------------------
 Translated Stack Trace
--------------------------------------------------

 [ Readable translated stack trace           ]

 [ Copy ] [ Export TXT ]

--------------------------------------------------
```

For batch operations, allow multiple stack traces and display results separately.

Use syntax highlighting for stack traces.

Highlight:
- Exception names
- Class names
- Method names
- Source files
- Line numbers

Add clear loading, success, empty, and error states.

---

# 8. Web Application

The web application should provide the main functionality without requiring users to install desktop software.

It should support:

- Project management
- Build management
- Mapping uploads
- Stack trace submission
- Batch retrace
- Translated result viewing
- Copying and downloading results

The web client must communicate with the Ktor backend.

Do not attempt to run JVM-based R8 Retrace inside the browser.

Use the same API contracts and shared models where practical.

---

# 9. Performance and Security

Follow these requirements:

- Never expose mapping files publicly.
- Never execute uploaded files.
- Validate uploaded files.
- Limit upload sizes through configuration.
- Validate application and build identifiers.
- Use authentication for server-to-server integration.
- Do not log sensitive mapping contents.
- Preserve original crash reports.
- Handle concurrent Retrace requests safely.
- Avoid loading APK files into memory unnecessarily.
- Provide useful error messages without exposing internal server exceptions.

The system should be suitable for internal developer tooling and small-to-medium applications.

Do not introduce enterprise-level complexity.

---

# 10. Testing

Write meaningful tests for:

### Retrace Engine

- Valid mapping file
- Valid obfuscated stack trace
- Multiple stack traces
- Invalid mapping file
- Empty stack trace
- Unmapped stack frames
- Inlined methods
- Incorrect line numbers
- Missing mapping file

Use real or representative R8-generated mapping fixtures.

Do not write tests that simply mock the Retrace Engine and claim that deobfuscation works.

### Backend

- Project creation
- Build creation
- Mapping upload
- Retrace endpoint
- Batch retrace
- Missing build
- Invalid request
- Authentication

### UI

Test important state transitions and error states where practical.

---

# 11. Development Strategy

Implement the project in working increments.

Do not attempt to create the entire application in one giant code generation step.

Follow this order:

## Phase 1: Foundation

- Inspect the development environment.
- Create the Gradle project.
- Configure Kotlin Multiplatform.
- Configure Compose Multiplatform.
- Configure desktop targets for Linux and Windows.
- Configure the Web target.
- Configure Koin.
- Configure the basic modular structure.
- Ensure the project builds.

## Phase 2: Retrace Engine

- Integrate Google R8 Retrace.
- Implement the JVM engine.
- Add real mapping fixtures.
- Implement single and batch operations.
- Write unit tests.
- Verify actual deobfuscation.

Do not proceed to UI polish before this works.

## Phase 3: Desktop Application

- Implement project management.
- Implement build management.
- Implement mapping upload.
- Implement stack trace input.
- Implement translation.
- Implement result viewer.
- Implement export and copy functionality.

The desktop application should work without a backend.

## Phase 4: Backend

- Implement Ktor server.
- Implement persistence.
- Implement file storage.
- Implement project/build APIs.
- Implement mapping upload.
- Implement Retrace APIs.
- Implement authentication.
- Add API integration tests.

## Phase 5: Web Application

- Implement web UI.
- Connect it to the backend.
- Implement the main workflows.
- Verify actual browser functionality.

## Phase 6: Integration and Documentation

- Write PHP integration examples.
- Document API endpoints.
- Document local development.
- Document Linux installation.
- Document Windows installation.
- Document backend deployment.
- Provide environment configuration examples.

---

# 12. Build and Delivery Requirements

The final project must include:

1. Working Linux desktop application.
2. Working Windows desktop application.
3. Working Web application.
4. Independent Ktor backend.
5. Actual R8 Retrace integration.
6. Working single-stack-trace translation.
7. Working batch translation.
8. Mapping management.
9. PHP integration documentation.
10. Automated tests.
11. README with setup instructions.
12. Example configuration files.
13. Build and run commands for every target.

Provide executable build scripts where useful.

For desktop distribution, use Gradle Compose distribution tasks and verify that the generated packages are usable on their respective platforms.

For backend deployment, provide a Dockerfile and Docker Compose configuration if practical.

---

# 13. Critical Instructions for the AI Agent

These rules are mandatory:

1. Do not stop at planning. Implement the project.
2. Do not generate fake functionality or placeholder implementations.
3. Do not claim a feature works unless it has been implemented and tested.
4. Do not invent R8 APIs or dependency versions. Verify them.
5. Do not write a custom ProGuard/R8 mapping parser.
6. Do not overengineer the architecture.
7. Do not create unnecessary modules.
8. Keep desktop and backend Retrace logic reusable.
9. Keep common code genuinely platform-independent.
10. Use stable, compatible dependency versions whenever possible.
11. Avoid unnecessary dependency upgrades.
12. Compile and test after meaningful changes.
13. Fix compilation errors before moving forward.
14. Preserve existing working code when modifying the project.
15. Do not leave unfinished TODOs in critical functionality.
16. If a platform limitation prevents a requirement, document it and implement the best practical alternative.
17. Do not ask for confirmation for routine implementation decisions.
18. Maintain a DEVELOPMENT_PROGRESS.md file to track completed tasks, remaining tasks, known issues, and build status.
19. Make focused, logically grouped commits if Git is available.
20. Keep the README updated as implementation progresses.

If you discover that the proposed architecture is technically incompatible with a platform, adjust it using the simplest reliable solution and explain the decision in the documentation.

---

# Final Goal

Deliver a functional product, not just a demo or architecture prototype.

The most important user journey must work end-to-end:

1. Create an Android application project.
2. Upload its mapping.txt.
3. Select its build version.
4. Paste an obfuscated Android crash stack trace.
5. Press Retrace.
6. Receive the original readable stack trace.
7. Copy or export the result.

The same Retrace functionality must also be accessible through the Ktor REST API so an existing PHP crash-reporting backend can use it without implementing any mapping-related logic itself.

Prioritize a working, lightweight, maintainable product over architectural perfection.

Start by inspecting the repository and environment, then implement Phase 1 and continue through the phases autonomously.
