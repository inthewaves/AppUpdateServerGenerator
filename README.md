# AppUpdateServer generator
A CLI tool to manage a repository for an app update server.

Some facts about the app update repository:
* The repository is meant to be uploaded to a static web server with the CLI tool being executed on
  a local machine.
* The repository state / repository is determined by the static files.
* This app update server supports deltas using Google's archive-patcher library, forked at
  https://github.com/inthewaves/archive-patcher.

## Usage

### Repository management Requirements:
* This tool is tested on and developed for Linux only.
* `openssl`, `apksigner`, and `aapt2` need to be in included your `PATH` variable.
  * `openssl` should be packaged in most Linux distributions
  * `apksigner` and `aapt2` are tools in the Android SDK Build Tools package. The SDK can be
    typically installed using Android Studio, but instructions on getting those tools standalone can
    be found at https://developer.android.com/studio/command-line (`sdkmanager`).
* You need an RSA or EC key in PKCS8 format to sign the metadata. The key needs to be decrypted.
  There are test keys in the repo (`testkey_ec.pk8`, `testkey_rsa.pk8`), but these should not be
  used in production.

To insert an APK (or multiple APKs) into the repo, run this command after building the jar with
`./gradlew build`:

```bash
$ ./appservergen insert-apk [OPTIONS] -k decrypted_signing_key APKS
```

This handles metadata and delta generation. If the repository directories don't already exist, the
tool will create the directories. This will not delete / move the source APK provided to the tool.

See `./app-update-server-generator insert-apk --help` for more options.

To validate the repository (i.e. make sure the metadata is consistent, the signatures for the
metadata actually verify, the APK information is correct, and the deltas apply correctly), run

```bash
$ ./appservergen validate [OPTIONS]
```

#### Example of repository structure
* A sample of what the directory tree for a repository looks like and the file sizes:

  <!-- tree --dirsfirst --du -h app_repo_data -->
  ```plain
  app_repo_data/
  ├── [ 121M]  apps
  │   ├── [ 7.1M]  app.attestation.auditor
  │   │   ├── [ 2.2M]  24.apk
  │   │   ├── [ 2.2M]  25.apk
  │   │   ├── [ 2.2M]  26.apk
  │   │   ├── [ 249K]  delta-24-to-26.gz
  │   │   ├── [ 247K]  delta-25-to-26.gz
  │   │   ├── [ 1.5K]  ic_launcher.png
  │   │   └── [  477]  latest.txt
  │   ├── [ 114M]  org.chromium.chrome
  │   │   ├── [  27M]  438910534.apk
  │   │   ├── [  27M]  443006634.apk
  │   │   ├── [  27M]  443008234.apk
  │   │   ├── [  27M]  443009134.apk
  │   │   ├── [ 4.2M]  delta-438910534-to-443009134.gz
  │   │   ├── [ 674K]  delta-443006634-to-443009134.gz
  │   │   ├── [ 583K]  delta-443008234-to-443009134.gz
  │   │   └── [  595]  latest.txt
  │   ├── [  988]  latest-bulk-metadata.txt
  │   └── [  187]  latest-index.txt
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
  MEUCIEBMFgd7tSXZUkDgTs4BnNCIx05w2WbXvEFrSHCN8DaRAiEAmixIEQlU68BXU+5TD6Bou4216OdYeeZlOF8i8HbOsmQ=
  1621142129
  app.attestation.auditor 26 1621142129
  org.chromium.chrome 443009134 1621142129
  ```
  
  The first line contains a base64-encoded signature, the second line contains the last update
  timestamp, and the rest of the lines contain versions with last-update timestamps.
  
* **latest.txt**: Sample for `org.chromium.chrome`:
  
  ```plain
  MEQCIBln9g8VaGaVjUnlazklxrY4Vl2Yb2h/RWzeST5U8YzzAiB39klMJhUSNrx0CisZ3jQpZ9FjBKFOTRntJQdPmxq4KA==
  {"package":"org.chromium.chrome","label":"Vanadium","latestVersionCode":443009134,"latestVersionName":"90.0.4430.91","lastUpdateTimestamp":1621142129,"sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaInfo":[{"versionCode":443008234,"sha256Checksum":"0JEp1W1w6rnTpv2dWyqFI9W1ZFIHQMSF/1ZATjkdjXA="},{"versionCode":443006634,"sha256Checksum":"24XV98Qz26gn6dV44Kr8aTSJCf8oyIXxfomFGL87HGI="},{"versionCode":438910534,"sha256Checksum":"zt0pH24gFTDLSjuRFwXzP4GmH6dWLvTrmuC1xh1SxvM="}]}
  ```
  
  The first line contains a base64-encoded signature of the JSON metadata.
  
  The prettified JSON for the second line is:
  
  ```json
  {
    "package": "org.chromium.chrome",
    "label": "Vanadium",
    "latestVersionCode": 443009134,
    "latestVersionName": "90.0.4430.91",
    "lastUpdateTimestamp": 1621142129,
    "sha256Checksum": "V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=",
    "deltaInfo": [
      {
        "versionCode": 443008234,
        "sha256Checksum": "0JEp1W1w6rnTpv2dWyqFI9W1ZFIHQMSF/1ZATjkdjXA="
      },
      {
        "versionCode": 443006634,
        "sha256Checksum": "24XV98Qz26gn6dV44Kr8aTSJCf8oyIXxfomFGL87HGI="
      },
      {
        "versionCode": 438910534,
        "sha256Checksum": "zt0pH24gFTDLSjuRFwXzP4GmH6dWLvTrmuC1xh1SxvM="
      }
    ]
  }
  ```

* **latest-bulk-metadata.txt**: This is a file of all the metadata in the repository:

  ```plain
  MEYCIQDeEGpCCCQgHrE37cp5Uhds6THRjFCfr/c5D5q/3CBQIQIhAOMecdQjHUg3qIxBtdMqWQ3g8tAbL2f/D7ZdVeJUsHxs
  1621142129
  {"package":"app.attestation.auditor","label":"Auditor","latestVersionCode":26,"latestVersionName":"26","lastUpdateTimestamp":1621142129,"sha256Checksum":"LZo/7Hr/tCoSidZGAr67iz/O1nhHBdUIkpWqrEVJh7I=","deltaInfo":[{"versionCode":25,"sha256Checksum":"xUsN2tuUQWtxdrscGF8rEvpdilq6BSb6fe8xLwaviAA="},{"versionCode":24,"sha256Checksum":"HwI4GQGC1E+Xc5BVKwzSZhAOgZWG3KZzfkTYk0mO5pg="}]}
  {"package":"org.chromium.chrome","label":"Vanadium","latestVersionCode":443009134,"latestVersionName":"90.0.4430.91","lastUpdateTimestamp":1621142129,"sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaInfo":[{"versionCode":443008234,"sha256Checksum":"0JEp1W1w6rnTpv2dWyqFI9W1ZFIHQMSF/1ZATjkdjXA="},{"versionCode":443006634,"sha256Checksum":"24XV98Qz26gn6dV44Kr8aTSJCf8oyIXxfomFGL87HGI="},{"versionCode":438910534,"sha256Checksum":"zt0pH24gFTDLSjuRFwXzP4GmH6dWLvTrmuC1xh1SxvM="}]}
  ```
  
  This is used for bulk downloads (e.g., first-time startup or force refreshes).

### Delta generation
The jar also supports generating deltas directly for convenience:
```bash
$ ./appservergen generate-delta [OPTIONS] OLDFILE NEWFILE OUTPUTDELTA
$ ./appservergen apply-delta [OPTIONS] OLDFILE DELTAFILE NEWFILE
```
See the help option `-h` for `OPTIONS`.

## TODO
* The Android app
* More unit tests
* A group install system for apps that should be installed together at once (e.g., Chromium
  packages)
* System for pruning older versions of apps
* Maybe a configuration file to configure things such as maximum number of deltas to generate which
  can be used to determine pruning.
* Refactoring
