# Versions and releases

The private source repository is https://github.com/AntoinS/CleanFrame. `upstream` retains the original MIT project; keep its license, copyright and third-party notices. The public extension repository is https://github.com/AntoinS/cleanframe-addons and contains only reviewed catalog data, widget packages and contributor documentation.

Use Semantic Versioning for the host (`package.json`, package-lock.json and app/config.xml). Android derives its human-readable name from package.json and adds its monotonically increasing build number. Increase the default versionCode for every shipped APK; the editor's local counter must also exceed the installed TV version. Never reuse a code or replace an existing Git tag or release asset.

For each release:

1. Update versions and CHANGELOG.md. Run source, browser, editor and Android checks. Include new API tests and German/English 1080p/720p visual inspection.
2. Commit reviewed sources, create an annotated `vMAJOR.MINOR.PATCH` tag and push it to the private origin. Use the Prepare versioned release workflow to create a draft from an existing tag, or the GitHub CLI with the exact tag and changelog.
3. Build locally with the existing private signing identity. Verify the signer against the saved local fingerprint. Attach the APK and SHA256SUMS to the private release. Do not publish credentials, .local data, design drafts, proprietary fonts or signing material.
4. Mark the release prerelease until native TV checks pass. Installing remains a separate deployment: back up APK/preferences, merge editor preference changes and preserve Home/boot/account state.
5. Publish only the approved add-on packages and their metadata in the public catalog repository. Packages use independent semantic versions and immutable paths; catalog revisions have their own tags/releases. Document the minimum host API. Unknown kinds/APIs fail closed.

GitHub source archives contain code; they are not signed TV packages. CI cannot reproduce this owner's signing identity without additional explicit setup. No signing secrets are stored in Actions.
