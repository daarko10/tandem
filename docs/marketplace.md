# Releasing Tandem to the JetBrains Marketplace

Agents prepare; the owner presses the button (docs/launch.md, "Outward-facing rules"). Everything below marked **Owner** is
a step only the owner takes: secrets, the upload, the review and publishing a release.

## What the repository already does
- `CHANGELOG.md`: one section per version. The section of the version in `gradle.properties` becomes the plugin's change
  notes (`patchPluginXml`, through the Gradle changelog plugin) and the GitHub release's text.
- `src/main/resources/META-INF/pluginIcon.svg` and `pluginIcon_dark.svg`: the 40×40 logo shown on the Marketplace and in
  Settings | Plugins.
- `build.gradle.kts`: `signPlugin` and `publishPlugin` read `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`
  and `PUBLISH_TOKEN` from the environment only. Nothing else needs them, and they are never in the repository.
- `.github/workflows/release.yml` (Actions > Release > Run workflow, on `main` only): refuses a version that is already
  tagged or has no `CHANGELOG.md` section, runs `./gradlew check`, signs the ZIP and verifies the signature, uploads the
  signed ZIP as an artifact, drafts a GitHub release `v<version>` with it, and uploads to the Marketplace only when
  "Upload the signed ZIP to the JetBrains Marketplace" is ticked.

## One-time setup (Owner)
1. **Signing certificate.** On your own machine, outside the repository:
   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```
   Put the contents of `chain.crt` in the repository secret `CERTIFICATE_CHAIN`, of `private.pem` in `PRIVATE_KEY`, and the
   passphrase you chose in `PRIVATE_KEY_PASSWORD` (Settings > Secrets and variables > Actions). Keep both files somewhere
   safe: every later version must be signed with the same key.
2. **Vendor.** Sign in to plugins.jetbrains.com with your JetBrains Account and set up the vendor profile the plugin is
   published under. `plugin.xml` names the vendor "DaArKo Labs" with the URL `https://daarkolabs.com`; that URL answered
   HTTP 520 on 2026-10-29, so make it load, or change the URL in `plugin.xml`, before the upload.

## The first version (Owner, once)
The Marketplace takes a new plugin's first version through its web form only; `publishPlugin` can update a listed plugin
but cannot create one.
1. Merge the release's pull requests into `main`.
2. Actions > Release > Run workflow on `main`, with the upload box **unticked**. Download `signed-plugin` from the run.
3. On plugins.jetbrains.com, choose Upload plugin and give it the signed ZIP. Fill in the listing (below) and submit it.
   JetBrains reviews every new plugin by hand; the listing appears when they approve it.
4. Publish the draft GitHub release the run created. Publishing it creates the tag `v<version>`.
5. Marketplace > your profile > My Tokens: create a token and put it in the repository secret `PUBLISH_TOKEN`.

## Every later version
1. Raise `version` in `gradle.properties`, add its section to `CHANGELOG.md` (move what is under `[Unreleased]`), merge.
2. **Owner:** Actions > Release > Run workflow on `main`, upload box ticked. The Marketplace reviews the update before it
   is listed.
3. **Owner:** publish the draft GitHub release.

## The listing
- **Name:** Tandem. **Description:** from `plugin.xml`, as built. It says what `main` does today and calls Tandem a plugin
  that works with Claude Code, never "a Claude Code plugin" (docs/launch.md, tactics 9 and 10).
- **License:** Apache 2.0, `https://github.com/daarko10/tandem/blob/main/LICENSE`.
- **Source code:** `https://github.com/daarko10/tandem`. **Issue tracker:** `https://github.com/daarko10/tandem/issues`.
- **Tags:** the Marketplace's AI and chat tags, whichever exist when you submit.
- **Logo:** none to upload: the Marketplace reads `pluginIcon.svg` from the ZIP.
- **Screenshots**, all the same size and theme, from `runIde` on a real project, nothing personal in view (file names,
  paths, account names):
  1. An edit open in the diff, the proposal changed by hand, Accept and Reject visible.
  2. Two sessions: the sidebar with both, the session tabs, one of them working.
  3. A reply with tool rows, a Bash row and the footer's mode, model and context ring.
  4. The `@` palette with a file picked, and the selection chip in the composer.
  5. The `+` menu or the MCP panel.
- Use no Anthropic or Claude logo anywhere in the listing or the screenshots: Tandem is not affiliated with Anthropic.
