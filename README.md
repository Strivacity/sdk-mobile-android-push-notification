# Push Notification SDK

## Development

Before committing, verify your changes pass formatting and static analysis checks locally:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v21) && ./gradlew spotlessCheck detekt lint
```

This runs:

- **Spotless** (`spotlessCheck`) — verifies Kotlin/Java code formatting via ktlint / google-java-format
- **Detekt** (`detekt`) — static analysis for Kotlin code smells and best practices
- **Android Lint** (`lint`) — Android-specific static analysis

If `spotlessCheck` fails, auto-fix formatting issues with:

```bash
./gradlew spotlessApply
```

These same checks run in CI on every pull request targeting `main`.
