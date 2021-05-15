# AppUpdateServer generator
A CLI tool to manage a repository for an app update server.

Some facts about the app update repository:
* The repository is meant to be uploaded to a static web server with the CLI tool being executed on a
  local machine.
* The repository state / repository is determined by the static files.
* This app update server supports deltas using Google's archive-patcher library, forked at
  https://github.com/inthewaves/archive-patcher.

## Usage

### Repository management
Requirements:
* This tool is tested on and developed for Linux only.
* `openssl`, `apksigner`, and `aapt2` need to be in included your `PATH` variable.
  * `openssl` should be packaged in most Linux distributions
  * `apksigner` and `aapt2` are tools in the Android SDK Build Tools package. The SDK can be
    typically installed using Android Studio, but instructions on getting those tools standalone
    can be found at https://developer.android.com/studio/command-line (`sdkmanager`).
* You need an RSA or EC key in PKCS8 format to sign the metadata. The key needs to be decrypted.
  There are test keys in the repo (`testkey_ec.pk8`, `testkey_rsa.pk8`), but these should not be
  used in production.

To insert an APK (or multiple APKs) into the repo, run this command after
building the jar with `./gradlew build`:

```bash
$ ./app-update-server-generator insert-apk [OPTIONS] -k decrypted_signing_key APKS
```

This handles metadata and delta generation. If the repository directories don't already exist, the
tool will create the directories. This will not delete / move the source APK provided to the tool.

See `./app-update-server-generator insert-apk --help` for more options.

#### Example of repository structure
* A sample of what the directory tree for a repository looks like and the file sizes:

  <!-- tree --dirsfirst --du -h app_repo_data -->
  ```plain
  app_repo_data
  ├── [ 121M]  apps
  │   ├── [ 7.1M]  app.attestation.auditor
  │   │   ├── [ 2.2M]  24.apk
  │   │   ├── [ 2.2M]  25.apk
  │   │   ├── [ 2.2M]  26.apk
  │   │   ├── [ 249K]  delta-24-to-26.gz
  │   │   ├── [ 247K]  delta-25-to-26.gz
  │   │   ├── [ 1.5K]  ic_launcher.png
  │   │   └── [  330]  latest.txt
  │   ├── [ 114M]  org.chromium.chrome
  │   │   ├── [  27M]  438910534.apk
  │   │   ├── [  27M]  443006634.apk
  │   │   ├── [  27M]  443008234.apk
  │   │   ├── [  27M]  443009134.apk
  │   │   ├── [ 4.2M]  delta-438910534-to-443009134.gz
  │   │   ├── [ 674K]  delta-443006634-to-443009134.gz
  │   │   ├── [ 583K]  delta-443008234-to-443009134.gz
  │   │   └── [  368]  latest.txt
  │   ├── [  614]  latest-bulk-metadata.txt
  │   └── [  187]  latest-index.txt
  └── [  178]  public-signing-key.pem
  ```

  **public-signing-key.pem**: The public key will be used to validate the signatures in the repo _during local 
  generation_, and it will also validate the private key passed into the `insert-apk` command to prevent the use of
  a different key. This public key file in the root directory isn't meant to be consumed by the
  client app, so it could be preferable to not include it in a sync to the static file server.
  (Instead, the client apps should have the public key included in the app by default.)

* **latest-index.txt**: Sample:
  
  ```plain
  MEUCIQD65BtWxRBDhalD9cyGxRvgB1uiRfqJXvXsHyWn0jPXEgIgfuh3rA6qCn67VF1vtNSZrCOvG7JKrzRNO5xMWnyIn2Y=
  1621053334
  app.attestation.auditor:26:1621053334
  org.chromium.chrome:443009134:1621053334
  ```
  
  The first line contains a base64-encoded signature, the second line contains the last update
  timestamp, and the rest of the lines contain versions with last updated timestamps.
  
* **latest.txt**: Sample for `org.chromium.chrome`:
  
  ```plain
  MEUCIF8xSiJ1d6m8Wzycxjp1gaVBgMU7WXlSvJ/12X3GZJnLAiEAhbkwICElT9F3apUXdeTfew3DHfkySg+3wRmxnUXe+po=
  {"package":"org.chromium.chrome","label":"Vanadium","latestVersionCode":443009134,"latestVersionName":"90.0.4430.91","sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaAvailableVersions":[438910534,443006634,443008234],"lastUpdateTimestamp":1620860027}
  ```
  
  The first line contains a base64-encoded signature of the JSON metadata. The prettified JSON for the
  second line is:
  
  ```json
  {
    "package": "org.chromium.chrome",
    "label": "Vanadium",
    "latestVersionCode": 443009134,
    "latestVersionName": "90.0.4430.91",
    "sha256Checksum": "V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=",
    "deltaAvailableVersions": [
      438910534,
      443006634,
      443008234
    ],
    "lastUpdateTimestamp": 1620860027
  }
  ```

* **latest-bulk-metadata.txt**: Sample:

  ```plain
  MEQCIBqK5Tl/AZ7sX1iIjMFZ+MGAXrm+aHV7mGeGKXEwPdUBAiAWkNTqyISAbN7yTbw5Fu6cHCWoa/QDYSthCqINA81iAA==
  1621053334
  {"package":"app.attestation.auditor","label":"Auditor","latestVersionCode":26,"latestVersionName":"26","sha256Checksum":"LZo/7Hr/tCoSidZGAr67iz/O1nhHBdUIkpWqrEVJh7I=","deltaAvailableVersions":[25,24],"lastUpdateTimestamp":1621053334}
  {"package":"org.chromium.chrome","label":"Vanadium","latestVersionCode":443009134,"latestVersionName":"90.0.4430.91","sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaAvailableVersions":[443008234,443006634,438910534],"lastUpdateTimestamp":1621053334}
  ```
  
  This is used for bulk downloads (e.g., first-time startup or force refreshes). It contains all
  the app metadata in the repo.

### Delta generation
The jar also supports generating deltas directly for convenience:
```bash
$ ./app-update-server-generator generate-delta [OPTIONS] OLDFILE NEWFILE OUTPUTDELTA
$ ./app-update-server-generator apply-delta [OPTIONS] OLDFILE DELTAFILE NEWFILE
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
