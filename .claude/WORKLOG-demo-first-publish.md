# WORKLOG: the first publish of the demo page and image

2026-11-16, cloud session. The project lead ran the "Demo" workflow on `scala` for the first time (run 43661091506,
2026-11-15, at 261251f) and made the package `tda4j-demo` public. This is the check of what that run published, and
the fix for what it got wrong. Earlier: `WORKLOG-web-playground-build.md` (the build), `WORKLOG-demo-page-jars.md`
(the jar cut in two).

## What the run published

Every step was green, the image push for both platforms included.

- **The page did not work.** `https://tda4j.appliedtopology.org/playground/` served `index.html`, and both jars it
  names answered 404. The commit the run pushed to `gh-pages` (85fb353a) holds `playground/index.html` and nothing
  else.
- **The image works.** `docker pull ghcr.io/appliedtopology/tda4j-demo` with no login succeeds. `latest` and
  `0.7.0-SNAPSHOT` are one index with `linux/amd64` and `linux/arm64`. The pulled image passes
  `check-demo-container.mjs` (13 checks, 20 s). The ARM image cannot be run here.

## Why the jars were missing

`gh-pages` has the `.gitignore` of a Java source tree: `*.class`, `*.log`, `*.jar`, `*.war`, `*.ear`, `*.zip`,
`*.tar.gz`, `*.rar`, `out/`, `.idea/`, `target/`. The publish step ran `git add -A playground`, which skips ignored
files without a message, so the commit had the page alone.

**Why the trial before the merge did not show it**: the step had been run against a scratch repository made with
`git init` and a seed commit. That repository had no `.gitignore`. A publish step has to be tried against a copy of the
branch it will meet.

## Why the visitor saw nothing

The page stayed on "Opening the library" for ever, with `TypeError: Cannot convert object to primitive value` in the
console. With a jar missing, or present without the class, `library.org...WebRepl` fails with a Java exception, and the
page's report of a failed start joined that object to a string. Probed in headless Chromium (CheerpJ 5.0), the object:

| asked | answer |
|---|---|
| `typeof failure`, constructor | `object`, `Object` |
| `failure.message` | `undefined`, and the read starts Java code ("Java code still running, check for a missing 'await'") |
| `String(failure)`, `"" + failure` | throws the `TypeError` above |
| `await failure.toString()` | `java.lang.ClassNotFoundException: org.appliedtopology.tda4j.demo.WebRepl` |
| `await failure.getMessage()` | `org.appliedtopology.tda4j.demo.WebRepl` |

The same join was in the report of a failed input.

## The fix

1. `demo.yml`, "Publish the page": `git add -A -f playground`, then the step stops unless `index.html` and the three
   jars are in the index (`git ls-files --error-unmatch`).
2. `demo.yml`, new last step "Use the published page in the browser": waits until the site serves the four files of the
   build, byte for byte (up to 15 minutes; GitHub Pages deploys the branch after the push), then runs
   `check-web-playground.mjs` on the published address. It runs also when the image push failed. The address comes from
   the branch's `CNAME`, else the repository's `github.io` address.
3. `check-web-playground.mjs` takes an address as well as a folder, and stops at once with the page's own words when
   the page says it cannot start (it waited five minutes for a prompt before).
4. The page asks its site for the three jars (`HEAD`) before it fetches the runtime, and names the missing ones. A
   failure is described by `await failure.toString()` unless it is an `Error`.
5. The image's labels: `org.opencontainers.image.title` was `ubuntu` and `created` the base image's day, both
   inherited. The workflow sets them.

## What was run

The publish step, taken out of the workflow file and run against a scratch copy of the real `gh-pages`
(`git push <scratch>.git origin/gh-pages:refs/heads/gh-pages`), with the site of the current `scala`:

| step | result | `playground/` in the pushed commit |
|---|---|---|
| as it was | exit 0 | `index.html` |
| new | exit 0 | `index.html`, the three jars (66.2 MB, 6.3 MB, 0.16 MB) |
| new without `-f` | exit 1, "playground/tda4j-demo-project.jar is not in what would be pushed", nothing pushed | unchanged |
| new, a second time | exit 0, "No changes to publish." | unchanged |
| `docs.yml`'s publish step on top (with a real docs build) | commits | the four files still there; no file of the docs output is ignored |

The new last step, taken out of the workflow file the same way:

| site | result |
|---|---|
| a local server without the jars | "is not served", then the error after the last attempt |
| the live site as it is today | "index.html is not this build's yet", then the error |
| a local server with this build | files match at the first attempt, 21 checks pass in 74 s |

The page: 21 checks on the folder (77 s); on a folder with no jars the check fails in under a second with "This copy of
the page cannot start. Its site does not have tda4j-demo-web-patch.jar (HTTP 404), ..."; with a project jar that
holds only a manifest it fails in 6 s with "The session could not start: java.lang.ClassNotFoundException: ...". No
page error in either.

## Not verified

- **The page has never started a session from GitHub Pages.** The site answers a Range request with 206 (asked on
  `playground/index.html`). With `Accept-Encoding: gzip` the same page comes compressed, and the range then counts
  compressed bytes; whether a `.jar` is served compressed could not be asked while no jar is there. The first run
  with the fix is the test, in its last step.
- The two changed workflow steps have not run on GitHub. That a deploy takes less than the 15 minutes the last step
  waits is an assumption.
- The ARM image has never been run (#334).

The Demo workflow has to run once more after this change is merged: until then the page on the docs site is the one
without jars.
