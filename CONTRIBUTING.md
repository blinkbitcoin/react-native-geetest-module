## Development workflow

To get started with the project, run `pnpm install` in the root directory to install the required dependencies for each package:

```sh
pnpm install
```

> While it's possible to use [`npm`](https://github.com/npm/cli), the tooling is built around [`pnpm`](https://pnpm.io/), so you'll have an easier time if you use `pnpm` for development.

While developing, you can run the [example app](/example/) to test your changes. Any changes you make in your library's JavaScript code will be reflected in the example app without a rebuild. If you change any native code, then you'll need to rebuild the example app.

To start the packager:

```sh
pnpm example start
```

To run the example app on Android:

```sh
pnpm example android
```

To run the example app on iOS:

```sh
pnpm example ios
```

Make sure your code passes TypeScript and ESLint. Run the following to verify:

```sh
pnpm typescript
pnpm lint
```

To fix formatting errors, run the following:

```sh
pnpm lint --fix
```

Remember to add tests for your change if possible. Run the unit tests by:

```sh
pnpm test
```

That covers the JavaScript. The Android module has its own Robolectric suite, run
from `android/`:

```sh
./gradlew test
```

It needs JDK 17 on `JAVA_HOME` — Gradle 7.6.4 and AGP 7.4.2 do not run on 21. Both
suites run in CI on pull requests to `main`.

To edit the Objective-C files, open `example/ios/GeetestModuleExample.xcworkspace` in XCode and find the source files at `Pods > Development Pods > react-native-geetest-module`.

To edit the Kotlin files, open `example/android` in Android studio and find the source files at `reactnativegeetestmodule` under `Android`.

### Linting and tests

[ESLint](https://eslint.org/), [Prettier](https://prettier.io/), [TypeScript](https://www.typescriptlang.org/)

We use [TypeScript](https://www.typescriptlang.org/) for type checking, [ESLint](https://eslint.org/) with [Prettier](https://prettier.io/) for linting and formatting the code, and [Jest](https://jestjs.io/) for testing.

Our pre-commit hooks verify that the linter and tests pass when committing.

### Publishing to npm

Releases are handled by the CI pipeline: when changes land on `main`, the release job bumps the version based on the changelog, creates the GitHub release and publishes the package to npm.

### Scripts

The `package.json` file contains various scripts for common tasks:

- `pnpm bootstrap`: setup project by installing all dependencies and pods.
- `pnpm build`: build the library with tsup.
- `pnpm typescript`: type-check files with TypeScript.
- `pnpm lint`: lint files with ESLint.
- `pnpm test`: run unit tests with Jest.
- `pnpm example start`: start the Metro server for the example app.
- `pnpm example android`: run the example app on Android.
- `pnpm example ios`: run the example app on iOS.

### Sending a pull request

> **Working on your first pull request?** You can learn how from this _free_ series: [How to Contribute to an Open Source Project on GitHub](https://egghead.io/series/how-to-contribute-to-an-open-source-project-on-github).

When you're sending a pull request:

- Prefer small pull requests focused on one change.
- Verify that linters and tests are passing.
- Review the documentation to make sure it looks good.
- Follow the pull request template when opening a pull request.
- For pull requests that change the API or implementation, discuss with maintainers first by opening an issue.
