# AppUpdateServer generator
A CLI tool to manage a repository for an app update server.

Some facts about the app update repository:
* The repository is meant to be uploaded to a static web server with the CLI tool being executed on
  a local machine.
* The repository state is determined by the static files.
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
  generation. (To get more space, you could do `sudo mount -o remount,size=5G /tmp`.)

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

Note: On the serverside, groups are purely used as a tag. There is a warning from the CLI tool
around groups in the add command, where you will be warned if you are only updating a proper subset
of a group's packages and not all of them. It is the client's responsibility to download all APKs in
a group that have updates and to install them atomically. For this reason, it's important that the
updates for packages in a group are pushed to the server all at once.

For example, for a new Chromium update, the `add` command should be run on the new APKs for WebView,
the Trichrome Library, and the Chrome app.

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

Note: The `add` command asks for optional release notes for every package. In this example, there
are release notes added to the latest version of the Auditor app. The `add` command also takes extra
time to generate all of the deltas.

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
│   │   └── [ 1.5K]  latest.txt
│   ├── [ 320M]  app.vanadium.trichromelibrary
│   │   ├── [  94M]  443009134.apk
│   │   ├── [  94M]  443021034.apk
│   │   ├── [  97M]  447207734.apk
│   │   ├── [  18M]  delta-443009134-to-447207734.gz
│   │   ├── [  18M]  delta-443021034-to-447207734.gz
│   │   └── [ 1.0K]  latest.txt
│   ├── [ 226M]  app.vanadium.webview
│   │   ├── [  63M]  443009134.apk
│   │   ├── [  63M]  443021034.apk
│   │   ├── [  66M]  447207734.apk
│   │   ├── [  18M]  delta-443009134-to-447207734.gz
│   │   ├── [  18M]  delta-443021034-to-447207734.gz
│   │   └── [ 1.0K]  latest.txt
│   ├── [  78M]  org.chromium.chrome
│   │   ├── [  27M]  443009134.apk
│   │   ├── [  27M]  443021034.apk
│   │   ├── [  17M]  447207733.apk
│   │   ├── [ 3.5M]  delta-443009134-to-447207733.gz
│   │   ├── [ 3.5M]  delta-443021034-to-447207733.gz
│   │   └── [ 1013]  latest.txt
│   ├── [ 4.2K]  latest-bulk-metadata.txt
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
  MEUCIEHLthq1XGK6LSQtNINEs6vAHs/NcPz2FlgdxTwRIc/lAiEA6EpwRt4jyR2MREffyajTCRclNZ73zXr7WzpadKWtWKs=
  1622244209
  app.attestation.auditor 27 1622243967
  app.vanadium.trichromelibrary 447207734 1622244209
  app.vanadium.webview 447207734 1622244209
  org.chromium.chrome 447207733 1622244209
  ```

  The first line contains a base64-encoded signature, the second line contains the last update
  timestamp, and the rest of the lines contain versions with last-update timestamps.

* **latest.txt**: Sample for `org.chromium.chrome`:

  ```plain
  MEQCIGC6HCyFn3Ii2uRc9IW8BO+MkCZ8oWBL1G5KLr7MoZSBAiBBTt7lMBXr3Xr+vX+xiGCFzrNyTwTQl0FiG4xkr2js4Q==
  {"package":"org.chromium.chrome","groupId":"chromium","label":"Vanadium","lastUpdateTimestamp":1622244209,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaInfo":[],"releaseNotes":null},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"+Ykbo9NOe6pf0TqYwlIBI5fb3nXEvZ4ItdAkwAwTahE=","deltaInfo":[],"releaseNotes":null},{"versionCode":447207733,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=","deltaInfo":[{"baseVersionCode":443009134,"sha256Checksum":"jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="},{"baseVersionCode":443021034,"sha256Checksum":"ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="}],"releaseNotes":null}]}
  ```

  The first line contains a base64-encoded signature of the JSON metadata.

  The prettified JSON for the second line is:

  ```json
  {
      "package": "org.chromium.chrome",
      "groupId": "chromium",
      "label": "Vanadium",
      "lastUpdateTimestamp": 1622244209,
      "releases": [
          {
              "versionCode": 443009134,
              "versionName": "90.0.4430.91",
              "minSdkVersion": 29,
              "releaseTimestamp": 1622243967,
              "sha256Checksum": "V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=",
              "deltaInfo": [],
              "releaseNotes": null
          },
          {
              "versionCode": 443021034,
              "versionName": "90.0.4430.210",
              "minSdkVersion": 29,
              "releaseTimestamp": 1622243967,
              "sha256Checksum": "+Ykbo9NOe6pf0TqYwlIBI5fb3nXEvZ4ItdAkwAwTahE=",
              "deltaInfo": [],
              "releaseNotes": null
          },
          {
              "versionCode": 447207733,
              "versionName": "91.0.4472.77",
              "minSdkVersion": 29,
              "releaseTimestamp": 1622243967,
              "sha256Checksum": "yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=",
              "deltaInfo": [
                  {
                      "baseVersionCode": 443009134,
                      "sha256Checksum": "jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="
                  },
                  {
                      "baseVersionCode": 443021034,
                      "sha256Checksum": "ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="
                  }
              ],
              "releaseNotes": null
          }
      ]
  }
  ```

  Note: `groupId` is used to indicate to clients about apps that should be installed atomically.
  In this case, it is used to group the Trichromium packages together.

* **latest-bulk-metadata.txt**: This is a file of all the metadata in the repository:

  ```plain
  MEQCIDcFSO9dq/pm4BIx9WKrWGi0uyp37RBFIc6eN5x/nJpYAiBVOYBSXHcbJkcwCe2OhA/6bFCZWb489SlIEzIRiHOSrA==
  1622244209
  {"package":"app.attestation.auditor","groupId":null,"label":"Auditor","lastUpdateTimestamp":1622243967,"releases":[{"versionCode":24,"versionName":"24","minSdkVersion":26,"releaseTimestamp":1622243967,"sha256Checksum":"HpecQ50szYqQ991bMv1pg4DyMGBzbDFpmhr+/oHH+OU=","deltaInfo":[],"releaseNotes":null},{"versionCode":25,"versionName":"25","minSdkVersion":26,"releaseTimestamp":1622243967,"sha256Checksum":"ac4QPtAcnlUZ6HL8GoS7fGx/dZe+yyXPvfz6CvtrvKQ=","deltaInfo":[],"releaseNotes":null},{"versionCode":26,"versionName":"26","minSdkVersion":26,"releaseTimestamp":1622243967,"sha256Checksum":"LZo/7Hr/tCoSidZGAr67iz/O1nhHBdUIkpWqrEVJh7I=","deltaInfo":[],"releaseNotes":null},{"versionCode":27,"versionName":"27","minSdkVersion":26,"releaseTimestamp":1622243967,"sha256Checksum":"CNpHPoTVixSI7TRuRpiMbWA1A28ZBrMTDophzdjfZ6g=","deltaInfo":[{"baseVersionCode":24,"sha256Checksum":"Xrm5wxQOXLoaoGk7/UtfTce9JL24LppPrBcuwgfeNHM="},{"baseVersionCode":25,"sha256Checksum":"hV+pV13g/JSeMF4yYzGpxtE9o6BPYjsp5LAbbDKzNfA="},{"baseVersionCode":26,"sha256Checksum":"eX7VGj7905BR4T/kmAVkkJp74oqOlSFEbPL8sy/c7pc="}],"releaseNotes":"<p><a href=\"https://github.com/GrapheneOS/Auditor/compare/26...27\">Full list of changes from the previous release (version 26)</a>. Notable changes:</p>\n<ul>\n  <li>modernize UI (dark mode, etc.)</li>\n  <li>modernize implementation</li>\n  <li>update dependencies</li>\n</ul>\n"}]}
  {"package":"app.vanadium.trichromelibrary","groupId":"chromium","label":"Trichrome Library","lastUpdateTimestamp":1622244209,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"3kuRhe+gYFQ1O+L1+gM5eardAWfsB73b/1sRK+pEtL0=","deltaInfo":[],"releaseNotes":null},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"6KJZ1d6UldqcAWHMzhpXont4XLddZi5YCUI0N1So740=","deltaInfo":[],"releaseNotes":null},{"versionCode":447207734,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"NLpi8yQgYJAfWBj2/l3C2QMdfJncRC/t8aPA3RlE1hM=","deltaInfo":[{"baseVersionCode":443009134,"sha256Checksum":"RoZbWjLPvxENxcMWnV7LDBRulpQJjC2P4MpBdcKdjCo="},{"baseVersionCode":443021034,"sha256Checksum":"QZve0/D+qqGzIozptH+D5saUCxrdNSGjWWcmJFbb16I="}],"releaseNotes":null}]}
  {"package":"app.vanadium.webview","groupId":"chromium","label":"Vanadium System WebView","lastUpdateTimestamp":1622244209,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"YnKVJ3RZsoS6fxRhHgrfAZGXA/F+RiYOyKjlVlIG6uo=","deltaInfo":[],"releaseNotes":null},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"VZ2iISVUcxxvQsnemdGIFhpuNU2MGyUujUJXvuPzV2Y=","deltaInfo":[],"releaseNotes":null},{"versionCode":447207734,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"Xo1wfFSTg7Gi4Z+1didiJJX/IlDcy7gw9mS3CRCv78s=","deltaInfo":[{"baseVersionCode":443009134,"sha256Checksum":"PKuxV8pF72nrK5M1tB/Hnra0cI7yPRdiucSlm6aHGLc="},{"baseVersionCode":443021034,"sha256Checksum":"Sq+tXujrv4Fe6o6MnIYEOTGB62amygiOhS6chgd3bC8="}],"releaseNotes":null}]}
  {"package":"org.chromium.chrome","groupId":"chromium","label":"Vanadium","lastUpdateTimestamp":1622244209,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaInfo":[],"releaseNotes":null},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"+Ykbo9NOe6pf0TqYwlIBI5fb3nXEvZ4ItdAkwAwTahE=","deltaInfo":[],"releaseNotes":null},{"versionCode":447207733,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1622243967,"sha256Checksum":"yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=","deltaInfo":[{"baseVersionCode":443009134,"sha256Checksum":"jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="},{"baseVersionCode":443021034,"sha256Checksum":"ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="}],"releaseNotes":null}]}
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
