# Repository Guidelines

## Project Structure & Module Organization
- Source: `src/main/java/com/houqing/tools/video_compressor_backend/` with packages: `controller`, `service`, `dto`, `config`.
- Config: `src/main/resources/application.properties` (FFmpeg paths and file I/O dirs).
- Tests: `src/test/java/...` mirror the main package; default JUnit 5.
- Build output: `target/` (do not commit).
- Key properties: `ffmpeg.ffmpeg-path`, `ffmpeg.ffprobe-path`, `file.upload-dir`, `file.output-dir`.

## Build, Test, and Development Commands
- Prereqs: JDK 17+, Maven Wrapper, FFmpeg/FFprobe available (PATH or properties).
- Build JAR: `./mvnw clean package` (Windows: `mvnw.cmd clean package`).
- Run locally: `./mvnw spring-boot:run`.
- Run tests: `./mvnw test` (or from IDE). 
- IDE: run `VideoCompressorBackendApplication` main class.

## Coding Style & Naming Conventions
- Java 17, 4-space indentation, UTF-8 files.
- Packages: lowercase; classes: PascalCase; methods/fields: camelCase.
- Suffixes: controllers `*Controller`, services `*Service`, DTOs `*DTO`.
- Use Spring stereotypes (`@RestController`, `@Service`, `@Configuration`) and Lombok where present.
- API routes live under `/api/video/**` to match existing controller.

## Testing Guidelines
- Framework: JUnit 5 with Spring Boot (`@SpringBootTest`).
- Naming: `ClassNameTests` in the same package as the class under test.
- Prefer unit tests for `service` and lightweight MVC tests for controllers; add integration tests only when needed.
- Run tests via `./mvnw test`; keep tests independent and fast.

## Commit & Pull Request Guidelines
- Commits: concise, imperative summaries (EN/ZH OK). Example: `fix: CORS preflight parsing` or `feat: 添加比特率压缩模式`.
- Include context, affected modules, and references (e.g., `#123`).
- PRs: clear description, steps to verify, screenshots/logs when UI/API behavior changes, note config impacts (FFmpeg paths, CORS, ports).

## Security & Configuration Tips
- Ensure `file.upload-dir` and `file.output-dir` exist and are writable.
- Configure FFmpeg via PATH or `application.properties` (`ffmpeg.ffmpeg-path`, `ffmpeg.ffprobe-path`).
- Adjust allowed origins in `config/CorsConfig.java`; default port is `8080`.
