# AppUpdateServer generator
A CLI tool to manage a repository for an app update server.

Some facts about the app update repository:
* The repository is meant to be uploaded to a static web server with the CLI tool being executed on
  a local machine.
* The repository state / repository is determined by the static files.
* This app update server supports deltas using Google's archive-patcher library, forked at
  https://github.com/inthewaves/archive-patcher.

## Usage

### Repository management

#### Requirements
* This tool is tested on and developed for Linux only.
* `openssl`, `apksigner`, and `aapt2` need to be in included your `PATH` variable.
  * `openssl` should be packaged in most Linux distributions
  * `apksigner` and `aapt2` are tools in the Android SDK Build Tools package. The SDK can be
    typically installed using Android Studio, but instructions on getting those tools standalone can
    be found at https://developer.android.com/studio/command-line (`sdkmanager`).
* You need an RSA or EC key in PKCS8 format to sign the metadata. The key needs to be decrypted.
  There are test keys in the repo (`testkey_ec.pk8`, `testkey_rsa.pk8`), but these should not be
  used in production.
* If inserting many APKs at once, the system temp directory should have enough space for delta
  generation.

#### Common commands

##### Adding

To add one or more APKs into the repo, run this command after building the jar with `./gradlew build`:

```bash
$ ./appservergen add [OPTIONS] -k decrypted_signing_key APKS
```

This handles metadata and delta generation. If the repository directories don't already exist, the
tool will create the directories. This will not delete / move the source APK provided to the tool.
This command will ask for release notes for every package being inserted and only for the most
recent version of a package.

See `./appservergen add --help` for more options.

##### Groups

Packages can be tagged with a `groupId` that indicates to clients should atomically install new
updates for APKs in a group. This is useful when there are apps that have shared libraries such as
Chromium.

See `./appservergen group --help` for details.

##### Validation of repository state

To validate the repository (i.e. make sure the metadata is consistent, the signatures for the
metadata actually verify, the APK information is correct, and the deltas apply correctly), run

```bash
$ ./appservergen validate [OPTIONS]
```

##### Delta generation
The tool also supports generating deltas directly for convenience:
```bash
$ ./appservergen generate-delta [OPTIONS] OLDFILE NEWFILE OUTPUTDELTA
$ ./appservergen apply-delta [OPTIONS] OLDFILE DELTAFILE NEWFILE
```
See the help option `-h` for `OPTIONS`.

## Example of repository structure

This is a directory full of apps to be inserted into a repository:

```plain
TestAPKs/
├── Auditor-24.apk
├── Auditor-25.apk
├── Auditor-26.apk
├── Auditor-27.apk
├── TrichromeChrome-90.0.4430.210.apk
├── TrichromeChrome-90.0.4430.91.apk
├── TrichromeChrome-91.0.4472.77.apk
├── TrichromeLibrary-90.0.4430.210.apk
├── TrichromeLibrary-90.0.4430.91.apk
├── TrichromeLibrary-91.0.4472.77.apk
├── TrichromeWebView-90.0.4430.210.apk
├── TrichromeWebView-90.0.4430.91.apk
├── TrichromeWebView-91.0.4472.77.apk
```

These commands were run to create the following sample repository:
```bash
$ ./appservergen add -k testkey_ec.pk8 TestAPKs/*.apk
$ ./appservergen group create -k testkey_ec.pk8 chromium app.vanadium.trichromelibrary app.vanadium.webview org.chromium.chrome
```

Note: The `add` command asks for optional release notes for every package. The `add` command also
takes extra time to generate all of the deltas.

<!-- tree --dirsfirst --du -h app_repo_data -->
```plain
app_repo_data
├── [ 635M]  apps
│   ├── [  10M]  app.attestation.auditor
│   │   ├── [ 2.2M]  24.apk
│   │   ├── [ 2.2M]  25.apk
│   │   ├── [ 2.2M]  26.apk
│   │   ├── [ 2.2M]  27.apk
│   │   ├── [ 509K]  delta-24-to-27.gz
│   │   ├── [ 509K]  delta-25-to-27.gz
│   │   ├── [ 495K]  delta-26-to-27.gz
│   │   └── [  874]  latest.txt
│   ├── [ 320M]  app.vanadium.trichromelibrary
│   │   ├── [  94M]  443009134.apk
│   │   ├── [  94M]  443021034.apk
│   │   ├── [  97M]  447207734.apk
│   │   ├── [  18M]  delta-443009134-to-447207734.gz
│   │   ├── [  18M]  delta-443021034-to-447207734.gz
│   │   └── [  565]  latest.txt
│   ├── [ 226M]  app.vanadium.webview
│   │   ├── [  63M]  443009134.apk
│   │   ├── [  63M]  443021034.apk
│   │   ├── [  66M]  447207734.apk
│   │   ├── [  18M]  delta-443009134-to-447207734.gz
│   │   ├── [  18M]  delta-443021034-to-447207734.gz
│   │   └── [  562]  latest.txt
│   ├── [  78M]  org.chromium.chrome
│   │   ├── [  27M]  443009134.apk
│   │   ├── [  27M]  443021034.apk
│   │   ├── [  17M]  447207733.apk
│   │   ├── [ 3.5M]  delta-443009134-to-447207733.gz
│   │   ├── [ 3.5M]  delta-443021034-to-447207733.gz
│   │   └── [  546]  latest.txt
│   ├── [ 2.2K]  latest-bulk-metadata.txt
│   └── [  280]  latest-index.txt
└── [  178]  public-signing-key.pem
```

* **public-signing-key.pem**: The public key will be used to validate the signatures in the repo for
  the `validate` command, and it will also validate the private key passed into the `insert-apk`
  command to prevent the use of a different key. This public key file in the root directory isn't
  meant to be consumed by the client app, so it could be preferable to not include it in a sync to
  the static file server.  (Instead, the client apps should have the public key included in the app
  by default.)

  On initial insertion into an empty repository, this public key is generated from the private
  signing key used in the `insert-apk` command.

* **latest-index.txt**: Sample:

  ```plain
  MEUCIDGlrdFl2KqXro+OvRGzZPrWMZJNrVCH/BKrRvwLTyHzAiEAkw2yySyF14AVDf5sP3/fsPc4krgv8C/v0+QxD6y/uw0=
  1622153179
  app.attestation.auditor 27 1622152923
  app.vanadium.trichromelibrary 447207734 1622153179
  app.vanadium.webview 447207734 1622153179
  org.chromium.chrome 447207733 1622153179
  ```

  The first line contains a base64-encoded signature, the second line contains the last update
  timestamp, and the rest of the lines contain versions with last-update timestamps.

* **latest.txt**: Sample for `org.chromium.chrome`:

  ```plain
  MEYCIQDKAulMgytIpX4XrNlgbdz4EJUblPPCI7OkyfB0pARRbwIhALU9+LzF6rQXe/NPZLbvf9WRbbFpNQGdLPZ0L1XzCwie
  {"package":"org.chromium.chrome","groupId":"chromium","label":"Vanadium","latestVersionCode":447207733,"latestVersionName":"91.0.4472.77","lastUpdateTimestamp":1622153179,"sha256Checksum":"yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=","deltaInfo":[{"versionCode":443021034,"sha256Checksum":"ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="},{"versionCode":443009134,"sha256Checksum":"jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="}],"releaseNotes":null}
  ```

  The first line contains a base64-encoded signature of the JSON metadata.

  The prettified JSON for the second line is:

  ```json
  {
      "package": "org.chromium.chrome",
      "groupId": "chromium",
      "label": "Vanadium",
      "latestVersionCode": 447207733,
      "latestVersionName": "91.0.4472.77",
      "lastUpdateTimestamp": 1622153179,
      "sha256Checksum": "yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=",
      "deltaInfo": [
          {
              "versionCode": 443021034,
              "sha256Checksum": "ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="
          },
          {
              "versionCode": 443009134,
              "sha256Checksum": "jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="
          }
      ],
      "releaseNotes": null
  }
  ```

  Note: `groupId` is used to indicate to clients about apps that should be installed atomically.
  In this case, it is used to group the Trichromium packages together.

* **latest-bulk-metadata.txt**: This is a file of all the metadata in the repository:

  ```plain
  MEUCIB8/EeRxvuqELcjiDpecqNz9UqLjmIQcmyU/UvE6KfekAiEAsNjIgFGG/anV0Zo1tecbAIHix3fJ4Tt84RxjQ2+f9cs=
  1622153179
  {"package":"app.attestation.auditor","groupId":null,"label":"Auditor","latestVersionCode":27,"latestVersionName":"27","lastUpdateTimestamp":1622152923,"sha256Checksum":"CNpHPoTVixSI7TRuRpiMbWA1A28ZBrMTDophzdjfZ6g=","deltaInfo":[{"versionCode":26,"sha256Checksum":"eX7VGj7905BR4T/kmAVkkJp74oqOlSFEbPL8sy/c7pc="},{"versionCode":25,"sha256Checksum":"hV+pV13g/JSeMF4yYzGpxtE9o6BPYjsp5LAbbDKzNfA="},{"versionCode":24,"sha256Checksum":"Xrm5wxQOXLoaoGk7/UtfTce9JL24LppPrBcuwgfeNHM="}],"releaseNotes":"<p><a href=\"https://github.com/GrapheneOS/Auditor/compare/26...27\">Full list of changes from the previous release (version 26)</a>. Notable changes:</p>\n<ul>\n  <li>modernize UI (dark mode, etc.)</li>\n  <li>modernize implementation</li>\n  <li>update dependencies</li>\n</ul>\n"}
  {"package":"app.vanadium.trichromelibrary","groupId":"chromium","label":"Trichrome Library","latestVersionCode":447207734,"latestVersionName":"91.0.4472.77","lastUpdateTimestamp":1622153179,"sha256Checksum":"NLpi8yQgYJAfWBj2/l3C2QMdfJncRC/t8aPA3RlE1hM=","deltaInfo":[{"versionCode":443021034,"sha256Checksum":"QZve0/D+qqGzIozptH+D5saUCxrdNSGjWWcmJFbb16I="},{"versionCode":443009134,"sha256Checksum":"RoZbWjLPvxENxcMWnV7LDBRulpQJjC2P4MpBdcKdjCo="}],"releaseNotes":null}
  {"package":"app.vanadium.webview","groupId":"chromium","label":"Vanadium System WebView","latestVersionCode":447207734,"latestVersionName":"91.0.4472.77","lastUpdateTimestamp":1622153179,"sha256Checksum":"Xo1wfFSTg7Gi4Z+1didiJJX/IlDcy7gw9mS3CRCv78s=","deltaInfo":[{"versionCode":443021034,"sha256Checksum":"Sq+tXujrv4Fe6o6MnIYEOTGB62amygiOhS6chgd3bC8="},{"versionCode":443009134,"sha256Checksum":"PKuxV8pF72nrK5M1tB/Hnra0cI7yPRdiucSlm6aHGLc="}],"releaseNotes":null}
  {"package":"org.chromium.chrome","groupId":"chromium","label":"Vanadium","latestVersionCode":447207733,"latestVersionName":"91.0.4472.77","lastUpdateTimestamp":1622153179,"sha256Checksum":"yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=","deltaInfo":[{"versionCode":443021034,"sha256Checksum":"ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="},{"versionCode":443009134,"sha256Checksum":"jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="}],"releaseNotes":null}
  ```

  This is used for bulk downloads (e.g., first-time startup or force refreshes).

## TODO
* The Android app
* More unit tests
* System for pruning older versions of apps
* Maybe a configuration file to configure things such as maximum number of deltas to generate which
  can be used to determine pruning.
* Refactoring
* Detect free space for concurrent delta generation. The rule of thumb is that generating a delta
  between a previous version and the newest version is that if the previous version is `x` bytes,
  and the new version is `y` bytes, then the delta generation requires 
  `2 * max(x,y) + 4 * (max(x,y)+ 1)` bytes.
