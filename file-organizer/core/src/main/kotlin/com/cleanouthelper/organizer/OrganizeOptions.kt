package com.cleanouthelper.organizer

data class OrganizeOptions(
    /** Give files with unclear names (IMG_2931.jpg, download (3).pdf) a clear name. */
    val renameUnclearFiles: Boolean = true,
    /** Read the words in screenshots, scans and photos of documents (slower). */
    val readTextInPictures: Boolean = true,
    /** Describe photos and videos: what is in them and where they were taken (slower). */
    val describePhotos: Boolean = true,
    /** Look for identical copies and move the extra copies to the review folder. */
    val findDuplicates: Boolean = true,
    /** Name of the folder everything is organized into, at the top of the phone's storage. */
    val outputFolderName: String = DEFAULT_OUTPUT_FOLDER,
) {
    companion object {
        const val DEFAULT_OUTPUT_FOLDER = "Organized Files"
    }
}
