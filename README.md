# AppUpdateServer generator
A CLI tool to manage a repository for an app update server.

Some facts about the app update repository:
* The repository is meant to be uploaded to a static web server with the CLI tool being executed on
  a local machine.
* The repository state is kept in a SQLite database; however, the APK and delta files are stored on the filesystem.
* This app update server supports deltas using Google's archive-patcher library, forked at
  https://github.com/inthewaves/archive-patcher.

## Building and running

Clone the repository using `git clone --recurse-submodules` and do `./gradlew build`.
Then, use `appservergen` as the binary.

## Usage

### Repository management

#### Requirements
* This tool is tested on and developed for Linux only.
* `openssl` needs to be in included your `PATH` variable; it should be packaged in a typical Linux
  distribution.
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
The tool also supports generating and applying deltas directly for convenience:
```bash
$ ./appservergen generate-delta [OPTIONS] OLDFILE NEWFILE OUTPUTDELTA
$ ./appservergen apply-delta [OPTIONS] OLDFILE DELTAFILE NEWFILE
```
See the help option `-h` for `OPTIONS`. Note that this works on ZIP files in general (APKs are ZIP
files).

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
├── app-release-appA.apk
├── app-release-appA.apk.idsig
├── app-release-appB.apk
└── app-release-appB.apk.idsig
```

Note: `.apk.idsig` indicates
[APK Signature Scheme v4](https://source.android.com/security/apksigning/v4)
signature files which are supported by this tool.

For this example, we run this command to create an example repository:
```bash
$ ./appservergen add -k testkey_ec.pk8 ~/TestAPKs/*.apk
```

Note: The `add` command asks for optional release notes for every package. In this example, there
are release notes added to the latest version of the Auditor app. The `add` command also takes extra
time to generate all the deltas.

<!-- tree --dirsfirst --du -h app_repo_data -->
```plain
app_repo_data
├── [ 648M]  apps
│   ├── [  10M]  app.attestation.auditor
│   │   ├── [ 2.2M]  24.apk
│   │   ├── [ 2.2M]  25.apk
│   │   ├── [ 2.2M]  26.apk
│   │   ├── [ 2.2M]  27.apk
│   │   ├── [ 509K]  delta-24-to-27.gz
│   │   ├── [ 509K]  delta-25-to-27.gz
│   │   ├── [ 495K]  delta-26-to-27.gz
│   │   ├── [ 2.4K]  ic_launcher
│   │   └── [ 1.1K]  latest.txt
│   ├── [ 320M]  app.vanadium.trichromelibrary
│   │   ├── [  94M]  443009134.apk
│   │   ├── [  94M]  443021034.apk
│   │   ├── [  97M]  447207734.apk
│   │   ├── [  18M]  delta-443009134-to-447207734.gz
│   │   ├── [  18M]  delta-443021034-to-447207734.gz
│   │   ├── [ 4.4K]  ic_launcher
│   │   └── [  991]  latest.txt
│   ├── [ 226M]  app.vanadium.webview
│   │   ├── [  63M]  443009134.apk
│   │   ├── [  63M]  443021034.apk
│   │   ├── [  66M]  447207734.apk
│   │   ├── [  18M]  delta-443009134-to-447207734.gz
│   │   ├── [  18M]  delta-443021034-to-447207734.gz
│   │   ├── [ 4.0K]  ic_launcher
│   │   └── [ 1.5K]  latest.txt
│   ├── [ 7.3M]  com.example.appa
│   │   ├── [ 7.2M]  1.apk
│   │   ├── [  65K]  1.apk.idsig
│   │   ├── [ 3.3K]  ic_launcher
│   │   └── [  496]  latest.txt
│   ├── [ 5.7M]  com.example.appb
│   │   ├── [ 5.6M]  1.apk
│   │   ├── [  53K]  1.apk.idsig
│   │   ├── [ 3.3K]  ic_launcher
│   │   └── [  496]  latest.txt
│   ├── [  78M]  org.chromium.chrome
│   │   ├── [  27M]  443009134.apk
│   │   ├── [  27M]  443021034.apk
│   │   ├── [  17M]  447207733.apk
│   │   ├── [ 3.5M]  delta-443009134-to-447207733.gz
│   │   ├── [ 3.5M]  delta-443021034-to-447207733.gz
│   │   ├── [ 1.9K]  ic_launcher
│   │   └── [ 1.4K]  latest.txt
│   ├── [ 5.4K]  latest-bulk-metadata.txt
│   └── [  349]  latest-index.txt
├── [  80K]  apprepo.db
├── [  32K]  apprepo.db-shm
├── [    0]  apprepo.db-wal
└── [  178]  public-signing-key.pem
```

The files meant to be synced with a static file server are the files located in
`app_repo_data/apps`. A short overview of each file:

* **public-signing-key.pem**: The public key will be used to validate the signatures in the repo for
  the `validate` command, and it will also validate the private key passed into the `insert-apk`
  command to prevent the use of a different key. This public key file in the root directory isn't
  meant to be consumed by the client app. (Instead, the client apps should have the public key
  included in the app by default.)

  On initial insertion into an empty repository, this public key is generated from the private
  signing key used in the `insert-apk` command.

* **latest-index.txt**: Sample:

  ```plain
  YAAAAA== MEYCIQD5lF2JKSI5x0N/ZSP1D8JOp8viJluNJyIfhhMldNX6JwIhAJCDj+D86QacSMjLKG4IFWRAVlVTZvI7NxefuXEw1MR4
  1623215965
  app.attestation.auditor 27 1623215965
  org.chromium.chrome 447207733 1623215965
  app.vanadium.trichromelibrary 447207734 1623215965
  app.vanadium.webview 447207734 1623215965
  com.example.appa 1 1623215965
  com.example.appb 1 1623215965
  ```
  This contains minimal information for clients to decide if they need to fetch updates to app
  metadata. The first line contains a base64-encoded signature, the second line contains the last
  update timestamp of the repository index, and the rest of the lines contain the app's versions
  with timestamps of when the apps were last updated (doesn't necessarily mean a new release).

  See the
  [AppRepoIndex](src/main/kotlin/org/grapheneos/appupdateservergenerator/api/AppRepoIndex.kt) class
  for more details.

* **latest.txt**: Sample for `org.chromium.chrome`:

  ```plain
  YAAAAA== MEQCIE6CPpbUDG4GugsAUao6P8m+M8VtyWtvFIpedCUJJlORAiBv9cN/tbBDWMPGLWD7u/LPTN6Z6VQFg3zYQiENvy+ztA==
  {"package":"org.chromium.chrome","repoIndexTimestamp":1623215965,"label":"Vanadium","iconSha256":"4qDeoGZDik6AIva6JGCQ66jK2OrPl0VYNLI0ZRefS+8=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":443009134,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}]},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"+Ykbo9NOe6pf0TqYwlIBI5fb3nXEvZ4ItdAkwAwTahE=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":443021034,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}]},{"versionCode":447207733,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":447207734,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}],"deltaInfo":[{"baseVersionCode":443009134,"sha256":"jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="},{"baseVersionCode":443021034,"sha256":"ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="}]}]}
  ```

  The first line contains a base64-encoded signature of the JSON metadata.

  The prettified JSON for the second line is:

  ```json
  {
      "package": "org.chromium.chrome",
      "repoIndexTimestamp": 1623215965,
      "label": "Vanadium",
      "iconSha256": "4qDeoGZDik6AIva6JGCQ66jK2OrPl0VYNLI0ZRefS+8=",
      "lastUpdateTimestamp": 1623215965,
      "releases": [
          {
              "versionCode": 443009134,
              "versionName": "90.0.4430.91",
              "minSdkVersion": 29,
              "releaseTimestamp": 1623215965,
              "apkSha256": "V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=",
              "usesStaticLibraries": [
                  {
                      "name": "app.vanadium.trichromelibrary",
                      "version": 443009134,
                      "certDigests": [
                          "c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"
                      ]
                  }
              ]
          },
          {
              "versionCode": 443021034,
              "versionName": "90.0.4430.210",
              "minSdkVersion": 29,
              "releaseTimestamp": 1623215965,
              "apkSha256": "+Ykbo9NOe6pf0TqYwlIBI5fb3nXEvZ4ItdAkwAwTahE=",
              "usesStaticLibraries": [
                  {
                      "name": "app.vanadium.trichromelibrary",
                      "version": 443021034,
                      "certDigests": ["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]
                  }
              ]
          },
          {
              "versionCode": 447207733,
              "versionName": "91.0.4472.77",
              "minSdkVersion": 29,
              "releaseTimestamp": 1623215965,
              "apkSha256": "yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=",
              "usesStaticLibraries": [
                  {
                      "name": "app.vanadium.trichromelibrary",
                      "version": 447207734,
                      "certDigests": ["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]
                  }
              ],
              "deltaInfo": [
                  {
                      "baseVersionCode": 443009134,
                      "sha256": "jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="
                  },
                  {
                      "baseVersionCode": 443021034,
                      "sha256": "ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="
                  }
              ]
          }
      ]
  }
  ```

  This sample does not contain all possible fields. See the
  [ApiMetadata](src/main/kotlin/org/grapheneos/appupdateservergenerator/api/AppMetadata.kt) class
  for a summary of the serializable fields.

* **latest-bulk-metadata.txt**: This is a file of all the metadata in the repository:

  ```plain
  YAAAAA== MEUCIQDDsx+p0ROZx1XtlIjBbgo+xcm3bUVidWVqMZJt3U6eSgIgRajFzg9UmEuj4I0bEyq4Ld/zualQoa3wo5NAcaOqm+M=
  1623215965
  {"package":"app.attestation.auditor","repoIndexTimestamp":1623215965,"label":"Auditor","iconSha256":"NGmjNgJa9og+0Bt4Ic+rYICpy87iIC1DzbWSJtzwEYo=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":24,"versionName":"24","minSdkVersion":26,"releaseTimestamp":1623215965,"apkSha256":"HpecQ50szYqQ991bMv1pg4DyMGBzbDFpmhr+/oHH+OU="},{"versionCode":25,"versionName":"25","minSdkVersion":26,"releaseTimestamp":1623215965,"apkSha256":"ac4QPtAcnlUZ6HL8GoS7fGx/dZe+yyXPvfz6CvtrvKQ="},{"versionCode":26,"versionName":"26","minSdkVersion":26,"releaseTimestamp":1623215965,"apkSha256":"LZo/7Hr/tCoSidZGAr67iz/O1nhHBdUIkpWqrEVJh7I="},{"versionCode":27,"versionName":"27","minSdkVersion":26,"releaseTimestamp":1623215965,"apkSha256":"CNpHPoTVixSI7TRuRpiMbWA1A28ZBrMTDophzdjfZ6g=","deltaInfo":[{"baseVersionCode":24,"sha256":"Xrm5wxQOXLoaoGk7/UtfTce9JL24LppPrBcuwgfeNHM="},{"baseVersionCode":25,"sha256":"hV+pV13g/JSeMF4yYzGpxtE9o6BPYjsp5LAbbDKzNfA="},{"baseVersionCode":26,"sha256":"eX7VGj7905BR4T/kmAVkkJp74oqOlSFEbPL8sy/c7pc="}]}]}
  {"package":"org.chromium.chrome","repoIndexTimestamp":1623215965,"label":"Vanadium","iconSha256":"4qDeoGZDik6AIva6JGCQ66jK2OrPl0VYNLI0ZRefS+8=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"V+Pg4LWMltx8ee3dpYhlNN3G20OdP3BOeH19fRiWpaA=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":443009134,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}]},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"+Ykbo9NOe6pf0TqYwlIBI5fb3nXEvZ4ItdAkwAwTahE=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":443021034,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}]},{"versionCode":447207733,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"yTIy1ogEjhmPiw7Ubzs4JggjZE9rP+yYEMFN7A4zyG0=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":447207734,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}],"deltaInfo":[{"baseVersionCode":443009134,"sha256":"jwqasvDbTdbWHsgCkbPBOFPfK2fkLwNrNysZmnur6dU="},{"baseVersionCode":443021034,"sha256":"ksDLguRgApplNcRxDySVE1LXbvU6vUZmby6+Aw1onA8="}]}]}
  {"package":"app.vanadium.trichromelibrary","repoIndexTimestamp":1623215965,"label":"Trichrome Library","iconSha256":"VVAolTWel7q54taRRHvWPG3S0mmLq5QPCZ6nSKSEV4o=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"3kuRhe+gYFQ1O+L1+gM5eardAWfsB73b/1sRK+pEtL0="},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"6KJZ1d6UldqcAWHMzhpXont4XLddZi5YCUI0N1So740="},{"versionCode":447207734,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"NLpi8yQgYJAfWBj2/l3C2QMdfJncRC/t8aPA3RlE1hM=","deltaInfo":[{"baseVersionCode":443009134,"sha256":"RoZbWjLPvxENxcMWnV7LDBRulpQJjC2P4MpBdcKdjCo="},{"baseVersionCode":443021034,"sha256":"QZve0/D+qqGzIozptH+D5saUCxrdNSGjWWcmJFbb16I="}]}]}
  {"package":"app.vanadium.webview","repoIndexTimestamp":1623215965,"label":"Vanadium System WebView","iconSha256":"NSuIf9WWMU6wUCkMXCOaRVBY0lwNISRViLuXegbjrOU=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":443009134,"versionName":"90.0.4430.91","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"YnKVJ3RZsoS6fxRhHgrfAZGXA/F+RiYOyKjlVlIG6uo=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":443009134,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}]},{"versionCode":443021034,"versionName":"90.0.4430.210","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"VZ2iISVUcxxvQsnemdGIFhpuNU2MGyUujUJXvuPzV2Y=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":443021034,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}]},{"versionCode":447207734,"versionName":"91.0.4472.77","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"Xo1wfFSTg7Gi4Z+1didiJJX/IlDcy7gw9mS3CRCv78s=","usesStaticLibraries":[{"name":"app.vanadium.trichromelibrary","version":447207734,"certDigests":["c6adb8b83c6d4c17d292afde56fd488a51d316ff8f2c11c5410223bff8a7dbb3"]}],"deltaInfo":[{"baseVersionCode":443009134,"sha256":"PKuxV8pF72nrK5M1tB/Hnra0cI7yPRdiucSlm6aHGLc="},{"baseVersionCode":443021034,"sha256":"Sq+tXujrv4Fe6o6MnIYEOTGB62amygiOhS6chgd3bC8="}]}]}
  {"package":"com.example.appa","repoIndexTimestamp":1623215965,"label":"AppA","iconSha256":"qZhDhyqQ+/y+SyNamucIvh2xxaR34avVco71mKgsxHA=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":1,"versionName":"1.0","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"/I+iQcq4RsmVu0MLfW+uZZL2mUv5WLJeNh4qv6PomJg=","v4SigSha256":"3/PxeJvFHKDlqUgk5PMojd2A6jfHklAQ3d51GMtCLu4="}]}
  {"package":"com.example.appb","repoIndexTimestamp":1623215965,"label":"AppB","iconSha256":"qZhDhyqQ+/y+SyNamucIvh2xxaR34avVco71mKgsxHA=","lastUpdateTimestamp":1623215965,"releases":[{"versionCode":1,"versionName":"1.0","minSdkVersion":29,"releaseTimestamp":1623215965,"apkSha256":"arYsOFv2oz15A9cHbH+ThoTQClYQrXbc7BGwj7VzB/4=","v4SigSha256":"eze3hxfWlHNXVlOKKCfCeXdqWOq8ZLuK2spCzLfSkcI="}]}
  ```

  This is used for bulk downloads (e.g., first-time startup or force refreshes).

  See the
  [BulkAppMetadata](src/main/kotlin/org/grapheneos/appupdateservergenerator/api/BulkAppMetadata.kt)
  class for more details.

## TODO
* The Android app
* Figure out a way to convert Vector drawables in APK resources to PNGs or SVGs. Maybe
  https://github.com/neworld/vd2svg?
* More unit tests
* System for pruning older versions of apps
* Maybe a configuration file to configure things such as maximum number of deltas to generate which
  can be used to determine pruning.
* Refactoring
