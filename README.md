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
* This tool is tested and developed on Linux only.
* `openssl`, `apksigner`, and `aapt2` need to be in included your `PATH` variable.
  * `openssl` should be packaged in most Linux distributions
  * `apksigner` and `aapt2` are tools in the Android SDK Build Tools package. The SDK can be
    typically installed using Android Studio, but instructions on getting those tools standalone
    can be found at https://developer.android.com/studio/command-line (`sdkmanager`).
* You need an RSA or EC key in PKCS8 format to sign the metadata. The key needs to be decrypted.

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

  ```plain
  app_repo_data
  └── [ 121M]  apps
      ├── [ 7.1M]  app.attestation.auditor
      │   ├── [ 2.2M]  24.apk
      │   ├── [ 2.2M]  25.apk
      │   ├── [ 2.2M]  26.apk
      │   ├── [ 249K]  delta-24-to-26.gz
      │   ├── [ 247K]  delta-25-to-26.gz
      │   └── [  287]  latest.txt
      ├── [ 114M]  org.chromium.chrome
      │   ├── [  27M]  438910534.apk
      │   ├── [  27M]  443006634.apk
      │   ├── [  27M]  443008234.apk
      │   ├── [  27M]  443009134.apk
      │   ├── [ 4.2M]  delta-438910534-to-443009134.gz
      │   ├── [ 674K]  delta-443006634-to-443009134.gz
      │   ├── [ 583K]  delta-443008234-to-443009134.gz
      │   └── [  314]  latest.txt
      └── [  165]  latest-index.txt
  ```

* A sample of `latest-index.txt`:
  
  ```plain
  MEUCIAI/UvoLxRK+oKByBOiqcWHZYbXGXN023Q60WqRbBGOxAiEArbSFqzGbREW4PTa4tr4hHLym4lGrwE3b/1r3C3vlFZQ=
  1620527802
  app.attestation.auditor:26
  org.chromium.chrome:443009134
  ```
  
  The first line contains a base64-encoded signature, the second line contains the last update
  timestamp, and the rest of the lines contain versioning info.
  
* A sample of `latest.txt` for `org.chromium.chrome`:
  
  ```plain
  MEUCIQCwYKB/u3FnaA+1+BrJwrQsJdFjure9LA/Z4XAH+/obXwIgfQUN5lIlrv33iqO7G5J8paoEVsZQITSHbdW2Vy/tF/o=
  {"package":"org.chromium.chrome","latestVersionCode":443009134,"sha256Checksum":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","deltaAvailableVersions":[443008234,443006634,438910534],"lastUpdateTimestamp":1620527802}
  ```
  
  The first line contains a base64-encoded signature of the JSON metadata, and the prettified JSON
  is:
  
  ```plain
  {
    "package": "org.chromium.chrome",
    "latestVersionCode": 443009134,
    "sha256Checksum": "V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=",
    "deltaAvailableVersions": [
        443008234,
        443006634,
        438910534
    ],
    "lastUpdateTimestamp": 1620527802
  }
  ```


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
