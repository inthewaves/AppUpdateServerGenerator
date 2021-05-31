package org.grapheneos.appupdateservergenerator.repo

sealed class AppRepoException : Exception {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)

    class EditFailed : AppRepoException {
        constructor(message: String) : super(message)
    }
    class MoreRecentVersionInRepo(message: String): AppRepoException(message)
    class InsertFailed : AppRepoException {
        constructor() : super()
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
        constructor(cause: Throwable) : super(cause)
    }
    class GroupDoesntExist : AppRepoException {
        constructor(message: String) : super(message)
    }
    class ApkSigningCertMismatch : AppRepoException {
        constructor(message: String) : super(message)
    }
    class RepoSigningKeyMismatch : AppRepoException {
        constructor(message: String) : super(message)
    }
    class InvalidRepoState : AppRepoException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
    class AppDetailParseFailed : AppRepoException {
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}