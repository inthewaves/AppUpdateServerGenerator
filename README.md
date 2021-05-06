# AppUpdateServer generator
A CLI tool to manage a repository for an app update server.

This app update server supports deltas using Google's archive-patcher library, forked at
https://github.com/inthewaves/archive-patcher.

## Usage

### Repository management
Some facts about the app update repository:
* The repository is meant to be uploaded to a static web server with the jar being executed on a
  local machine.
* The repository state is determined by the static files.

To insert an APK (or multiple APKs) into the repo, run this command after
building the jar with `./gradlew build`:

```bash
$ ./app-update-server-generator insert-apk [OPTIONS] APKS
```

This handles metadata and delta generation. If the repository directories don't already exist, the
tool will create the directories. This will not delete / move the source APK provided to the tool.

### Delta generation
The jar also supports generating deltas directly for convenience:
```bash
$ ./app-update-server-generator generate-delta [OPTIONS] OLDFILE NEWFILE OUTPUTDELTA
$ ./app-update-server-generator apply-delta [OPTIONS] OLDFILE DELTAFILE NEWFILE
```
See the help option `-h` for `OPTIONS`.

## TODO
* The Android app
* Metadata signing
* Main index
* Unit tests