# Contributing to AndroGrid Android Worker

Thank you for your interest in contributing to the AndroGrid mobile worker application! We welcome community contributions to expand device hardware support, optimize execution performance, and enhance battery efficiency.

---

## Code of Conduct

All contributors are expected to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Development Workflow: GitHub Flow

1. **Fork and Branch:**
   - Always branch off `main`.
   - Use semantic branch naming conventions: `feat/feature-name`, `fix/bug-description`, or `chore/task-name`.
   - Direct commits to `main` are strictly forbidden.
2. **Test-First Development (TDD):**
   - Write failing Robolectric unit tests or coroutine test cases before implementing new features.
   - Verify tests pass locally: `./gradlew testDebugUnitTest`.
3. **Coding Standards:**
   - Follow standard Android and Kotlin style guides (`ktlint`).
   - All public classes, functions, and interfaces must include comprehensive KDoc docstrings.
   - Punctuation Rule: Never use em dash characters in documentation, comments, or commit messages. Use standard ASCII hyphens (-) or colons (:).
4. **Submitting Pull Requests:**
   - Open a pull request against `main`.
   - Provide context on how changes were verified across Android versions (especially API 28 vs API 34).
   - Commits will be squash-merged or rebased to maintain a clean linear history.

---

## Reporting Issues

- Use the GitHub Issue Tracker to report bugs or request features.
- Include device model, Android version, Root status (rooted vs unrooted), and Logcat traces.
- For security vulnerabilities, do NOT open a public issue. Refer to [SECURITY.md](SECURITY.md).
