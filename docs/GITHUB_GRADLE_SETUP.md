# GitHub + Gradle setup status (v0.38)

## Completed locally

- Added `.github/workflows/android-build.yml` to provision JDK 17, Android SDK Platform 35, and Gradle 8.11.1 on GitHub Actions.
- Workflow runs `testDebugUnitTest`, `assembleDebug`, then publishes the debug APK and Android test report as artifacts.
- Added README instructions explaining the workflow.
- Re-ran `scripts/verify_project.sh`: all 34 Python tests passed; HTML/JavaScript packaging and Chromium smoke tests passed.

## Blockers / not yet completed

- The connected GitHub account was identified as `eeefff6989-pixel`, but no repositories were visible through the connected account. The connector also returned no installed accounts. No repository has been created or written by this workflow.
- The available GitHub connector actions do not expose repository creation or an Actions workflow-dispatch operation here.
- The environment has no Gradle executable, Gradle Wrapper JAR, Android SDK, or `adb`, and network DNS is unavailable. A real Gradle build cannot be run in this environment.
- The new workflow has not run. No APK has been built or validated on a device.

## Required next action

Create an empty repository in GitHub (recommended name `ii-pauk`) under the connected account, then upload/push the contents of this project archive. Ensure GitHub Actions is enabled. The `Android build and tests` workflow will run on a push touching `android-project/**` or `.github/workflows/android-build.yml`, or can be started manually from Actions > Android build and tests > Run workflow. Download the `ii-pauk-v0.38-debug-apk` artifact only if the job passes.

Do not commit signing keys, `.env` files, or API secrets. The generated debug APK is for testing, not a signed production release.
